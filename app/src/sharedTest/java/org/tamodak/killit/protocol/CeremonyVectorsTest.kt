package org.tamodak.killit.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing ceremony (SPEC.md §6.3, §6.4), against `ceremony.json`. */
class CeremonyVectorsTest {
    private val vectors = Vectors.load("ceremony.json")
    private val ceremonies = vectors.array("ceremonies").map { it.obj }

    /** Reveals, the commitment, the transcript, the safety code and the pair id all match. */
    @Test
    fun ceremoniesMatch() {
        for (ceremony in ceremonies) {
            val creator = Vectors.phone(ceremony.str("creator"))
            val joiner = Vectors.phone(ceremony.str("joiner"))
            val creatorBundle = decodeBundle(creator.bundle).ok(creator.name)
            val joinerBundle = decodeBundle(joiner.bundle).ok(joiner.name)

            assertArrayEquals(ceremony.hex("creatorReveal"), encodeReveal(creatorBundle, ceremony.hex("creatorNonce")))
            assertArrayEquals(ceremony.hex("joinerReveal"), encodeReveal(joinerBundle, ceremony.hex("joinerNonce")))
            assertArrayEquals(ceremony.hex("commit"), commitment(ceremony.hex("joinerReveal")))
            assertTrue(matchesCommitment(ceremony.hex("joinerReveal"), ceremony.hex("commit")))

            val creatorReveal = decodeReveal(ceremony.hex("creatorReveal")).ok()
            val joinerReveal = decodeReveal(ceremony.hex("joinerReveal")).ok()
            assertTrue(verifyBundle(creatorReveal.bundle))
            assertTrue(verifyBundle(joinerReveal.bundle))
            val transcript = transcript(creatorReveal, joinerReveal)
            assertArrayEquals(ceremony.hex("transcript"), transcript)
            assertEquals(ceremony.str("safetyCode"), safetyCode(transcript))
            assertEquals(ceremony.str("safetyCodeDisplay"), formatSafetyCode(safetyCode(transcript)))
            assertArrayEquals(ceremony.hex("pairId"), pairId(transcript))
        }
    }

    /** Each rejection fails at the check the vectors name, and no earlier. */
    @Test
    fun rejections() {
        for (element in vectors.array("rejections")) {
            val case = element.obj
            val name = case.str("name")
            when (case.str("check")) {
                "commitment" -> {
                    val commit = ceremonies[case.int("ceremony")].hex("commit")
                    assertTrue(name, decodeReveal(case.hex("joinerReveal")) is Decoded.Ok)
                    assertFalse(name, matchesCommitment(case.hex("joinerReveal"), commit))
                }
                "decode" -> assertTrue(name, decodeReveal(case.hex("reveal")) is Decoded.Rejected)
                "encSig" -> assertFalse(name, verifyBundle(decodeReveal(case.hex("reveal")).ok(name).bundle))
                else -> throw AssertionError("unknown check in $name")
            }
        }
    }

    /** Safety codes are always six digits, low values included. */
    @Test
    fun safetyCodesAreSixDigits() {
        repeat(500) {
            val code = safetyCode(randomBytes(64))
            assertEquals(6, code.length)
            assertTrue(code.all { it in '0'..'9' })
        }
    }
}
