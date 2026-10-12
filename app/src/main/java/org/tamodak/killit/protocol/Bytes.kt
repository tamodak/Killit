package org.tamodak.killit.protocol

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/*
 * Byte-level building blocks for every encoder and decoder in the protocol (SPEC.md §1).
 *
 * This package is Killit's implementation of website/protocol/SPEC.md. It is plain Kotlin with no
 * Android types, so every rule runs in JVM tests against the shared test vectors, the same files
 * the server's TypeScript implementation is checked against.
 *
 * Decoding is total (SPEC.md §1.2): a decoder returns a fully validated value or a rejection. The
 * readers throw [DecodeException] internally; the public `decode…` functions catch it at their
 * boundary and return [Decoded], so no exception escapes into code that handles network input.
 */

/** Why a decoder refused its input. Thrown only inside this package. */
class DecodeException(message: String) : Exception(message)

/** The outcome of a total decoder: a validated value, or the reason it was refused. */
sealed interface Decoded<out T> {
    /** @param value the validated value. */
    data class Ok<T>(val value: T) : Decoded<T>

    /** @param reason why the input was refused; safe to log, it never quotes the input. */
    data class Rejected(val reason: String) : Decoded<Nothing>

    /** @return the value, or null when the input was refused. */
    fun valueOrNull(): T? = (this as? Ok)?.value

    /**
     * Unwraps a nested decoder's result inside another decoder.
     *
     * @throws DecodeException carrying the rejection's reason.
     */
    fun valueOrThrow(): T = when (this) {
        is Ok -> value
        is Rejected -> throw DecodeException(reason)
    }
}

/**
 * Runs a decoder that may throw and turns every failure into a rejection.
 *
 * Any exception counts, not only [DecodeException]: a decoder bug, or a checked exception from the
 * platform's cryptography, must still end in a refusal rather than an exception in whatever
 * processes network input. Errors such as running out of memory still propagate.
 *
 * @param decode the decoder.
 * @return its value, or the reason it failed.
 */
inline fun <T> decodeWith(decode: () -> T): Decoded<T> =
    try {
        Decoded.Ok(decode())
    } catch (e: DecodeException) {
        Decoded.Rejected(e.message ?: "malformed input")
    } catch (e: Exception) {
        Decoded.Rejected("malformed input")
    }

/** Joins byte strings in order. */
fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}

/** Compares two byte strings without leaking where they differ. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

private val hexDigits = "0123456789abcdef".toCharArray()

/**
 * Encodes bytes as lowercase hex, the form the test vectors and the logs use. Built by hand rather
 * than with `String.format`, which consults the default locale.
 */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, byte ->
        val value = byte.toInt() and 0xff
        out[2 * i] = hexDigits[value ushr 4]
        out[2 * i + 1] = hexDigits[value and 0x0f]
    }
    return String(out)
}

/**
 * Decodes lowercase hex.
 *
 * @throws DecodeException on odd length or any character outside `0-9a-f`.
 */
fun String.fromHex(): ByteArray {
    if (length % 2 != 0 || any { it !in '0'..'9' && it !in 'a'..'f' }) throw DecodeException("invalid hex")
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

private val base64UrlAlphabet = Regex("^[A-Za-z0-9_-]*$")

/** Encodes bytes as base64url without padding (SPEC.md §1.1). */
fun ByteArray.toBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

/**
 * Decodes base64url without padding, accepting only the canonical encoding.
 *
 * Re-encoding and comparing refuses padding, foreign characters and non-zero trailing bits, so every
 * byte string has exactly one accepted spelling and signed or hashed values stay unambiguous.
 *
 * @param maxBytes refuses longer input before decoding anything.
 * @throws DecodeException for anything but the canonical encoding.
 */
fun String.fromBase64Url(maxBytes: Int = Int.MAX_VALUE): ByteArray {
    if (length.toLong() > (maxBytes.toLong() * 4 + 2) / 3) throw DecodeException("base64url too long")
    if (!base64UrlAlphabet.matches(this) || length % 4 == 1) throw DecodeException("invalid base64url")
    val bytes = try {
        Base64.getUrlDecoder().decode(this)
    } catch (e: IllegalArgumentException) {
        throw DecodeException("invalid base64url")
    }
    if (bytes.toBase64Url() != this) throw DecodeException("non-canonical base64url")
    if (bytes.size > maxBytes) throw DecodeException("base64url too long")
    return bytes
}

/** Encodes a `u16` (SPEC.md §1.1). */
fun u16(value: Int): ByteArray {
    require(value in 0..0xffff) { "u16 out of range: $value" }
    return byteArrayOf((value ushr 8).toByte(), value.toByte())
}

/** Encodes a `u64`. Every `u64` in the protocol is a time in milliseconds, so it is never negative. */
fun u64(value: Long): ByteArray {
    require(value >= 0) { "u64 out of range: $value" }
    return ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }
}

/** Appends protocol fields in order, validating each one, so an encoder can't emit what a decoder would refuse. */
class ByteWriter {
    private val out = ByteArrayOutputStream()

    /** Appends a `u8`. */
    fun u8(value: Int): ByteWriter = apply {
        require(value in 0..0xff) { "u8 out of range: $value" }
        out.write(value)
    }

    /** Appends a `bool` as `0x00` or `0x01`. */
    fun bool(value: Boolean): ByteWriter = u8(if (value) 1 else 0)

    /** Appends a `u16`. */
    fun u16(value: Int): ByteWriter = apply { out.write(org.tamodak.killit.protocol.u16(value)) }

    /** Appends a `u64`. */
    fun u64(value: Long): ByteWriter = apply { out.write(org.tamodak.killit.protocol.u64(value)) }

    /** Appends exactly [length] bytes; anything else is a programming error. */
    fun fixed(value: ByteArray, length: Int): ByteWriter = apply {
        require(value.size == length) { "expected $length bytes, got ${value.size}" }
        out.write(value)
    }

    /** Appends bytes as they are, with no length prefix. */
    fun raw(value: ByteArray): ByteWriter = apply { out.write(value) }

    /** Appends a `blob16`: a `u16` length and the bytes. */
    fun blob16(value: ByteArray): ByteWriter = apply {
        u16(value.size)
        out.write(value)
    }

    /** @return everything written so far. */
    fun finish(): ByteArray = out.toByteArray()
}

/** Reads protocol fields in order and refuses anything malformed, short or left over. */
class ByteReader(private val bytes: ByteArray) {
    private var offset = 0

    /** How many bytes are left unread. */
    val remaining: Int get() = bytes.size - offset

    /** Reads a `u8`. */
    fun u8(): Int {
        need(1)
        return bytes[offset++].toInt() and 0xff
    }

    /** Reads a `bool`, refusing any byte other than `0x00` and `0x01`. */
    fun bool(): Boolean = when (u8()) {
        0 -> false
        1 -> true
        else -> throw DecodeException("invalid bool")
    }

    /** Reads a `u16`. */
    fun u16(): Int {
        need(2)
        val value = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        offset += 2
        return value
    }

    /**
     * Reads a `u64`, refusing values with the top bit set: every `u64` is a time in milliseconds, and
     * the TypeScript implementation refuses anything beyond a safe integer the same way.
     */
    fun u64(): Long {
        need(8)
        var value = 0L
        repeat(8) { value = (value shl 8) or (bytes[offset++].toLong() and 0xff) }
        if (value < 0 || value > MAX_SAFE_INTEGER) throw DecodeException("u64 out of range")
        return value
    }

    /** Reads exactly [length] bytes, as a copy. */
    fun fixed(length: Int): ByteArray {
        need(length)
        val value = bytes.copyOfRange(offset, offset + length)
        offset += length
        return value
    }

    /** Reads a `blob16`, refusing a declared length above [maxBytes] before reading it. */
    fun blob16(maxBytes: Int): ByteArray {
        val length = u16()
        if (length > maxBytes) throw DecodeException("field too long")
        return fixed(length)
    }

    /** Reads everything that is left. */
    fun rest(): ByteArray = fixed(remaining)

    /** Refuses trailing bytes; every decoder ends with this. */
    fun end() {
        if (remaining != 0) throw DecodeException("trailing bytes")
    }

    private fun need(length: Int) {
        if (length < 0 || remaining < length) throw DecodeException("truncated")
    }

    private companion object {
        /** JavaScript's `Number.MAX_SAFE_INTEGER`, the limit the reference implementation reads up to. */
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}
