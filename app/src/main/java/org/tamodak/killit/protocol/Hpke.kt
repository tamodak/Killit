package org.tamodak.killit.protocol

import com.google.crypto.tink.ConfigurationV0
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes

/**
 * HPKE (RFC 9180) for the one suite Killit uses (SPEC.md §2.1): base mode,
 * DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-128-GCM, single-shot — through Tink.
 *
 * Tink's HPKE `HybridEncrypt` passes its context info to HPKE as `info` and seals with an empty
 * AAD, and with no output prefix it returns `enc ‖ ciphertext ‖ tag`: exactly the envelope layout
 * of SPEC.md §10.1. The vectors in `hpke.json` (RFC 9180's own, and envelopes the reference
 * implementation sealed) check that.
 *
 * Primitives come from [ConfigurationV0], a fixed configuration, rather than Tink's global
 * registry, so nothing registered elsewhere in the process can change what runs here.
 */
object Hpke {
    /** The suite, without Tink's key-id prefix: the protocol names keys by fingerprint instead. */
    val PARAMETERS: HpkeParameters = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()

    /**
     * A peer's encryption key as Tink's key type.
     *
     * @throws DecodeException for anything but 32 bytes.
     */
    fun publicKey(raw: ByteArray): HpkePublicKey {
        if (raw.size != X25519_KEY_BYTES) throw DecodeException("an X25519 key is 32 bytes")
        return HpkePublicKey.create(PARAMETERS, Bytes.copyFrom(raw), null)
    }

    /**
     * A private key from its raw bytes, which Tink checks against [rawPublic]. Phones never call
     * this — their key is generated inside a Keystore-sealed keyset — but tests open the reference
     * envelopes with the vectors' keys.
     */
    fun privateKey(rawPrivate: ByteArray, rawPublic: ByteArray): HpkePrivateKey =
        HpkePrivateKey.create(publicKey(rawPublic), SecretBytes.copyFrom(rawPrivate, InsecureSecretKeyAccess.get()))

    /** An encrypter to one recipient's key, drawing a fresh ephemeral key for every message. */
    fun encrypter(recipientEncPk: ByteArray): HybridEncrypt =
        KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(publicKey(recipientEncPk)).withRandomId().makePrimary())
            .build()
            .getPrimitive(ConfigurationV0.get(), HybridEncrypt::class.java)

    /** A decrypter for a keyset whose primary key is an HPKE private key in this suite. */
    fun decrypter(keyset: KeysetHandle): HybridDecrypt =
        keyset.getPrimitive(ConfigurationV0.get(), HybridDecrypt::class.java)

    /** A decrypter for one private key. */
    fun decrypter(privateKey: HpkePrivateKey): HybridDecrypt =
        decrypter(
            KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.importKey(privateKey).withRandomId().makePrimary())
                .build(),
        )

    /**
     * The raw public key of a keyset's primary key, for the bundle.
     *
     * @throws IllegalStateException when the primary key isn't an HPKE key in this suite.
     */
    fun publicKeyBytes(keyset: KeysetHandle): ByteArray {
        val key = keyset.primary.key
        check(key is HpkePrivateKey && key.parameters == PARAMETERS) { "the keyset's primary key is not Killit's HPKE suite" }
        return key.publicKey.publicKeyBytes.toByteArray()
    }
}
