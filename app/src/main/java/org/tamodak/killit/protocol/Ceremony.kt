package org.tamodak.killit.protocol

/*
 * The pairing ceremony's cryptography (SPEC.md §6.3, §6.4): reveals, the joiner's commitment, the
 * safety code and the pair id.
 *
 * The commitment is what limits a man-in-the-middle server to one guess. The joiner fixes its
 * bundle and nonce before it sees the creator's, and the creator reveals before it sees the
 * joiner's, so the server must commit to its fake keys on each side before learning the nonce
 * that decides that side's safety code.
 */

/** Length of each side's ceremony nonce. */
const val CEREMONY_NONCE_BYTES = 32

/** `bundle ‖ nonce`. */
const val REVEAL_BYTES = BUNDLE_BYTES + CEREMONY_NONCE_BYTES

/** A commitment is one SHA-256. */
const val COMMIT_BYTES = 32

/** A pair id is one SHA-256. */
const val PAIR_ID_BYTES = 32

/**
 * One side's reveal, decoded.
 *
 * @param bundleBytes the bundle exactly as received, for the transcript.
 */
class Reveal(val bundle: Bundle, val bundleBytes: ByteArray, val nonce: ByteArray)

/** Encodes `reveal = bundle ‖ nonce`. */
fun encodeReveal(bundle: Bundle, nonce: ByteArray): ByteArray =
    ByteWriter().raw(encodeBundle(bundle)).fixed(nonce, CEREMONY_NONCE_BYTES).finish()

/** Decodes a reveal. Like [decodeBundle], it doesn't verify `encSig`. */
fun decodeReveal(bytes: ByteArray): Decoded<Reveal> = decodeWith {
    if (bytes.size != REVEAL_BYTES) throw DecodeException("a reveal is 194 bytes")
    val reader = ByteReader(bytes)
    val bundle = reader.bundle()
    val nonce = reader.fixed(CEREMONY_NONCE_BYTES)
    reader.end()
    Reveal(bundle, bytes.copyOfRange(0, BUNDLE_BYTES), nonce)
}

/** `commit = SHA-256(label("KILLIT-COMMIT-v1") ‖ reveal_B)`. */
fun commitment(reveal: ByteArray): ByteArray = sha256(Labels.COMMIT, reveal)

/** True when a reveal matches a commitment. */
fun matchesCommitment(reveal: ByteArray, commit: ByteArray): Boolean =
    commit.size == COMMIT_BYTES && constantTimeEquals(commitment(reveal), commit)

/** `transcript = bundle_A ‖ bundle_B ‖ n_A ‖ n_B`, A being the creator and B the joiner. */
fun transcript(creator: Reveal, joiner: Reveal): ByteArray =
    concat(creator.bundleBytes, joiner.bundleBytes, creator.nonce, joiner.nonce)

/**
 * The six-digit safety code both people compare on a call.
 *
 * `u64(HMAC-SHA256("KILLIT-SAS-v1", transcript)[0..8]) mod 10^6`. Taking 64 bits before the
 * reduction makes the bias towards low codes about 5 × 10^-14, which is nothing.
 */
fun safetyCode(transcript: ByteArray): String {
    val mac = hmacSha256(Labels.SAFETY_CODE_KEY, transcript)
    val value = mac.copyOfRange(0, 8).fold(0UL) { acc, byte -> (acc shl 8) or (byte.toULong() and 0xffUL) }
    return (value % 1_000_000UL).toString().padStart(6, '0')
}

/** Groups a safety code for reading aloud: `"042917"` → `"042 917"`. */
fun formatSafetyCode(code: String): String = code.substring(0, 3) + " " + code.substring(3)

/** `pairId = SHA-256(label("KILLIT-PAIR-v1") ‖ transcript)`: the pairing's name in every message. */
fun pairId(transcript: ByteArray): ByteArray = sha256(Labels.PAIR, transcript)
