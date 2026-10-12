package org.tamodak.killit.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.pairing.DeviceKeys
import org.tamodak.killit.pairing.PairingServer
import org.tamodak.killit.protocol.ApiAuth
import org.tamodak.killit.protocol.ApiRequest
import org.tamodak.killit.protocol.deviceId
import org.tamodak.killit.protocol.randomBytes
import org.tamodak.killit.protocol.signRequest
import org.tamodak.killit.protocol.toBase64Url

/**
 * The one class that talks to the pairing server (PLAN.md §6.2 rule 3).
 *
 * Every call except the attestation challenge is signed with the identity key (SPEC.md §4.2):
 * there are no tokens or cookies to steal, and the signature binds the server's host, so a request
 * meant for the test server can't be replayed against production. Requests that fail because this
 * phone's clock is off are retried once, after correcting the clock from the server's `Date`
 * header.
 *
 * What it refuses to do is as deliberate: follow redirects (an API answer never moves), cache
 * anything, or read an answer larger than [MAX_RESPONSE_BYTES]. Nothing in a response is trusted
 * for anything that matters; the protocol layer decodes and verifies whatever is binary.
 *
 * Failures surface as exceptions: [ApiError] when the server answered with an error, and any
 * other [IOException] when it couldn't be reached or answered nonsense.
 *
 * @param baseUrl the server, e.g. `https://api.tamodak.org`.
 * @param audience the host requests are signed for (SPEC.md §4.1).
 * @param identity this phone's keys; null before the identity key exists.
 * @param now the wall clock, in milliseconds.
 */
class ApiClient(
    private val baseUrl: String,
    private val audience: String,
    private val identity: suspend () -> DeviceKeys.Identity?,
    private val http: OkHttpClient = defaultHttpClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PairingServer {
    /**
     * How far this phone's clock is behind the server's, learned from a `stale_timestamp` refusal.
     * Kept only for the process's lifetime: the clock may be corrected in the meantime.
     */
    @Volatile
    private var clockOffsetMillis = 0L

    // ---------------------------------------------------------------- registration (SPEC.md §5)

    /** `POST /v1/register/challenge`: the one unsigned call. */
    suspend fun challenge(): ChallengeResponse =
        call("POST", "/v1/register/challenge", null, ChallengeResponse.serializer(), signed = false)

    /** `POST /v1/devices`: registers the identity key with its attestation chain. */
    suspend fun register(challenge: ByteArray, chain: List<ByteArray>): DeviceResponse =
        call(
            "POST",
            "/v1/devices",
            json.encodeToString(RegisterRequest.serializer(), RegisterRequest(challenge.toBase64Url(), chain.map { it.toBase64Url() })),
            DeviceResponse.serializer(),
        )

    /** `GET /v1/devices/me`: answers only if the server knows this phone's key. */
    suspend fun me(): DeviceResponse = call("GET", "/v1/devices/me", null, DeviceResponse.serializer())

    /** `PUT /v1/devices/me/push`. */
    suspend fun setPushToken(token: String) {
        call<Unit>("PUT", "/v1/devices/me/push", json.encodeToString(PushTokenRequest.serializer(), PushTokenRequest(token)), null)
    }

    /** `DELETE /v1/devices/me/push`. */
    suspend fun clearPushToken() {
        call<Unit>("DELETE", "/v1/devices/me/push", null, null)
    }

    /** `DELETE /v1/devices/me`: forgets this phone, its links, its invites and its mailbox. */
    suspend fun deleteDevice() {
        call<Unit>("DELETE", "/v1/devices/me", null, null)
    }

    // ---------------------------------------------------------------- invites (SPEC.md §6)

    /** `POST /v1/invites`: a new code, retiring this phone's earlier unfinished invites. */
    override suspend fun createInvite(): InviteCreated =
        call("POST", "/v1/invites", null, InviteCreated.serializer()).also { requireId(it.id) }

    /** `POST /v1/invites/join` with the joiner's commitment. A wrong, used or expired code is a 404. */
    override suspend fun joinInvite(code: String, commit: ByteArray): InviteJoined =
        call(
            "POST",
            "/v1/invites/join",
            json.encodeToString(JoinRequest.serializer(), JoinRequest(code, commit.toBase64Url())),
            InviteJoined.serializer(),
        ).also { requireId(it.id) }

    /**
     * `GET /v1/invites/{id}`. With [known], the server holds the request until the invite changes
     * from that version or [waitSeconds] pass.
     */
    override suspend fun invite(id: String, waitSeconds: Int, known: String?): InviteView {
        requireId(id)
        val query = if (known == null) "" else "?wait=$waitSeconds&known=${known.urlSafe()}"
        return call("GET", "/v1/invites/$id$query", null, InviteView.serializer())
    }

    /** `POST /v1/invites/{id}/reveal`. */
    override suspend fun reveal(id: String, reveal: ByteArray): InviteView {
        requireId(id)
        return call(
            "POST",
            "/v1/invites/$id/reveal",
            json.encodeToString(RevealRequest.serializer(), RevealRequest(reveal.toBase64Url())),
            InviteView.serializer(),
        )
    }

    /** `DELETE /v1/invites/{id}`: "They don't match", back, or giving up. */
    override suspend fun cancelInvite(id: String) {
        requireId(id)
        call<Unit>("DELETE", "/v1/invites/$id", null, null)
    }

    // ---------------------------------------------------------------- links (SPEC.md §7)

    /** `POST /v1/links`: confirms the safety code; the link exists once both phones have. */
    override suspend fun confirmLink(inviteId: String): LinkConfirmed {
        requireId(inviteId)
        return call(
            "POST",
            "/v1/links",
            json.encodeToString(LinkRequest.serializer(), LinkRequest(inviteId)),
            LinkConfirmed.serializer(),
        )
    }

    /** `GET /v1/links`: every linked phone, with whole days since it was last seen. */
    override suspend fun links(): List<LinkInfo> = call("GET", "/v1/links", null, LinksResponse.serializer()).links

    /** `DELETE /v1/links/{peer}`. Idempotent. */
    override suspend fun deleteLink(peerFp: ByteArray) {
        call<Unit>("DELETE", "/v1/links/${deviceId(peerFp)}", null, null)
    }

    // ---------------------------------------------------------------- mailbox (SPEC.md §8)

    /**
     * `POST /v1/messages`: all or nothing. A recipient that isn't linked fails the whole batch
     * with `403 not_linked`, naming the peers.
     *
     * @param messages recipient fingerprint and envelope, at most 50.
     * @return how many the server accepted.
     */
    override suspend fun send(messages: List<Pair<ByteArray, ByteArray>>): Int {
        val body = SendRequest(messages.map { (to, envelope) -> OutgoingMessage(deviceId(to), envelope.toBase64Url()) })
        return call("POST", "/v1/messages", json.encodeToString(SendRequest.serializer(), body), SendResponse.serializer()).accepted
    }

    /** `GET /v1/messages`, held by the server for up to [waitSeconds] when the mailbox is empty. */
    override suspend fun fetch(waitSeconds: Int): List<InboxMessage> {
        val query = if (waitSeconds > 0) "?wait=$waitSeconds" else ""
        return call("GET", "/v1/messages$query", null, InboxResponse.serializer()).messages
            .onEach { requireId(it.id) }
    }

    /** `POST /v1/messages/ack`: the server deletes these messages. */
    override suspend fun ack(ids: List<String>) {
        ids.forEach(::requireId)
        call<Unit>("POST", "/v1/messages/ack", json.encodeToString(AckRequest.serializer(), AckRequest(ids)), null)
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Sends one request and decodes its answer, retrying once with a corrected clock when the
     * server refuses the timestamp.
     *
     * @param body the exact JSON to send, or null for none. It is signed byte for byte.
     * @param serializer decodes a successful answer; null when none is expected (`204`).
     */
    private suspend fun <T> call(method: String, target: String, body: String?, serializer: KSerializer<T>?, signed: Boolean = true): T =
        withContext(io) {
            val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            try {
                exchange(method, target, bytes, body != null, signed, serializer)
            } catch (stale: StaleClock) {
                clockOffsetMillis = stale.serverTime - now()
                KillitLog.w(KillitLog.NET, "Clock is off by ${clockOffsetMillis / 1000} s; retrying once")
                try {
                    exchange(method, target, bytes, body != null, signed, serializer)
                } catch (again: StaleClock) {
                    throw again.error
                }
            }
        }

    /** One request and its answer. A new nonce and timestamp are drawn every time. */
    private suspend fun <T> exchange(
        method: String,
        target: String,
        body: ByteArray,
        hasBody: Boolean,
        signed: Boolean,
        serializer: KSerializer<T>?,
    ): T = http.newCall(build(method, target, body, hasBody, signed)).await().use { response ->
        val text = readLimited(response.body)
        val path = target.substringBefore('?')
        if (response.isSuccessful) {
            KillitLog.d(KillitLog.NET) { "$method $path -> ${response.code}" }
            @Suppress("UNCHECKED_CAST")
            return@use if (serializer == null) Unit as T else decode(serializer, text)
        }
        val error = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        val failure = ApiError(
            status = response.code,
            code = error?.error ?: "unknown",
            reason = error?.reason,
            state = error?.state,
            peers = error?.peers.orEmpty(),
            retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
        )
        KillitLog.d(KillitLog.NET) { "$method $path -> ${failure.message}" }
        val serverTime = response.headers.getDate("Date")?.time
        if (failure.status == 401 && failure.reason == "stale_timestamp" && serverTime != null) throw StaleClock(failure, serverTime)
        throw failure
    }

    /** Builds a request, signing it when [signed] (SPEC.md §4.2). */
    private suspend fun build(method: String, target: String, body: ByteArray, hasBody: Boolean, signed: Boolean): Request {
        val url = (baseUrl + target).toHttpUrl()
        val sentTarget = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val builder = Request.Builder().url(url)
        val requestBody = when {
            hasBody -> body.toRequestBody(JSON)
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(method, requestBody)
        if (signed) {
            val keys = identity() ?: throw IOException("this phone has no identity key yet")
            val request = ApiRequest(audience, method, sentTarget, now() + clockOffsetMillis, randomBytes(ApiAuth.NONCE_BYTES), body)
            for ((name, value) in signRequest(keys.signer, keys.fp, request)) builder.header(name, value)
        }
        return builder.build()
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T =
        try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw MalformedResponseException()
        } catch (e: IllegalArgumentException) {
            throw MalformedResponseException()
        }

    /** Reads a body, refusing one larger than [MAX_RESPONSE_BYTES] before buffering it. */
    private fun readLimited(body: ResponseBody): String {
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw MalformedResponseException()
        val source = body.source()
        if (source.request(MAX_RESPONSE_BYTES + 1)) throw MalformedResponseException()
        return source.buffer.readUtf8()
    }

    /** An id the server issued goes into a path, so it must be exactly a UUID. */
    private fun requireId(id: String) {
        if (!uuid.matches(id)) throw MalformedResponseException()
    }

    /** `known` is the server's own base64url version string; anything else is not sent back. */
    private fun String.urlSafe(): String {
        if (!versionPattern.matches(this)) throw MalformedResponseException()
        return this
    }

    companion object {
        /** The largest answer read: 32 envelopes in base64url, with room to spare. */
        const val MAX_RESPONSE_BYTES = 1L shl 20

        private val JSON = "application/json".toMediaType()
        private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val versionPattern = Regex("^[A-Za-z0-9_-]{1,32}$")

        /** Unknown fields are ignored, so the server can add some without breaking older phones. */
        internal val json = Json { ignoreUnknownKeys = true }

        /**
         * The HTTP client: no redirects, no cache, no cookies, and a read timeout longer than the
         * longest poll the server holds (30 s).
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/**
 * The server answered with an error (SPEC.md §4.3).
 *
 * @param code the stable `error` field, e.g. `not_found`, or `unknown` when the body wasn't one.
 * @param reason `reason`, for `unauthorized` and `attestation_rejected`.
 * @param state `state`, for `conflict`.
 * @param peers `peers`, for `not_linked`: base64url fingerprints.
 * @param retryAfterSeconds the `Retry-After` header, for `429` and `503`.
 */
class ApiError(
    val status: Int,
    val code: String,
    val reason: String? = null,
    val state: String? = null,
    val peers: List<String> = emptyList(),
    val retryAfterSeconds: Long? = null,
) : IOException("$status $code${reason?.let { " ($it)" } ?: ""}")

/** The server answered something that isn't what the API returns. Treated like a network failure. */
class MalformedResponseException : IOException("malformed response")

/** A `stale_timestamp` refusal, with the server's time from its `Date` header. */
private class StaleClock(val error: ApiError, val serverTime: Long) : Exception(error.message)

/** Runs a call on OkHttp's dispatcher, cancelling it when the coroutine is cancelled. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
