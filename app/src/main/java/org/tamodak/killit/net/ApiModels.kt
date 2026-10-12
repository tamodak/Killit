package org.tamodak.killit.net

import kotlinx.serialization.Serializable

/*
 * The JSON the pairing API speaks (SPEC.md §5–§8), one class per body.
 *
 * Codecs are generated at compile time, so nothing is bound by reflection, and only the declared
 * fields are read. Binary values arrive as base64url strings and are turned into bytes by the
 * protocol layer's strict decoders, which check their exact sizes; envelopes and reveals are
 * opened and verified end to end. Nothing in a response is trusted for anything that matters
 * (SPEC.md §4.2, "Responses are not signed").
 */

@Serializable
class ChallengeResponse(val challenge: String, val expiresAt: Long)

@Serializable
class RegisterRequest(val challenge: String, val chain: List<String>)

@Serializable
class DeviceResponse(val device: String, val securityLevel: String)

@Serializable
class PushTargetRequest(val fid: String)

@Serializable
class InviteCreated(val id: String, val code: String, val expiresAt: Long)

@Serializable
class JoinRequest(val code: String, val commit: String)

@Serializable
class InviteJoined(val id: String, val expiresAt: Long)

/** Whether each side has confirmed the safety code. */
@Serializable
class InviteConfirmations(val self: Boolean, val peer: Boolean)

/**
 * An invite as one participant sees it (SPEC.md §6.2).
 *
 * @param state `open`, `joined`, `creator_revealed`, `revealed`, `linked`, `cancelled` or `expired`.
 * @param role the caller's part in the ceremony: `creator` or `joiner`. Never guardian or protected.
 * @param version changes whenever anything else does; sent back as `known` to long-poll.
 */
@Serializable
class InviteView(
    val id: String,
    val state: String,
    val role: String,
    val expiresAt: Long,
    val commit: String? = null,
    val creatorReveal: String? = null,
    val joinerReveal: String? = null,
    val confirmed: InviteConfirmations,
    val version: String,
)

@Serializable
class RevealRequest(val reveal: String)

@Serializable
class LinkRequest(val invite: String)

/** @param state `waiting` until both phones confirmed, then `linked`. */
@Serializable
class LinkConfirmed(val state: String, val peer: String)

@Serializable
class LinkInfo(val peer: String, val lastSeenDaysAgo: Int)

@Serializable
class LinksResponse(val links: List<LinkInfo>)

@Serializable
class OutgoingMessage(val to: String, val envelope: String)

@Serializable
class SendRequest(val messages: List<OutgoingMessage>)

@Serializable
class SendResponse(val accepted: Int)

@Serializable
class InboxMessage(val id: String, val from: String, val envelope: String, val receivedAt: Long)

@Serializable
class InboxResponse(val messages: List<InboxMessage>)

@Serializable
class AckRequest(val ids: List<String>)

/** An error body (SPEC.md §4.3). Only the fields some error carries are optional. */
@Serializable
class ErrorBody(
    val error: String,
    val reason: String? = null,
    val state: String? = null,
    val peers: List<String> = emptyList(),
)
