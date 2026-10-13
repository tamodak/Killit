package org.tamodak.killit.pairing

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.net.ApiError
import org.tamodak.killit.net.InboxMessage
import org.tamodak.killit.pairing.store.CeremonyEntity
import org.tamodak.killit.pairing.store.CeremonySide
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.OutboxEntity
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingDao
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.SeenMessageEntity
import org.tamodak.killit.pairing.store.UnknownMessageEntity
import org.tamodak.killit.protocol.Decoded
import org.tamodak.killit.protocol.ENVELOPE_BYTES
import org.tamodak.killit.protocol.FINGERPRINT_BYTES
import org.tamodak.killit.protocol.Hpke
import org.tamodak.killit.protocol.Kind
import org.tamodak.killit.protocol.MAX_ACK_IDS
import org.tamodak.killit.protocol.MAX_ENVELOPES_PER_POST
import org.tamodak.killit.protocol.MESSAGE_ID_BYTES
import org.tamodak.killit.protocol.MESSAGE_VERSION
import org.tamodak.killit.protocol.MessageBody
import org.tamodak.killit.protocol.MessageHeader
import org.tamodak.killit.protocol.NoticeBody
import org.tamodak.killit.protocol.OpenedMessage
import org.tamodak.killit.protocol.Sender
import org.tamodak.killit.protocol.decodeBody
import org.tamodak.killit.protocol.decodeReveal
import org.tamodak.killit.protocol.encodeBody
import org.tamodak.killit.protocol.fingerprint
import org.tamodak.killit.protocol.fromBase64Url
import org.tamodak.killit.protocol.openEnvelope
import org.tamodak.killit.protocol.randomBytes
import org.tamodak.killit.protocol.sealMessage
import org.tamodak.killit.protocol.verifyInner

/**
 * The phone's side of the mailbox (SPEC.md §8, §10): sealing messages into the outbox, sending
 * them, and opening what arrives.
 *
 * ### Sending
 *
 * A message is signed and sealed the moment it is created, and the envelope waits in the outbox
 * table until the server has accepted it. Nothing is lost to a dead network or a killed process,
 * and what eventually goes out is exactly what was created.
 *
 * ### Receiving
 *
 * Every envelope goes through the checks of SPEC.md §10.3 in order, and anything that fails one is
 * dropped silently: an unknown sender, an envelope that doesn't open, a header naming another
 * phone, a pair id this phone doesn't have with the sender, a bad signature, a repeated or
 * year-old message id, a kind the sender's role may not send, or a body that doesn't decode.
 * Only then does [handle] see it. A kind or version this Killit doesn't know is kept, unanswered.
 *
 * Every message is acknowledged once it has been handled or dropped, so a broken one can't clog
 * the mailbox. A message is marked seen only after it was handled, so one cut short by a crash is
 * delivered again, and the handlers are written to take a repeat calmly.
 *
 * @param identity this phone's registered identity.
 * @param handle applies a message that passed every check.
 * @param lock the engine's lock, held while one message is checked and handled, so the state it
 *   was checked against can't change before it is applied. Never held while waiting on the network.
 */
class Mailbox(
    private val dao: PairingDao,
    private val server: PairingServer,
    private val identity: suspend () -> DeviceKeys.Identity,
    private val handle: suspend (Received) -> Unit,
    private val lock: Mutex,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * A message that passed every check (SPEC.md §10.3 steps 1–8).
     *
     * @param pairing the pairing it belongs to; null for the ceremony kinds (PAIR_OFFER and
     *   PAIR_ANSWER, and the four of a move), which belong to [ceremony].
     */
    class Received(
        val header: MessageHeader,
        val body: MessageBody,
        val opened: OpenedMessage,
        val pairing: PairingEntity?,
        val ceremony: CeremonyEntity?,
    )

    /**
     * Seals [body] for the peer of [pairing] and queues it.
     *
     * @return the new message's id.
     */
    suspend fun post(pairing: PairingEntity, body: MessageBody): ByteArray =
        post(pairing.pairId, pairing.peerFp, pairing.peerEncPk, body)

    /**
     * Seals [body] and queues it.
     *
     * @param pairId the pairing the message belongs to, or the ceremony's.
     * @param peerFp the recipient.
     * @param peerEncPk the recipient's encryption key, from its verified bundle.
     */
    suspend fun post(pairId: ByteArray, peerFp: ByteArray, peerEncPk: ByteArray, body: MessageBody): ByteArray {
        val me = identity()
        val header = MessageHeader(
            version = MESSAGE_VERSION,
            kind = body.kind,
            messageId = randomBytes(MESSAGE_ID_BYTES),
            pairId = pairId,
            senderFp = me.fp,
            recipientFp = peerFp,
            createdAt = now(),
        )
        val envelope = sealMessage(header, encodeBody(body), me.signer, Hpke.encrypter(peerEncPk))
        dao.enqueue(OutboxEntity(recipientFp = peerFp, envelope = envelope, kind = body.kind, createdAt = now()))
        KillitLog.d(KillitLog.SYNC) { "Queued kind 0x${body.kind.toString(16)} for ${peerFp.short()}" }
        return header.messageId
    }

    /**
     * Sends everything in the outbox, oldest first, in batches the server accepts.
     *
     * A batch is all or nothing. When a recipient is no longer linked, its envelopes can never be
     * delivered, so they are dropped and the rest is sent again.
     *
     * @return true when the outbox is empty.
     * @throws IOException when the server can't be reached; the outbox keeps everything.
     */
    suspend fun flush(): Boolean {
        while (true) {
            val batch = dao.outbox(MAX_ENVELOPES_PER_POST)
            if (batch.isEmpty()) return true
            try {
                server.send(batch.map { it.recipientFp to it.envelope })
                dao.deleteOutbox(batch.map { it.id })
                KillitLog.d(KillitLog.SYNC) { "Sent ${batch.size} envelopes" }
            } catch (e: ApiError) {
                if (e.status == 403 && e.code == "not_linked" && e.peers.isNotEmpty()) {
                    for (peer in e.peers) {
                        val fp = runCatching { peer.fromBase64Url(FINGERPRINT_BYTES) }.getOrNull() ?: continue
                        KillitLog.w(KillitLog.SYNC, "Dropping envelopes for ${fp.short()}: no longer linked")
                        dao.deleteOutboxFor(fp)
                    }
                    continue
                }
                throw e
            }
        }
    }

    /**
     * Fetches, opens, handles and acknowledges messages until the mailbox is empty.
     *
     * @param waitSeconds how long the first fetch may wait for a message to arrive (a long poll).
     * @return how many messages were fetched.
     * @throws IOException when the server can't be reached.
     */
    suspend fun receive(waitSeconds: Int = 0): Int {
        var total = 0
        var wait = waitSeconds
        while (true) {
            val messages = server.fetch(wait)
            if (messages.isEmpty()) return total
            wait = 0
            val done = mutableListOf<String>()
            for (message in messages) {
                if (process(message)) done += message.id
            }
            done.chunked(MAX_ACK_IDS).forEach { server.ack(it) }
            total += messages.size
            // Something that couldn't be handled now stays in the mailbox; stop rather than fetch
            // it again at once.
            if (done.size < messages.size) return total
        }
    }

    /**
     * Opens and handles one message.
     *
     * @return true when it can be acknowledged: handled, or dropped for good. False only when a
     *   transient failure means it should be delivered again.
     */
    private suspend fun process(message: InboxMessage): Boolean {
        val reason = try {
            lock.withLock { open(message) }
        } catch (e: IOException) {
            KillitLog.w(KillitLog.SYNC, "Message ${message.id.take(8)} deferred: ${e.message}")
            return false
        } catch (e: Exception) {
            // A bug, not a bad message: drop it rather than retry it forever, and say so loudly.
            KillitLog.e(KillitLog.SYNC, "Message ${message.id.take(8)} failed while being handled", e)
            "handler failed"
        }
        if (reason != null) KillitLog.i(KillitLog.SYNC, "Dropped message ${message.id.take(8)}: $reason")
        return true
    }

    /**
     * Runs SPEC.md §10.3 on one message and hands it to [handle].
     *
     * @return null when it was handled or kept, or why it was dropped.
     */
    private suspend fun open(message: InboxMessage): String? {
        // 1. Size, and a sender this phone has a pairing or a ceremony with.
        val envelope = decodeOrNull { message.envelope.fromBase64Url(ENVELOPE_BYTES) } ?: return "malformed envelope"
        if (envelope.size != ENVELOPE_BYTES) return "wrong envelope size"
        val fromFp = decodeOrNull { message.from.fromBase64Url(FINGERPRINT_BYTES) } ?: return "malformed sender"
        if (fromFp.size != FINGERPRINT_BYTES) return "malformed sender"
        val pairings = dao.pairingsWith(fromFp)
        val ceremony = dao.ceremony()?.takeIf { ceremonyPeerFp(it)?.contentEquals(fromFp) == true }
        if (pairings.isEmpty() && ceremony == null) return "unknown sender"

        // 2–4. HPKE-Open with info bound to the sender, padding, and the header's parties.
        val me = identity()
        val opened = when (val result = openEnvelope(envelope, me.decrypter, me.fp, fromFp)) {
            is Decoded.Ok -> result.value
            is Decoded.Rejected -> return result.reason
        }
        val header = opened.header
        if (header.version != MESSAGE_VERSION) return keepUnknown(header, opened, pairings.firstOrNull())

        // 5. The pair id names a pairing with this sender, or the ceremony for the ceremony kinds.
        val ceremonyKind = Kind.isCeremonyKind(header.kind)
        val pairing = if (ceremonyKind) null else pairings.firstOrNull { it.pairId.contentEquals(header.pairId) }
        val signPk = if (ceremonyKind) {
            if (ceremony?.pairId?.contentEquals(header.pairId) != true) return "pair id is not the ceremony's"
            decodeReveal(ceremony.peerReveal ?: return "no peer reveal").valueOrNull()?.bundle?.signPk ?: return "bad peer reveal"
        } else {
            pairing?.peerSignPk ?: return "no such pairing"
        }

        // 6. The signature, under the key the pairing holds for the sender.
        if (!verifyInner(signPk, opened.inner, opened.signature)) return "bad signature"

        // 7. Not seen before, and not too old.
        if (dao.seenCount(fromFp, header.messageId) > 0) return "repeated message id"
        if (header.createdAt < now() - MAX_MESSAGE_AGE_MILLIS) return "older than a year"

        // 9 before 8: an unknown kind can't be checked further; it is kept as received.
        if (!Kind.isKnown(header.kind)) return keepUnknown(header, opened, pairing)

        // 8. The kind is allowed for the sender's role, and the body decodes strictly.
        if (!senderMayUse(header.kind, pairing, ceremony)) return "kind not allowed for the sender's role"
        val body = decodeBody(header.kind, opened.body).valueOrNull() ?: return "body does not decode"
        if (body is MessageBody.Notice && body.notice !is NoticeBody.Unknown && pairing?.myRole != PairRole.GUARDIAN) {
            return "only a protected phone sends notices"
        }

        // 10. Apply, then remember the id.
        handle(Received(header, body, opened, pairing, ceremony))
        dao.markSeen(SeenMessageEntity(fromFp, header.messageId, now()))
        return null
    }

    /** Keeps a message of an unknown kind or version for "Update Killit" (SPEC.md §10.3 step 9). */
    private suspend fun keepUnknown(header: MessageHeader, opened: OpenedMessage, pairing: PairingEntity?): String? {
        if (dao.seenCount(header.senderFp, header.messageId) > 0) return "repeated message id"
        dao.insertUnknown(
            UnknownMessageEntity(
                pairId = pairing?.pairId,
                senderFp = header.senderFp,
                version = header.version,
                kind = header.kind,
                inner = opened.inner,
                receivedAt = now(),
            ),
        )
        dao.markSeen(SeenMessageEntity(header.senderFp, header.messageId, now()))
        KillitLog.i(KillitLog.SYNC, "Kept a message this version can't read (version ${header.version}, kind ${header.kind})")
        return null
    }

    /**
     * SPEC.md §10.3 step 8: who may send each kind, by role in the pairing (§11). The ceremony
     * kinds go by who created the invite.
     */
    private fun senderMayUse(kind: Int, pairing: PairingEntity?, ceremony: CeremonyEntity?): Boolean =
        when (Kind.allowedSender(kind)) {
            Sender.CREATOR -> ceremony?.side == CeremonySide.JOINER
            Sender.JOINER -> ceremony?.side == CeremonySide.CREATOR
            Sender.GUARDIAN -> pairing?.myRole == PairRole.PROTECTED
            Sender.PROTECTED -> pairing?.myRole == PairRole.GUARDIAN
            Sender.EITHER -> pairing != null
            null -> false
        }

    private fun ceremonyPeerFp(ceremony: CeremonyEntity): ByteArray? {
        if (ceremony.stage == CeremonyStage.FAILED) return null
        val reveal = ceremony.peerReveal ?: return null
        return decodeReveal(reveal).valueOrNull()?.let { fingerprint(it.bundle.signPk) }
    }

    private inline fun <T> decodeOrNull(decode: () -> T): T? = runCatching(decode).getOrNull()

    companion object {
        /** Anything created longer ago than this, by this phone's clock, is refused (§10.3 step 7). */
        const val MAX_MESSAGE_AGE_MILLIS = 365L * 24 * 60 * 60 * 1000

        /** Seen ids are kept at least this long, so the age check takes over before they go. */
        const val SEEN_RETENTION_MILLIS = 400L * 24 * 60 * 60 * 1000
    }
}
