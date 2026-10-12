package org.tamodak.killit.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/*
 * Text and package-name rules (SPEC.md §1.3, §1.4).
 *
 * Remote text is the part of a message a person reads, so it is the part most worth constraining:
 * no control characters that could break a layout, no invisible leading or trailing space, and
 * limits in both code points (what the UI counts) and bytes (what fits in an envelope). Kotlin's
 * `trim()` and JavaScript's disagree about what white space is, so "trimmed" here means a fixed
 * list of code points, and receivers reject untrimmed text instead of trimming it.
 */

/**
 * The limits for one text field. Both the code-point and the byte limits must hold.
 *
 * @param paragraph true when line feeds are allowed (explanations and notes); false for a line.
 */
data class TextRule(
    val paragraph: Boolean,
    val minCodePoints: Int,
    val maxCodePoints: Int,
    val maxBytes: Int,
) {
    companion object {
        /** The name a phone calls itself in a pairing (SPEC.md §11.1). */
        val SELF_NAME = TextRule(paragraph = false, minCodePoints = 1, maxCodePoints = 40, maxBytes = 160)

        /** `Build.MANUFACTURER` and `Build.MODEL`. */
        val DEVICE_MODEL = TextRule(paragraph = false, minCodePoints = 0, maxCodePoints = 64, maxBytes = 256)

        /** An app's label. */
        val APP_LABEL = TextRule(paragraph = false, minCodePoints = 0, maxCodePoints = 100, maxBytes = 400)

        /** UNBLOCK_APP's and UNPAIR's explanation: at least ten code points (D3, G8). */
        val REQUIRED_EXPLANATION = TextRule(paragraph = true, minCodePoints = 10, maxCodePoints = 500, maxBytes = 2000)

        /** UNLOCK's explanation, which is optional (C5). */
        val OPTIONAL_EXPLANATION = TextRule(paragraph = true, minCodePoints = 0, maxCodePoints = 500, maxBytes = 2000)

        /** A guardian's note on a decision (G9). */
        val NOTE = TextRule(paragraph = true, minCodePoints = 0, maxCodePoints = 300, maxBytes = 1200)

        /** A field that must be empty, such as a declining PAIR_ANSWER's name. */
        val EMPTY = TextRule(paragraph = false, minCodePoints = 0, maxCodePoints = 0, maxBytes = 0)
    }
}

/**
 * True for the white space a text field may not start or end with: the Unicode `White_Space`
 * characters that the control-character rule doesn't already forbid.
 */
fun isEdgeWhiteSpace(codePoint: Int): Boolean =
    codePoint == 0x0a || codePoint == 0x20 || codePoint == 0xa0 || codePoint == 0x1680 ||
        codePoint in 0x2000..0x200a || codePoint == 0x202f || codePoint == 0x205f || codePoint == 0x3000

/** True for the control characters no text field may contain, line feed aside. */
private fun isForbiddenControl(codePoint: Int): Boolean =
    (codePoint <= 0x1f && codePoint != 0x0a) || codePoint in 0x7f..0x9f || codePoint == 0x2028 || codePoint == 0x2029

/**
 * Validates UTF-8 bytes against a rule and returns the text.
 *
 * Java's UTF-8 decoder, set to report errors, refuses overlong forms, encoded surrogates and
 * anything above U+10FFFF, and keeps a leading U+FEFF rather than dropping it, so both
 * implementations see the same code points.
 *
 * @throws DecodeException naming the first rule broken.
 */
fun checkText(bytes: ByteArray, rule: TextRule): String {
    if (bytes.size > rule.maxBytes) throw DecodeException("text too long")
    val text = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw DecodeException("invalid UTF-8")
    }
    var count = 0
    var first = -1
    var last = -1
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (isForbiddenControl(codePoint)) throw DecodeException("control character in text")
        if (codePoint == 0x0a && !rule.paragraph) throw DecodeException("line feed in a line field")
        if (first < 0) first = codePoint
        last = codePoint
        count++
        index += Character.charCount(codePoint)
    }
    if (count < rule.minCodePoints) throw DecodeException("text too short")
    if (count > rule.maxCodePoints) throw DecodeException("text too long")
    if (count > 0 && (isEdgeWhiteSpace(first) || isEdgeWhiteSpace(last))) throw DecodeException("text not trimmed")
    return text
}

/**
 * Encodes text as UTF-8 after checking it against a rule.
 *
 * A Kotlin string can hold a lone surrogate, which the encoder would turn into a replacement
 * character; such text is refused instead, so what is signed is what the sender wrote.
 *
 * @throws IllegalArgumentException when the text breaks the rule. Encoders fail loudly: the text
 *   comes from Killit's own UI, which validates it first.
 */
fun encodeText(text: String, rule: TextRule): ByteArray {
    val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val buffer = try {
        encoder.encode(java.nio.CharBuffer.wrap(text))
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("text contains a lone surrogate")
    }
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    try {
        checkText(bytes, rule)
    } catch (e: DecodeException) {
        throw IllegalArgumentException("text breaks its rule: ${e.message}")
    }
    return bytes
}

/**
 * Removes the edge white space of SPEC.md §1.3 from both ends. The UI calls this before
 * [encodeText]; receivers never do.
 */
fun trimText(text: String): String {
    val codePoints = text.codePoints().toArray()
    var start = 0
    var end = codePoints.size
    while (start < end && isEdgeWhiteSpace(codePoints[start])) start++
    while (end > start && isEdgeWhiteSpace(codePoints[end - 1])) end--
    return String(codePoints, start, end - start)
}

/**
 * Counts code points the way the text rules do, for the UI's length counters.
 *
 * @return the number of Unicode scalar values in [text].
 */
fun codePointCount(text: String): Int = text.codePointCount(0, text.length)

/**
 * Uppercases `a`–`z` and nothing else. `String.uppercase()` maps far more than ASCII, so a string
 * the server reads one way could read another way here; protocol strings are ASCII by rule.
 */
fun asciiUppercase(value: String): String =
    buildString(value.length) { value.forEach { append(if (it in 'a'..'z') it - ('a' - 'A') else it) } }

private val packageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$")

/** True when [name] is a package name under SPEC.md §1.4. */
fun isPackageName(name: String): Boolean = name.length in 1..255 && packageNamePattern.matches(name)

/** Writes a `text16` field after checking it against its rule. */
fun ByteWriter.text(text: String, rule: TextRule): ByteWriter = blob16(encodeText(text, rule))

/** Reads a `text16` field and checks it against its rule. */
fun ByteReader.text(rule: TextRule): String = checkText(blob16(rule.maxBytes), rule)

/** Writes a package name as `text16`. With [allowEmpty], an empty string stands for "unknown". */
fun ByteWriter.packageName(name: String, allowEmpty: Boolean = false): ByteWriter {
    require(isPackageName(name) || (allowEmpty && name.isEmpty())) { "not a package name: $name" }
    return blob16(name.toByteArray(StandardCharsets.US_ASCII))
}

/** Reads a package name written by [packageName]. */
fun ByteReader.packageName(allowEmpty: Boolean = false): String {
    val bytes = blob16(255)
    // ISO-8859-1 maps every byte to one char, so a non-ASCII byte can't hide from the pattern.
    val name = String(bytes, StandardCharsets.ISO_8859_1)
    if (!(isPackageName(name) || (allowEmpty && name.isEmpty()))) throw DecodeException("invalid package name")
    return name
}
