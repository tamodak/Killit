package org.tamodak.killit.protocol

/*
 * Domain-separation labels (SPEC.md §2.3).
 *
 * Every signed, hashed or MACed structure starts with its own label, so bytes produced for one
 * purpose can never be accepted for another: an API signature can't pass as a message signature,
 * a commitment can't pass as a pair id. A label is written as its length and its ASCII bytes,
 * which also keeps a future `-v10` from being a prefix of `-v1`.
 */

/** Encodes `label(s) = u8 len(s) ‖ ASCII(s)`. */
fun label(text: String): ByteArray {
    require(text.length in 1..255 && text.all { it in '!'..'~' }) { "labels are printable ASCII: $text" }
    return concat(byteArrayOf(text.length.toByte()), text.toByteArray(Charsets.US_ASCII))
}

/**
 * The protocol's labels. Each read returns a fresh array: they are shared constants, and a caller
 * that wrote into one would otherwise change it for everyone.
 */
object Labels {
    /** Fingerprints (SPEC.md §3.1). */
    val FINGERPRINT: ByteArray get() = label("KILLIT-FP-v1")

    /** Binding the encryption key to the signing key (SPEC.md §3.2). */
    val ENC_KEY: ByteArray get() = label("KILLIT-ENCKEY-v1")

    /** API request signatures (SPEC.md §4.2). */
    val API: ByteArray get() = label("KILLIT-API-v1")

    /** The joiner's commitment (SPEC.md §6.3). */
    val COMMIT: ByteArray get() = label("KILLIT-COMMIT-v1")

    /** Pair ids (SPEC.md §6.4). */
    val PAIR: ByteArray get() = label("KILLIT-PAIR-v1")

    /** Message signatures (SPEC.md §10.2). */
    val MESSAGE: ByteArray get() = label("KILLIT-MSG-v1")

    /** The HPKE `info` of every envelope (SPEC.md §10.1). */
    val ENVELOPE: ByteArray get() = label("KILLIT-ENV-v1")

    /**
     * The safety-code HMAC key (SPEC.md §6.4). Raw ASCII, not [label]-encoded: it is a key, not a
     * prefix of the data.
     */
    val SAFETY_CODE_KEY: ByteArray get() = "KILLIT-SAS-v1".toByteArray(Charsets.US_ASCII)
}
