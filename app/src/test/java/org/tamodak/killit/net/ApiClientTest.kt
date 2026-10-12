package org.tamodak.killit.net

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.tamodak.killit.pairing.DeviceKeys
import org.tamodak.killit.protocol.ApiAuth
import org.tamodak.killit.protocol.ApiRequest
import org.tamodak.killit.protocol.Vectors
import org.tamodak.killit.protocol.deviceId
import org.tamodak.killit.protocol.fromBase64Url
import org.tamodak.killit.protocol.verifyRequest

/** The API client against a scripted server: signatures, clock correction, errors and limits. */
class ApiClientTest {
    private val phone = Vectors.phone("A")
    private val identity = DeviceKeys.Identity(
        signPk = phone.signPk,
        fp = phone.fp,
        encPk = phone.encPk,
        signer = phone.signer,
        decrypter = phone.decrypter,
        strongBox = false,
    )
    private val server = MockWebServer()
    private var clock = 1_790_000_000_000L
    private lateinit var api: ApiClient

    @Before
    fun start() {
        server.start()
        api = ApiClient(
            baseUrl = server.url("/").toString().removeSuffix("/"),
            audience = AUDIENCE,
            identity = { identity },
            now = { clock },
        )
    }

    @After
    fun stop() {
        server.close()
    }

    private fun answer(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        server.enqueue(
            MockResponse.Builder().code(code).body(body).apply { headers.forEach { (name, value) -> setHeader(name, value) } }.build(),
        )
    }

    /** Rebuilds what the server verifies (SPEC.md §4.2) from what was actually sent. */
    private fun verifies(request: RecordedRequest): Boolean {
        val signed = ApiRequest(
            audience = AUDIENCE,
            method = request.method,
            target = request.target,
            timestamp = request.headers[ApiAuth.HEADER_TIMESTAMP]!!.toLong(),
            nonce = request.headers[ApiAuth.HEADER_NONCE]!!.fromBase64Url(16),
            body = request.body?.toByteArray() ?: ByteArray(0),
        )
        return verifyRequest(phone.signPk, signed, request.headers[ApiAuth.HEADER_SIGNATURE]!!.fromBase64Url(64))
    }

    /** A signed call carries the four headers, and its signature covers what was sent. */
    @Test
    fun signedRequestsVerify() = runBlocking<Unit> {
        answer(200, """{"device":"${deviceId(phone.fp)}","securityLevel":"tee"}""")
        assertEquals("tee", api.me().securityLevel)
        val request = server.takeRequest()
        assertEquals(deviceId(phone.fp), request.headers[ApiAuth.HEADER_DEVICE])
        assertEquals(clock.toString(), request.headers[ApiAuth.HEADER_TIMESTAMP])
        assertTrue(verifies(request))
    }

    /** A body is signed byte for byte, and a query string is part of the signed target. */
    @Test
    fun bodiesAndQueriesAreSigned() = runBlocking<Unit> {
        answer(200, """{"id":"$ID","expiresAt":1}""")
        api.joinInvite("K7QX-M2PD", ByteArray(32) { 7 })
        val join = server.takeRequest()
        assertTrue(join.body!!.utf8().startsWith("""{"code":"K7QX-M2PD","commit":"""))
        assertTrue(verifies(join))

        answer(200, """{"messages":[]}""")
        api.fetch(waitSeconds = 25)
        val fetch = server.takeRequest()
        assertEquals("/v1/messages?wait=25", fetch.target)
        assertTrue(verifies(fetch))
    }

    /** The attestation challenge is the one call made without a signature. */
    @Test
    fun challengeIsUnsigned() = runBlocking<Unit> {
        answer(200, """{"challenge":"AAAA","expiresAt":1}""")
        api.challenge()
        val request = server.takeRequest()
        assertNull(request.headers[ApiAuth.HEADER_SIGNATURE])
        assertNull(request.headers[ApiAuth.HEADER_DEVICE])
    }

    /** A stale timestamp is corrected from the server's `Date` and retried once, with a new nonce. */
    @Test
    fun staleClockIsCorrectedOnce() = runBlocking<Unit> {
        val serverTime = clock + 600_000L
        answer(401, """{"error":"unauthorized","reason":"stale_timestamp"}""", "Date" to httpDate(serverTime))
        answer(200, """{"device":"x","securityLevel":"tee"}""")
        api.me()
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(serverTime, second.headers[ApiAuth.HEADER_TIMESTAMP]!!.toLong())
        assertNotEquals(first.headers[ApiAuth.HEADER_NONCE], second.headers[ApiAuth.HEADER_NONCE])
        assertTrue(verifies(second))

        answer(401, """{"error":"unauthorized","reason":"stale_timestamp"}""", "Date" to httpDate(serverTime))
        answer(401, """{"error":"unauthorized","reason":"stale_timestamp"}""", "Date" to httpDate(serverTime))
        val error = assertThrows(ApiError::class.java) { runBlocking { api.me() } }
        assertEquals("stale_timestamp", error.reason)
    }

    /** Error bodies keep their machine-readable details (SPEC.md §4.3). */
    @Test
    fun errorsCarryTheirDetails() = runBlocking<Unit> {
        answer(403, """{"error":"not_linked","peers":["abc"]}""")
        val notLinked = assertThrows(ApiError::class.java) { runBlocking { api.send(listOf(phone.fp to ByteArray(8240))) } }
        assertEquals(listOf("abc"), notLinked.peers)

        answer(409, """{"error":"conflict","state":"link_limit"}""")
        assertEquals("link_limit", assertThrows(ApiError::class.java) { runBlocking { api.confirmLink(ID) } }.state)

        answer(429, """{"error":"rate_limited"}""", "Retry-After" to "42")
        assertEquals(42L, assertThrows(ApiError::class.java) { runBlocking { api.createInvite() } }.retryAfterSeconds)

        answer(500, "not json")
        assertEquals("unknown", assertThrows(ApiError::class.java) { runBlocking { api.links() } }.code)
    }

    /** A redirect is an error, never followed. */
    @Test
    fun redirectsAreNotFollowed() = runBlocking<Unit> {
        answer(302, "", "Location" to "https://example.com/")
        assertEquals(302, assertThrows(ApiError::class.java) { runBlocking { api.me() } }.status)
        assertEquals(1, server.requestCount)
    }

    /** Answers that are too large, not JSON, or carry ids that aren't ids are refused. */
    @Test
    fun malformedAnswersAreRefused() = runBlocking<Unit> {
        answer(200, "x".repeat((ApiClient.MAX_RESPONSE_BYTES + 1).toInt()))
        assertThrows(MalformedResponseException::class.java) { runBlocking { api.me() } }

        answer(200, "{")
        assertThrows(MalformedResponseException::class.java) { runBlocking { api.me() } }

        answer(201, """{"id":"../../devices/me","code":"K7QX-M2PD","expiresAt":1}""")
        assertThrows(MalformedResponseException::class.java) { runBlocking { api.createInvite() } }
    }

    /** Unknown fields are ignored, so a newer server doesn't break an older phone. */
    @Test
    fun unknownFieldsAreIgnored() = runBlocking<Unit> {
        answer(200, """{"links":[{"peer":"p","lastSeenDaysAgo":3,"extra":true}],"more":1}""")
        assertEquals(3, api.links().single().lastSeenDaysAgo)
    }

    /** Without an identity key nothing signed can be sent. */
    @Test
    fun noIdentityNoSignedCalls() {
        val anonymous = ApiClient(server.url("/").toString().removeSuffix("/"), AUDIENCE, identity = { null })
        assertThrows(IOException::class.java) { runBlocking { anonymous.me() } }
        assertEquals(0, server.requestCount)
    }

    /** Every body sent decodes as the server's strict schemas expect: no unknown or missing fields. */
    @Test
    fun envelopesAreSentAsBase64Url() = runBlocking<Unit> {
        answer(202, """{"accepted":1}""")
        val envelope = ByteArray(8240) { it.toByte() }
        assertEquals(1, api.send(listOf(phone.fp to envelope)))
        val body = server.takeRequest().body!!.utf8()
        val parsed = ApiClient.json.decodeFromString(SendRequest.serializer(), body)
        assertEquals(deviceId(phone.fp), parsed.messages.single().to)
        assertTrue(parsed.messages.single().envelope.fromBase64Url(8240).contentEquals(envelope))
    }

    /** An HTTP `Date` header value (RFC 9110 §5.6.7). */
    private fun httpDate(millis: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(millis))

    private companion object {
        const val AUDIENCE = "api.test.tamodak.org"
        const val ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
