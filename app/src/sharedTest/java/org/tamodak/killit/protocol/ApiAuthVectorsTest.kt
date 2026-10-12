package org.tamodak.killit.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Request signatures (SPEC.md §4.2), against `api-auth.json`. */
class ApiAuthVectorsTest {
    private val cases = Vectors.load("api-auth.json").array("cases").map { it.obj }

    private fun request(case: kotlinx.serialization.json.JsonObject) = ApiRequest(
        audience = case.str("audience"),
        method = case.str("method"),
        target = case.str("target"),
        timestamp = case.long("timestamp"),
        nonce = case.hex("nonce"),
        body = case.hex("body"),
    )

    /** Killit builds the same signed bytes as the reference for every case. */
    @Test
    fun signedBytesMatch() {
        for (case in cases) {
            assertArrayEquals(case.str("name"), case.hex("bodySha256"), sha256(case.hex("body")))
            assertArrayEquals(case.str("name"), case.hex("signedBytes"), apiSignedBytes(request(case)))
        }
    }

    /** The reference's signatures verify exactly where the vectors say they should. */
    @Test
    fun referenceSignaturesVerifyOnlyWhereValid() {
        for (case in cases) {
            val verifier = Vectors.phone(case.str("verifier"))
            assertEquals(case.str("name"), case.bool("valid"), verifyRequest(verifier.signPk, request(case), case.hex("signature")))
        }
    }

    /** Killit's headers match the reference's in everything but the randomised signature, which verifies. */
    @Test
    fun headersMatch() {
        for (case in cases.filter { "headers" in it }) {
            val signer = Vectors.phone(case.str("signer"))
            val expected = case.obj("headers")
            val headers = signRequest(signer.signer, signer.fp, request(case))
            for (name in listOf(ApiAuth.HEADER_DEVICE, ApiAuth.HEADER_TIMESTAMP, ApiAuth.HEADER_NONCE)) {
                assertEquals(case.str("name"), expected.str(name), headers.getValue(name))
            }
            assertEquals(case.hex("signature").toBase64Url(), expected.str(ApiAuth.HEADER_SIGNATURE))
            val signature = headers.getValue(ApiAuth.HEADER_SIGNATURE).fromBase64Url(SIGNATURE_BYTES)
            assertTrue(case.str("name"), verifyRequest(signer.signPk, request(case), signature))
        }
    }

    /** The method is signed in uppercase whatever case it was given in. */
    @Test
    fun methodIsUppercased() {
        val case = cases.first()
        val lower = request(case).let { ApiRequest(it.audience, it.method.lowercase(), it.target, it.timestamp, it.nonce, it.body) }
        assertArrayEquals(case.hex("signedBytes"), apiSignedBytes(lower))
    }

    /** A target with a space or a non-ASCII character can't be signed, so it can't be verified either. */
    @Test
    fun nonAsciiTargetsAreRefused() {
        val case = cases.first()
        val bad = request(case).let { ApiRequest(it.audience, it.method, "/v1/messages?x=ç", it.timestamp, it.nonce, it.body) }
        assertFalse(verifyRequest(Vectors.phone("A").signPk, bad, case.hex("signature")))
    }
}
