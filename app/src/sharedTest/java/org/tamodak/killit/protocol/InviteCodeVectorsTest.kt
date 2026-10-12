package org.tamodak.killit.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invite codes (SPEC.md §6.1), against `invite-code.json`. */
class InviteCodeVectorsTest {
    private val vectors = Vectors.load("invite-code.json")

    /** Both sides use the same alphabet. */
    @Test
    fun alphabetMatches() {
        assertEquals(vectors.str("alphabet"), InviteCode.ALPHABET)
    }

    /** Bytes format to the same symbols as the reference. */
    @Test
    fun formatting() {
        for (element in vectors.array("format")) {
            val case = element.obj
            assertEquals(case.str("code"), InviteCode.format(case.hex("bytes")))
        }
    }

    /** Typed input parses, or is refused, as the reference decides. */
    @Test
    fun parsing() {
        for (element in vectors.array("parse")) {
            val case = element.obj
            val input = case.str("input")
            val result = InviteCode.parse(input)
            if (case.bool("valid")) {
                assertArrayEquals(input, case.hex("bytes"), result.ok(input))
            } else {
                assertTrue(input, result is Decoded.Rejected)
            }
        }
    }

    /** Every code survives formatting and parsing. */
    @Test
    fun roundTrip() {
        repeat(200) {
            val bytes = randomBytes(InviteCode.BYTES)
            assertArrayEquals(bytes, InviteCode.parse(InviteCode.format(bytes)).ok())
        }
    }
}
