package org.tamodak.killit.pairing

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.tamodak.killit.core.KillitLog
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * This device's pairing identity: an ECDSA P-256 key pair held in the Android Keystore.
 *
 * ### Why ECDSA rather than Ed25519
 *
 * Ed25519 is the nicer signature scheme, but Android has no Keystore support for it and no JCA
 * support at all below API 33 — on this project's `minSdk` 26 it would mean bundling a library and
 * keeping the private key in ordinary app storage, where root can read it. ECDSA P-256 is
 * Keystore-native from API 23, so the private key is held by the TEE (or StrongBox) and **cannot
 * be extracted from the device at all**, which is the property the pairing design actually depends
 * on.
 *
 * ### The key is never regenerated automatically
 *
 * "Clear data" deletes an app's Keystore entries. If this class quietly generated a new key pair
 * when it found none, this device's public key would change while every paired peer still held the
 * old one — and the peers would be left unable to verify anything, with nothing on screen to
 * explain why. So creation is explicit ([createKeyPair]) and absence is reported ([hasKeyPair]).
 *
 * ### What losing this key does and does not break
 *
 * Unlocking *this* device needs the **peers'** public keys, which are stored durably and survive
 * Clear data — so this device can still be unlocked. What breaks is this device's ability to act
 * as a companion: it can no longer sign, so any peer that depends on it can no longer be unlocked.
 *
 * @param ioDispatcher where Keystore and signing work runs. Injectable so tests can supply a
 *   deterministic dispatcher.
 */
class PeerKeyStore(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Reports whether this device has a pairing identity at all.
     *
     * @return true when the key exists. An unreadable Keystore also reports false — the caller's
     *   response either way is to offer to create one.
     */
    suspend fun hasKeyPair(): Boolean = withContext(ioDispatcher) {
        runCatching { loadKeyStore().containsAlias(ALIAS) }.getOrElse { error ->
            KillitLog.w(KillitLog.PAIRING, "Keystore unreadable; assuming no pairing key", error)
            false
        }
    }

    /**
     * Creates the pairing identity, preferring StrongBox and falling back to the TEE.
     *
     * Safe to call twice — an existing key is kept rather than replaced, because replacing it would
     * invalidate every peer that has already stored it.
     *
     * @return true when a key exists afterwards, whether it was created here or already present.
     */
    suspend fun createKeyPair(): Boolean = withContext(ioDispatcher) {
        if (runCatching { loadKeyStore().containsAlias(ALIAS) }.getOrDefault(false)) {
            KillitLog.d(KillitLog.PAIRING) { "Pairing key already exists; keeping it" }
            return@withContext true
        }

        runCatching {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)

            // StrongBox is a separate security chip, and the flag that asks for it only exists
            // from API 28. Below that there is nothing to attempt: asking would be a no-op, and
            // reporting success would claim a guarantee the key does not have.
            val strongBoxPossible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            val usedStrongBox = strongBoxPossible && runCatching {
                generator.initialize(keySpec(strongBox = true))
                generator.generateKeyPair()
            }.fold(
                onSuccess = { true },
                onFailure = { error ->
                    // Most devices have no StrongBox at all, so this is the common path.
                    KillitLog.d(KillitLog.PAIRING) {
                        "StrongBox unavailable (${error.javaClass.simpleName}); falling back to the TEE"
                    }
                    false
                },
            )

            if (!usedStrongBox) {
                generator.initialize(keySpec(strongBox = false))
                generator.generateKeyPair()
            }

            KillitLog.i(
                KillitLog.PAIRING,
                "Pairing key created, held by ${if (usedStrongBox) "StrongBox" else "the TEE"}",
            )
            true
        }.getOrElse { error ->
            KillitLog.e(KillitLog.PAIRING, "Could not create the pairing key", error)
            false
        }
    }

    /**
     * Reads this device's public key from the Keystore.
     *
     * @return the compressed P-256 point, or null when there is no pairing identity. Callers that
     *   need the key after a "Clear data" should prefer the durable copy, which survives.
     */
    suspend fun publicKey(): ByteArray? = withContext(ioDispatcher) {
        val certificate = runCatching { loadKeyStore().getCertificate(ALIAS) }.getOrNull()
        val publicKey = certificate?.publicKey as? ECPublicKey
        if (publicKey == null) {
            KillitLog.d(KillitLog.PAIRING) { "No pairing public key available" }
            return@withContext null
        }
        encodePoint(publicKey.w)
    }

    /**
     * Signs a payload with this device's private key.
     *
     * The platform returns DER, which is variable-length; it is converted to the fixed-width raw
     * form so a signature occupies a known number of characters in a QR code.
     *
     * @param payload the bytes to sign, from [PairingProtocol.payload].
     * @return the raw `r || s` signature, or null when this device has no pairing identity.
     *   Callers must surface that rather than treat it as a verification failure, since the two
     *   mean very different things.
     */
    suspend fun sign(payload: ByteArray): ByteArray? = withContext(ioDispatcher) {
        val privateKey = runCatching { loadKeyStore().getKey(ALIAS, null) as? PrivateKey }.getOrNull()
        if (privateKey == null) {
            KillitLog.w(KillitLog.PAIRING, "Cannot sign: this device has no pairing key")
            return@withContext null
        }
        val der = runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(privateKey)
                update(payload)
                sign()
            }
        }.getOrElse { error ->
            KillitLog.e(KillitLog.PAIRING, "Signing failed", error)
            return@withContext null
        }

        derToRaw(der) ?: run {
            KillitLog.e(KillitLog.PAIRING, "Could not read the ${der.size}-byte signature the platform produced")
            null
        }
    }

    /**
     * Verifies a companion's signature against that peer's public key.
     *
     * @param peerPublicKey the companion's compressed P-256 point, as stored at pairing time.
     * @param payload the bytes that should have been signed, rebuilt locally rather than taken
     *   from the scanned response.
     * @param signature the raw `r || s` signature from the response payload.
     * @return true only on a positive verification. Anything malformed — a corrupt public key, a
     *   truncated signature, a QR that decoded to nonsense — is false. There is no "could not tell"
     *   outcome here on purpose: this guards an unlock, so anything short of a positive
     *   verification is a refusal.
     */
    suspend fun verify(
        peerPublicKey: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean = withContext(ioDispatcher) {
        val key = decodePublicKey(peerPublicKey) ?: return@withContext false
        val der = rawToDer(signature) ?: return@withContext false
        runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(payload)
                verify(der)
            }
        }.getOrElse { error ->
            KillitLog.d(KillitLog.PAIRING) { "Verification threw: ${error.javaClass.simpleName}" }
            false
        }
    }

    // ---------------------------------------------------------------- point encoding

    /**
     * Builds the generation spec for the pairing key.
     *
     * No user-authentication requirement is set: the key has to be usable to approve *another*
     * device, which happens while this phone is simply unlocked and in the user's hand.
     *
     * @param strongBox whether to request the separate security chip. Ignored below API 28, where
     *   the flag does not exist.
     * @return the spec to initialise the generator with.
     */
    private fun keySpec(strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

    /**
     * Opens the Android Keystore.
     *
     * @return the loaded store. `load(null)` is required before use and takes no password, since
     *   the Keystore is protected by the platform rather than by one Killit holds.
     */
    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        /** The platform-backed provider. Keys here cannot be exported from the device. */
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Keystore alias for the pairing key. Changing it orphans every existing pairing. */
        private const val ALIAS = "killit_pairing_ec"

        /** P-256, alias `secp256r1`. Keystore-native from API 23. */
        private const val CURVE = "secp256r1"

        /** Matches the single digest the key is generated for. */
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        /** Field size for P-256: each coordinate is exactly 32 bytes. */
        private const val COORDINATE_BYTES = 32

        // Compressed-point tags from SEC 1: the leading byte records the parity of y, which is what
        // lets the full point be rebuilt from x alone.
        private const val EVEN_TAG: Byte = 0x02
        private const val ODD_TAG: Byte = 0x03

        // DER tags, for converting between the platform's signature encoding and the raw form.
        private const val DER_SEQUENCE: Byte = 0x30
        private const val DER_INTEGER: Byte = 0x02

        /** Compressed point width: one parity tag plus the x coordinate. Sizes a QR field. */
        const val PUBLIC_KEY_BYTES = 1 + COORDINATE_BYTES

        /** Raw signature width: `r || s`, each a full coordinate. Sizes a QR field. */
        const val SIGNATURE_BYTES = COORDINATE_BYTES * 2

        // Exponents used when recovering y from x. Named because the curve arithmetic below is
        // hard enough to follow without bare BigInteger.valueOf calls in the middle of it.
        private val TWO: BigInteger = BigInteger.valueOf(2)
        private val THREE: BigInteger = BigInteger.valueOf(3)

        /**
         * P-256 domain parameters, needed to rebuild a public key from raw coordinates. Derived
         * from the platform rather than hardcoded, so there are no curve constants to get wrong.
         */
        private val curveSpec: ECParameterSpec? by lazy {
            runCatching {
                AlgorithmParameters.getInstance("EC").run {
                    init(ECGenParameterSpec(CURVE))
                    getParameterSpec(ECParameterSpec::class.java)
                }
            }.getOrElse { error ->
                KillitLog.e(KillitLog.PAIRING, "Could not load P-256 parameters", error)
                null
            }
        }

        /**
         * Compresses a public point to `0x02`/`0x03 || x`, the coordinate left-padded to exactly
         * 32 bytes.
         *
         * Compressed rather than uncompressed because this goes into a QR code, and dropping y
         * halves the field: 33 bytes instead of 65.
         *
         * @param point the public point from the Keystore certificate.
         * @return [PUBLIC_KEY_BYTES] bytes: the parity tag followed by x.
         */
        internal fun encodePoint(point: ECPoint): ByteArray {
            val encoded = ByteArray(PUBLIC_KEY_BYTES)
            encoded[0] = if (point.affineY.testBit(0)) ODD_TAG else EVEN_TAG
            writeCoordinate(point.affineX, encoded, 1)
            return encoded
        }

        /**
         * Rebuilds a public key from a compressed point, the inverse of [encodePoint].
         *
         * Recovers y by solving the curve equation `y² = x³ + ax + b` and taking the square root
         * that matches the parity tag. Both the square root and the resulting point are checked, so
         * a key that is not actually on the curve is rejected here rather than reaching the
         * verifier — an invalid-curve attack is exactly what an attacker would supply.
         *
         * @param encoded a compressed point, as produced by [encodePoint].
         * @return the public key, or null for anything that is not a well-formed P-256 point.
         */
        internal fun decodePublicKey(encoded: ByteArray): ECPublicKey? {
            if (encoded.size != PUBLIC_KEY_BYTES) {
                KillitLog.d(KillitLog.PAIRING) { "Malformed public key: ${encoded.size} bytes" }
                return null
            }
            val tag = encoded[0]
            if (tag != EVEN_TAG && tag != ODD_TAG) {
                KillitLog.d(KillitLog.PAIRING) { "Public key has tag $tag, expected a compressed point" }
                return null
            }
            val spec = curveSpec ?: return null
            val prime = (spec.curve.field as? ECFieldFp)?.p ?: return null

            // BigInteger(1, ...) forces a positive value; the plain constructor would read a
            // coordinate whose top bit is set as negative.
            val x = BigInteger(1, encoded.copyOfRange(1, PUBLIC_KEY_BYTES))
            if (x >= prime) return null

            val alpha = x.modPow(THREE, prime)
                .add(spec.curve.a.multiply(x))
                .add(spec.curve.b)
                .mod(prime)
            var y = alpha.modPow(prime.add(BigInteger.ONE).shiftRight(2), prime)
            if (y.signum() == 0 || y.modPow(TWO, prime) != alpha) {
                KillitLog.d(KillitLog.PAIRING) { "Public key rejected: x is not on the curve" }
                return null
            }
            if (y.testBit(0) != (tag == ODD_TAG)) y = prime.subtract(y)

            return runCatching {
                KeyFactory.getInstance("EC")
                    .generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
            }.getOrElse { error ->
                // Thrown when the point is not actually on the curve — i.e. a forged key.
                KillitLog.d(KillitLog.PAIRING) { "Public key rejected: ${error.javaClass.simpleName}" }
                null
            }
        }

        /**
         * Converts the platform's DER signature to the fixed-width raw form.
         *
         * Wraps [parseDer] so that a malformed signature — which indexes past the end of the array
         * — comes back as null instead of throwing.
         *
         * @param der the signature as the platform produced it.
         * @return [SIGNATURE_BYTES] bytes of `r || s`, or null if the input is not valid DER.
         */
        internal fun derToRaw(der: ByteArray): ByteArray? =
            runCatching { parseDer(der) }.getOrElse { error ->
                KillitLog.d(KillitLog.PAIRING) { "Signature is not valid DER: ${error.javaClass.simpleName}" }
                null
            }

        /**
         * Parses a DER `SEQUENCE { INTEGER r, INTEGER s }` into raw coordinates.
         *
         * Only the short-form length is accepted, and the declared length must account for exactly
         * the whole array: an ECDSA P-256 signature is far below the 128-byte boundary, so anything
         * using the long form or leaving trailing bytes did not come from this curve.
         *
         * @param der the signature as the platform produced it.
         * @return the raw form, or null if the structure does not match.
         */
        private fun parseDer(der: ByteArray): ByteArray? {
            var offset = 0
            if (der[offset++] != DER_SEQUENCE) return null
            val length = der[offset++].toInt() and 0xFF
            if (length >= 0x80 || offset + length != der.size) return null

            val raw = ByteArray(SIGNATURE_BYTES)
            repeat(2) { half ->
                if (der[offset++] != DER_INTEGER) return null
                val size = der[offset++].toInt() and 0xFF
                if (size == 0 || size > COORDINATE_BYTES + 1) return null
                val value = BigInteger(der.copyOfRange(offset, offset + size))
                if (value.signum() <= 0) return null
                offset += size
                writeCoordinate(value, raw, half * COORDINATE_BYTES)
            }
            return if (offset == der.size) raw else null
        }

        /**
         * Converts a raw signature back to DER, the inverse of [derToRaw].
         *
         * `BigInteger(1, …).toByteArray()` is what produces each INTEGER body: it forces a positive
         * value on the way in and re-adds a leading zero byte on the way out if the top bit is set,
         * which is exactly DER's own rule for signed integers.
         *
         * @param raw [SIGNATURE_BYTES] bytes of `r || s`, as carried in a response payload.
         * @return the DER encoding the platform verifier expects, or null if the input is the wrong
         *   length or all zero.
         */
        internal fun rawToDer(raw: ByteArray): ByteArray? {
            if (raw.size != SIGNATURE_BYTES) return null

            val r = BigInteger(1, raw.copyOfRange(0, COORDINATE_BYTES)).toByteArray()
            val s = BigInteger(1, raw.copyOfRange(COORDINATE_BYTES, SIGNATURE_BYTES)).toByteArray()
            if (BigInteger(1, raw).signum() == 0) return null

            val content = 2 + r.size + 2 + s.size
            val der = ByteArray(2 + content)
            var offset = 0
            der[offset++] = DER_SEQUENCE
            der[offset++] = content.toByte()
            der[offset++] = DER_INTEGER
            der[offset++] = r.size.toByte()
            r.copyInto(der, offset)
            offset += r.size
            der[offset++] = DER_INTEGER
            der[offset++] = s.size.toByte()
            s.copyInto(der, offset)
            return der
        }

        /**
         * Writes a coordinate big-endian into a buffer, left-padded to 32 bytes.
         *
         * [BigInteger.toByteArray] is variable-length: it drops leading zero bytes and *adds* one
         * when the top bit is set. Both cases have to be handled, or one coordinate in every few
         * hundred keys encodes to the wrong length.
         *
         * @param value the coordinate to write.
         * @param target the buffer to write into.
         * @param offset where in [target] the 32-byte field starts.
         */
        private fun writeCoordinate(value: BigInteger, target: ByteArray, offset: Int) {
            val bytes = value.toByteArray()
            when {
                bytes.size == COORDINATE_BYTES ->
                    bytes.copyInto(target, offset)

                bytes.size < COORDINATE_BYTES ->
                    bytes.copyInto(target, offset + COORDINATE_BYTES - bytes.size)

                // Longer than 32 means a leading sign byte; drop it.
                else ->
                    bytes.copyInto(target, offset, bytes.size - COORDINATE_BYTES, bytes.size)
            }
        }
    }
}
