package org.tamodak.killit.pairing

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.DurablePairingStore
import org.tamodak.killit.data.UnlockLimits
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.PendingUnlockEntity
import org.tamodak.killit.pairing.store.Vote
import org.tamodak.killit.protocol.CancelReason
import org.tamodak.killit.protocol.DecisionTarget
import org.tamodak.killit.protocol.MAX_GUARDIANS
import org.tamodak.killit.protocol.MAX_STATUS_ENTRIES
import org.tamodak.killit.protocol.MessageBody
import org.tamodak.killit.protocol.Outcome
import org.tamodak.killit.protocol.REQUEST_ID_BYTES
import org.tamodak.killit.protocol.RequestKind
import org.tamodak.killit.protocol.SESSION_NONCE_BYTES
import org.tamodak.killit.protocol.StatusEntry
import org.tamodak.killit.protocol.StoredRequest
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.Verdict
import org.tamodak.killit.protocol.randomBytes
import org.tamodak.killit.protocol.toHex
import org.tamodak.killit.protocol.verifyDecision

/**
 * The live unlock (PLAN.md §3.4, SPEC.md §11.4): the protected user asks every guardian to open
 * Killit, and waits on screen until they all have.
 *
 * Approvals bind a session nonce that exists only in this object's memory. It is never written
 * anywhere, so an approval can open nothing once the waiting screen is gone, whether the user left
 * it, a call took over (H12), or the process died (E-B6). Leaving cancels the request at once and
 * tells the guardians; one denial closes it (C3). After either, a cooldown starts, and only so
 * many requests are allowed a day (C4). Both limits are kept in the durable store, so Clear data
 * doesn't reset them.
 *
 * Even an approval only *offers* to open the gate ([RemoteEffects.Effect.OpenGate]): the screen
 * takes it only if it is still in the foreground showing this very session.
 *
 * Callers hold the engine's lock.
 *
 * @param cooldownMillis the pause after a denial or a cancel (`BuildConfig.UNLOCK_COOLDOWN_MILLIS`).
 * @param perDay requests allowed per local day (`BuildConfig.UNLOCKS_PER_DAY`).
 */
class LiveUnlock(
    private val store: PairingStore,
    private val mailbox: Mailbox,
    private val effects: RemoteEffects,
    private val durable: DurablePairingStore,
    private val cooldownMillis: Long,
    private val perDay: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = store.dao

    /** One guardian's answer so far. */
    class GuardianVote(val pairId: ByteArray, val name: String, val vote: Vote, val note: String)

    /** What the waiting screen shows. */
    sealed interface State {
        data object Idle : State

        /** Sent; waiting for [guardians]. */
        class Waiting(val sessionId: Long, val guardians: List<GuardianVote>) : State

        /** A guardian said no; the next request may be sent at [cooldownUntil]. */
        class Denied(val guardians: List<GuardianVote>, val cooldownUntil: Long) : State

        /** Every guardian approved: the gate opens for [sessionId], if it is still on screen. */
        class Approved(val sessionId: Long) : State
    }

    /** Whether a request may be sent now. */
    sealed interface Availability {
        data object Ready : Availability

        data object NoGuardians : Availability

        class CoolingDown(val until: Long) : Availability

        data object DailyLimitReached : Availability

        data object AlreadyWaiting : Availability
    }

    private class Session(
        val id: Long,
        val requestId: ByteArray,
        val nonce: ByteArray,
        val explanation: String,
        val votes: MutableMap<String, GuardianVote>,
    )

    private var session: Session? = null
    private var nextSessionId = 1L
    private var fallbackLimits = UnlockLimits(0L, 0, 0)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether [start] would send a request now, and if not, why. */
    suspend fun availability(): Availability {
        if (session != null) return Availability.AlreadyWaiting
        if (store.guardians().isEmpty()) return Availability.NoGuardians
        val limits = readLimits()
        if (limits.cooldownUntil > now()) return Availability.CoolingDown(limits.cooldownUntil)
        if (limits.day == today() && limits.count >= perDay) return Availability.DailyLimitReached
        return Availability.Ready
    }

    /**
     * Sends the request to every guardian and starts waiting.
     *
     * @param explanation optional (C5): the unlock was agreed by voice first.
     * @return the availability that stopped it, or [Availability.Ready] when it was sent.
     */
    suspend fun start(explanation: String): Availability {
        val available = availability()
        if (available != Availability.Ready) return available
        val text = RequestEngine.explanationOrNull(explanation, TextRule.OPTIONAL_EXPLANATION).orEmpty()
        val guardians = store.guardians()
        val limits = readLimits()
        val day = today()
        writeLimits(UnlockLimits(limits.cooldownUntil, day, if (limits.day == day) limits.count + 1 else 1))

        val current = Session(
            id = nextSessionId++,
            requestId = randomBytes(REQUEST_ID_BYTES),
            nonce = randomBytes(SESSION_NONCE_BYTES),
            explanation = text,
            votes = guardians.associateTo(LinkedHashMap()) { it.pairId.toHex() to GuardianVote(it.pairId, it.nickname, Vote.WAITING, "") },
        )
        dao.insertPendingUnlock(PendingUnlockEntity(current.requestId, now()))
        val body = MessageBody.Unlock(current.requestId, current.nonce, text, guardians.size.coerceIn(1, MAX_GUARDIANS))
        guardians.forEach { mailbox.post(it, body) }
        session = current
        _state.value = State.Waiting(current.id, current.votes.values.toList())
        KillitLog.i(KillitLog.REQ, "Unlock requested from ${guardians.size} guardians")
        return Availability.Ready
    }

    /**
     * The waiting screen is gone (left, a call, the process going away): the request is withdrawn
     * and the nonce forgotten, so a late approval matches nothing (E-B3).
     */
    suspend fun cancel() {
        val current = session ?: return
        close(current, Outcome.CANCELLED, CancelReason.WITHDRAWN)
        startCooldown()
        _state.value = State.Idle
        KillitLog.i(KillitLog.REQ, "Unlock request withdrawn")
    }

    /** Clears a finished request from the screen. */
    fun dismiss() {
        if (session == null) _state.value = State.Idle
    }

    /**
     * A DECISION, if it answers the live session. Verified against the in-memory nonce (§11.6).
     *
     * @return true when it was for the live session, whatever it said.
     */
    suspend fun onDecision(received: Mailbox.Received): Boolean {
        val current = session ?: return false
        val guardian = received.pairing ?: return false
        val decision = received.body as MessageBody.Decision
        if (!decision.requestId.contentEquals(current.requestId)) return false
        val key = guardian.pairId.toHex()
        val vote = current.votes[key] ?: return true
        if (vote.vote != Vote.WAITING) return true

        val stored = StoredRequest(
            requestId = current.requestId,
            target = DecisionTarget.Session(current.nonce),
            pairId = guardian.pairId,
            guardianFp = guardian.peerFp,
            guardianSignPk = guardian.peerSignPk,
            ownFp = received.header.recipientFp,
        )
        val verified = verifyDecision(stored, received.opened).valueOrNull() ?: return true
        val approved = verified.decision == Verdict.APPROVE
        current.votes[key] = GuardianVote(vote.pairId, vote.name, if (approved) Vote.APPROVED else Vote.DENIED, verified.note)
        KillitLog.i(KillitLog.REQ, "${guardian.peerFp.short()} ${if (approved) "approved" else "denied"} the unlock")
        evaluate(current)
        return true
    }

    /** B4: a guardian left while the user waits; the request is counted again without it. */
    suspend fun onGuardianRemoved(pairId: ByteArray) {
        val current = session ?: return
        if (current.votes.remove(pairId.toHex()) == null) return
        if (current.votes.isEmpty()) {
            close(current, Outcome.CANCELLED, CancelReason.GUARDIAN_REMOVED)
            _state.value = State.Idle
        } else {
            evaluate(current)
        }
    }

    /**
     * A guardian moved to a new phone while the user waits (SPEC.md §11.13): the request goes to the
     * new phone too, which never saw it, unless the guardian has answered already.
     *
     * @param guardian the pairing, already handed over.
     */
    suspend fun onGuardianMoved(guardian: PairingEntity) {
        val current = session ?: return
        if (current.votes[guardian.pairId.toHex()]?.vote != Vote.WAITING) return
        val count = current.votes.size.coerceIn(1, MAX_GUARDIANS)
        mailbox.post(guardian, MessageBody.Unlock(current.requestId, current.nonce, current.explanation, count))
    }

    /**
     * Tells guardians about requests a dead process left open. Their nonces died with it, so those
     * requests can never be approved; this only spares the guardians a stale "waiting now".
     */
    suspend fun cleanUp() {
        val guardians = store.guardians()
        for (pending in dao.pendingUnlocks()) {
            if (session?.requestId?.contentEquals(pending.requestId) == true) continue
            val body = MessageBody.Status(pending.requestId, RequestKind.UNLOCK, Outcome.CANCELLED, CancelReason.WITHDRAWN, emptyList())
            guardians.forEach { mailbox.post(it, body) }
            dao.deletePendingUnlock(pending.requestId)
        }
    }

    private suspend fun evaluate(current: Session) {
        val votes = current.votes.values
        when {
            votes.any { it.vote == Vote.DENIED } -> {
                close(current, Outcome.DENIED, CancelReason.NONE)
                _state.value = State.Denied(votes.toList(), startCooldown())
            }
            votes.all { it.vote == Vote.APPROVED } -> {
                close(current, Outcome.APPROVED, CancelReason.NONE)
                _state.value = State.Approved(current.id)
                effects.apply(RemoteEffects.Effect.OpenGate(current.id))
            }
            else -> {
                sendStatus(current, Outcome.OPEN, CancelReason.NONE)
                _state.value = State.Waiting(current.id, votes.toList())
            }
        }
    }

    /** Ends the session: guardians hear the outcome, and the nonce is forgotten. */
    private suspend fun close(current: Session, outcome: Outcome, reason: CancelReason) {
        sendStatus(current, outcome, reason)
        dao.deletePendingUnlock(current.requestId)
        session = null
    }

    private suspend fun sendStatus(current: Session, outcome: Outcome, reason: CancelReason) {
        val guardians = current.votes.values.mapNotNull { vote -> dao.pairing(vote.pairId)?.let { vote to it } }
        val entries = guardians.take(MAX_STATUS_ENTRIES).map { (vote, pairing) ->
            StatusEntry(pairing.peerFp, pairing.peerName, vote.vote.toGuardianState())
        }
        val body = MessageBody.Status(current.requestId, RequestKind.UNLOCK, outcome, reason, entries)
        guardians.forEach { (_, pairing: PairingEntity) -> mailbox.post(pairing, body) }
    }

    /** Starts the cooldown (C4) and returns when it ends. */
    private suspend fun startCooldown(): Long {
        val until = now() + cooldownMillis
        val limits = readLimits()
        writeLimits(UnlockLimits(until, limits.day, limits.count))
        return until
    }

    private fun today(): Int = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toEpochDay().toInt()

    private suspend fun readLimits(): UnlockLimits =
        (if (durable.isAvailable()) durable.readUnlockLimits() else null) ?: fallbackLimits

    private suspend fun writeLimits(limits: UnlockLimits) {
        fallbackLimits = limits
        if (durable.isAvailable()) durable.writeUnlockLimits(limits)
    }
}
