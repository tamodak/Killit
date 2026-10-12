package org.tamodak.killit.protocol

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fingerprints, bundles and the P-256 conversions (SPEC.md §2, §3), against `identities.json`. */
class IdentityVectorsTest {
    private val phones = Vectors.load("identities.json").array("phones").map { it.obj }

    /** Fingerprints, device ids, the key binding and the bundle encoding all match the reference. */
    @Test
    fun phonesMatchTheReference() {
        for (phone in phones) {
            val name = phone.str("name")
            val signPk = phone.hex("signPk")
            assertArrayEquals(name, phone.hex("fp"), fingerprint(signPk))
            assertEquals(name, phone.str("deviceId"), deviceId(fingerprint(signPk)))
            assertArrayEquals(name, phone.hex("encKeySignedBytes"), encKeyBindingBytes(phone.hex("encPk")))
            val bundle = Bundle(signPk, phone.hex("encPk"), phone.hex("encSig"))
            assertArrayEquals(name, phone.hex("bundle"), encodeBundle(bundle))
            assertTrue(name, verifyBundle(bundle))

            val decoded = decodeBundle(phone.hex("bundle")).ok(name)
            assertArrayEquals(name, signPk, decoded.signPk)
            assertArrayEquals(name, phone.hex("encPk"), decoded.encPk)
            assertArrayEquals(name, phone.hex("encSig"), decoded.encSig)
        }
    }

    /** Killit's own signatures, converted from DER, verify under the vector's public key. */
    @Test
    fun ownSignaturesVerify() {
        for (phone in Vectors.phones.values) {
            val encSig = signEncKey(phone.signer, phone.encPk)
            assertEquals(SIGNATURE_BYTES, encSig.size)
            assertTrue(phone.name, verifyBundle(Bundle(phone.signPk, phone.encPk, encSig)))
        }
    }

    /** Tink accepts each vector's X25519 key pair, which it checks for consistency. */
    @Test
    fun encryptionKeysAreConsistent() {
        for (phone in Vectors.phones.values) {
            assertArrayEquals(phone.name, phone.encPk, Hpke.privateKey(phone.encPrivate, phone.encPk).publicKey.publicKeyBytes.toByteArray())
        }
    }

    /** A point off the curve, a compressed point or a wrong length is refused before any key is built. */
    @Test
    fun badPointsAreRefused() {
        val good = Vectors.phone("A").signPk
        val offCurve = good.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        val compressed = good.copyOf().also { it[0] = 0x02 }
        assertThrows(DecodeException::class.java) { P256.publicKey(offCurve) }
        assertThrows(DecodeException::class.java) { P256.publicKey(compressed) }
        assertThrows(DecodeException::class.java) { P256.publicKey(good.copyOf(64)) }
        val bundle = Vectors.load("identities.json").array("phones")[0].obj.hex("bundle")
        bundle[1 + 64] = (bundle[1 + 64].toInt() xor 1).toByte()
        assertTrue(decodeBundle(bundle) is Decoded.Rejected)
    }

    /** Verification refuses zero, out-of-range and malformed signatures without throwing. */
    @Test
    fun verificationRefusesMalformedSignatures() {
        val phone = Vectors.phone("A")
        val data = byteArrayOf(1, 2, 3)
        val signature = phone.signer.sign(data)
        assertTrue(P256.verify(phone.signPk, data, signature))
        assertFalse(P256.verify(phone.signPk, byteArrayOf(1, 2, 4), signature))
        assertFalse(P256.verify(phone.signPk, data, ByteArray(64)))
        assertFalse(P256.verify(phone.signPk, data, signature.copyOf(63)))
        val order = P256.order.toByteArray().takeLast(32).toByteArray()
        assertFalse(P256.verify(phone.signPk, data, signature.copyOfRange(0, 32) + order))
        assertFalse(P256.verify(ByteArray(65), data, signature))
    }

    /** DER and `r ‖ s` convert both ways, including halves with the top bit set or leading zeros. */
    @Test
    fun derConversionRoundTrips() {
        val cases = listOf(
            ByteArray(32) { 0xff.toByte() }.also { it[0] = 0x7f } + ByteArray(32) { 1 },
            ByteArray(32) { if (it == 0) 0x80.toByte() else 7 } + ByteArray(32) { if (it < 3) 0 else 9 },
            ByteArray(31) + byteArrayOf(1) + ByteArray(32) { 0xfe.toByte() },
        )
        for (raw in cases) {
            val der = P256.rawToDer(raw)
            assertArrayEquals(raw, P256.derToRaw(der))
            assertEquals(BigInteger(1, raw.copyOfRange(0, 32)).toByteArray().size, der[3].toInt())
        }
        assertThrows(IllegalArgumentException::class.java) { P256.derToRaw(byteArrayOf(0x30, 0x02, 0x02, 0x00)) }
    }
}
