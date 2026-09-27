package org.tamodak.killit.pairing

import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.PairedPeer
import org.tamodak.killit.data.encodeBase64

/**
 * One round of collecting companion approvals.
 *
 * Every paired companion has to sign before the device opens, so a session is not a single
 * question and answer — it is a nonce held open while the phone is carried between people. The
 * same nonce serves the whole round: each companion's signature is distinguished by the signer
 * fingerprint bound into the payload, not by a fresh challenge each time.
 *
 * ### Lifetime
 *
 * [DEFAULT_LIFETIME_MILLIS] is ten minutes rather than the thirty to sixty seconds a single
 * challenge would need. A round means waking the other phone, opening Killit on it, reaching the
 * approval screen, scanning, and carrying the answer back — per companion. A session that expires
 * mid-round is worse than no timer: the user re-scans the first companion, times out again, and
 * never learns why.
 *
 * The session lives in the ViewModel, so it survives rotation and dies with the process. Dying is
 * correct — a half-collected set of approvals should not outlive the app.
 *
 * @param purpose what the collected signatures will authorise.
 * @param nonce held for the whole round, so every companion signs against the same challenge.
 * @param requesterPublicKey the device asking to be opened; its fingerprint goes into the QR and
 *   into what is signed.
 * @param expiresAtMillis wall-clock deadline for the whole round.
 * @param approvals Base64 fingerprints of the companions that have already signed, in scan order.
 */
data class ChallengeSession(
    val purpose: ChallengePurpose,
    val nonce: ByteArray,
    val requesterPublicKey: ByteArray,
    val expiresAtMillis: Long,
    val approvals: Set<String> = emptySet(),
) {
    /** Short identifier for the requesting device, as it appears in the challenge and payload. */
    val requesterFingerprint: ByteArray get() = PairedPeer.fingerprintOf(requesterPublicKey)

    /**
     * Reports whether the round has run out of time.
     *
     * @param nowMillis current wall-clock time.
     * @return true once [expiresAtMillis] has been reached.
     */
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    /**
     * Reports how long the round has left, floored at zero so an expired session reads as `0`.
     *
     * @param nowMillis current wall-clock time.
     * @return milliseconds remaining.
     */
    fun remainingMillis(nowMillis: Long): Long = (expiresAtMillis - nowMillis).coerceAtLeast(0L)

    /**
     * Reports whether the round is complete.
     *
     * @param peers every companion that must approve.
     * @return true once all of them have signed. An empty peer list is never satisfied — a device
     *   with no companions must not be openable by an empty set of approvals.
     */
    fun isSatisfiedBy(peers: List<PairedPeer>): Boolean =
        peers.isNotEmpty() && peers.all { it.fingerprint.encodeBase64() in approvals }

    /**
     * Reports whether one companion has already signed this round.
     *
     * @param peer the companion to check.
     * @return true when that peer's approval has been collected.
     */
    fun hasApproval(peer: PairedPeer): Boolean = peer.fingerprint.encodeBase64() in approvals

    /**
     * Records a companion's approval.
     *
     * @param peer the companion that signed.
     * @return a copy with that approval added. Re-adding an existing one is a no-op, since
     *   approvals are a set keyed by fingerprint.
     */
    fun withApproval(peer: PairedPeer): ChallengeSession =
        copy(approvals = approvals + peer.fingerprint.encodeBase64())

    /**
     * Renders this session as the QR a companion scans.
     *
     * @return the challenge payload. The deadline is narrowed to whole seconds, which is what the
     *   payload carries.
     */
    fun toQrPayload(): QrPayload.Challenge = QrPayload.Challenge(
        nonce = nonce,
        requesterFingerprint = requesterFingerprint,
        purpose = purpose,
        expiresAtEpochSeconds = expiresAtMillis / 1000,
    )

    /**
     * Compares by content, which the generated implementation would not do.
     *
     * [ByteArray] fields again: without this a session would never compare equal to itself and any
     * state comparison in Compose would treat every recomposition as a change.
     *
     * @param other the value to compare against.
     * @return true when every field matches, byte arrays by content.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChallengeSession) return false
        return purpose == other.purpose &&
            nonce.contentEquals(other.nonce) &&
            requesterPublicKey.contentEquals(other.requesterPublicKey) &&
            expiresAtMillis == other.expiresAtMillis &&
            approvals == other.approvals
    }

    /**
     * Hashes by content, to stay consistent with [equals].
     *
     * @return a hash derived from the byte arrays' contents rather than their identities.
     */
    override fun hashCode(): Int {
        var result = purpose.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + requesterPublicKey.contentHashCode()
        result = 31 * result + expiresAtMillis.hashCode()
        result = 31 * result + approvals.hashCode()
        return result
    }

    companion object {
        /** Long enough to carry the phone to a second person and back. */
        const val DEFAULT_LIFETIME_MILLIS = 600_000L

        /**
         * Opens a new round with a fresh nonce and no approvals.
         *
         * @param purpose what the collected signatures will authorise.
         * @param requesterPublicKey the device asking to be opened.
         * @param nowMillis current wall-clock time. Injectable so tests can fix the deadline.
         * @param lifetimeMillis how long the round stays valid.
         * @return the new session.
         */
        fun start(
            purpose: ChallengePurpose,
            requesterPublicKey: ByteArray,
            nowMillis: Long = System.currentTimeMillis(),
            lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS,
        ): ChallengeSession {
            KillitLog.i(KillitLog.PAIRING, "Starting a $purpose challenge, valid for ${lifetimeMillis}ms")
            return ChallengeSession(
                purpose = purpose,
                nonce = PairingProtocol.newNonce(),
                requesterPublicKey = requesterPublicKey,
                expiresAtMillis = nowMillis + lifetimeMillis,
            )
        }
    }
}
