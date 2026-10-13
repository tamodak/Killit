package org.tamodak.killit.pairing

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.net.ApiError
import org.tamodak.killit.net.InviteView
import org.tamodak.killit.pairing.store.CeremonyEntity
import org.tamodak.killit.pairing.store.CeremonyFailure
import org.tamodak.killit.pairing.store.CeremonyPurpose
import org.tamodak.killit.pairing.store.CeremonySide
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.LinkDeletionEntity
import org.tamodak.killit.pairing.store.MoveStage
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.protocol.Answer
import org.tamodak.killit.protocol.Bundle
import org.tamodak.killit.protocol.CEREMONY_NONCE_BYTES
import org.tamodak.killit.protocol.COMMIT_BYTES
import org.tamodak.killit.protocol.DeclineReason
import org.tamodak.killit.protocol.InviteCode
import org.tamodak.killit.protocol.MessageBody
import org.tamodak.killit.protocol.REVEAL_BYTES
import org.tamodak.killit.protocol.Reveal
import org.tamodak.killit.protocol.Role
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.commitment
import org.tamodak.killit.protocol.decodeReveal
import org.tamodak.killit.protocol.deviceId
import org.tamodak.killit.protocol.encodeReveal
import org.tamodak.killit.protocol.encodeText
import org.tamodak.killit.protocol.fingerprint
import org.tamodak.killit.protocol.fromBase64Url
import org.tamodak.killit.protocol.matchesCommitment
import org.tamodak.killit.protocol.pairId
import org.tamodak.killit.protocol.randomBytes
import org.tamodak.killit.protocol.safetyCode
import org.tamodak.killit.protocol.transcript
import org.tamodak.killit.protocol.verifyBundle

/**
 * The pairing ceremony (SPEC.md §6, PLAN.md §4): an invite code, a commitment, two reveals, a
 * safety code compared on a call, a link, and the PAIR_OFFER and PAIR_ANSWER that decide the roles.
 *
 * The order is what keeps a dishonest server down to one guess in a million: the joiner commits to
 * its keys before it sees the creator's, and the creator reveals before it sees the joiner's. So:
 *
 * - **Creator:** shows the code → once a joiner has committed, *stores that commitment*, then
 *   reveals → checks the joiner's reveal against the stored commitment, never a later copy.
 * - **Joiner:** commits when joining → checks the creator's reveal, then reveals.
 * - **Both:** check the peer's bundle signature and that the peer isn't this phone, compute the
 *   safety code and pair id from the transcript, and let the people compare. "They match" confirms
 *   the link; "They don't match" cancels everything.
 * - **Once linked:** the creator sends PAIR_OFFER naming its role. The joiner checks that role
 *   against the one it started out to take (it began from "Add a guardian" or "Guard a new
 *   phone"), and declines a conflict without asking. Otherwise it accepts at once: the people
 *   already said yes by comparing the code.
 *
 * A move to a new phone runs the same ceremony between two phones of one person (SPEC.md §6.5),
 * the new phone creating; once linked, [MoveEngine] takes over. Each phone knows which of the two
 * it started, and a phone that receives the other one's first message ends the ceremony.
 *
 * The state lives in the `ceremony` table, so the ceremony survives leaving Killit to make the
 * call. Network calls run without the engine's lock; state changes take it.
 *
 * @param identity this phone's registered identity.
 * @param deviceModel `Build.MANUFACTURER` and `Build.MODEL`, sent in the offer and answer.
 * @param canBeProtected whether this phone can take the protected role now: only a device owner
 *   can (PLAN.md §4).
 * @param appManagement this phone's management setting, which it states when it is the side to be
 *   guarded (SPEC.md §11.1), so both sides know what they agree to (G3).
 */
class CeremonyEngine(
    private val store: PairingStore,
    private val server: PairingServer,
    private val mailbox: Mailbox,
    private val peers: PeerList,
    private val identity: suspend () -> DeviceKeys.Identity,
    private val lock: Mutex,
    private val deviceModel: String,
    private val canBeProtected: suspend () -> Boolean,
    private val appManagement: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = store.dao

    /** Takes a move over once its ceremony is linked. */
    lateinit var moves: MoveEngine

    /** The ceremony in progress, or the last one until it is dismissed. */
    val ceremony: Flow<CeremonyEntity?> = dao.observeCeremony()

    /** Why joining didn't start a ceremony. */
    enum class JoinResult { JOINED, CODE_NOT_FOUND, RATE_LIMITED }

    /**
     * Shows a new invite code (creator). Any earlier unfinished ceremony is cancelled: the server
     * retires earlier invites anyway.
     *
     * @param myRole the role this phone will take, known from where the user started.
     * @param selfName the name this phone gives itself, 1–40 code points once trimmed.
     */
    suspend fun create(myRole: PairRole, selfName: String) {
        val name = checkedName(selfName)
        check(myRole != PairRole.PROTECTED || canBeProtected()) { "only a device owner can be protected" }
        createInvite(CeremonyPurpose.PAIR, myRole, name)
        KillitLog.i(KillitLog.PAIR, "Invite created; this phone will be $myRole")
    }

    /**
     * Joins with a code typed by the user (joiner), committing to this phone's keys first.
     *
     * @param code what the user typed; mapped to ASCII by the field, parsed per SPEC.md §6.1.
     */
    suspend fun join(code: String, myRole: PairRole, selfName: String): JoinResult {
        val name = checkedName(selfName)
        check(myRole != PairRole.PROTECTED || canBeProtected()) { "only a device owner can be protected" }
        return joinInvite(code, CeremonyPurpose.PAIR, myRole, name).also {
            if (it == JoinResult.JOINED) KillitLog.i(KillitLog.PAIR, "Joined an invite; this phone will be $myRole")
        }
    }

    /**
     * New phone of a move: shows a code for the old phone to join (SPEC.md §6.5). Only a phone
     * without pairings takes a move; the caller checks.
     */
    suspend fun createMove() {
        createInvite(CeremonyPurpose.MOVE, MOVE_ROLE, "")
        KillitLog.i(KillitLog.PAIR, "Move invite created")
    }

    /** Old phone of a move: joins the new phone's code, committing to this phone's keys first. */
    suspend fun joinMove(code: String): JoinResult = joinInvite(code, CeremonyPurpose.MOVE, MOVE_ROLE, "").also {
        if (it == JoinResult.JOINED) KillitLog.i(KillitLog.PAIR, "Joined a move invite")
    }

    private suspend fun createInvite(purpose: CeremonyPurpose, myRole: PairRole, name: String) {
        abandonCurrent()
        val me = identity()
        val created = server.createInvite()
        val nonce = randomBytes(CEREMONY_NONCE_BYTES)
        val reveal = encodeReveal(me.bundle(), nonce)
        lock.withLock {
            dao.replaceCeremony(
                newCeremony(purpose, created.id, CeremonySide.CREATOR, myRole, name, created.code, created.expiresAt, nonce, reveal, null, CeremonyStage.WAITING_FOR_JOINER),
            )
        }
    }

    private suspend fun joinInvite(code: String, purpose: CeremonyPurpose, myRole: PairRole, name: String): JoinResult {
        val bytes = InviteCode.parse(code).valueOrNull() ?: return JoinResult.CODE_NOT_FOUND
        val normalized = InviteCode.format(bytes)
        abandonCurrent()
        val me = identity()
        val nonce = randomBytes(CEREMONY_NONCE_BYTES)
        val reveal = encodeReveal(me.bundle(), nonce)
        val commit = commitment(reveal)
        val joined = try {
            server.joinInvite(normalized, commit)
        } catch (e: ApiError) {
            return when (e.status) {
                404 -> JoinResult.CODE_NOT_FOUND
                429 -> JoinResult.RATE_LIMITED
                else -> throw e
            }
        }
        lock.withLock {
            dao.replaceCeremony(
                newCeremony(purpose, joined.id, CeremonySide.JOINER, myRole, name, normalized, joined.expiresAt, nonce, reveal, commit, CeremonyStage.WAITING_FOR_CREATOR),
            )
        }
        return JoinResult.JOINED
    }

    /**
     * Follows the invite one step: waits up to [waitSeconds] for it to change, then does what the
     * new state calls for. Does nothing once the ceremony is linked; the mailbox takes over there.
     *
     * @throws IOException when the server can't be reached; the ceremony waits where it is.
     */
    suspend fun poll(waitSeconds: Int) {
        val current = dao.ceremony() ?: return
        if (current.stage !in POLLED) return
        var view = try {
            server.invite(current.inviteId, waitSeconds, current.inviteVersion)
        } catch (e: ApiError) {
            if (e.status != 404) throw e
            lock.withLock { fail(CeremonyFailure.CANCELLED) }
            return
        }

        // The creator reveals once a joiner is committed, and keeps the commitment it saw first.
        if (current.side == CeremonySide.CREATOR && view.state == "joined" && view.creatorReveal == null) {
            val commit = view.commit?.let { decode(it, COMMIT_BYTES) } ?: return
            val saved = lock.withLock {
                val latest = dao.ceremony()?.takeIf { it.inviteId == current.inviteId } ?: return@withLock null
                (latest.commit ?: commit).also {
                    if (latest.commit == null) dao.updateCeremony(latest.copy(commit = commit, stage = CeremonyStage.WAITING_FOR_JOINER_REVEAL))
                }
            } ?: return
            if (!saved.contentEquals(commit)) {
                lock.withLock { fail(CeremonyFailure.INVALID) }
                cancelQuietly(current.inviteId)
                return
            }
            view = server.reveal(current.inviteId, current.myReveal)
        }

        // The joiner checks the creator's reveal, and pins it, before revealing its own. The
        // transcript is built from the pinned copy, so a server that showed another reveal later,
        // after learning the joiner's nonce, would only make the codes differ.
        if (current.side == CeremonySide.JOINER && view.state == "creator_revealed" && view.joinerReveal == null) {
            val creatorBytes = view.creatorReveal?.let { decode(it, REVEAL_BYTES) }
            if (creatorBytes == null || checkedPeerReveal(creatorBytes) == null) {
                lock.withLock { fail(CeremonyFailure.INVALID) }
                cancelQuietly(current.inviteId)
                return
            }
            val pinned = lock.withLock {
                val latest = dao.ceremony()?.takeIf { it.inviteId == current.inviteId } ?: return@withLock null
                (latest.peerReveal ?: creatorBytes).also {
                    if (latest.peerReveal == null) dao.updateCeremony(latest.copy(peerReveal = creatorBytes))
                }
            } ?: return
            if (!pinned.contentEquals(creatorBytes)) {
                lock.withLock { fail(CeremonyFailure.INVALID) }
                cancelQuietly(current.inviteId)
                return
            }
            view = server.reveal(current.inviteId, current.myReveal)
        }

        val invalid = lock.withLock { apply(view) }
        if (invalid) cancelQuietly(current.inviteId)
    }

    /** "They match": confirms the safety code, which creates the link once both phones have. */
    suspend fun confirm() {
        val current = dao.ceremony() ?: return
        if (current.stage != CeremonyStage.COMPARING && current.stage != CeremonyStage.CONFIRMED) return
        lock.withLock { dao.ceremony()?.takeIf { it.inviteId == current.inviteId }?.let { dao.updateCeremony(it.copy(stage = CeremonyStage.CONFIRMED)) } }
        val result = try {
            server.confirmLink(current.inviteId)
        } catch (e: ApiError) {
            val failure = when {
                e.status == 409 && e.state == "link_limit" -> CeremonyFailure.LINK_LIMIT
                e.status == 410 -> CeremonyFailure.EXPIRED
                e.status == 404 || e.status == 409 -> CeremonyFailure.CANCELLED
                else -> throw e
            }
            lock.withLock { fail(failure) }
            return
        }
        val peer = peerBundle(current) ?: return
        if (result.peer != deviceId(fingerprint(peer.signPk))) {
            // The server named another phone than the one whose keys were compared.
            lock.withLock { fail(CeremonyFailure.INVALID) }
            return
        }
        if (result.state == "linked") lock.withLock { dao.ceremony()?.takeIf { it.inviteId == current.inviteId }?.let { linked(it) } }
    }

    /** "They don't match": someone may be interfering. Everything is cancelled. */
    suspend fun reject() {
        val current = dao.ceremony() ?: return
        cancelQuietly(current.inviteId)
        lock.withLock { fail(CeremonyFailure.MISMATCH) }
        KillitLog.w(KillitLog.PAIR, "Safety codes didn't match; ceremony cancelled")
    }

    /**
     * Gives up on the ceremony: back, or a timeout the user accepted. A linked move follows its
     * own rules ([MoveEngine.cancel]).
     *
     * @return false when it is a move that can't be given up now.
     */
    suspend fun cancel(): Boolean {
        val current = dao.ceremony() ?: return true
        if (current.stage == CeremonyStage.PAIRED || current.stage == CeremonyStage.FAILED) return true
        if (current.purpose == CeremonyPurpose.MOVE && current.stage == CeremonyStage.LINKED) {
            return lock.withLock { dao.ceremony()?.takeIf { it.inviteId == current.inviteId }?.let { moves.cancel(it) } ?: true }
        }
        cancelQuietly(current.inviteId)
        lock.withLock { fail(CeremonyFailure.CANCELLED) }
        return true
    }

    /** Sets the nickname chosen on the last step (A5). */
    suspend fun setNickname(nickname: String) = lock.withLock {
        val current = dao.ceremony() ?: return@withLock
        val id = current.pairId ?: return@withLock
        if (current.stage != CeremonyStage.PAIRED) return@withLock
        peers.rename(id, nickname)
        dao.updateCeremony(current.copy(nickname = nickname))
    }

    /** Forgets a finished ceremony, once the user has seen how it ended. */
    suspend fun dismiss() = lock.withLock {
        val current = dao.ceremony() ?: return@withLock
        if (current.stage == CeremonyStage.PAIRED || current.stage == CeremonyStage.FAILED) dao.deleteCeremony()
    }

    /** Joiner: the creator's PAIR_OFFER (SPEC.md §11.1). Called with the lock held. */
    suspend fun onOffer(received: Mailbox.Received) {
        val current = received.ceremony ?: return
        if (current.side != CeremonySide.JOINER) return
        if (current.stage != CeremonyStage.CONFIRMED && current.stage != CeremonyStage.LINKED) return
        val offer = received.body as MessageBody.PairOffer
        val peer = peerBundle(current) ?: return
        val pairIdBytes = current.pairId ?: return
        val peerFp = fingerprint(peer.signPk)
        if (current.purpose == CeremonyPurpose.MOVE) {
            // The code was a pairing's, and this phone joined it to move: neither happens.
            mailbox.post(pairIdBytes, peerFp, peer.encPk, MessageBody.PairAnswer(Answer.DECLINE, DeclineReason.USER, "", "", appManagement = false))
            fail(CeremonyFailure.WRONG_PURPOSE)
            return
        }
        val expected = if (current.myRole == PairRole.GUARDIAN) Role.PROTECTED else Role.GUARDIAN
        val conflict = offer.senderRole != expected
        if (conflict || (current.myRole == PairRole.PROTECTED && !canBeProtected())) {
            val reason = if (conflict) DeclineReason.ROLE_CONFLICT else DeclineReason.USER
            mailbox.post(pairIdBytes, peerFp, peer.encPk, MessageBody.PairAnswer(Answer.DECLINE, reason, "", "", appManagement = false))
            fail(if (conflict) CeremonyFailure.ROLE_CONFLICT else CeremonyFailure.DECLINED)
            KillitLog.w(KillitLog.PAIR, "Declined an offer: $reason")
            return
        }
        peers.added(pairing(current, peer, offer.selfName, offer.deviceModel, offer.appManagement))
        mailbox.post(
            pairIdBytes,
            peerFp,
            peer.encPk,
            MessageBody.PairAnswer(
                Answer.ACCEPT,
                DeclineReason.NONE,
                current.selfName,
                deviceModel,
                appManagement = current.myRole == PairRole.PROTECTED && appManagement(),
            ),
        )
        dao.updateCeremony(current.copy(stage = CeremonyStage.PAIRED, peerName = offer.selfName, peerModel = offer.deviceModel, nickname = offer.selfName))
        KillitLog.i(KillitLog.PAIR, "Paired as ${current.myRole}")
    }

    /** Creator: the joiner's PAIR_ANSWER (SPEC.md §11.1). Called with the lock held. */
    suspend fun onAnswer(received: Mailbox.Received) {
        val current = received.ceremony ?: return
        if (current.side != CeremonySide.CREATOR || current.stage != CeremonyStage.LINKED) return
        if (current.purpose != CeremonyPurpose.PAIR) return
        val answer = received.body as MessageBody.PairAnswer
        if (answer.answer == Answer.DECLINE) {
            fail(if (answer.reason == DeclineReason.ROLE_CONFLICT) CeremonyFailure.ROLE_CONFLICT else CeremonyFailure.DECLINED)
            return
        }
        // A decoder can't see roles: a joiner becoming the guardian has no setting to state.
        if (current.myRole == PairRole.PROTECTED && answer.appManagement) {
            fail(CeremonyFailure.INVALID)
            return
        }
        val peer = peerBundle(current) ?: return
        peers.added(pairing(current, peer, answer.selfName, answer.deviceModel, answer.appManagement))
        dao.updateCeremony(current.copy(stage = CeremonyStage.PAIRED, peerName = answer.selfName, peerModel = answer.deviceModel, nickname = answer.selfName))
        KillitLog.i(KillitLog.PAIR, "Paired as ${current.myRole}")
    }

    /**
     * Applies what the invite says now. Called with the lock held.
     *
     * @return true when something didn't verify, so the invite should be cancelled.
     */
    private suspend fun apply(view: InviteView): Boolean {
        val current = dao.ceremony()?.takeIf { it.inviteId == view.id } ?: return false
        if (current.stage !in POLLED) return false
        if (view.state == "expired") return false.also { fail(CeremonyFailure.EXPIRED) }
        if (view.state == "cancelled") return false.also { fail(CeremonyFailure.CANCELLED) }
        var updated = current.copy(inviteVersion = view.version)

        val revealed = view.creatorReveal != null && view.joinerReveal != null
        if (revealed && current.stage.ordinal < CeremonyStage.COMPARING.ordinal) {
            val creator = current.side == CeremonySide.CREATOR
            val shownMine = decode((if (creator) view.creatorReveal else view.joinerReveal)!!, REVEAL_BYTES)
            val shownPeer = decode((if (creator) view.joinerReveal else view.creatorReveal)!!, REVEAL_BYTES)
            // The creator checks the joiner's reveal against the commitment it stored before
            // revealing; the joiner uses the creator's reveal it pinned before revealing.
            val peerBytes = if (creator) {
                shownPeer?.takeIf { matchesCommitment(it, current.commit ?: ByteArray(0)) }
            } else {
                current.peerReveal?.takeIf { shownPeer?.contentEquals(it) == true }
            }
            val peer = peerBytes?.let { checkedPeerReveal(it) }
            val mine = decodeReveal(current.myReveal).valueOrNull()
            if (peer == null || mine == null || shownMine?.contentEquals(current.myReveal) != true) {
                KillitLog.w(KillitLog.PAIR, "The peer's reveal didn't verify; cancelling")
                fail(CeremonyFailure.INVALID)
                return true
            }
            val transcriptBytes = if (creator) transcript(mine, peer) else transcript(peer, mine)
            updated = updated.copy(
                peerReveal = peerBytes,
                pairId = pairId(transcriptBytes),
                safetyCode = safetyCode(transcriptBytes),
                stage = CeremonyStage.COMPARING,
            )
            KillitLog.i(KillitLog.PAIR, "Safety code ready to compare")
        }
        dao.updateCeremony(updated)
        if (updated.stage == CeremonyStage.CONFIRMED && view.state == "linked") linked(updated)
        return false
    }

    /**
     * The link exists. The creator sends its offer; the joiner now waits for it. Called with the
     * lock held.
     */
    private suspend fun linked(current: CeremonyEntity) {
        if (current.stage == CeremonyStage.LINKED || current.stage == CeremonyStage.PAIRED) return
        if (current.purpose == CeremonyPurpose.MOVE) {
            if (current.side == CeremonySide.CREATOR) {
                moves.onLinked(current)
            } else {
                dao.updateCeremony(current.copy(stage = CeremonyStage.LINKED, moveStage = MoveStage.AWAITING_REQUEST))
                KillitLog.i(KillitLog.PAIR, "Linked to the new phone")
            }
            return
        }
        if (current.side == CeremonySide.CREATOR) {
            val peer = peerBundle(current) ?: return
            val role = if (current.myRole == PairRole.GUARDIAN) Role.GUARDIAN else Role.PROTECTED
            mailbox.post(
                current.pairId ?: return,
                fingerprint(peer.signPk),
                peer.encPk,
                MessageBody.PairOffer(role, current.selfName, deviceModel, appManagement = current.myRole == PairRole.PROTECTED && appManagement()),
            )
        }
        dao.updateCeremony(current.copy(stage = CeremonyStage.LINKED))
        KillitLog.i(KillitLog.PAIR, "Linked")
    }

    /** Ends the ceremony without a pairing or a move. Called with the lock held. */
    internal suspend fun failCurrent(failure: CeremonyFailure) = fail(failure)

    /** Ends the ceremony without a pairing. Called with the lock held. */
    private suspend fun fail(failure: CeremonyFailure) {
        val current = dao.ceremony() ?: return
        if (current.stage == CeremonyStage.PAIRED || current.stage == CeremonyStage.FAILED) return
        dao.updateCeremony(current.copy(stage = CeremonyStage.FAILED, failure = failure))
        // A link may exist already; it goes once nothing needs it.
        val peer = peerBundle(current)
        if (peer != null) {
            val fp = fingerprint(peer.signPk)
            if (dao.pairingsWith(fp).isEmpty()) dao.requestLinkDeletion(LinkDeletionEntity(fp, now()))
        }
        KillitLog.i(KillitLog.PAIR, "Ceremony ended: $failure")
    }

    /**
     * Cancels whatever ceremony is in progress before a new one starts. A move the other phone
     * may be committing can't be cut short.
     *
     * @throws IllegalStateException for such a move.
     */
    private suspend fun abandonCurrent() {
        val current = dao.ceremony() ?: return
        check(!moves.mustFinish(current)) { "a move is under way" }
        if (current.purpose == CeremonyPurpose.MOVE && current.stage == CeremonyStage.LINKED) {
            lock.withLock { dao.ceremony()?.let { moves.cancel(it) } }
        } else if (current.stage != CeremonyStage.PAIRED && current.stage != CeremonyStage.FAILED) {
            cancelQuietly(current.inviteId)
        }
        lock.withLock { dao.deleteCeremony() }
    }

    private suspend fun cancelQuietly(inviteId: String) {
        try {
            server.cancelInvite(inviteId)
        } catch (e: IOException) {
            KillitLog.d(KillitLog.PAIR) { "Cancelling the invite failed: ${e.message}; it expires anyway" }
        }
    }

    /**
     * Decodes a peer's reveal and checks what every phone must check before showing a code: the
     * bundle's signature, and that the peer isn't this phone (SPEC.md §6.4).
     */
    private suspend fun checkedPeerReveal(bytes: ByteArray): Reveal? {
        val reveal = decodeReveal(bytes).valueOrNull() ?: return null
        if (!verifyBundle(reveal.bundle)) return null
        if (fingerprint(reveal.bundle.signPk).contentEquals(identity().fp)) return null
        return reveal
    }

    /** The peer's bundle, from its reveal: what the safety code covered. */
    internal fun peerBundle(current: CeremonyEntity): Bundle? =
        current.peerReveal?.let { decodeReveal(it).valueOrNull()?.bundle }

    private fun pairing(current: CeremonyEntity, peer: Bundle, peerName: String, peerModel: String, peerAppManagement: Boolean) =
        PairingEntity(
            pairId = current.pairId!!,
            peerFp = fingerprint(peer.signPk),
            peerSignPk = peer.signPk,
            peerEncPk = peer.encPk,
            peerEncSig = peer.encSig,
            myRole = current.myRole,
            peerName = peerName,
            nickname = peerName,
            peerModel = peerModel,
            // The protected side's setting, kept on the guardian's side: this phone's own lives in
            // the durable store, where an unlocked session changes it.
            appManagement = current.myRole == PairRole.GUARDIAN && peerAppManagement,
            createdAt = now(),
            unpairChances = PairingStore.MAX_CHANCES,
            lastSeenDaysAgo = 0,
            silenceAlerted = false,
        )

    private fun newCeremony(
        purpose: CeremonyPurpose,
        inviteId: String,
        side: CeremonySide,
        myRole: PairRole,
        selfName: String,
        code: String,
        expiresAt: Long,
        nonce: ByteArray,
        reveal: ByteArray,
        commit: ByteArray?,
        stage: CeremonyStage,
    ) = CeremonyEntity(
        inviteId = inviteId,
        side = side,
        myRole = myRole,
        selfName = selfName,
        code = code,
        expiresAt = expiresAt,
        myNonce = nonce,
        myReveal = reveal,
        commit = commit,
        peerReveal = null,
        pairId = null,
        safetyCode = null,
        stage = stage,
        failure = null,
        inviteVersion = null,
        peerName = null,
        peerModel = null,
        nickname = null,
        createdAt = now(),
        purpose = purpose,
    )

    private fun decode(text: String, size: Int): ByteArray? =
        runCatching { text.fromBase64Url(size) }.getOrNull()?.takeIf { it.size == size }

    private fun checkedName(name: String): String {
        val trimmed = RequestEngine.explanationOrNull(name, TextRule.SELF_NAME)
        requireNotNull(trimmed) { "a name is 1–40 characters" }
        encodeText(trimmed, TextRule.SELF_NAME)
        return trimmed
    }

    private companion object {
        /** A move's ceremony stores a role it never uses: no pairing comes out of it. */
        val MOVE_ROLE = PairRole.GUARDIAN

        /** The stages in which the invite itself is followed. */
        val POLLED = setOf(
            CeremonyStage.WAITING_FOR_JOINER,
            CeremonyStage.WAITING_FOR_JOINER_REVEAL,
            CeremonyStage.WAITING_FOR_CREATOR,
            CeremonyStage.COMPARING,
            CeremonyStage.CONFIRMED,
        )
    }
}
