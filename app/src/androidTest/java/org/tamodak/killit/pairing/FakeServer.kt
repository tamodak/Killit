package org.tamodak.killit.pairing

import java.io.IOException
import java.util.UUID
import org.tamodak.killit.net.ApiError
import org.tamodak.killit.net.InboxMessage
import org.tamodak.killit.net.InviteConfirmations
import org.tamodak.killit.net.InviteCreated
import org.tamodak.killit.net.InviteJoined
import org.tamodak.killit.net.InviteView
import org.tamodak.killit.net.LinkConfirmed
import org.tamodak.killit.net.LinkInfo
import org.tamodak.killit.protocol.ENVELOPE_BYTES
import org.tamodak.killit.protocol.InviteCode
import org.tamodak.killit.protocol.decodeReveal
import org.tamodak.killit.protocol.deviceId
import org.tamodak.killit.protocol.fingerprint
import org.tamodak.killit.protocol.matchesCommitment
import org.tamodak.killit.protocol.randomBytes
import org.tamodak.killit.protocol.toBase64Url

/**
 * An in-memory pairing server with the real one's rules (SPEC.md §6–§8, `website/server`), so the
 * whole engine can be exercised with several phones in one process.
 *
 * It never waits: a long poll answers at once. Tests drive time with the shared clock.
 */
class FakeServer(private val now: () -> Long) {
    private class Invite(
        val id: String,
        val code: String,
        val creator: String,
        var joiner: String? = null,
        var commit: ByteArray? = null,
        var creatorReveal: ByteArray? = null,
        var joinerReveal: ByteArray? = null,
        var creatorConfirmed: Boolean = false,
        var joinerConfirmed: Boolean = false,
        var state: String = "open",
        var expiresAt: Long,
        var version: Int = 0,
    ) {
        val live: Boolean get() = state in setOf("open", "joined", "creator_revealed", "revealed")
    }

    private class Stored(val id: String, val to: String, val from: String, val envelope: ByteArray)

    private val invites = mutableListOf<Invite>()
    private val links = mutableSetOf<Set<String>>()
    private val messages = mutableListOf<Stored>()

    /** Days since each device was last seen, as `GET /v1/links` reports them. */
    val lastSeenDays = mutableMapOf<String, Int>()

    /** Every envelope ever accepted, for replay tests. */
    val delivered = mutableListOf<Pair<String, ByteArray>>()

    /** False to make the mailbox calls fail as if the network or the server were gone. */
    @Volatile
    var reachable = true

    /** The client a phone with [fp] uses. */
    fun client(fp: ByteArray): PairingServer = Client(deviceId(fp))

    /** Messages waiting for [fp]. */
    @Synchronized
    fun waitingFor(fp: ByteArray): Int = messages.count { it.to == deviceId(fp) }

    /** Puts an envelope back in a mailbox, as a replaying server would. */
    @Synchronized
    fun inject(to: ByteArray, from: ByteArray, envelope: ByteArray) {
        messages += Stored(UUID.randomUUID().toString(), deviceId(to), deviceId(from), envelope)
    }

    /** True when the two phones are linked. */
    @Synchronized
    fun linked(a: ByteArray, b: ByteArray): Boolean = setOf(deviceId(a), deviceId(b)) in links

    private inner class Client(private val me: String) : PairingServer {
        override suspend fun createInvite(): InviteCreated = synchronized(this@FakeServer) {
            invites.filter { it.creator == me && it.live }.forEach { it.state = "cancelled"; it.version++ }
            val code = InviteCode.format(randomBytes(InviteCode.BYTES))
            val invite = Invite(UUID.randomUUID().toString(), code, me, expiresAt = now() + LIFETIME)
            invites += invite
            InviteCreated(invite.id, code, invite.expiresAt)
        }

        override suspend fun joinInvite(code: String, commit: ByteArray): InviteJoined = synchronized(this@FakeServer) {
            val bytes = InviteCode.parse(code).valueOrNull() ?: throw ApiError(404, "not_found")
            val formatted = InviteCode.format(bytes)
            val invite = invites.firstOrNull { it.code == formatted && it.state == "open" && it.expiresAt > now() && it.creator != me }
                ?: throw ApiError(404, "not_found")
            invite.joiner = me
            invite.commit = commit
            invite.state = "joined"
            invite.expiresAt = now() + LIFETIME
            invite.version++
            InviteJoined(invite.id, invite.expiresAt)
        }

        override suspend fun invite(id: String, waitSeconds: Int, known: String?): InviteView = synchronized(this@FakeServer) {
            view(participant(id), me)
        }

        override suspend fun reveal(id: String, reveal: ByteArray): InviteView = synchronized(this@FakeServer) {
            val invite = participant(id)
            val decoded = decodeReveal(reveal).valueOrNull() ?: throw ApiError(400, "bad_request")
            if (deviceId(fingerprint(decoded.bundle.signPk)) != me) throw ApiError(400, "bad_request")
            if (invite.live && invite.expiresAt <= now()) throw ApiError(410, "expired")
            if (invite.creator == me) {
                if (invite.state != "joined") throw ApiError(409, "conflict", state = invite.state)
                invite.creatorReveal = reveal
                invite.state = "creator_revealed"
            } else {
                if (invite.state != "creator_revealed") throw ApiError(409, "conflict", state = invite.state)
                if (!matchesCommitment(reveal, invite.commit ?: ByteArray(0))) throw ApiError(400, "bad_request")
                invite.joinerReveal = reveal
                invite.state = "revealed"
            }
            invite.version++
            view(invite, me)
        }

        override suspend fun cancelInvite(id: String): Unit = synchronized(this@FakeServer) {
            val invite = participant(id)
            if (invite.live) {
                invite.state = "cancelled"
                invite.version++
            }
        }

        override suspend fun confirmLink(inviteId: String): LinkConfirmed = synchronized(this@FakeServer) {
            val invite = participant(inviteId)
            val joiner = invite.joiner ?: throw ApiError(404, "not_found")
            val creator = invite.creator == me
            val peer = if (creator) joiner else invite.creator
            if (invite.state == "linked") return@synchronized LinkConfirmed("linked", peer)
            if (invite.live && invite.expiresAt <= now()) throw ApiError(410, "expired")
            if (invite.state != "revealed") throw ApiError(409, "conflict", state = invite.state)
            if (creator) invite.creatorConfirmed = true else invite.joinerConfirmed = true
            invite.version++
            if (!(invite.creatorConfirmed && invite.joinerConfirmed)) return@synchronized LinkConfirmed("waiting", peer)
            links += setOf(invite.creator, joiner)
            invite.state = "linked"
            LinkConfirmed("linked", peer)
        }

        override suspend fun links(): List<LinkInfo> = synchronized(this@FakeServer) {
            links.filter { me in it }.map { link ->
                val peer = (link - me).single()
                LinkInfo(peer, lastSeenDays[peer] ?: 0)
            }
        }

        override suspend fun deleteLink(peerFp: ByteArray): Unit = synchronized(this@FakeServer) {
            links -= setOf(me, deviceId(peerFp))
        }

        override suspend fun send(messages: List<Pair<ByteArray, ByteArray>>): Int = synchronized(this@FakeServer) {
            reach()
            val unlinked = messages.map { deviceId(it.first) }.distinct().filter { setOf(me, it) !in links }
            if (unlinked.isNotEmpty()) throw ApiError(403, "not_linked", peers = unlinked)
            for ((to, envelope) in messages) {
                check(envelope.size == ENVELOPE_BYTES)
                this@FakeServer.messages += Stored(UUID.randomUUID().toString(), deviceId(to), me, envelope)
                delivered += deviceId(to) to envelope
            }
            messages.size
        }

        override suspend fun fetch(waitSeconds: Int): List<InboxMessage> = synchronized(this@FakeServer) {
            reach()
            this@FakeServer.messages.filter { it.to == me }.take(32).map {
                InboxMessage(it.id, it.from, it.envelope.toBase64Url(), now())
            }
        }

        override suspend fun ack(ids: List<String>): Unit = synchronized(this@FakeServer) {
            reach()
            this@FakeServer.messages.removeAll { it.to == me && it.id in ids }
        }

        private fun reach() {
            if (!reachable) throw IOException("server unreachable")
        }

        private fun participant(id: String): Invite =
            invites.firstOrNull { it.id == id && (it.creator == me || it.joiner == me) } ?: throw ApiError(404, "not_found")
    }

    private fun view(invite: Invite, me: String): InviteView {
        val creator = invite.creator == me
        return InviteView(
            id = invite.id,
            state = if (invite.live && invite.expiresAt <= now()) "expired" else invite.state,
            role = if (creator) "creator" else "joiner",
            expiresAt = invite.expiresAt,
            commit = invite.commit?.toBase64Url(),
            creatorReveal = invite.creatorReveal?.toBase64Url(),
            joinerReveal = invite.joinerReveal?.toBase64Url(),
            confirmed = InviteConfirmations(
                self = if (creator) invite.creatorConfirmed else invite.joinerConfirmed,
                peer = if (creator) invite.joinerConfirmed else invite.creatorConfirmed,
            ),
            version = invite.version.toString(),
        )
    }

    private companion object {
        const val LIFETIME = 15L * 60 * 1000
    }
}
