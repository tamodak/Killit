package org.tamodak.killit.pairing

import org.tamodak.killit.net.InboxMessage
import org.tamodak.killit.net.InviteCreated
import org.tamodak.killit.net.InviteJoined
import org.tamodak.killit.net.InviteView
import org.tamodak.killit.net.LinkConfirmed
import org.tamodak.killit.net.LinkInfo

/**
 * What pairing needs from the server (SPEC.md §6–§8): invites, links and the mailbox.
 *
 * [org.tamodak.killit.net.ApiClient] is the implementation. The interface exists so the whole
 * engine can be tested with two phones and an in-memory server in one process; it is also a
 * reminder of how little pairing asks of the server, which is trusted for none of it.
 *
 * Every call throws [java.io.IOException] when the server can't be reached, and
 * [org.tamodak.killit.net.ApiError] when it refuses.
 */
interface PairingServer {
    suspend fun createInvite(): InviteCreated

    suspend fun joinInvite(code: String, commit: ByteArray): InviteJoined

    suspend fun invite(id: String, waitSeconds: Int = 0, known: String? = null): InviteView

    suspend fun reveal(id: String, reveal: ByteArray): InviteView

    suspend fun cancelInvite(id: String)

    suspend fun confirmLink(inviteId: String): LinkConfirmed

    suspend fun links(): List<LinkInfo>

    suspend fun deleteLink(peerFp: ByteArray)

    /** Copies this phone's links to the new phone of a move (SPEC.md §7). */
    suspend fun handoverLinks(to: ByteArray): Int

    suspend fun send(messages: List<Pair<ByteArray, ByteArray>>): Int

    suspend fun fetch(waitSeconds: Int = 0): List<InboxMessage>

    suspend fun ack(ids: List<String>)
}
