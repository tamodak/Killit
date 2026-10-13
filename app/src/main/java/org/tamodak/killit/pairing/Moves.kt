package org.tamodak.killit.pairing

import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.DurablePairingStore
import org.tamodak.killit.data.MovedAway
import org.tamodak.killit.pairing.store.AppChancesEntity
import org.tamodak.killit.pairing.store.CeremonyEntity
import org.tamodak.killit.pairing.store.CeremonyFailure
import org.tamodak.killit.pairing.store.CeremonyPurpose
import org.tamodak.killit.pairing.store.CeremonySide
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.EventType
import org.tamodak.killit.pairing.store.LinkDeletionEntity
import org.tamodak.killit.pairing.store.MovePageEntity
import org.tamodak.killit.pairing.store.MoveStage
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.protocol.AppState
import org.tamodak.killit.protocol.Assembly
import org.tamodak.killit.protocol.Bundle
import org.tamodak.killit.protocol.Kind
import org.tamodak.killit.protocol.MAX_CHANCES
import org.tamodak.killit.protocol.MAX_MOVED_APPS
import org.tamodak.killit.protocol.MOVE_ID_BYTES
import org.tamodak.killit.protocol.MessageBody
import org.tamodak.killit.protocol.MoveAnswerCode
import org.tamodak.killit.protocol.MoveDeclineReason
import org.tamodak.killit.protocol.MoveOutcome
import org.tamodak.killit.protocol.MovedApp
import org.tamodak.killit.protocol.MovedPairing
import org.tamodak.killit.protocol.MovedState
import org.tamodak.killit.protocol.NoticeBody
import org.tamodak.killit.protocol.PROTECTION_BITS
import org.tamodak.killit.protocol.Role
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.assembleMoveState
import org.tamodak.killit.protocol.decodeBody
import org.tamodak.killit.protocol.encodeBody
import org.tamodak.killit.protocol.fingerprint
import org.tamodak.killit.protocol.pageMoveState
import org.tamodak.killit.protocol.randomBytes

/**
 * Moving to a new phone (PLAN.md §3.10, SPEC.md §11.14): one person's pairings, and the protection
 * of an old phone that has guardians, go from the old phone to the new one.
 *
 * ### The ceremony
 *
 * The new phone creates the invite and the old one joins it, and the two screens are compared side
 * by side (SPEC.md §6.5). [CeremonyEngine] runs that part and hands over here once they are linked.
 * A code alone wouldn't do: a server answering the join with its own keys would receive every
 * pairing.
 *
 * ### Old phone
 *
 * It answers MOVE_REQUEST with its state, in pages, and waits for the new phone to say it is ready.
 * Then its user confirms, within 30 minutes: the server copies its links to the new phone, its own
 * open requests are withdrawn, every pair receives a HANDOVER signed with the key it already trusts,
 * and the new phone receives MOVE_DONE last. Once all of that has gone out, the phone forgets every
 * pairing and deletes its identity. If it had guardians it stays frozen: its blocks stay, and only
 * releasing device owner ends that, so a move can't be a way to shed them.
 *
 * ### New phone
 *
 * It checks the pages and stages them, and puts them in use only on MOVE_DONE (COMMITTED). It sends
 * its pairs nothing before then, so each pair has handled the HANDOVER before anything from the new
 * phone reaches it. The protection a move brings passes through [RemoteEffects] as effect (e), which
 * a phone with guardians never takes.
 *
 * Callers hold the engine's lock.
 *
 * @param deviceModel this phone's `Build.MANUFACTURER` and `Build.MODEL`, sent in MOVE_REQUEST.
 * @param canBeProtected whether this phone can take guardians: device owner, with a passkey, and
 *   not frozen by a move of its own.
 * @param protectionBits this phone's protection options, bit (option − 1) each.
 */
class MoveEngine(
    private val store: PairingStore,
    private val mailbox: Mailbox,
    private val effects: RemoteEffects,
    private val apps: AppBlocker,
    private val durable: DurablePairingStore,
    private val deviceModel: String,
    private val canBeProtected: suspend () -> Boolean,
    private val protectionBits: suspend () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = store.dao

    /** Ends the ceremony when a move doesn't happen. */
    lateinit var ceremony: CeremonyEngine

    /** Tells the guardians what a move blocked here as new. */
    lateinit var peers: PeerList

    /** Withdraws the old phone's own requests before it moves. */
    lateinit var requests: RequestEngine

    /** Withdraws a live unlock the old phone still waits on. */
    lateinit var unlock: LiveUnlock

    /** Where committing a move stands. */
    sealed interface Commit {
        /** The new phone is ready, and is [newFp]. */
        class Ready(val newFp: ByteArray) : Commit

        /** There is no move ready to commit. */
        data object NotReady : Commit

        /** More than 30 minutes passed since the new phone was ready; the move is given up. */
        data object TooLate : Commit

        /** A pairing came or went since the state was sent; the move is given up. */
        data object Changed : Commit
    }

    // ---------------------------------------------------------------- new phone

    /** New phone: linked to the old one, so it asks for the old phone's state. */
    suspend fun onLinked(current: CeremonyEntity) {
        val peer = ceremony.peerBundle(current) ?: return
        mailbox.post(current.pairId ?: return, fingerprint(peer.signPk), peer.encPk, MessageBody.MoveRequest(deviceModel, canBeProtected()))
        dao.updateCeremony(current.copy(stage = CeremonyStage.LINKED, moveStage = MoveStage.AWAITING_STATE))
        KillitLog.i(KillitLog.PAIR, "Linked to the old phone; asking for its state")
    }

    /**
     * New phone: one page of the old phone's state. Once every page is in, the whole is checked,
     * and the old phone hears whether this phone can take it.
     */
    suspend fun onMoveState(received: Mailbox.Received) {
        val current = moveOf(received, CeremonySide.CREATOR) ?: return
        if (current.moveStage != MoveStage.AWAITING_STATE) return
        val page = received.body as MessageBody.MoveState
        val existing = dao.movePage(page.page)
        if (existing != null) {
            if (!existing.body.contentEquals(received.opened.body)) decline(current, page.moveId, MoveDeclineReason.INVALID, CeremonyFailure.INVALID)
            return
        }
        dao.insertMovePage(MovePageEntity(page.page, received.opened.body))
        if (dao.movePages().size < page.pageCount) return

        val state = when (val assembled = staged(received.header.recipientFp, received.header.senderFp)) {
            is Assembly.Incomplete -> return
            is Assembly.Invalid -> {
                KillitLog.w(KillitLog.PAIR, "The old phone's state breaks a rule: ${assembled.reason}")
                decline(current, page.moveId, MoveDeclineReason.INVALID, CeremonyFailure.INVALID)
                return
            }
            is Assembly.Complete -> assembled.value
        }
        val guardians = state.pairings.count { it.role == Role.PROTECTED }
        when {
            dao.pairings().isNotEmpty() -> decline(current, state.moveId, MoveDeclineReason.HAS_PAIRINGS, CeremonyFailure.HAS_PAIRINGS)
            guardians > 0 && !canBeProtected() ->
                decline(current, state.moveId, MoveDeclineReason.NOT_DEVICE_OWNER, CeremonyFailure.NOT_DEVICE_OWNER)
            else -> {
                postToPeer(current, MessageBody.MoveAnswer(state.moveId, MoveAnswerCode.READY, MoveDeclineReason.NONE))
                dao.updateCeremony(
                    current.copy(
                        moveStage = MoveStage.READY,
                        moveId = state.moveId,
                        moveReadyAt = now(),
                        moveGuardians = guardians,
                        moveWards = state.pairings.size - guardians,
                    ),
                )
                KillitLog.i(KillitLog.PAIR, "Ready to take ${state.pairings.size} pairings over")
            }
        }
    }

    /** New phone: the old phone committed the move, or gave it up. */
    suspend fun onMoveDone(received: Mailbox.Received) {
        val current = moveOf(received, CeremonySide.CREATOR) ?: return
        val done = received.body as MessageBody.MoveDone
        when (current.moveStage) {
            // The old phone stops at once when it has guardians this phone can't take.
            MoveStage.AWAITING_STATE -> if (done.outcome == MoveOutcome.ABANDONED) {
                dao.deleteMovePages()
                ceremony.failCurrent(if (canBeProtected()) CeremonyFailure.ABANDONED else CeremonyFailure.NOT_DEVICE_OWNER)
            }
            MoveStage.READY -> when {
                !done.moveId.contentEquals(current.moveId) -> Unit
                done.outcome == MoveOutcome.ABANDONED -> {
                    dao.deleteMovePages()
                    ceremony.failCurrent(CeremonyFailure.ABANDONED)
                }
                else -> activate(current, received.header.recipientFp, received.header.senderFp)
            }
            else -> Unit
        }
    }

    /**
     * New phone: puts the staged state in use (SPEC.md §11.14 step 5). The pairings become this
     * phone's; with guardians among them, so does the old phone's protection, through effect (e).
     *
     * Safe to repeat after a crash: the message is delivered again, pairings already added are
     * kept, and effect (e) is refused once guardians are here, because it was applied already.
     */
    private suspend fun activate(current: CeremonyEntity, newFp: ByteArray, oldFp: ByteArray) {
        val state = (staged(newFp, oldFp) as? Assembly.Complete)?.value ?: return
        var blockedAsNew = emptyList<String>()
        if (state.pairings.any { it.role == Role.PROTECTED }) {
            val adopt = RemoteEffects.Effect.AdoptMove(state.apps.map { it.app to it.state }, state.appManagement, state.protection)
            if (effects.apply(adopt) == RemoteEffects.Outcome.APPLIED) blockedAsNew = adopt.blockedAsNew
            store.setAllAppChances(state.apps.map { AppChancesEntity(it.app.packageName, it.app.certDigest, it.chancesLeft) })
        }
        for (moved in state.pairings) {
            if (dao.pairing(moved.pairId) != null) continue
            val pairing = moved.toEntity()
            store.addPairing(pairing)
            dao.deleteLinkDeletion(pairing.peerFp)
            dao.insertEvent(EventEntity(pairId = pairing.pairId, at = now(), type = EventType.MOVED_HERE, peerName = pairing.nickname, appLabel = null, packageName = null, otherName = null, value = null, flag = null, time = null))
        }
        // The guardians hear of what was blocked here as new, as of any install (D1).
        for (name in blockedAsNew) apps.describe(name)?.let { peers.notifyGuardians(NoticeBody.AppInstalled(it, blocked = true)) }
        // The ceremony's link has done its work; the old phone deletes itself anyway.
        dao.requestLinkDeletion(LinkDeletionEntity(oldFp, now()))
        dao.deleteMovePages()
        dao.updateCeremony(current.copy(stage = CeremonyStage.PAIRED, moveStage = MoveStage.MOVED))
        KillitLog.i(KillitLog.PAIR, "Moved here: ${state.pairings.size} pairings, ${state.apps.size} decisions about apps")
    }

    /** Puts the staged pages together, with every check of SPEC.md §11.14 step 3. */
    private suspend fun staged(newFp: ByteArray, oldFp: ByteArray): Assembly<MovedState> =
        assembleMoveState(stagedPages(), newFp, oldFp)

    private fun MovedPairing.toEntity(): PairingEntity {
        val guarding = role == Role.GUARDIAN
        return PairingEntity(
            pairId = pairId,
            peerFp = fingerprint(peer.signPk),
            peerSignPk = peer.signPk,
            peerEncPk = peer.encPk,
            peerEncSig = peer.encSig,
            myRole = if (guarding) PairRole.GUARDIAN else PairRole.PROTECTED,
            peerName = peerName,
            nickname = nickname,
            peerModel = peerModel,
            appManagement = guarding && appManagement,
            createdAt = createdAt,
            unpairChances = if (guarding) PairingStore.MAX_CHANCES else unpairChances,
            lastSeenDaysAgo = null,
            silenceAlerted = false,
            // This phone's MANAGE commands are counted afresh: the guarded phone forgot the old
            // phone's when it handled the HANDOVER (SPEC.md §11.13).
            manageSeq = 0L,
        )
    }

    // ---------------------------------------------------------------- old phone

    /**
     * Old phone: the new phone asks for this phone's state. A phone with guardians moves only to a
     * phone that can take them: only a device owner can be protected (PLAN.md §4).
     */
    suspend fun onMoveRequest(received: Mailbox.Received) {
        val current = received.ceremony ?: return
        if (current.side != CeremonySide.JOINER) return
        if (current.stage != CeremonyStage.CONFIRMED && current.stage != CeremonyStage.LINKED) return
        if (current.purpose != CeremonyPurpose.MOVE) {
            // The code was a move's, and this phone joined it to pair: neither happens.
            postToPeer(current, MessageBody.MoveDone(randomBytes(MOVE_ID_BYTES), MoveOutcome.ABANDONED))
            ceremony.failCurrent(CeremonyFailure.WRONG_PURPOSE)
            return
        }
        // A background sync may read the mailbox before the invite showed this phone the link.
        if (current.stage == CeremonyStage.LINKED && current.moveStage != MoveStage.AWAITING_REQUEST) return
        val request = received.body as MessageBody.MoveRequest
        val moveId = randomBytes(MOVE_ID_BYTES)
        val guarded = store.guardians().isNotEmpty()
        if (guarded && !request.deviceOwner) {
            postToPeer(current, MessageBody.MoveDone(moveId, MoveOutcome.ABANDONED))
            ceremony.failCurrent(CeremonyFailure.NOT_DEVICE_OWNER)
            KillitLog.w(KillitLog.PAIR, "The new phone can't take guardians; not moving")
            return
        }
        val state = stateToMove(moveId, guarded)
        val pages = pageMoveState(state)
        pages.forEach { postToPeer(current, it) }
        // Kept to check, when the user confirms, that the pairings sent are still the ones there are.
        dao.deleteMovePages()
        pages.forEach { dao.insertMovePage(MovePageEntity(it.page, encodeBody(it))) }
        val guardians = state.pairings.count { it.role == Role.PROTECTED }
        dao.updateCeremony(
            current.copy(
                stage = CeremonyStage.LINKED,
                moveStage = MoveStage.AWAITING_ANSWER,
                moveId = moveId,
                movePeerModel = request.deviceModel,
                moveGuardians = guardians,
                moveWards = state.pairings.size - guardians,
            ),
        )
        KillitLog.i(KillitLog.PAIR, "Sent ${state.pairings.size} pairings to the new phone in ${pages.size} pages")
    }

    /** Old phone: whether the new phone took the state. */
    suspend fun onMoveAnswer(received: Mailbox.Received) {
        val current = moveOf(received, CeremonySide.JOINER) ?: return
        val answer = received.body as MessageBody.MoveAnswer
        if (current.moveStage != MoveStage.AWAITING_ANSWER || !answer.moveId.contentEquals(current.moveId)) return
        if (answer.answer == MoveAnswerCode.READY) {
            dao.updateCeremony(current.copy(moveStage = MoveStage.READY, moveReadyAt = now()))
            KillitLog.i(KillitLog.PAIR, "The new phone is ready")
            return
        }
        dao.deleteMovePages()
        ceremony.failCurrent(
            when (answer.reason) {
                MoveDeclineReason.NOT_DEVICE_OWNER -> CeremonyFailure.NOT_DEVICE_OWNER
                MoveDeclineReason.HAS_PAIRINGS -> CeremonyFailure.HAS_PAIRINGS
                MoveDeclineReason.INVALID -> CeremonyFailure.INVALID
                MoveDeclineReason.USER, MoveDeclineReason.NONE -> CeremonyFailure.DECLINED
            },
        )
    }

    /**
     * Old phone: whether the move can be committed now. A move whose window closed, or whose
     * pairings changed since the state was sent, is given up here.
     */
    suspend fun prepareCommit(): Commit {
        val current = oldPhoneMove() ?: return Commit.NotReady
        if (current.moveStage != MoveStage.READY) return Commit.NotReady
        if (now() - (current.moveReadyAt ?: 0L) > COMMIT_WINDOW_MILLIS) {
            abandon(current, CeremonyFailure.EXPIRED)
            return Commit.TooLate
        }
        val sent = stagedPages().flatMap { it.pairings }.map { it.pairId.toList() }.toSet()
        if (sent != dao.pairings().map { it.pairId.toList() }.toSet()) {
            abandon(current, CeremonyFailure.PAIRINGS_CHANGED)
            return Commit.Changed
        }
        val peer = ceremony.peerBundle(current) ?: return Commit.NotReady
        return Commit.Ready(fingerprint(peer.signPk))
    }

    /**
     * Old phone: the server has copied the links to [newFp]. Every pair receives its HANDOVER, the
     * new phone MOVE_DONE (COMMITTED) last, so the pairs' mailboxes hold the HANDOVER ahead of
     * anything the new phone sends them. This phone's own requests are withdrawn first: the new
     * phone doesn't take them over.
     */
    suspend fun commit(newFp: ByteArray): Commit {
        val check = prepareCommit()
        if (check !is Commit.Ready) return check
        if (!check.newFp.contentEquals(newFp)) return Commit.NotReady
        val current = oldPhoneMove() ?: return Commit.NotReady
        val newBundle = ceremony.peerBundle(current) ?: return Commit.NotReady
        unlock.cancel()
        requests.withdrawAll()
        val handover = MessageBody.Handover(newBundle, current.movePeerModel.orEmpty())
        dao.pairings().forEach { mailbox.post(it, handover) }
        postToPeer(current, MessageBody.MoveDone(current.moveId ?: return Commit.NotReady, MoveOutcome.COMMITTED))
        dao.updateCeremony(current.copy(moveStage = MoveStage.COMMITTING))
        KillitLog.i(KillitLog.PAIR, "Move committed; telling the pairs")
        return check
    }

    /**
     * Old phone: once everything the commit queued has gone out, every pairing is forgotten. A
     * phone that had guardians is frozen in the same durable write (PLAN.md §3.10), so it can't come
     * out of this with neither its guardians nor the freeze.
     *
     * @return true when this phone just finished moving, so its identity can go.
     */
    suspend fun finishIfSent(): Boolean {
        val current = oldPhoneMove()?.takeIf { it.moveStage == MoveStage.COMMITTING } ?: return false
        if (dao.outbox(1).isNotEmpty()) return false
        val guarded = store.guardians().isNotEmpty()
        store.clear(movedAway = if (guarded) MovedAway(now(), current.movePeerModel.orEmpty()) else null)
        // Kept so the screen can say where everything went. Nothing follows it any more.
        dao.insertCeremony(current.copy(stage = CeremonyStage.PAIRED, moveStage = MoveStage.MOVED))
        KillitLog.i(KillitLog.PAIR, "Moved away${if (guarded) "; this phone stays frozen" else ""}")
        return true
    }

    /**
     * The user gives the move up. The old phone may until it commits. The new phone may until it
     * says it is ready, and after that only once the old phone can no longer commit.
     *
     * @return false when giving up isn't allowed now.
     */
    suspend fun cancel(current: CeremonyEntity): Boolean {
        when (current.side) {
            CeremonySide.JOINER -> when (current.moveStage) {
                MoveStage.COMMITTING, MoveStage.MOVED -> return false
                else -> abandon(current, CeremonyFailure.CANCELLED)
            }
            CeremonySide.CREATOR -> when (current.moveStage) {
                MoveStage.MOVED -> return false
                MoveStage.READY -> {
                    if (now() < (current.moveReadyAt ?: 0L) + GIVE_UP_AFTER_MILLIS) return false
                    dao.deleteMovePages()
                    ceremony.failCurrent(CeremonyFailure.CANCELLED)
                }
                else -> {
                    // The old phone chose the move id; without a page of its state there is none.
                    val moveId = current.moveId ?: stagedPages().firstOrNull()?.moveId
                    if (moveId != null) postToPeer(current, MessageBody.MoveAnswer(moveId, MoveAnswerCode.DECLINE, MoveDeclineReason.USER))
                    dao.deleteMovePages()
                    ceremony.failCurrent(CeremonyFailure.CANCELLED)
                }
            }
        }
        KillitLog.i(KillitLog.PAIR, "Move given up")
        return true
    }

    /** True when a move is under way that must not be cut short by starting another ceremony. */
    fun mustFinish(current: CeremonyEntity): Boolean =
        current.purpose == CeremonyPurpose.MOVE && current.stage == CeremonyStage.LINKED && when (current.side) {
            CeremonySide.JOINER -> current.moveStage == MoveStage.COMMITTING
            CeremonySide.CREATOR -> current.moveStage == MoveStage.READY && now() < (current.moveReadyAt ?: 0L) + GIVE_UP_AFTER_MILLIS
        }

    /** What this phone hands its new phone (SPEC.md §11.14 step 2). */
    private suspend fun stateToMove(moveId: ByteArray, guarded: Boolean): MovedState {
        val pairings = dao.pairings().map { it.toMoved() }
        if (!guarded) return MovedState(moveId, appManagement = false, protection = 0, pairings, emptyList())
        val decisions = apps.decisionsToMove()
            // Within what one move carries, blocks come first: they are what protects the phone.
            .sortedBy { (_, state) -> if (state == AppState.ALLOWED) 1 else 0 }
            .take(MAX_MOVED_APPS)
            .map { (app, state) -> MovedApp(app, state, store.appChances(app.packageName, app.certDigest).coerceIn(0, MAX_CHANCES)) }
        return MovedState(moveId, durable.readAppManagement() == true, protectionBits() and PROTECTION_BITS, pairings, decisions)
    }

    private fun PairingEntity.toMoved(): MovedPairing {
        val guarding = myRole == PairRole.GUARDIAN
        return MovedPairing(
            pairId = pairId,
            role = if (guarding) Role.GUARDIAN else Role.PROTECTED,
            peer = Bundle(peerSignPk, peerEncPk, peerEncSig),
            peerName = peerName,
            // A name the protocol can't carry would stop the whole move; the peer's own name can.
            nickname = RequestEngine.explanationOrNull(nickname, TextRule.SELF_NAME) ?: peerName,
            peerModel = RequestEngine.explanationOrNull(peerModel, TextRule.DEVICE_MODEL).orEmpty(),
            appManagement = guarding && appManagement,
            unpairChances = if (guarding) 0 else unpairChances.coerceIn(0, MAX_CHANCES),
            createdAt = createdAt,
        )
    }

    /** Old phone: gives the move up before committing it, for [failure]. */
    suspend fun abandonMove(failure: CeremonyFailure) {
        val current = oldPhoneMove() ?: return
        if (current.moveStage != MoveStage.COMMITTING && current.moveStage != MoveStage.MOVED) abandon(current, failure)
    }

    /** Old phone: gives the move up, and tells the new phone so it discards what it staged. */
    private suspend fun abandon(current: CeremonyEntity, failure: CeremonyFailure) {
        postToPeer(current, MessageBody.MoveDone(current.moveId ?: randomBytes(MOVE_ID_BYTES), MoveOutcome.ABANDONED))
        dao.deleteMovePages()
        ceremony.failCurrent(failure)
    }

    /** New phone: tells the old phone it won't take the state. */
    private suspend fun decline(current: CeremonyEntity, moveId: ByteArray, reason: MoveDeclineReason, failure: CeremonyFailure) {
        postToPeer(current, MessageBody.MoveAnswer(moveId, MoveAnswerCode.DECLINE, reason))
        dao.deleteMovePages()
        ceremony.failCurrent(failure)
        KillitLog.i(KillitLog.PAIR, "Declined the move: $reason")
    }

    // ---------------------------------------------------------------- both

    /** The move this message belongs to: a linked move ceremony in which this phone plays [side]. */
    private fun moveOf(received: Mailbox.Received, side: CeremonySide): CeremonyEntity? =
        received.ceremony?.takeIf { it.purpose == CeremonyPurpose.MOVE && it.side == side && it.stage == CeremonyStage.LINKED }

    private suspend fun oldPhoneMove(): CeremonyEntity? =
        dao.ceremony()?.takeIf { it.purpose == CeremonyPurpose.MOVE && it.side == CeremonySide.JOINER && it.stage == CeremonyStage.LINKED }

    /** The pages kept: the new phone's staged state, or what the old phone sent. */
    private suspend fun stagedPages(): List<MessageBody.MoveState> =
        dao.movePages().mapNotNull { decodeBody(Kind.MOVE_STATE, it.body).valueOrNull() as? MessageBody.MoveState }

    private suspend fun postToPeer(current: CeremonyEntity, body: MessageBody) {
        val peer = ceremony.peerBundle(current) ?: return
        mailbox.post(current.pairId ?: return, fingerprint(peer.signPk), peer.encPk, body)
    }

    companion object {
        /** How long the old phone may commit after the new one is ready (SPEC.md §13). */
        const val COMMIT_WINDOW_MILLIS = 30L * 60 * 1000

        /** How long after saying it is ready the new phone must wait before it may give up. */
        const val GIVE_UP_AFTER_MILLIS = 60L * 60 * 1000
    }
}
