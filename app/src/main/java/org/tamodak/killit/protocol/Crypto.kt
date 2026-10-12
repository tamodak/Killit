package org.tamodak.killit.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * The primitives of SPEC.md §2 that the platform provides: SHA-256, HMAC-SHA256 and P-256 ECDSA
 * verification. Signing happens in Android Keystore (pairing/Identity.kt) and encryption in Tink
 * (Hpke.kt); this file only converts between their formats and the protocol's.
 */

/** Length of an uncompressed P-256 point: `0x04 ‖ X ‖ Y`. */
const val P256_POINT_BYTES = 65

/** Length of a P1363 signature: `r ‖ s`. */
const val SIGNATURE_BYTES = 64

/** Length of an X25519 key. */
const val X25519_KEY_BYTES = 32

/**
 * Something that signs with a phone's identity key: ECDSA P-256 / SHA-256, returning the 64-byte
 * `r ‖ s` form.
 *
 * On a phone this is the hardware-held key in Android Keystore, which never leaves the secure
 * hardware; the protocol code only ever sees this interface, so it can't mishandle the key.
 */
fun interface Signer {
    /** @return the signature over [data]. */
    fun sign(data: ByteArray): ByteArray
}

private val secureRandom = SecureRandom()

/** Draws [length] bytes from the platform's CSPRNG, which seeds itself. */
fun randomBytes(length: Int): ByteArray = ByteArray(length).also(secureRandom::nextBytes)

/** SHA-256 over the concatenation of [parts]. */
fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
        parts.forEach(::update)
        digest()
    }

/** HMAC-SHA256 under [key] over the concatenation of [parts]. */
fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(::update)
        doFinal()
    }

/** P-256 (secp256r1) and the arithmetic needed to check that a point is on it. */
object P256 {
    /** The field prime. */
    private val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)

    /** The curve's `b` coefficient; `a` is −3. */
    private val b = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    /** The group order, which bounds both halves of a signature. */
    val order: BigInteger = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    /** The platform's parameters for the curve, for building keys. */
    private val parameters: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    /**
     * Turns a 65-byte uncompressed point into a public key.
     *
     * The point is checked against the curve equation here rather than trusting the platform to,
     * so a decoded key is always a real one whichever provider is underneath.
     *
     * @throws DecodeException on any other length or prefix, or a point off the curve.
     */
    fun publicKey(point: ByteArray): PublicKey {
        if (point.size != P256_POINT_BYTES || point[0] != 0x04.toByte()) {
            throw DecodeException("not an uncompressed P-256 point")
        }
        val x = BigInteger(1, point.copyOfRange(1, 33))
        val y = BigInteger(1, point.copyOfRange(33, 65))
        if (x >= p || y >= p) throw DecodeException("P-256 coordinate out of range")
        // y² = x³ − 3x + b (mod p)
        val left = y.multiply(y).mod(p)
        val right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(b).mod(p)
        if (left != right) throw DecodeException("P-256 point not on the curve")
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), parameters))
        } catch (e: java.security.GeneralSecurityException) {
            throw DecodeException("P-256 point refused")
        }
    }

    /** Exports a P-256 public key as its uncompressed point. */
    fun point(key: ECPublicKey): ByteArray =
        concat(byteArrayOf(0x04), key.w.affineX.toFixed(32), key.w.affineY.toFixed(32))

    /**
     * Converts a DER signature, as Android Keystore and the JCA produce, to the protocol's 64-byte
     * `r ‖ s`.
     *
     * @throws IllegalArgumentException when [der] is not a well-formed ECDSA signature.
     */
    fun derToRaw(der: ByteArray): ByteArray {
        val reader = DerReader(der)
        reader.expect(0x30)
        val sequenceLength = reader.length()
        require(sequenceLength == reader.remaining) { "DER signature length mismatch" }
        val r = reader.integer()
        val s = reader.integer()
        require(reader.remaining == 0) { "trailing bytes after the DER signature" }
        return concat(r.toFixed(32), s.toFixed(32))
    }

    /** Converts a 64-byte `r ‖ s` signature to DER, for the platform's verifier. */
    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == SIGNATURE_BYTES) { "a raw signature is 64 bytes" }
        val r = derInteger(BigInteger(1, raw.copyOfRange(0, 32)))
        val s = derInteger(BigInteger(1, raw.copyOfRange(32, 64)))
        return concat(byteArrayOf(0x30), derLength(r.size + s.size), r, s)
    }

    /**
     * Verifies a 64-byte `r ‖ s` ECDSA P-256 / SHA-256 signature.
     *
     * Returns false, never throws, for a malformed signature or key: this guards authority, so
     * anything short of a positive verification is a refusal. A zero or out-of-range `r` or `s` is
     * refused before the platform sees it.
     */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_BYTES) return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r.signum() == 0 || s.signum() == 0 || r >= order || s >= order) return false
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey(publicKey))
                update(data)
                verify(rawToDer(signature))
            }
        } catch (e: DecodeException) {
            false
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
    }

    /** A big-endian unsigned integer padded or trimmed to [size] bytes. */
    private fun BigInteger.toFixed(size: Int): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == size -> bytes
            bytes.size == size + 1 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, bytes.size)
            bytes.size < size -> ByteArray(size - bytes.size) + bytes
            else -> throw IllegalArgumentException("integer longer than $size bytes")
        }
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val body = value.toByteArray() // minimal two's complement: a leading 0x00 keeps it positive
        return concat(byteArrayOf(0x02), derLength(body.size), body)
    }

    private fun derLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xff -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> throw IllegalArgumentException("DER length too large")
    }

    /** Just enough of a DER reader for an ECDSA signature. */
    private class DerReader(private val bytes: ByteArray) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset

        fun expect(tag: Int) {
            require(offset < bytes.size && (bytes[offset].toInt() and 0xff) == tag) { "unexpected DER tag" }
            offset++
        }

        fun length(): Int {
            require(offset < bytes.size) { "truncated DER" }
            val first = bytes[offset++].toInt() and 0xff
            if (first < 0x80) return first
            require(first == 0x81 && offset < bytes.size) { "unsupported DER length" }
            return bytes[offset++].toInt() and 0xff
        }

        fun integer(): BigInteger {
            expect(0x02)
            val length = length()
            require(length in 1..33 && length <= remaining) { "bad DER integer" }
            val value = BigInteger(1, bytes.copyOfRange(offset, offset + length))
            offset += length
            return value
        }
    }
}
