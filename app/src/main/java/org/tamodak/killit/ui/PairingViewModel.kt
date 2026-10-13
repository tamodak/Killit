package org.tamodak.killit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tamodak.killit.ServiceLocator
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.KnownPackage
import org.tamodak.killit.net.ApiError
import org.tamodak.killit.pairing.AttestationRejectedException
import org.tamodak.killit.pairing.CeremonyEngine
import org.tamodak.killit.pairing.IdentityLostException
import org.tamodak.killit.pairing.LiveUnlock
import org.tamodak.killit.pairing.PairingEngine
import org.tamodak.killit.pairing.PairingPreferences
import org.tamodak.killit.pairing.RequestEngine
import org.tamodak.killit.pairing.store.AppChancesEntity
import org.tamodak.killit.pairing.store.CeremonyEntity
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.OutgoingRequestWithCopies
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.WardAppEntity
import org.tamodak.killit.pairing.store.WardListEntity
import org.tamodak.killit.protection.PackageGuard
import org.tamodak.killit.protocol.AppRef
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.fitText
import org.tamodak.killit.protocol.fromHex
import org.tamodak.killit.protocol.toHex

/** Something pairing has to tell the user once. */
sealed interface PairingMessage {
    /** The server couldn't be reached. Nothing was lost; it is retried. */
    data object Offline : PairingMessage

    /** This phone's hardware can't prove its keys are safe, so it can't pair (E-E7). */
    data class CantPair(val reason: String) : PairingMessage

    /** The server no longer knows this phone's identity. */
    data object IdentityLost : PairingMessage

    /** The invite code is wrong, used or expired. */
    data object CodeNotFound : PairingMessage

    /** Too many attempts; try again later. */
    data object RateLimited : PairingMessage

    /** A request couldn't be sent, and why. */
    data class NotSent(val result: RequestEngine.AskResult) : PairingMessage

    /** A request was sent to every guardian. */
    data object Sent : PairingMessage

    /** Changes to a guarded phone's apps were sent; it applies them once it is online. */
    data object ManageSent : PairingMessage
}

/**
 * Everything the pairing screens show, in one snapshot.
 *
 * @param pairings every pairing, in both directions.
 * @param ceremony the pairing ceremony in progress or just finished.
 * @param incoming requests from phones this phone guards.
 * @param outgoing this phone's own requests to its guardians.
 * @param history the local 90-day history (I10).
 * @param unknownMessages how many messages need a newer Killit (E-F1).
 * @param unlock the live unlock's state.
 * @param known default-blocking's decisions, to list the apps this phone can ask about.
 * @param chances UNBLOCK_APP chances left, keyed by [chancesKey].
 * @param selfName the name this phone last called itself, offered again.
 * @param guardianNoticeSeen whether the guardian passkey explanation was acknowledged (§15.11).
 * @param wardApps the guarded phones' apps, from their last lists (PLAN.md §3.8).
 * @param wardLists the app list asked for last from each guarded phone.
 */
data class PairingUiState(
    val pairings: List<PairingEntity> = emptyList(),
    val ceremony: CeremonyEntity? = null,
    val incoming: List<IncomingRequestEntity> = emptyList(),
    val outgoing: List<OutgoingRequestWithCopies> = emptyList(),
    val history: List<EventEntity> = emptyList(),
    val unknownMessages: Int = 0,
    val unlock: LiveUnlock.State = LiveUnlock.State.Idle,
    val unlockAvailability: LiveUnlock.Availability? = null,
    val known: Map<String, KnownPackage> = emptyMap(),
    val chances: Map<String, Int> = emptyMap(),
    val selfName: String = "",
    val guardianNoticeSeen: Boolean = true,
    val wardApps: List<WardAppEntity> = emptyList(),
    val wardLists: List<WardListEntity> = emptyList(),
    val busy: Boolean = false,
    val message: PairingMessage? = null,
) {
    /** This phone's guardians. */
    val guardians: List<PairingEntity> get() = pairings.filter { it.myRole == PairRole.PROTECTED }

    /** The phones this phone guards. */
    val wards: List<PairingEntity> get() = pairings.filter { it.myRole == PairRole.GUARDIAN }

    /** Requests still waiting for this phone's answer. */
    val waitingForMe: List<IncomingRequestEntity>
        get() = incoming.filter { it.state.isOpen && it.myVote == org.tamodak.killit.pairing.store.Vote.WAITING }

    /** The pairing with this hex pair id, if it still exists. */
    fun pairing(pairIdHex: String): PairingEntity? = pairings.firstOrNull { it.pairId.toHex() == pairIdHex }

    /** UNBLOCK_APP chances left for an app (D4); three for one never asked about. */
    fun chancesFor(packageName: String, certDigest: ByteArray): Int = chances[chancesKey(packageName, certDigest)] ?: 3

    companion object {
        fun chancesKey(packageName: String, certDigest: ByteArray) = packageName + "/" + certDigest.toHex()
    }
}

/**
 * The pairing screens' state and actions, beside [KillitViewModel], which still decides what may be
 * shown. This one never grants anything: the engine checks every request itself, and what a
 * screen can open is [KillitViewModel]'s call.
 */
class PairingViewModel(
    private val engine: PairingEngine,
    private val prefs: PairingPreferences,
    private val guard: PackageGuard,
    private val defaultName: String,
) : ViewModel() {
    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { engine.store.pairings.collect { all -> _state.update { it.copy(pairings = all) } } }
        viewModelScope.launch { engine.ceremony.ceremony.collect { c -> _state.update { it.copy(ceremony = c) } } }
        viewModelScope.launch { engine.incoming.collect { list -> _state.update { it.copy(incoming = list) } } }
        viewModelScope.launch { engine.outgoing.collect { list -> _state.update { it.copy(outgoing = list) } } }
        viewModelScope.launch { engine.history.collect { list -> _state.update { it.copy(history = list) } } }
        viewModelScope.launch { engine.unknownMessages.collect { list -> _state.update { it.copy(unknownMessages = list.size) } } }
        viewModelScope.launch { engine.unlock.state.collect { u -> _state.update { it.copy(unlock = u) } } }
        viewModelScope.launch { guard.packages.collect { known -> _state.update { it.copy(known = known) } } }
        viewModelScope.launch {
            engine.store.dao.observeAppChances().collect { rows ->
                _state.update { it.copy(chances = rows.associate { r: AppChancesEntity -> PairingUiState.chancesKey(r.packageName, r.certDigest) to r.chancesLeft }) }
            }
        }
        viewModelScope.launch { prefs.guardianNoticeAcknowledged.collect { seen -> _state.update { it.copy(guardianNoticeSeen = seen) } } }
        viewModelScope.launch { engine.wardApps.collect { apps -> _state.update { it.copy(wardApps = apps) } } }
        viewModelScope.launch { engine.wardLists.collect { lists -> _state.update { it.copy(wardLists = lists) } } }
        viewModelScope.launch { _state.update { it.copy(selfName = prefs.selfName() ?: defaultName) } }
    }

    // ---------------------------------------------------------------- the ceremony

    /**
     * Starts a ceremony: shows a code, or joins with one.
     *
     * @param code null to show a code; what the user typed to join one.
     */
    fun startCeremony(role: PairRole, name: String, code: String?) = act {
        prefs.setSelfName(name.trim())
        _state.update { it.copy(selfName = name.trim()) }
        if (code == null) {
            engine.ceremony.create(role, name)
        } else {
            when (engine.ceremony.join(code, role, name)) {
                CeremonyEngine.JoinResult.JOINED -> Unit
                CeremonyEngine.JoinResult.CODE_NOT_FOUND -> say(PairingMessage.CodeNotFound)
                CeremonyEngine.JoinResult.RATE_LIMITED -> say(PairingMessage.RateLimited)
            }
        }
    }

    fun confirmCode() = act { engine.ceremony.confirm() }

    fun rejectCode() = act { engine.ceremony.reject() }

    fun cancelCeremony() = act { engine.ceremony.cancel() }

    fun setNickname(name: String) = act { engine.ceremony.setNickname(name.trim()) }

    fun dismissCeremony() = act { engine.ceremony.dismiss() }

    /**
     * Follows the ceremony for as long as the wizard is on screen: the invite while the two phones
     * reveal and confirm, then the mailbox for the offer and answer. Run from the screen, so it
     * stops when the screen does.
     */
    suspend fun followCeremony() = followWhile {
        val stage = _state.value.ceremony?.stage
        if (stage == null || stage == CeremonyStage.PAIRED || stage == CeremonyStage.FAILED) {
            delay(IDLE_MILLIS)
        } else {
            engine.followCeremony(LONG_POLL_SECONDS)
        }
    }

    // ---------------------------------------------------------------- protected side

    fun askToUnblock(packageName: String, explanation: String, onSent: () -> Unit) = act {
        report(engine.askToUnblock(packageName, explanation), onSent)
    }

    fun askToRemove(pairIdHex: String, explanation: String, onSent: () -> Unit) = act {
        report(engine.askToRemove(pairIdHex.fromHex(), explanation), onSent)
    }

    fun withdraw(requestIdHex: String) = act { engine.withdraw(requestIdHex.fromHex()) }

    fun refreshUnlockAvailability() = act { _state.update { it.copy(unlockAvailability = engine.unlockAvailability()) } }

    fun startUnlock(explanation: String) = act {
        val result = engine.startUnlock(explanation)
        _state.update { it.copy(unlockAvailability = result) }
    }

    fun dismissUnlockResult() {
        engine.unlock.dismiss()
        refreshUnlockAvailability()
    }

    /** Follows the live unlock while its screen is on: decisions arrive through a long poll. */
    suspend fun followUnlock() = followWhile {
        if (_state.value.unlock is LiveUnlock.State.Waiting) engine.sync(LONG_POLL_SECONDS) else delay(IDLE_MILLIS)
    }

    /**
     * Blocks more apps without the guardians (D7).
     *
     * @return through [onDone], the apps Android refused to suspend.
     */
    fun blockMore(packageNames: Set<String>, onDone: (List<String>) -> Unit) = act {
        onDone(guard.blockMore(packageNames))
    }

    // ---------------------------------------------------------------- guardian side

    fun decide(pairIdHex: String, requestIdHex: String, approve: Boolean, note: String, onDone: () -> Unit) = act {
        if (engine.decide(pairIdHex.fromHex(), requestIdHex.fromHex(), approve, note)) onDone()
    }

    fun leave(pairIdHex: String, onDone: () -> Unit) = act {
        engine.leave(pairIdHex.fromHex())
        onDone()
    }

    fun rename(pairIdHex: String, nickname: String) = act { engine.rename(pairIdHex.fromHex(), nickname.trim()) }

    fun acknowledgeGuardianNotice() = act { prefs.acknowledgeGuardianNotice() }

    /** Asks a guarded phone for its app list (SPEC.md §11.10). */
    fun requestAppList(pairIdHex: String) = act { engine.requestAppList(pairIdHex.fromHex()) }

    /** Sends a guarded phone the changes to its apps: each with true to block it, false to unblock it. */
    fun manageApps(pairIdHex: String, changes: List<Pair<AppRef, Boolean>>) = act {
        if (engine.manageApps(pairIdHex.fromHex(), changes)) say(PairingMessage.ManageSent)
    }

    /**
     * Follows a guarded phone's answer while its app list is on screen: its pages and the notices
     * of what changed arrive through a long poll.
     */
    suspend fun followAppList(pairIdHex: String) = followWhile {
        val list = _state.value.wardLists.firstOrNull { it.pairId.toHex() == pairIdHex }
        val waiting = list != null && list.completedAt == null && !list.failed
        val pending = _state.value.wardApps.any { it.pairId.toHex() == pairIdHex && it.pendingBlock != null }
        if (waiting || pending) engine.sync(LONG_POLL_SECONDS) else delay(IDLE_MILLIS)
    }

    // ---------------------------------------------------------------- either side

    private var refreshing: Job? = null

    /**
     * Syncs now, for a screen that was just opened; failures stay quiet, the background retries.
     * Walking through several screens starts one sync, not one per screen.
     */
    fun refresh() {
        if (refreshing?.isActive == true) return
        refreshing = viewModelScope.launch {
            runCatching {
                engine.sync()
                engine.refreshLastSeen()
            }.onFailure { KillitLog.d(KillitLog.SYNC) { "Refresh failed: ${it.message}" } }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Runs a user action with the busy flag up, and turns failures into a message. */
    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: AttestationRejectedException) {
                say(PairingMessage.CantPair(e.reason))
            } catch (e: IdentityLostException) {
                say(PairingMessage.IdentityLost)
            } catch (e: ApiError) {
                say(if (e.status == 429) PairingMessage.RateLimited else PairingMessage.Offline)
            } catch (e: IOException) {
                say(PairingMessage.Offline)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun report(result: RequestEngine.AskResult, onSent: () -> Unit) {
        if (result is RequestEngine.AskResult.Sent) {
            say(PairingMessage.Sent)
            onSent()
        } else {
            say(PairingMessage.NotSent(result))
        }
    }

    private fun say(message: PairingMessage) = _state.update { it.copy(message = message) }

    /** Repeats [step] until cancelled, backing off while the server can't be reached. */
    private suspend fun followWhile(step: suspend () -> Unit) {
        var backoff = 1_000L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try {
                step()
                backoff = 1_000L
            } catch (e: IOException) {
                KillitLog.d(KillitLog.SYNC) { "Following failed: ${e.message}; retrying" }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    companion object {
        /** How long one long poll may wait; the server holds at most 30 s (SPEC.md §13). */
        const val LONG_POLL_SECONDS = 25

        /** How often an idle follower looks again. */
        private const val IDLE_MILLIS = 1_000L

        val Factory = viewModelFactory {
            initializer {
                PairingViewModel(
                    engine = ServiceLocator.pairing,
                    prefs = ServiceLocator.pairingPreferences,
                    guard = ServiceLocator.packageGuard,
                    defaultName = fitText(android.os.Build.MODEL.orEmpty(), TextRule.SELF_NAME),
                )
            }
        }
    }
}
