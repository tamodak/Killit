package org.tamodak.killit.protocol

/*
 * Phone identities (SPEC.md §3): the fingerprint, and the bundle two phones exchange while
 * pairing.
 */

/** A fingerprint is a full SHA-256: nothing is truncated, so finding a collision is out of reach. */
const val FINGERPRINT_BYTES = 32

/** The only bundle version this implementation reads or writes. */
const val BUNDLE_VERSION = 1

/** `u8 version ‖ signPk (65) ‖ encPk (32) ‖ encSig (64)`. */
const val BUNDLE_BYTES = 1 + P256_POINT_BYTES + X25519_KEY_BYTES + SIGNATURE_BYTES

/** `fp = SHA-256(label("KILLIT-FP-v1") ‖ signPk)`: a phone's address and identity. */
fun fingerprint(signPk: ByteArray): ByteArray = sha256(Labels.FINGERPRINT, signPk)

/** How the server names a device in paths and headers: its fingerprint in base64url. */
fun deviceId(fp: ByteArray): String = fp.toBase64Url()

/**
 * A decoded bundle. Structure and key encodings are valid; [verifyBundle] checks [encSig].
 *
 * A plain class rather than a data class, like every protocol value holding byte arrays: generated
 * equality would compare the arrays by identity. Code compares the fields it needs instead.
 */
class Bundle(
    /** The identity key: a 65-byte uncompressed P-256 point. */
    val signPk: ByteArray,
    /** The X25519 key envelopes are sealed to. */
    val encPk: ByteArray,
    /** [signPk]'s signature over [encKeyBindingBytes] of [encPk]. */
    val encSig: ByteArray,
)

/**
 * The bytes `encSig` signs: the encryption key together with its HPKE suite, so the key can't be
 * reused under a weaker suite than the one it was published for.
 */
fun encKeyBindingBytes(encPk: ByteArray): ByteArray =
    ByteWriter()
        .raw(Labels.ENC_KEY)
        .u16(HPKE_KEM_ID)
        .u16(HPKE_KDF_ID)
        .u16(HPKE_AEAD_ID)
        .fixed(encPk, X25519_KEY_BYTES)
        .finish()

/** Signs a new encryption key with the phone's identity key, producing `encSig`. */
fun signEncKey(signer: Signer, encPk: ByteArray): ByteArray = signer.sign(encKeyBindingBytes(encPk))

/** Encodes a bundle (SPEC.md §3.2). */
fun encodeBundle(bundle: Bundle): ByteArray =
    ByteWriter()
        .u8(BUNDLE_VERSION)
        .fixed(bundle.signPk, P256_POINT_BYTES)
        .fixed(bundle.encPk, X25519_KEY_BYTES)
        .fixed(bundle.encSig, SIGNATURE_BYTES)
        .finish()

/** Reads a bundle; shared by [decodeBundle] and the reveal decoder. */
fun ByteReader.bundle(): Bundle {
    if (u8() != BUNDLE_VERSION) throw DecodeException("unsupported bundle version")
    val signPk = fixed(P256_POINT_BYTES)
    P256.publicKey(signPk)
    val encPk = fixed(X25519_KEY_BYTES)
    val encSig = fixed(SIGNATURE_BYTES)
    return Bundle(signPk, encPk, encSig)
}

/**
 * Decodes a bundle: version, sizes, and a signing key that is a real P-256 point. It doesn't
 * verify `encSig`; [verifyBundle] must pass before `encPk` is used.
 */
fun decodeBundle(bytes: ByteArray): Decoded<Bundle> = decodeWith {
    val reader = ByteReader(bytes)
    val bundle = reader.bundle()
    reader.end()
    bundle
}

/** True when `encSig` binds `encPk` to `signPk`. */
fun verifyBundle(bundle: Bundle): Boolean = P256.verify(bundle.signPk, encKeyBindingBytes(bundle.encPk), bundle.encSig)
