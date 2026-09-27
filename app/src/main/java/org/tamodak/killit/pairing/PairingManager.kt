package org.tamodak.killit.pairing

import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.LockRepository
import org.tamodak.killit.data.PairedPeer

/**
 * Why a scanned QR did not do what the user expected.
 *
 * Each case is distinct because each needs a different sentence on screen: telling someone their
 * companion is not paired, when the real problem is that the code was a supermarket barcode, is
 * how a working feature comes to look broken.
 */
sealed interface ScanOutcome {
    /** The scan did what it was meant to. */
    data object Accepted : ScanOutcome

    /** Not a Killit code at all — the camera saw a poster, a URL, a product barcode. */
    data object NotAKillitCode : ScanOutcome

    /** A Killit code, but not the kind this screen is waiting for. */
    data object WrongKind : ScanOutcome

    /** The requester's own approval round has run out of time; start a new one. */
    data object Expired : ScanOutcome

    /** The signature did not verify against the stored key for that companion. */
    data object BadSignature : ScanOutcome

    /** A response arrived from a device that is not paired with this one. */
    data object UnknownPeer : ScanOutcome

    /** This companion has already signed this round. */
    data object AlreadyApproved : ScanOutcome

    /** This device has no pairing key, so it cannot sign for anyone. */
    data object NoPairingKey : ScanOutcome

    /** Pairing needs device owner, so the record survives "Clear data". */
    data object NotDeviceOwner : ScanOutcome

    /** Tried to pair with a device that is already a companion. */
    data object AlreadyPaired : ScanOutcome

    /** Tried to pair a device with itself. */
    data object SelfPairing : ScanOutcome
}

/**
 * The rules that turn a scanned QR into a decision.
 *
 * Kept out of the ViewModel so the whole protocol can be exercised without a UI, and kept in one
 * place so the unlock path and the unpair path cannot drift apart — they differ only in the
 * purpose tag, and that is a difference worth having in one function rather than two screens.
 *
 * @param repository the peer list, guardianships and own-key storage.
 * @param keyStore this device's signing identity and the signature verifier.
 */
class PairingManager(
    private val repository: LockRepository,
    private val keyStore: PeerKeyStore,
) {

    /**
     * Ensures this device has a pairing identity and returns its public key.
     *
     * Creating the key is explicit rather than lazy — see [PeerKeyStore] for why a silently
     * regenerated key would strand every peer that stored the old one. A key minted here where one
     * used to exist is logged loudly, because that is the "Clear data" case and it is unrecoverable
     * for every peer that stored the old key.
     *
     * @return this device's compressed public key, or null if no identity could be established.
     */
    suspend fun ensureIdentity(): ByteArray? {
        repository.readCachedOwnPublicKey()?.let { return it }

        val existed = keyStore.hasKeyPair()
        if (!existed && !keyStore.createKeyPair()) return null
        val publicKey = keyStore.publicKey() ?: return null

        if (!existed) {
            KillitLog.w(
                KillitLog.PAIRING,
                "Minted a brand-new pairing identity (${KillitLog.fingerprint(publicKey)}). Any " +
                    "device that paired with this one before now holds the OLD key and will " +
                    "reject this device's approvals as an unknown companion.",
            )
        }
        repository.setOwnPublicKey(publicKey)
        return publicKey
    }

    /**
     * Adds the companion offered by a scanned pairing QR.
     *
     * Refuses to pair a device with itself: the peer list is what the unlock check runs against,
     * and a device listed as its own companion could sign its own approval.
     *
     * @param raw the scanned text.
     * @param ownPublicKey this device's public key, to detect the self-pairing case.
     * @return [ScanOutcome.Accepted] when the companion was stored, or the case explaining why not.
     */
    suspend fun acceptPairing(raw: String, ownPublicKey: ByteArray): ScanOutcome {
        val payload = QrPayload.decode(raw) ?: return ScanOutcome.NotAKillitCode
        if (payload !is QrPayload.Pairing) return ScanOutcome.WrongKind

        if (payload.publicKey.contentEquals(ownPublicKey)) {
            KillitLog.w(KillitLog.PAIRING, "Refusing to pair this device with itself")
            return ScanOutcome.SelfPairing
        }

        val peer = PairedPeer(
            publicKey = payload.publicKey,
            label = payload.label.ifBlank { UNNAMED_PEER },
            pairedAtMillis = System.currentTimeMillis(),
        )

        val existing = repository.readCachedPeers()
        if (existing.any { it.publicKey.contentEquals(peer.publicKey) }) return ScanOutcome.AlreadyPaired

        if (!repository.addPeer(peer)) return ScanOutcome.NotDeviceOwner

        KillitLog.i(
            KillitLog.PAIRING,
            "Stored '${peer.label}' as a companion, key ${KillitLog.fingerprint(peer.publicKey)}, " +
                "fingerprint ${KillitLog.fingerprint(peer.fingerprint)}",
        )
        return ScanOutcome.Accepted
    }

    /**
     * Signs a challenge shown by another device — this phone acting as somebody's companion.
     *
     * Reachable without unlocking this device on purpose. Signing proves possession of this
     * device's private key; it opens nothing here. Requiring this phone to be unlocked first would
     * deadlock two mutually paired phones, since neither could open to help the other.
     *
     * The deadline carried in the challenge is deliberately **not** enforced here: it was stamped
     * by the other phone's clock, and it is unauthenticated, so a forged challenge simply carries
     * a later one. What bounds a round is [applyResponse] checking the requester's own session
     * against the requester's own clock, plus the nonce. Do not reinstate a refusal here — two
     * phones that disagree about the time would never complete a single unlock.
     *
     * @param raw the scanned text.
     * @return the outcome, paired with the response to show back when it is
     *   [ScanOutcome.Accepted], and null otherwise.
     */
    suspend fun approveChallenge(raw: String): Pair<ScanOutcome, QrPayload.Response?> {
        val payload = QrPayload.decode(raw) ?: return ScanOutcome.NotAKillitCode to null
        if (payload !is QrPayload.Challenge) return ScanOutcome.WrongKind to null

        val pastDeadlineMillis = System.currentTimeMillis() - payload.expiresAtEpochSeconds * 1000
        if (pastDeadlineMillis > 0) {
            KillitLog.w(
                KillitLog.PAIRING,
                "Challenge reads ${pastDeadlineMillis / 1000}s past its deadline by this clock; " +
                    "signing anyway. A large figure here means the two phones disagree about the " +
                    "time, not that the request is old.",
            )
        }

        val ownPublicKey = repository.readCachedOwnPublicKey() ?: keyStore.publicKey()
        if (ownPublicKey == null) {
            KillitLog.w(KillitLog.PAIRING, "Cannot approve: this device has no pairing identity")
            return ScanOutcome.NoPairingKey to null
        }

        val signature = keyStore.sign(
            PairingProtocol.payload(
                purpose = payload.purpose,
                nonce = payload.nonce,
                requesterFingerprint = payload.requesterFingerprint,
                signerFingerprint = PairedPeer.fingerprintOf(ownPublicKey),
            )
        ) ?: return ScanOutcome.NoPairingKey to null

        // Where this phone finds out it is load-bearing. Pairing is one-directional — the other
        // device scanned a code and stored it, and nothing was sent back — so an approval is the
        // first and only moment a companion learns that somebody's phone cannot open without it.
        when (payload.purpose) {
            ChallengePurpose.UNLOCK -> repository.addGuardianship(payload.requesterFingerprint)
            ChallengePurpose.UNPAIR -> repository.removeGuardianship(payload.requesterFingerprint)
        }

        val signerFingerprint = PairedPeer.fingerprintOf(ownPublicKey)
        KillitLog.i(
            KillitLog.PAIRING,
            "Approved a ${payload.purpose} challenge, signing as key " +
                "${KillitLog.fingerprint(ownPublicKey)}, fingerprint " +
                KillitLog.fingerprint(signerFingerprint),
        )
        return ScanOutcome.Accepted to QrPayload.Response(
            signerFingerprint = signerFingerprint,
            signature = signature,
        )
    }

    /**
     * Checks a companion's response against an open session.
     *
     * On success the session is returned with that companion recorded; the caller decides whether
     * enough approvals have accumulated. Everything else returns the original session untouched,
     * so a bad scan never costs the approvals already collected.
     *
     * This is where a round is actually bounded: the session's own deadline, on this device's own
     * clock, plus the nonce. See [approveChallenge] for why the companion side does not enforce it.
     *
     * @param raw the scanned text.
     * @param session the open round to apply the response to.
     * @param peers every companion that must approve, for looking up the signer.
     * @return the outcome, paired with the session — updated on success, unchanged otherwise.
     */
    suspend fun applyResponse(
        raw: String,
        session: ChallengeSession,
        peers: List<PairedPeer>,
    ): Pair<ScanOutcome, ChallengeSession> {
        val payload = QrPayload.decode(raw) ?: return ScanOutcome.NotAKillitCode to session
        if (payload !is QrPayload.Response) return ScanOutcome.WrongKind to session

        if (session.isExpired(System.currentTimeMillis())) {
            KillitLog.i(KillitLog.PAIRING, "Response arrived after the session expired")
            return ScanOutcome.Expired to session
        }

        val peer = peers.firstOrNull { it.fingerprint.contentEquals(payload.signerFingerprint) }
        if (peer == null) {
            KillitLog.w(
                KillitLog.PAIRING,
                "Response from a device that is not a companion of this one. " +
                    "Signer ${KillitLog.fingerprint(payload.signerFingerprint)}; " +
                    "${peers.size} companion(s) stored: " +
                    peers.joinToString { "'${it.label}'=${KillitLog.fingerprint(it.fingerprint)}" },
            )
            return ScanOutcome.UnknownPeer to session
        }
        if (session.hasApproval(peer)) {
            KillitLog.d(KillitLog.PAIRING) { "'${peer.label}' has already signed this round" }
            return ScanOutcome.AlreadyApproved to session
        }

        // Rebuilt here rather than trusting anything in the response: the signature is only
        // meaningful against the payload this device believes it asked for.
        val expected = PairingProtocol.payload(
            purpose = session.purpose,
            nonce = session.nonce,
            requesterFingerprint = session.requesterFingerprint,
            signerFingerprint = peer.fingerprint,
        )

        if (!keyStore.verify(peer.publicKey, expected, payload.signature)) {
            KillitLog.w(KillitLog.PAIRING, "Signature from '${peer.label}' did not verify")
            return ScanOutcome.BadSignature to session
        }

        val updated = session.withApproval(peer)
        KillitLog.i(
            KillitLog.PAIRING,
            "'${peer.label}' approved; ${updated.approvals.size} of ${peers.size} companions so far",
        )
        return ScanOutcome.Accepted to updated
    }

    private companion object {
        /** Stands in when a pairing QR carries a blank label, so the peer list is never empty text. */
        const val UNNAMED_PEER = "Unnamed device"
    }
}
