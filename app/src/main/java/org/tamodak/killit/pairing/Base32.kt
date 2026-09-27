package org.tamodak.killit.pairing

/**
 * RFC 4648 Base32 alphabet, minus the padding.
 *
 * Every character is in QR's alphanumeric mode, which encodes at 5.5 bits per character against
 * byte mode's 8 — the reason [QrPayload] uses Base32 rather than the Base64 used for storage.
 * Padding is omitted because Killit's payloads carry no separators: field widths are fixed and
 * known to both ends, so `=` would only make the code denser.
 */
private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/**
 * Reports how many characters [encodeBase32] will produce for a given input.
 *
 * Used by [QrPayload] to compute field offsets at class-init time, so the decoders can slice a
 * payload by position instead of parsing delimiters.
 *
 * @param byteCount the number of bytes to be encoded.
 * @return the encoded length, rounding up because a partial final group still costs a character.
 */
internal fun base32Length(byteCount: Int): Int = (byteCount * 8 + 4) / 5

/**
 * Encodes bytes as unpadded Base32.
 *
 * Bytes are shifted through a bit accumulator, five bits at a time; any leftover bits at the end
 * are left-aligned into one final character. Zero-length input encodes to the empty string rather
 * than being rejected, which keeps an absent optional field from needing a special case.
 *
 * @return the encoded string, drawn only from [ALPHABET].
 */
internal fun ByteArray.encodeBase32(): String {
    if (isEmpty()) return ""

    val out = StringBuilder(base32Length(size))
    var buffer = 0
    var bits = 0

    for (byte in this) {
        buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            out.append(ALPHABET[(buffer ushr bits) and 0x1F])
        }
        buffer = buffer and ((1 shl bits) - 1)
    }

    if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
    return out.toString()
}

/**
 * Decodes what [encodeBase32] wrote.
 *
 * Strict in both directions, and deliberately so: this runs on QR codes read off a camera, where a
 * partially resolved or misread code is routine. Rather than returning plausible-looking bytes from
 * a damaged payload, it rejects anything that could not have come from [encodeBase32] —
 *
 * - a character outside [ALPHABET];
 * - five or more leftover bits, which would mean a whole unconsumed character;
 * - non-zero padding bits, which a correct encoder never emits;
 * - fewer bytes produced than the length implies.
 *
 * Returning null rather than throwing keeps the scanner's per-frame path free of exception
 * handling.
 *
 * @return the decoded bytes, or null if the input is not well-formed Base32.
 */
internal fun String.decodeBase32(): ByteArray? {
    if (isEmpty()) return ByteArray(0)

    val out = ByteArray(length * 5 / 8)
    var index = 0
    var buffer = 0
    var bits = 0

    for (char in this) {
        val value = ALPHABET.indexOf(char)
        if (value < 0) return null
        buffer = (buffer shl 5) or value
        bits += 5
        if (bits >= 8) {
            bits -= 8
            out[index++] = ((buffer ushr bits) and 0xFF).toByte()
            buffer = buffer and ((1 shl bits) - 1)
        }
    }

    if (bits >= 5) return null
    if (buffer != 0) return null
    if (index != out.size) return null
    return out
}
