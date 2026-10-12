package org.tamodak.killit.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text rules, package names and base64url (SPEC.md §1), against `encoding.json`. */
class EncodingVectorsTest {
    private val vectors = Vectors.load("encoding.json")

    private val rules = mapOf(
        "selfName" to TextRule.SELF_NAME,
        "deviceModel" to TextRule.DEVICE_MODEL,
        "appLabel" to TextRule.APP_LABEL,
        "requiredExplanation" to TextRule.REQUIRED_EXPLANATION,
        "optionalExplanation" to TextRule.OPTIONAL_EXPLANATION,
        "note" to TextRule.NOTE,
        "empty" to TextRule.EMPTY,
    )

    /** Every rule has the reference's limits, and the reference has no rule Killit lacks. */
    @Test
    fun rulesMatchTheReference() {
        val reference = vectors.obj("rules")
        assertEquals(reference.keys, rules.keys)
        for ((name, element) in reference) {
            val expected = element.obj
            val rule = rules.getValue(name)
            assertEquals(name, expected.str("kind") == "paragraph", rule.paragraph)
            assertEquals(name, expected.int("minCodePoints"), rule.minCodePoints)
            assertEquals(name, expected.int("maxCodePoints"), rule.maxCodePoints)
            assertEquals(name, expected.int("maxBytes"), rule.maxBytes)
        }
    }

    /** Each text case is accepted or refused as the reference decides, and accepted text re-encodes to the same bytes. */
    @Test
    fun textCases() {
        for (element in vectors.array("text")) {
            val case = element.obj
            val bytes = case.hex("utf8")
            val rule = rules.getValue(case.str("rule"))
            val why = case.str("why")
            val result = decodeWith { checkText(bytes, rule) }
            assertEquals(why, case.bool("valid"), result is Decoded.Ok)
            if (result is Decoded.Ok) assertArrayEquals(why, bytes, encodeText(result.value, rule))
        }
    }

    /** Package names follow the same pattern on both sides. */
    @Test
    fun packageNames() {
        for (element in vectors.array("packageNames")) {
            val case = element.obj
            assertEquals(case.str("name"), case.bool("valid"), isPackageName(case.str("name")))
        }
    }

    /** Only the canonical unpadded base64url spelling of a byte string is accepted. */
    @Test
    fun base64Url() {
        for (element in vectors.array("base64url")) {
            val case = element.obj
            val text = case.str("text")
            if (case.bool("valid")) {
                val bytes = case.hex("bytes")
                assertArrayEquals(text, bytes, text.fromBase64Url())
                assertEquals(text, text, bytes.toBase64Url())
            } else {
                assertThrows(text, DecodeException::class.java) { text.fromBase64Url() }
            }
        }
    }

    /** A length limit refuses longer input before decoding it. */
    @Test
    fun base64UrlRespectsItsLimit() {
        val sixteen = ByteArray(16) { it.toByte() }.toBase64Url()
        assertEquals(16, sixteen.fromBase64Url(16).size)
        assertThrows(DecodeException::class.java) { sixteen.fromBase64Url(15) }
    }

    /** [trimText] removes exactly the edge white space receivers refuse, and nothing else. */
    @Test
    fun trimTextMatchesTheEdgeRule() {
        assertEquals("Ali", trimText("　 Ali \n"))
        assertEquals("﻿Ali", trimText("﻿Ali "))
        assertEquals("", trimText("   "))
        val trimmed = trimText("  Mom ")
        assertEquals("Mom", trimmed)
        assertEquals(trimmed, checkText(encodeText(trimmed, TextRule.SELF_NAME), TextRule.SELF_NAME))
    }

    /** Text from the platform is made to fit its rule, and the result always passes the rule's checks. */
    @Test
    fun fitTextAlwaysFits() {
        val label = "  My\u0000App\n\u2028Pro\ud800  "
        val fitted = fitText(label, TextRule.APP_LABEL)
        assertEquals("My App  Pro", fitted)
        assertEquals(fitted, checkText(encodeText(fitted, TextRule.APP_LABEL), TextRule.APP_LABEL))
        val long = "\ud83d\ude00".repeat(60)
        val cut = fitText(long, TextRule.SELF_NAME)
        assertEquals(40, codePointCount(cut))
        assertEquals("a b", fitText("a\nb", TextRule.DEVICE_MODEL))
        assertEquals("a\nb", fitText("a\nb", TextRule.NOTE))
        val bytesBound = fitText("\u00e7".repeat(300), TextRule.SELF_NAME)
        assertTrue(encodeText(bytesBound, TextRule.SELF_NAME).size <= TextRule.SELF_NAME.maxBytes)
    }

    /** A Kotlin string can hold half a surrogate pair; it is refused rather than silently replaced. */
    @Test
    fun loneSurrogateIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { encodeText("Ali\ud800", TextRule.SELF_NAME) }
        assertEquals(2, codePointCount("😀ç"))
    }

    /** The case folding used for invite codes and HTTP methods never consults the locale. */
    @Test
    fun asciiUppercaseIgnoresTheLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
            assertEquals("PATCH", asciiUppercase("patch"))
            assertEquals("I", asciiUppercase("i"))
            assertEquals("ı", asciiUppercase("ı"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    /** Readers refuse truncation, trailing bytes, out-of-range integers and non-boolean booleans. */
    @Test
    fun readersAreStrict() {
        assertThrows(DecodeException::class.java) { ByteReader(byteArrayOf(1)).u16() }
        assertThrows(DecodeException::class.java) { ByteReader(byteArrayOf(2)).bool() }
        assertThrows(DecodeException::class.java) { ByteReader(byteArrayOf(0, 1)).apply { u8() }.end() }
        assertThrows(DecodeException::class.java) { ByteReader(byteArrayOf(0x00, 0x05, 1, 2)).blob16(4) }
        assertThrows(DecodeException::class.java) { ByteReader(ByteArray(8) { if (it == 1) 0x20 else 0 }).u64() }
        assertEquals(9_007_199_254_740_991L, ByteReader(u64(9_007_199_254_740_991L)).u64())
        assertTrue(decodeWith { ByteReader(byteArrayOf()).u8() } is Decoded.Rejected)
        assertFalse(decodeWith { 1 } is Decoded.Rejected)
    }
}
