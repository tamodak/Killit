package org.tamodak.killit.protocol

/*
 * API request signatures (SPEC.md §4.2).
 *
 * There are no bearer tokens: every request is signed by the phone's hardware-held key, so a
 * leaked request can't be reused (the nonce and the 300-second window stop that) and a server
 * breach yields no credential to steal.
 */

/** The four authentication headers and the window the server checks them against. */
object ApiAuth {
    const val HEADER_DEVICE = "killit-device"
    const val HEADER_TIMESTAMP = "killit-timestamp"
    const val HEADER_NONCE = "killit-nonce"
    const val HEADER_SIGNATURE = "killit-signature"

    /** Length of a request nonce. */
    const val NONCE_BYTES = 16

    /** How far a request's timestamp may be from the server's clock. */
    const val WINDOW_MILLIS = 300_000L
}

/**
 * Everything a request signature covers.
 *
 * @param audience the API host the request is meant for, e.g. `api.tamodak.org`, so a request
 *   signed for the test server can't be replayed against production.
 * @param method the HTTP method; signed in uppercase.
 * @param target the path and query exactly as sent.
 * @param timestamp milliseconds since the epoch.
 * @param body the exact body bytes; empty when there is no body.
 */
class ApiRequest(
    val audience: String,
    val method: String,
    val target: String,
    val timestamp: Long,
    val nonce: ByteArray,
    val body: ByteArray,
)

/** True for the printable ASCII that audiences, methods and targets are made of. */
private fun isHeaderText(value: String): Boolean = value.length in 1..2048 && value.all { it in '!'..'~' }

/** Writes an ASCII value as `text16`, refusing anything outside printable ASCII. */
private fun ByteWriter.ascii(value: String): ByteWriter {
    require(isHeaderText(value)) { "not printable ASCII: $value" }
    return blob16(value.toByteArray(Charsets.US_ASCII))
}

/**
 * Builds `signedBytes` (SPEC.md §4.2).
 *
 * The method is uppercased with [asciiUppercase], so no Unicode case mapping can turn it into
 * something other than what the server rebuilds.
 */
fun apiSignedBytes(request: ApiRequest): ByteArray {
    require(request.nonce.size == ApiAuth.NONCE_BYTES) { "nonce must be 16 bytes" }
    return ByteWriter()
        .raw(Labels.API)
        .ascii(request.audience)
        .ascii(asciiUppercase(request.method))
        .ascii(request.target)
        .u64(request.timestamp)
        .fixed(request.nonce, ApiAuth.NONCE_BYTES)
        .raw(sha256(request.body))
        .finish()
}

/** Signs a request and returns its four authentication headers. */
fun signRequest(signer: Signer, fp: ByteArray, request: ApiRequest): Map<String, String> {
    require(fp.size == FINGERPRINT_BYTES) { "a fingerprint is 32 bytes" }
    return mapOf(
        ApiAuth.HEADER_DEVICE to deviceId(fp),
        ApiAuth.HEADER_TIMESTAMP to request.timestamp.toString(),
        ApiAuth.HEADER_NONCE to request.nonce.toBase64Url(),
        ApiAuth.HEADER_SIGNATURE to signer.sign(apiSignedBytes(request)).toBase64Url(),
    )
}

/** True when [signature] covers [request] under [signPk]. Never throws. The server's check, for tests. */
fun verifyRequest(signPk: ByteArray, request: ApiRequest, signature: ByteArray): Boolean {
    val bytes = try {
        apiSignedBytes(request)
    } catch (e: IllegalArgumentException) {
        return false
    }
    return P256.verify(signPk, bytes, signature)
}
