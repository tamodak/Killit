package org.tamodak.killit.pairing

import org.tamodak.killit.data.PairedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The challenge deadline: how it survives the wire, and where it actually gets enforced.
 *
 * The expiry is the one variable-width field in any payload — Base36, running to the end of the
 * string — so it is the one that can change the payload's length, and the only place a decoder
 * cannot slice by a fixed offset.
 */
class ChallengeExpiryTest {

    /**
     * Builds a challenge whose only interesting field is the deadline.
     *
     * @param expiresAtEpochSeconds the deadline to encode.
     * @return a challenge with fixed, valid-width filler in every other field.
     */
    private fun challengeAt(expiresAtEpochSeconds: Long) = QrPayload.Challenge(
        nonce = ByteArray(PairingProtocol.NONCE_BYTES) { it.toByte() },
        requesterFingerprint = ByteArray(PairedPeer.FINGERPRINT_BYTES) { it.toByte() },
        purpose = ChallengePurpose.UNLOCK,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
    )

    /** A challenge issued right now encodes and decodes with its deadline intact. */
    @Test
    fun aFreshChallengeSurvivesTheWire() {
        val now = System.currentTimeMillis()
        val expiresAt = (now + ChallengeSession.DEFAULT_LIFETIME_MILLIS) / 1000

        val encoded = challengeAt(expiresAt).encode()
        println("encoded=$encoded length=${encoded.length} expiresAt=$expiresAt")

        val decoded = QrPayload.decode(encoded) as? QrPayload.Challenge
        assertNotNull("A freshly encoded challenge must decode", decoded)
        assertEquals("Expiry must survive base36", expiresAt, decoded!!.expiresAtEpochSeconds)
    }

    /**
     * The deadline survives every width the Base36 field can take.
     *
     * The boundaries are where a digit is added, which is where a decoder that assumed a fixed
     * width would start reading the wrong characters — including the one epoch seconds actually
     * crosses in 2038.
     */
    @Test
    fun expiryRoundTripsAcrossTheBase36DigitBoundaries() {
        // 36^6 and 36^7 are where the field changes width; epoch seconds crosses 36^6 in 2038.
        val boundaries = listOf(
            0L, 1L, 35L, 36L,
            60_466_175L, 60_466_176L,
            2_176_782_335L, 2_176_782_336L,
            System.currentTimeMillis() / 1000,
            99_999_999_999L,
        )
        boundaries.forEach { expiresAt ->
            val decoded = QrPayload.decode(challengeAt(expiresAt).encode()) as? QrPayload.Challenge
            assertNotNull("$expiresAt failed to decode", decoded)
            assertEquals(expiresAt, decoded!!.expiresAtEpochSeconds)
        }
    }

    /**
     * A round stays open for its full lifetime, and closes after it.
     *
     * A session measures itself against the clock that created it, so it is the one place an
     * expiry means anything. Nothing compares a deadline from one device against another's clock.
     */
    @Test
    fun theWholeLifetimeIsUsable() {
        val start = 1_000_000_000L
        // Built directly rather than through ChallengeSession.start, which logs.
        val session = ChallengeSession(
            purpose = ChallengePurpose.UNLOCK,
            nonce = ByteArray(PairingProtocol.NONCE_BYTES),
            requesterPublicKey = ByteArray(PeerKeyStore.PUBLIC_KEY_BYTES),
            expiresAtMillis = start + ChallengeSession.DEFAULT_LIFETIME_MILLIS,
        )

        assertFalse("at t+0", session.isExpired(start))
        assertFalse(
            "one second before the deadline",
            session.isExpired(start + ChallengeSession.DEFAULT_LIFETIME_MILLIS - 1_000),
        )
        assertTrue(
            "one second after",
            session.isExpired(start + ChallengeSession.DEFAULT_LIFETIME_MILLIS + 1_000),
        )
    }
}
