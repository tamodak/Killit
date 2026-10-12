package org.tamodak.killit.protocol

/*
 * Invite codes (SPEC.md §6.1): 40 random bits written as 8 Crockford base32 symbols.
 *
 * Crockford's alphabet leaves out I, L, O and U, and parsing maps the look-alikes back, so a code
 * read aloud or copied by hand survives the usual mistakes. 40 bits against a 15-minute lifetime
 * and per-device rate limits is far out of a guesser's reach, and the safety code stops anyone
 * who gets in anyway.
 */
object InviteCode {
    /** The symbols in value order. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** A code is 5 random bytes. */
    const val BYTES = 5

    /** Symbols in a code. */
    const val SYMBOLS = 8

    /** Anything longer than this can't be a code, even with separators, and is refused unread. */
    private const val MAX_INPUT_LENGTH = 32

    /** Formats 5 bytes as `XXXX-XXXX`, most significant bits first. */
    fun format(bytes: ByteArray): String {
        require(bytes.size == BYTES) { "an invite code is 5 bytes" }
        val value = bytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
        val symbols = CharArray(SYMBOLS) { i -> ALPHABET[((value ushr (35 - 5 * i)) and 31).toInt()] }
        return String(symbols, 0, 4) + "-" + String(symbols, 4, 4)
    }

    /**
     * Parses what a person typed: hyphens and spaces are dropped, case is ignored, and `I`/`L`
     * read as `1` and `O` as `0`. The result must be exactly 8 symbols.
     *
     * Only ASCII is accepted, and case is folded for `a`–`z` alone: Unicode case mapping differs
     * between platforms and locales (a Turkish `i` uppercases to `İ`), so the phone and the server
     * agree on every string only if neither applies it. The code-entry field maps the keyboard's
     * input to ASCII before calling this.
     */
    fun parse(input: String): Decoded<ByteArray> = decodeWith {
        if (input.length > MAX_INPUT_LENGTH) throw DecodeException("invite code too long")
        if (input.any { it !in ' '..'~' }) throw DecodeException("invite code is not ASCII")
        val symbols = buildString {
            for (char in asciiUppercase(input)) {
                when (char) {
                    '-', ' ' -> Unit
                    'I', 'L' -> append('1')
                    'O' -> append('0')
                    else -> append(char)
                }
            }
        }
        if (symbols.length != SYMBOLS) throw DecodeException("an invite code has 8 symbols")
        var value = 0L
        for (symbol in symbols) {
            val digit = ALPHABET.indexOf(symbol)
            if (digit < 0) throw DecodeException("invalid invite code symbol")
            value = (value shl 5) or digit.toLong()
        }
        ByteArray(BYTES) { i -> (value ushr (8 * (BYTES - 1 - i))).toByte() }
    }
}
