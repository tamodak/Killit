package org.tamodak.killit.pairing

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoding every QR payload is built from.
 *
 * Hand-written, so it is worth testing at every length rather than at one: the bit-packing loop
 * carries a partial byte between characters, and the lengths where that leftover is not a whole
 * byte are exactly where an off-by-one hides.
 */
class Base32Test {

    /**
     * Encoding then decoding must return the input, at every length from empty to 64 bytes.
     *
     * Also pins the two properties the wire format depends on: that the encoded length is
     * predictable — [QrPayload] computes its field offsets from it — and that the output stays
     * inside QR's alphanumeric set, which is the whole reason for using Base32 over Base64.
     */
    @Test
    fun roundTripsAtEveryLength() {
        val random = SecureRandom()

        for (size in 0..64) {
            val bytes = ByteArray(size).also { random.nextBytes(it) }
            val encoded = bytes.encodeBase32()

            assertEquals("Encoded length must be predictable", base32Length(size), encoded.length)
            assertTrue(
                "Base32 has to stay inside the QR alphanumeric set: $encoded",
                encoded.all { it in 'A'..'Z' || it in '2'..'7' },
            )
            assertTrue("Round trip failed at $size bytes", bytes.contentEquals(encoded.decodeBase32()))
        }
    }

    /**
     * Pins the three field widths the decoders slice by.
     *
     * These are derived at class-init time rather than hardcoded, so a change to a key or signature
     * size shifts every offset in [QrPayload] at once. Asserting the numbers here means such a
     * change has to be deliberate: it fails this test rather than silently producing payloads the
     * other side reads at the wrong boundaries.
     */
    @Test
    fun theFieldWidthsTheWireFormatDependsOn() {
        assertEquals(53, base32Length(PeerKeyStore.PUBLIC_KEY_BYTES))
        assertEquals(103, base32Length(PeerKeyStore.SIGNATURE_BYTES))
        assertEquals(26, base32Length(PairingProtocol.NONCE_BYTES))
    }

    /**
     * Malformed input decodes to null rather than to plausible-looking bytes.
     *
     * This runs on QR codes read off a camera, where a partial or misread code is routine. Every
     * case below is one a lenient decoder would happily accept.
     */
    @Test
    fun rejectsAnythingItDidNotProduce() {
        assertNull("Lowercase is not in the alphabet", "aaaa".decodeBase32())
        assertNull("Padding is not accepted", "AAAA====".decodeBase32())
        assertNull("0, 1 and 8 are not in the alphabet", "AAA0".decodeBase32())

        // Lengths that cannot come from a whole number of bytes: one, three or six characters
        // leave five or more bits over, which is a character that encoded nothing.
        listOf("A", "AAA", "AAAAAA").forEach {
            assertNull("${it.length} characters is not a valid length", it.decodeBase32())
        }

        // Canonical decoding: the unused low bits of the last character must be zero.
        assertNull("Non-zero padding bits", "AB".decodeBase32())
    }
}
