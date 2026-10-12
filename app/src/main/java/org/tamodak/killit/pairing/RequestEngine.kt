package org.tamodak.killit.pairing

import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.pairing.store.CancelCause
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.EventType
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.OutgoingRequestEntity
import org.tamodak.killit.pairing.store.OutgoingRequestWithCopies
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.RequestCopyEntity
import org.tamodak.killit.pairing.store.RequestState
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.pairing.store.StatusEntries
import org.tamodak.killit.pairing.store.StatusEntryRecord
import org.tamodak.killit.pairing.store.Vote
import org.tamodak.killit.protocol.AppInfo
import org.tamodak.killit.protocol.AppRef
import org.tamodak.killit.protocol.CancelReason
import org.tamodak.killit.protocol.DecisionTarget
import org.tamodak.killit.protocol.GuardianState
import org.tamodak.killit.protocol.MAX_GUARDIANS
import org.tamodak.killit.protocol.MAX_STATUS_ENTRIES
import org.tamodak.killit.protocol.MessageBody
import org.tamodak.killit.protocol.Outcome
import org.tamodak.killit.protocol.REQUEST_ID_BYTES
import org.tamodak.killit.protocol.RequestKind
import org.tamodak.killit.protocol.StatusEntry
import org.tamodak.killit.protocol.StoredRequest
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.UnpairedHow
import org.tamodak.killit.protocol.Verdict
import org.tamodak.killit.protocol.encodeText
import org.tamodak.killit.protocol.randomBytes
import org.tamodak.killit.protocol.toHex
import org.tamodak.killit.protocol.trimText
import org.tamodak.killit.protocol.verifyDecision

/**
 * The asynchronous requests between a protected phone and its guardians: UNBLOCK_APP and UNPAIR
 * (SPEC.md §11.3, §11.5), the DECISIONs on them (§11.6) and the STATUS that keeps every guardian's
 * copy current (§11.7). The live UNLOCK is [LiveUnlock]'s.
 *
 * ### Protected side
 *
 * Every guardian must approve; one denial closes the request (PLAN.md §3.3). A denial costs the
 * app, or the guardian to be removed, one of its three chances (D4, G8); nothing else does. An
 * unblock that is approved is carried out only through [RemoteEffects], and only for the same app
 * and signing certificate the guardians saw. An UNPAIR that nobody denies within 30 days removes
 * the guardian on this phone's own clock (B2): the escape that needs no server and nobody's answer.
 *
 * A DECISION counts only after it verifies against this phone's own stored request (§11.6), and
 * only once per guardian and request.
 *
 * When guardians come or go while requests are open, the requests are counted again (G6): a new
 * guardian receives them too, and a request can complete because the last guardian it waited for
 * left.
 *
 * ### Guardian side
 *
 * Incoming requests are stored and announced; this phone answers each once, with an optional note
 * (G9), and follows the others' answers through STATUS (G10).
 *
 * Callers hold the engine's lock.
 *
 * @param unpairTimerMillis how long an UNPAIR waits for a denial (`BuildConfig.UNPAIR_TIMER_MILLIS`).
 */
class RequestEngine(
    private val store: PairingStore,
    private val mailbox: Mailbox,
    private val effects: RemoteEffects,
    private val apps: AppBlocker,
    private val notifier: PairingNotifier,
    private val unpairTimerMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = store.dao

    /** Removes guardians whose UNPAIR completed. */
    lateinit var peers: PeerList

    /** The outcome of asking for something. */
    sealed interface AskResult {
        /** Sent to every guardian. */
        class Sent(val requestId: ByteArray) : AskResult

        /** This phone has no guardians: it can decide by itself. */
        data object NoGuardians : AskResult

        /** The explanation is too short or too long, or holds characters that can't be sent. */
        data object BadExplanation : AskResult

        /** The app isn't blocked, so there is nothing to ask. */
        data object NotBlocked : AskResult

        /** The app isn't installed. */
        data object NotInstalled : AskResult

        /** The same thing is already being asked. */
        data object AlreadyOpen : AskResult

        /** Every chance was used (D4, G8). */
        data object NoChancesLeft : AskResult

        /** The pairing to remove isn't one of this phone's guardians. */
        data object NotAGuardian : AskResult
    }

    // ---------------------------------------------------------------- protected side

    /**
     * Asks every guardian to unblock an app (SPEC.md §11.3).
     *
     * @param explanation 10–500 code points once trimmed (D3).
     */
    suspend fun askToUnblock(packageName: String, explanation: String): AskResult {
        val guardians = store.guardians()
        if (guardians.isEmpty()) return AskResult.NoGuardians
        val text = explanationOrNull(explanation, TextRule.REQUIRED_EXPLANATION) ?: return AskResult.BadExplanation
        if (!apps.isBlocked(packageName)) return AskResult.NotBlocked
        val app = apps.describe(packageName) ?: return AskResult.NotInstalled
        if (dao.openUnblockFor(packageName) != null) return AskResult.AlreadyOpen
        val chances = store.appChances(packageName, app.certDigest)
        if (chances < 1) return AskResult.NoChancesLeft

        val requestId = randomBytes(REQUEST_ID_BYTES)
        val request = OutgoingRequestEntity(
            requestId = requestId,
            type = RequestType.UNBLOCK_APP,
            packageName = packageName,
            certDigest = app.certDigest,
            appLabel = app.label,
            installer = app.installer,
            firstInstalled = app.firstInstalled,
            icon = app.icon,
            targetPairId = null,
            targetFp = null,
            explanation = text,
            chancesLeft = chances.coerceAtMost(PairingStore.MAX_CHANCES),
            createdAt = now(),
            timerEndsAt = null,
            state = RequestState.OPEN,
            cancelCause = null,
            closedAt = null,
            applied = false,
        )
        dao.insertOutgoing(request)
        sendToNewCopies(request, guardians)
        KillitLog.i(KillitLog.REQ, "Asked ${guardians.size} guardians to unblock $packageName (${chances} chances)")
        return AskResult.Sent(requestId)
    }

    /**
     * Asks every guardian, the one to be removed included (B1), to remove a guardian (§11.5). The
     * 30-day timer starts now.
     */
    suspend fun askToRemove(targetPairId: ByteArray, explanation: String): AskResult {
        val guardians = store.guardians()
        val target = guardians.firstOrNull { it.pairId.contentEquals(targetPairId) } ?: return AskResult.NotAGuardian
        val text = explanationOrNull(explanation, TextRule.REQUIRED_EXPLANATION) ?: return AskResult.BadExplanation
        if (dao.openUnpairFor(targetPairId) != null) return AskResult.AlreadyOpen
        if (target.unpairChances < 1) return AskResult.NoChancesLeft

        val requestId = randomBytes(REQUEST_ID_BYTES)
        val request = OutgoingRequestEntity(
            requestId = requestId,
            type = RequestType.UNPAIR,
            packageName = null,
            certDigest = null,
            appLabel = null,
            installer = null,
            firstInstalled = null,
            icon = null,
            targetPairId = target.pairId,
            targetFp = target.peerFp,
            explanation = text,
            chancesLeft = target.unpairChances.coerceAtMost(PairingStore.MAX_CHANCES),
            createdAt = now(),
            timerEndsAt = now() + unpairTimerMillis,
            state = RequestState.OPEN,
            cancelCause = null,
            closedAt = null,
            applied = false,
        )
        dao.insertOutgoing(request)
        sendToNewCopies(request, guardians)
        KillitLog.i(KillitLog.REQ, "Asked ${guardians.size} guardians to remove ${target.peerFp.short()}")
        return AskResult.Sent(requestId)
    }

    /** Withdraws one of this phone's open requests (B3); it costs no chance. */
    suspend fun withdraw(requestId: ByteArray) {
        val request = dao.outgoing(requestId)?.request ?: return
        if (request.state.isOpen) close(request, RequestState.CANCELLED, CancelCause.WITHDRAWN)
    }

    /** A guardian's DECISION on an UNBLOCK_APP or UNPAIR (§11.6). */
    suspend fun onDecision(received: Mailbox.Received) {
        val guardian = received.pairing ?: return
        val decision = received.body as MessageBody.Decision
        val request = dao.outgoing(decision.requestId) ?: return
        if (!request.request.state.isOpen) return
        val copy = request.copies.firstOrNull { it.pairId.contentEquals(guardian.pairId) } ?: return
        if (copy.vote != Vote.WAITING) return

        val stored = StoredRequest(
            requestId = request.request.requestId,
            target = targetOf(request.request),
            pairId = guardian.pairId,
            guardianFp = guardian.peerFp,
            guardianSignPk = guardian.peerSignPk,
            ownFp = received.header.recipientFp,
        )
        val verified = verifyDecision(stored, received.opened).valueOrNull()
        if (verified == null) {
            KillitLog.w(KillitLog.REQ, "A decision didn't match its request; ignored")
            return
        }
        val approved = verified.decision == Verdict.APPROVE
        dao.setVote(request.request.requestId, guardian.pairId, if (approved) Vote.APPROVED else Vote.DENIED, verified.note, now())
        KillitLog.i(KillitLog.REQ, "${guardian.peerFp.short()} ${if (approved) "approved" else "denied"} a ${request.request.type}")
        notifier.guardianAnswered(guardian, request.request, approved, verified.note)
        evaluate(request.request.requestId)
    }

    /** H11: an app with an open request was uninstalled. The request ends without using a chance. */
    suspend fun onPackageRemoved(packageName: String) {
        val request = dao.openUnblockFor(packageName) ?: return
        close(request, RequestState.CANCELLED, CancelCause.APP_UNINSTALLED)
    }

    /** G11: apps were unblocked directly in an unlocked session; their requests are settled. */
    suspend fun onUnblockedLocally(packageNames: Collection<String>) {
        for (name in packageNames) {
            val request = dao.openUnblockFor(name) ?: continue
            close(request, RequestState.CANCELLED, CancelCause.RESOLVED_BY_UNLOCK)
        }
    }

    /** G6, E-C2: a new guardian receives every request still open, and everyone the new count. */
    suspend fun onGuardianAdded(guardian: PairingEntity) {
        for (open in dao.openOutgoing()) {
            sendToNewCopies(open.request, listOf(guardian))
            sendStatus(open.request.requestId)
        }
    }

    /**
     * G6: a guardian is gone. Its answers no longer count, and every open request is evaluated
     * again; an UNPAIR aimed at it has nothing left to do.
     */
    suspend fun onGuardianRemoved(guardian: PairingEntity) {
        for (open in dao.openOutgoing()) {
            val request = open.request
            if (request.type == RequestType.UNPAIR && request.targetPairId?.contentEquals(guardian.pairId) == true) {
                close(request, RequestState.CANCELLED, CancelCause.GUARDIAN_REMOVED)
                continue
            }
            if (open.copies.none { it.pairId.contentEquals(guardian.pairId) }) continue
            dao.deleteCopy(request.requestId, guardian.pairId)
            evaluate(request.requestId)
        }
    }

    /**
     * Completes every UNPAIR whose 30 days passed without a denial (B1, B2), and cancels unblock
     * requests for apps that are gone (H11, for an uninstall the receiver missed).
     */
    suspend fun checkTimers() {
        for (open in dao.openOutgoing()) {
            val request = open.request
            when (request.type) {
                RequestType.UNPAIR -> if ((request.timerEndsAt ?: Long.MAX_VALUE) <= now()) {
                    KillitLog.i(KillitLog.REQ, "UNPAIR timer ran out; removing ${request.targetFp?.short()}")
                    close(request, RequestState.APPROVED, null, timer = true)
                }
                RequestType.UNBLOCK_APP -> if (apps.describe(request.packageName ?: continue) == null) {
                    close(request, RequestState.CANCELLED, CancelCause.APP_UNINSTALLED)
                }
                RequestType.UNLOCK -> Unit
            }
        }
    }

    /** Carries out approvals that were cut short, for example by the process dying. */
    suspend fun applyPendingApprovals() {
        for (request in dao.unappliedApprovals()) apply(request, timer = false)
    }

    /** The next time an UNPAIR timer runs out, so a check can be scheduled for it. */
    suspend fun nextTimer(): Long? =
        dao.openOutgoing().mapNotNull { it.request.timerEndsAt }.minOrNull()

    /** Decides from the copies whether a request is complete, and acts on it (§3.3). */
    private suspend fun evaluate(requestId: ByteArray) {
        val current = dao.outgoing(requestId) ?: return
        val request = current.request
        if (!request.state.isOpen) return
        when {
            current.copies.isEmpty() -> close(request, RequestState.CANCELLED, CancelCause.GUARDIAN_REMOVED)
            current.copies.any { it.vote == Vote.DENIED } -> close(request, RequestState.DENIED, null)
            current.copies.all { it.vote == Vote.APPROVED } -> close(request, RequestState.APPROVED, null)
            else -> sendStatus(requestId)
        }
    }

    /**
     * Closes a request: records it, tells every guardian (STATUS), then carries out what the
     * outcome means.
     */
    private suspend fun close(request: OutgoingRequestEntity, state: RequestState, cause: CancelCause?, timer: Boolean = false) {
        // Removing a guardian can settle other requests along the way; close each only once.
        if (dao.outgoing(request.requestId)?.request?.state?.isOpen != true) return
        dao.closeOutgoing(request.requestId, state, cause, now())
        sendStatus(request.requestId)
        val closed = dao.outgoing(request.requestId)?.request ?: return
        history(closed)
        notifier.requestClosed(closed)
        KillitLog.i(KillitLog.REQ, "${request.type} closed: $state${cause?.let { " ($it)" } ?: ""}")
        when (state) {
            RequestState.DENIED -> spendChance(closed)
            RequestState.APPROVED -> apply(closed, timer)
            RequestState.OPEN, RequestState.CANCELLED -> Unit
        }
    }

    /** A denial costs the app, or the guardian to be removed, one chance (D4, G8). */
    private suspend fun spendChance(request: OutgoingRequestEntity) {
        when (request.type) {
            RequestType.UNBLOCK_APP -> {
                val packageName = request.packageName ?: return
                val digest = request.certDigest ?: return
                store.setAppChances(packageName, digest, (store.appChances(packageName, digest) - 1).coerceAtLeast(0))
            }
            RequestType.UNPAIR -> {
                val target = dao.pairing(request.targetPairId ?: return) ?: return
                store.setUnpairChances(target.pairId, (target.unpairChances - 1).coerceAtLeast(0))
            }
            RequestType.UNLOCK -> Unit
        }
    }

    /** Carries out an approval, at most once. */
    private suspend fun apply(request: OutgoingRequestEntity, timer: Boolean) {
        when (request.type) {
            RequestType.UNBLOCK_APP -> {
                val packageName = request.packageName ?: return
                val digest = request.certDigest ?: return
                if (!effects.apply(RemoteEffects.Effect.UnblockApp(packageName, digest))) return
                // G7: an unblocked app starts again with every chance.
                store.setAppChances(packageName, digest, PairingStore.MAX_CHANCES)
            }
            RequestType.UNPAIR -> {
                val target = request.targetPairId ?: return
                peers.removeGuardian(target, if (timer) UnpairedHow.TIMER else UnpairedHow.ALL_APPROVED)
            }
            RequestType.UNLOCK -> Unit
        }
        dao.markApplied(request.requestId)
    }

    /** Sends a request to guardians that don't have it yet, and records their copies. */
    private suspend fun sendToNewCopies(request: OutgoingRequestEntity, guardians: List<PairingEntity>) {
        val existing = dao.outgoing(request.requestId)?.copies.orEmpty()
        val fresh = guardians.filter { g -> existing.none { it.pairId.contentEquals(g.pairId) } }
        if (fresh.isEmpty()) return
        dao.upsertCopies(fresh.map { RequestCopyEntity(request.requestId, it.pairId, it.peerFp, Vote.WAITING, "", null) })
        val count = (existing.size + fresh.size).coerceIn(1, MAX_GUARDIANS)
        val body = when (request.type) {
            RequestType.UNBLOCK_APP -> MessageBody.UnblockApp(
                requestId = request.requestId,
                app = AppInfo(
                    packageName = request.packageName!!,
                    certDigest = request.certDigest!!,
                    label = request.appLabel.orEmpty(),
                    installer = request.installer.orEmpty(),
                    firstInstalled = request.firstInstalled ?: 0L,
                    icon = request.icon,
                ),
                explanation = request.explanation,
                chancesLeft = request.chancesLeft,
                guardianCount = count,
            )
            RequestType.UNPAIR -> MessageBody.Unpair(
                requestId = request.requestId,
                targetPairId = request.targetPairId!!,
                targetFp = request.targetFp!!,
                explanation = request.explanation,
                chancesLeft = request.chancesLeft,
                guardianCount = count,
                timerEndsAt = request.timerEndsAt!!,
            )
            RequestType.UNLOCK -> error("a live unlock is LiveUnlock's")
        }
        fresh.forEach { mailbox.post(it, body) }
    }

    /**
     * Sends every guardian with a copy where the request stands and how each guardian answered
     * (§11.7). Names are the guardians' own, which this phone can't edit.
     */
    private suspend fun sendStatus(requestId: ByteArray) {
        val current = dao.outgoing(requestId) ?: return
        val request = current.request
        val guardians = current.copies.mapNotNull { copy -> dao.pairing(copy.pairId)?.let { copy to it } }
        val entries = guardians.take(MAX_STATUS_ENTRIES).map { (copy, pairing) ->
            StatusEntry(copy.guardianFp, pairing.peerName, copy.vote.toGuardianState())
        }
        val body = MessageBody.Status(
            requestId = requestId,
            requestKind = request.type.toRequestKind(),
            outcome = request.state.toOutcome(),
            reason = request.cancelCause.toCancelReason(),
            guardians = entries,
        )
        guardians.forEach { (_, pairing) -> mailbox.post(pairing, body) }
    }

    private suspend fun history(request: OutgoingRequestEntity) {
        val type = when (request.state) {
            RequestState.APPROVED -> EventType.REQUEST_APPROVED
            RequestState.DENIED -> EventType.REQUEST_DENIED
            RequestState.CANCELLED -> EventType.REQUEST_CANCELLED
            RequestState.OPEN -> return
        }
        val target = request.targetPairId?.let { dao.pairing(it) }
        dao.insertEvent(
            EventEntity(
                pairId = request.targetPairId,
                at = now(),
                type = type,
                peerName = target?.nickname,
                appLabel = request.appLabel,
                packageName = request.packageName,
                otherName = null,
                value = request.type.toRequestKind().code,
                flag = null,
                time = null,
            ),
        )
    }

    // ---------------------------------------------------------------- guardian side

    /** A request from a phone this phone guards. A repeat of one already stored is ignored. */
    suspend fun onIncoming(received: Mailbox.Received) {
        val from = received.pairing ?: return
        val base = IncomingBase(from.pairId, received.header.createdAt)
        val entity = when (val body = received.body) {
            is MessageBody.UnblockApp -> base.entity(
                requestId = body.requestId,
                type = RequestType.UNBLOCK_APP,
                app = body.app,
                explanation = body.explanation,
                chancesLeft = body.chancesLeft,
                guardianCount = body.guardianCount,
            )
            is MessageBody.Unlock -> base.entity(
                requestId = body.requestId,
                type = RequestType.UNLOCK,
                sessionNonce = body.sessionNonce,
                explanation = body.explanation,
                guardianCount = body.guardianCount,
            )
            is MessageBody.Unpair -> base.entity(
                requestId = body.requestId,
                type = RequestType.UNPAIR,
                targetPairId = body.targetPairId,
                targetFp = body.targetFp,
                timerEndsAt = body.timerEndsAt,
                explanation = body.explanation,
                chancesLeft = body.chancesLeft,
                guardianCount = body.guardianCount,
            )
            else -> return
        }
        if (dao.insertIncoming(entity) == -1L) return
        KillitLog.i(KillitLog.REQ, "${entity.type} arrived from ${from.peerFp.short()}")
        notifier.requestArrived(from, entity)
    }

    /**
     * Answers a request from a phone this phone guards (§11.6). Once per request: the answer
     * can't be changed.
     *
     * @param note optional, at most 300 code points once trimmed (G9).
     * @return false when there is nothing to answer, or the note can't be sent.
     */
    suspend fun decide(pairId: ByteArray, requestId: ByteArray, approve: Boolean, note: String): Boolean {
        val pairing = dao.pairing(pairId)?.takeIf { it.myRole == PairRole.GUARDIAN } ?: return false
        val request = dao.incoming(pairId, requestId) ?: return false
        if (!request.state.isOpen || request.myVote != Vote.WAITING) return false
        val text = explanationOrNull(note, TextRule.NOTE) ?: return false
        val target = when (request.type) {
            RequestType.UNBLOCK_APP -> DecisionTarget.App(AppRef(request.packageName ?: return false, request.certDigest ?: return false))
            RequestType.UNLOCK -> DecisionTarget.Session(request.sessionNonce ?: return false)
            RequestType.UNPAIR -> DecisionTarget.Pairing(request.targetPairId ?: return false)
        }
        val verdict = if (approve) Verdict.APPROVE else Verdict.DENY
        mailbox.post(pairing, MessageBody.Decision(requestId, target, verdict, text))
        dao.setMyVote(pairId, requestId, if (approve) Vote.APPROVED else Vote.DENIED, text, now())
        KillitLog.i(KillitLog.REQ, "${if (approve) "Approved" else "Denied"} a ${request.type} from ${pairing.peerFp.short()}")
        notifier.requestSettled(pairing, request)
        return true
    }

    /** Where a request this phone was asked about stands, and the other guardians' answers (G10). */
    suspend fun onStatus(received: Mailbox.Received) {
        val from = received.pairing ?: return
        val status = received.body as MessageBody.Status
        val request = dao.incoming(from.pairId, status.requestId) ?: return
        if (request.type.toRequestKind() != status.requestKind) return
        val entries = status.guardians.map { StatusEntryRecord(it.guardianFp.toHex(), it.guardianName, it.state.toVote()) }
        val state = status.outcome.toState()
        dao.setIncomingStatus(from.pairId, status.requestId, state, status.reason.toCause(), StatusEntries.encode(entries))
        if (request.state.isOpen && !state.isOpen) {
            dao.incoming(from.pairId, status.requestId)?.let { notifier.requestSettled(from, it) }
        }
    }

    /** Requests still waiting for this phone's answer, for reminders (D9). */
    suspend fun waitingForMe(): List<IncomingRequestEntity> = dao.waitingForMe()

    /** Fields every incoming request shares. */
    private inner class IncomingBase(val pairId: ByteArray, val senderCreatedAt: Long) {
        fun entity(
            requestId: ByteArray,
            type: RequestType,
            explanation: String,
            guardianCount: Int,
            app: AppInfo? = null,
            sessionNonce: ByteArray? = null,
            targetPairId: ByteArray? = null,
            targetFp: ByteArray? = null,
            timerEndsAt: Long? = null,
            chancesLeft: Int? = null,
        ) = IncomingRequestEntity(
            pairId = pairId,
            requestId = requestId,
            type = type,
            packageName = app?.packageName,
            certDigest = app?.certDigest,
            appLabel = app?.label,
            installer = app?.installer,
            firstInstalled = app?.firstInstalled,
            icon = app?.icon,
            sessionNonce = sessionNonce,
            targetPairId = targetPairId,
            targetFp = targetFp,
            timerEndsAt = timerEndsAt,
            explanation = explanation,
            chancesLeft = chancesLeft,
            guardianCount = guardianCount,
            senderCreatedAt = senderCreatedAt,
            receivedAt = now(),
            myVote = Vote.WAITING,
            myNote = "",
            decidedAt = null,
            state = RequestState.OPEN,
            cancelCause = null,
            statusEntries = StatusEntries.encode(emptyList()),
            remindedAt = null,
        )
    }

    companion object {
        /**
         * Trims text the way receivers expect it (SPEC.md §1.3) and checks it against its rule.
         *
         * @return the text to send, or null when it can't be sent.
         */
        fun explanationOrNull(text: String, rule: TextRule): String? {
            val trimmed = trimText(text)
            return trimmed.takeIf { runCatching { encodeText(it, rule) }.isSuccess }
        }

        /** What a DECISION on [request] must bind (§11.6). */
        internal fun targetOf(request: OutgoingRequestEntity): DecisionTarget = when (request.type) {
            RequestType.UNBLOCK_APP -> DecisionTarget.App(AppRef(request.packageName!!, request.certDigest!!))
            RequestType.UNPAIR -> DecisionTarget.Pairing(request.targetPairId!!)
            RequestType.UNLOCK -> error("a live unlock binds its in-memory session")
        }
    }
}

internal fun RequestType.toRequestKind(): RequestKind = when (this) {
    RequestType.UNBLOCK_APP -> RequestKind.UNBLOCK_APP
    RequestType.UNLOCK -> RequestKind.UNLOCK
    RequestType.UNPAIR -> RequestKind.UNPAIR
}

internal fun RequestState.toOutcome(): Outcome = when (this) {
    RequestState.OPEN -> Outcome.OPEN
    RequestState.APPROVED -> Outcome.APPROVED
    RequestState.DENIED -> Outcome.DENIED
    RequestState.CANCELLED -> Outcome.CANCELLED
}

internal fun Outcome.toState(): RequestState = when (this) {
    Outcome.OPEN -> RequestState.OPEN
    Outcome.APPROVED -> RequestState.APPROVED
    Outcome.DENIED -> RequestState.DENIED
    Outcome.CANCELLED -> RequestState.CANCELLED
}

internal fun CancelCause?.toCancelReason(): CancelReason = when (this) {
    null -> CancelReason.NONE
    CancelCause.WITHDRAWN -> CancelReason.WITHDRAWN
    CancelCause.APP_UNINSTALLED -> CancelReason.APP_UNINSTALLED
    CancelCause.RESOLVED_BY_UNLOCK -> CancelReason.RESOLVED_BY_UNLOCK
    CancelCause.GUARDIAN_REMOVED -> CancelReason.GUARDIAN_REMOVED
}

internal fun CancelReason.toCause(): CancelCause? = when (this) {
    CancelReason.NONE -> null
    CancelReason.WITHDRAWN -> CancelCause.WITHDRAWN
    CancelReason.APP_UNINSTALLED -> CancelCause.APP_UNINSTALLED
    CancelReason.RESOLVED_BY_UNLOCK -> CancelCause.RESOLVED_BY_UNLOCK
    CancelReason.GUARDIAN_REMOVED -> CancelCause.GUARDIAN_REMOVED
}

internal fun Vote.toGuardianState(): GuardianState = when (this) {
    Vote.WAITING -> GuardianState.WAITING
    Vote.APPROVED -> GuardianState.APPROVED
    Vote.DENIED -> GuardianState.DENIED
}

internal fun GuardianState.toVote(): Vote = when (this) {
    GuardianState.WAITING -> Vote.WAITING
    GuardianState.APPROVED -> Vote.APPROVED
    GuardianState.DENIED -> Vote.DENIED
}
