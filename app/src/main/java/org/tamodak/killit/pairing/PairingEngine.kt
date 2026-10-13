package org.tamodak.killit.pairing

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.DurablePairingStore
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.OutgoingRequestWithCopies
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.pairing.store.UnknownMessageEntity
import org.tamodak.killit.pairing.store.WardAppEntity
import org.tamodak.killit.pairing.store.WardListEntity
import org.tamodak.killit.protocol.AppRef
import org.tamodak.killit.protocol.MessageBody
import org.tamodak.killit.protocol.NoticeBody

/**
 * Pairing, assembled: the one object the rest of the app talks to about guardians, guarded
 * phones and the requests between them.
 *
 * It owns the engine's lock. Every change to pairing state — a message being applied, a request
 * being sent, an answer being given — happens under it, one at a time, so nothing interleaves.
 * Waiting on the network never holds it.
 *
 * After anything that queues a message it tries to send at once, and if the network isn't there,
 * [scheduler] makes sure a background sync will.
 *
 * @param identity this phone's registered identity ([Registration.ensureRegistered]).
 * @param canBeProtected whether this phone can take the protected role: it must be device owner.
 * @param scheduler the background work pairing needs.
 * @param onGuarded applies the device policies that follow from having guardians, or not
 *   (H2, I1); called whenever that changes, and when [enforceGuarded] is asked to.
 * @param registerPush makes sure the server can wake this phone with a push, once it has pairings.
 */
class PairingEngine(
    val store: PairingStore,
    private val server: PairingServer,
    private val identity: suspend () -> DeviceKeys.Identity,
    private val apps: AppBlocker,
    private val durable: DurablePairingStore,
    private val notifier: PairingNotifier,
    private val config: Config,
    canBeProtected: suspend () -> Boolean,
    private val scheduler: Scheduler = object : Scheduler {},
    private val onGuarded: suspend (Boolean) -> Unit = {},
    private val registerPush: suspend () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * The background work pairing needs (PLAN.md §7), kept out of the engine so it runs in tests
     * without WorkManager. Each call is cheap to repeat.
     */
    interface Scheduler {
        /** Messages are queued and couldn't be sent: sync once the network is back. */
        fun soon() {}

        /** There is a pairing: sync in the background from now on. */
        fun periodic() {}

        /** An UNPAIR timer runs out at [atMillis]: sync then, not at the next periodic sync. */
        fun at(atMillis: Long) {}
    }

    /**
     * Durations, limits and device facts the engine needs.
     *
     * @param deviceModel `Build.MANUFACTURER` and `Build.MODEL`.
     * @param listAnswersPerHour app lists answered per guardian per hour (SPEC.md §11.10).
     */
    class Config(
        val deviceModel: String,
        val unpairTimerMillis: Long,
        val unlockCooldownMillis: Long,
        val unlocksPerDay: Int,
        val silenceAlertMillis: Long,
        val listAnswersPerHour: Int,
    )

    private val lock = Mutex()
    private val dao = store.dao

    val effects = RemoteEffects(apps, managementEnabled = ::appManagementEnabled)
    val mailbox = Mailbox(dao, server, identity, ::dispatch, lock, now)
    val peers = PeerList(store, mailbox, server, notifier, now)
    val requests = RequestEngine(store, mailbox, effects, apps, notifier, config.unpairTimerMillis, now)
    val unlock = LiveUnlock(store, mailbox, effects, durable, config.unlockCooldownMillis, config.unlocksPerDay, now)
    val management = AppManagement(store, mailbox, effects, apps, durable, notifier, config.listAnswersPerHour, now)
    val ceremony = CeremonyEngine(
        store, server, mailbox, peers, identity, lock, config.deviceModel, canBeProtected, ::appManagementEnabled, now,
    )

    init {
        peers.requests = requests
        peers.unlock = unlock
        peers.management = management
        requests.peers = peers
        management.requests = requests
        management.peers = peers
    }

    @Volatile
    private var lastSeenRefreshedAt = 0L

    @Volatile
    private var prunedAt = 0L

    /** Whether this process has made sure the periodic sync is scheduled. */
    @Volatile
    private var periodicScheduled = false

    /** The UNPAIR timer a sync was last scheduled for. */
    @Volatile
    private var scheduledTimer: Long? = null

    /** Whether the guarded policies were last applied for a phone with guardians; null before. */
    @Volatile
    private var guardedApplied: Boolean? = null

    val outgoing: Flow<List<OutgoingRequestWithCopies>> = dao.observeOutgoing()
    val incoming: Flow<List<IncomingRequestEntity>> = dao.observeIncoming()
    val history: Flow<List<EventEntity>> = dao.observeEvents()
    val unknownMessages: Flow<List<UnknownMessageEntity>> = dao.observeUnknown()

    /** Guardian side: the apps of the phones this phone guards, from their last lists. */
    val wardApps: Flow<List<WardAppEntity>> = dao.observeWardApps()

    /** Guardian side: the app list asked for last from each guarded phone, and how it went. */
    val wardLists: Flow<List<WardListEntity>> = dao.observeWardLists()

    /** Routes a message that passed every check to what handles its kind. Called under the lock. */
    private suspend fun dispatch(received: Mailbox.Received) {
        when (received.body) {
            is MessageBody.PairOffer -> ceremony.onOffer(received)
            is MessageBody.PairAnswer -> ceremony.onAnswer(received)
            is MessageBody.UnblockApp, is MessageBody.Unlock, is MessageBody.Unpair, is MessageBody.GuardianUnblock ->
                requests.onIncoming(received)
            is MessageBody.Decision -> if (!unlock.onDecision(received)) requests.onDecision(received)
            is MessageBody.Status -> requests.onStatus(received)
            MessageBody.Leave -> peers.onLeave(received)
            is MessageBody.Unpaired -> peers.onUnpaired(received)
            is MessageBody.Notice -> peers.onNotice(received)
            is MessageBody.ListRequest -> management.onListRequest(received)
            is MessageBody.AppList -> management.onAppList(received)
            is MessageBody.Manage -> management.onManage(received)
            is MessageBody.Handover, is MessageBody.MoveRequest, is MessageBody.MoveState, is MessageBody.MoveAnswer,
            is MessageBody.MoveDone -> Unit
        }
    }

    /**
     * True when there is anything to sync: a pairing, a ceremony, or messages and link deletions
     * still waiting. A phone that never paired never talks to the server.
     */
    suspend fun hasWork(): Boolean =
        dao.pairings().isNotEmpty() || dao.ceremony() != null || dao.outbox(1).isNotEmpty() || dao.linkDeletions().isNotEmpty()

    /**
     * One round of synchronisation: cleanup, then everything queued goes out, everything waiting
     * comes in, and the UNPAIR timers are checked.
     *
     * The timers come after the inbox, so a denial already waiting there stops an UNPAIR whose
     * time has run out (B2). A server that can't be reached, or answers nonsense, doesn't hold them
     * back: they are purely local, so the server going away never locks anyone in (PLAN.md §6.4).
     *
     * @param waitSeconds how long to wait for messages if none are waiting; the live screens
     *   (an unlock in progress, a ceremony) wait, background syncs don't.
     * @throws IOException when the server can't be reached; nothing is lost.
     */
    suspend fun sync(waitSeconds: Int = 0) {
        if (!hasWork()) return
        identity()
        store.reconcile()
        lock.withLock {
            unlock.cleanUp()
            requests.applyPendingApprovals()
        }
        val failure = try {
            mailbox.flush()
            mailbox.receive(waitSeconds)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        lock.withLock { requests.checkTimers() }
        if (failure != null) throw failure
        mailbox.flush()
        peers.deleteFinishedLinks()
        if (now() - lastSeenRefreshedAt > LAST_SEEN_INTERVAL_MILLIS) refreshLastSeen()
        if (now() - prunedAt > PRUNE_INTERVAL_MILLIS) prune()
        keepScheduled()
        enforceGuarded()
    }

    /**
     * Applies the device policies that follow from having guardians (H2, I1) when that changed
     * since they were last applied in this process, or always when [force] is set: at startup,
     * where they may have been lost.
     */
    suspend fun enforceGuarded(force: Boolean = false) {
        if (force) store.reconcile()
        val guarded = store.guardians().isNotEmpty()
        if (!force && guarded == guardedApplied) return
        onGuarded(guarded)
        guardedApplied = guarded
    }

    /**
     * Keeps the background work in step with what there is while anything is paired: the periodic
     * sync, the push registration, and a sync for the next UNPAIR timer. The worker cancels itself
     * once nothing is paired.
     */
    private suspend fun keepScheduled() {
        if (dao.pairings().isEmpty()) return
        if (!periodicScheduled) {
            scheduler.periodic()
            periodicScheduled = true
        }
        try {
            registerPush()
        } catch (e: IOException) {
            KillitLog.d(KillitLog.SYNC) { "Push registration deferred: ${e.message}" }
        }
        val timer = requests.nextTimer()
        if (timer != null && timer != scheduledTimer) {
            scheduler.at(timer)
            scheduledTimer = timer
        }
    }

    /** Asks the server how recently each peer was seen (§3.11), for the pairs screens. */
    suspend fun refreshLastSeen() {
        if (dao.pairings().isEmpty()) return
        peers.refreshLastSeen(config.silenceAlertMillis)
        lastSeenRefreshedAt = now()
    }

    // ---------------------------------------------------------------- the ceremony

    /** Follows the ceremony: the invite until linked, then the mailbox for the offer and answer. */
    suspend fun followCeremony(waitSeconds: Int) {
        val current = dao.ceremony() ?: return
        if (current.stage == CeremonyStage.LINKED) {
            sync(waitSeconds)
        } else {
            ceremony.poll(waitSeconds)
            send()
        }
    }

    // ---------------------------------------------------------------- protected side

    suspend fun askToUnblock(packageName: String, explanation: String): RequestEngine.AskResult =
        locked { requests.askToUnblock(packageName, explanation) }

    suspend fun askToRemove(targetPairId: ByteArray, explanation: String): RequestEngine.AskResult =
        locked { requests.askToRemove(targetPairId, explanation) }.also { keepScheduled() }

    suspend fun withdraw(requestId: ByteArray) = locked { requests.withdraw(requestId) }

    suspend fun unlockAvailability(): LiveUnlock.Availability = lock.withLock { unlock.availability() }

    suspend fun startUnlock(explanation: String): LiveUnlock.Availability = locked { unlock.start(explanation) }

    suspend fun cancelUnlock() = locked { unlock.cancel() }

    /** Whether this phone lets its guardians manage its apps (PLAN.md §3.8). */
    suspend fun appManagementEnabled(): Boolean = management.isEnabled()

    /**
     * Turns app management on or off and tells every guardian (H6). Only an unlocked session may
     * call this (G3); the caller checks.
     *
     * @return false when the setting couldn't be stored.
     */
    suspend fun setAppManagement(enabled: Boolean): Boolean = locked { management.setEnabled(enabled) }

    /** Tells every guardian something (SPEC.md §11.9): an app was installed, a setting changed. */
    suspend fun notifyGuardians(notice: NoticeBody) {
        if (store.guardians().isEmpty()) return
        locked { peers.notifyGuardians(notice) }
    }

    /**
     * D1: default-blocking just blocked new apps. A guarded phone tells its guardians, with what
     * they need to recognise each app.
     */
    suspend fun onAppsBlocked(packageNames: List<String>) {
        if (store.guardians().isEmpty()) return
        val notices = packageNames.mapNotNull { apps.describe(it) }.map { NoticeBody.AppInstalled(it, blocked = true) }
        if (notices.isEmpty()) return
        locked { notices.forEach { peers.notifyGuardians(it) } }
    }

    /** H11: an app was uninstalled. */
    suspend fun onPackageRemoved(packageName: String) = locked { requests.onPackageRemoved(packageName) }

    /** G11: apps were unblocked directly in an unlocked session. */
    suspend fun onUnblockedLocally(packageNames: Collection<String>) = locked { requests.onUnblockedLocally(packageNames) }

    /**
     * H7: device owner is about to be released. Every guardian is removed and told, and what only
     * a protected phone keeps is forgotten, so protecting it again later starts afresh.
     */
    suspend fun releaseAll() {
        locked { peers.releaseAll() }
        store.clearProtection()
    }

    // ---------------------------------------------------------------- guardian side

    suspend fun decide(pairId: ByteArray, requestId: ByteArray, approve: Boolean, note: String): Boolean =
        locked { requests.decide(pairId, requestId, approve, note) }

    suspend fun leave(pairId: ByteArray) = locked { peers.leave(pairId) }

    /** Asks a guarded phone for its app list (SPEC.md §11.10). */
    suspend fun requestAppList(pairId: ByteArray): Boolean = locked { management.requestList(pairId) }

    /**
     * Sends a guarded phone this guardian's changes to its apps (SPEC.md §11.11).
     *
     * @param changes each app with the state wanted: true to block it, false to unblock it.
     */
    suspend fun manageApps(pairId: ByteArray, changes: List<Pair<AppRef, Boolean>>): Boolean =
        locked { management.send(pairId, changes) }

    /**
     * The requests a reminder names (D9): waiting for this phone's answer, other than live
     * unlocks, and, when reminders come only once, not reminded before. They are marked reminded.
     *
     * @return each request with the phone it came from.
     */
    suspend fun remind(onlyOnce: Boolean): List<Pair<IncomingRequestEntity, PairingEntity>> =
        lock.withLock {
            val at = now()
            dao.waitingForMe()
                .filter { it.type != RequestType.UNLOCK && (!onlyOnce || it.remindedAt == null) }
                .mapNotNull { request -> dao.pairing(request.pairId)?.let { request to it } }
                .onEach { (request, _) -> dao.setReminded(request.pairId, request.requestId, at) }
        }

    // ---------------------------------------------------------------- either side

    suspend fun rename(pairId: ByteArray, nickname: String) = lock.withLock { peers.rename(pairId, nickname) }

    /** True when this phone guards at least one phone. */
    suspend fun isGuardian(): Boolean = dao.wards().isNotEmpty()

    /**
     * Runs [block] under the lock, then tries to send whatever it queued, and applies what a
     * change of guardians means.
     */
    private suspend fun <T> locked(block: suspend () -> T): T {
        val result = lock.withLock { block() }
        send()
        enforceGuarded()
        return result
    }

    /** Sends what is queued now; when the network isn't there, a background sync will. */
    private suspend fun send() {
        if (dao.outbox(1).isEmpty()) return
        try {
            mailbox.flush()
        } catch (e: IOException) {
            KillitLog.d(KillitLog.SYNC) { "Sending deferred: ${e.message}" }
            scheduler.soon()
        }
    }

    /** Forgets what is old enough: seen ids after 400 days, history after 90 (I10). */
    private suspend fun prune() {
        val now = now()
        dao.pruneSeen(now - Mailbox.SEEN_RETENTION_MILLIS)
        dao.pruneEvents(now - HISTORY_MILLIS)
        dao.pruneUnknown(now - HISTORY_MILLIS)
        dao.pruneOutgoing(now - HISTORY_MILLIS)
        dao.pruneIncoming(now - HISTORY_MILLIS)
        prunedAt = now
    }

    private companion object {
        const val LAST_SEEN_INTERVAL_MILLIS = 60L * 60 * 1000
        const val PRUNE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
        const val HISTORY_MILLIS = 90L * 24 * 60 * 60 * 1000
    }
}
