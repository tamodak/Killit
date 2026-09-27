package org.tamodak.killit.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.CredentialStore
import org.tamodak.killit.data.DurableStore
import org.tamodak.killit.data.LockPreferences
import org.tamodak.killit.data.LockRepository
import org.tamodak.killit.data.PairedPeer
import org.tamodak.killit.data.encodeBase64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The challenge/response round, driven end to end.
 *
 * This device stands in for the locked phone; a second, software-only key pair stands in for the
 * companion, so the whole exchange can be exercised without two handsets. What it cannot cover is
 * the durable half — `addPeer` needs device owner — so the peer list here is built directly.
 */
@RunWith(AndroidJUnit4::class)
class PairingFlowTest {

    /** The instrumentation context, which owns the stores these tests write to. */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** This device's real Keystore identity — the locked phone's side of the exchange. */
    private val keyStore = PeerKeyStore()

    /** Real, and inert unless the test handset happens to be provisioned. */
    private val dpc = DevicePolicyController(context)

    /** The local store. Cleared around every test. */
    private val prefs = LockPreferences(context)

    /** Available only on a provisioned handset; [clearPairings] handles both cases. */
    private val durable = DurableStore(dpc)

    /** Backs the manager below, and is what the peer list is read from and written to. */
    private val repository = LockRepository(
        prefs = prefs,
        durable = durable,
        credentials = CredentialStore(),
    )

    /** The subject: the rules that turn a scanned QR into a decision. */
    private val manager = PairingManager(repository, keyStore)

    /** A stand-in companion whose private key lives in this test rather than in the Keystore. */
    private lateinit var companion: SoftwarePeer

    /** This device's own public key, which every challenge is built against. */
    private lateinit var ownPublicKey: ByteArray

    /** Turns tracing on, clears leftover pairings, and mints both sides of the exchange. */
    @Before
    fun setUp() = runBlocking {
        KillitLog.verbose = true
        clearPairings()
        assertTrue(keyStore.createKeyPair())
        ownPublicKey = keyStore.publicKey()!!
        companion = SoftwarePeer("Companion")
    }

    /**
     * Leaving a pairing behind would lock the app on this device out of its passkey — the tests
     * run against the real stores, and on a provisioned handset that write is durable.
     */
    @After
    fun tearDown() = runBlocking { clearPairings() }

    /** Empties the peer list and guardianships from both stores, whichever are available. */
    private suspend fun clearPairings() {
        prefs.writePeers(emptyList())
        prefs.writeGuardianships(emptySet())
        if (durable.isAvailable()) {
            durable.writePeers(emptyList())
            // Approving in a test records a guardianship. Left behind on a provisioned handset it
            // would hold this device's own release and hardening toggles shut for good.
            durable.writeGuardianships(emptySet())
        }
    }

    /** With a single companion, that companion's signature completes the round. */
    @Test
    fun aCompanionApprovalOpensTheSession(): Unit = runBlocking {
        val peers = listOf(companion.asPeer())
        val session = ChallengeSession.start(ChallengePurpose.UNLOCK, ownPublicKey)

        assertFalse("An empty session must not satisfy anything", session.isSatisfiedBy(peers))

        val (outcome, updated) = manager.applyResponse(
            companion.respondTo(session).encode(), session, peers,
        )

        assertEquals(ScanOutcome.Accepted, outcome)
        assertTrue("One companion, one approval, done", updated.isSatisfiedBy(peers))
    }

    /**
     * With two companions, the round completes only after both have signed.
     *
     * The core of "every companion must approve": one signature is not enough for two peers.
     */
    @Test
    fun everyCompanionMustApprove(): Unit = runBlocking {
        val second = SoftwarePeer("Second")
        val peers = listOf(companion.asPeer(), second.asPeer())
        val session = ChallengeSession.start(ChallengePurpose.UNLOCK, ownPublicKey)

        val (firstOutcome, afterFirst) = manager.applyResponse(
            companion.respondTo(session).encode(), session, peers,
        )
        assertEquals(ScanOutcome.Accepted, firstOutcome)
        assertFalse("One of two is not enough", afterFirst.isSatisfiedBy(peers))

        val (secondOutcome, afterSecond) = manager.applyResponse(
            second.respondTo(afterFirst).encode(), afterFirst, peers,
        )
        assertEquals(ScanOutcome.Accepted, secondOutcome)
        assertTrue("Both approved", afterSecond.isSatisfiedBy(peers))
    }

    /**
     * Scanning the same companion twice is reported and changes nothing.
     *
     * Re-scanning the same phone must not stand in for the companion that has not signed yet.
     */
    @Test
    fun thesameCompanionCannotApproveTwice(): Unit = runBlocking {
        val second = SoftwarePeer("Second")
        val peers = listOf(companion.asPeer(), second.asPeer())
        val session = ChallengeSession.start(ChallengePurpose.UNLOCK, ownPublicKey)

        val (_, afterFirst) = manager.applyResponse(companion.respondTo(session).encode(), session, peers)
        val (outcome, afterRepeat) = manager.applyResponse(
            companion.respondTo(afterFirst).encode(), afterFirst, peers,
        )

        assertEquals(ScanOutcome.AlreadyApproved, outcome)
        assertEquals("A repeat scan must change nothing", afterFirst, afterRepeat)
        assertFalse(afterRepeat.isSatisfiedBy(peers))
    }

    /** A signature from a device that is not a companion is refused, and the round is untouched. */
    @Test
    fun rejectsApprovalFromAnUnpairedPhone(): Unit = runBlocking {
        val stranger = SoftwarePeer("Stranger")
        val peers = listOf(companion.asPeer())
        val session = ChallengeSession.start(ChallengePurpose.UNLOCK, ownPublicKey)

        val (outcome, unchanged) = manager.applyResponse(
            stranger.respondTo(session).encode(), session, peers,
        )
        assertEquals(ScanOutcome.UnknownPeer, outcome)
        assertEquals(session, unchanged)
    }

    /**
     * An approval issued for one locked phone does not open a second one.
     *
     * A signature collected for one phone must not open another that shares the same companion.
     * The requester fingerprint bound into the payload is what prevents it.
     */
    @Test
    fun approvalDoesNotTransferToAnotherDevice(): Unit = runBlocking {
        val peers = listOf(companion.asPeer())
        val otherDevice = SoftwarePeer("Other locked phone")

        val theirSession = ChallengeSession.start(ChallengePurpose.UNLOCK, otherDevice.publicKey)
        val responseForThem = companion.respondTo(theirSession)

        // Same nonce, but our session — so the requester fingerprint differs.
        val ourSession = theirSession.copy(requesterPublicKey = ownPublicKey)
        val (outcome, unchanged) = manager.applyResponse(responseForThem.encode(), ourSession, peers)

        assertEquals(ScanOutcome.BadSignature, outcome)
        assertEquals(ourSession, unchanged)
    }

    /**
     * An unlock signature does not satisfy an unpair challenge.
     *
     * An approval to open must not double as an approval to be removed — the companion agreed to
     * one, not the other.
     */
    @Test
    fun unlockApprovalDoesNotAuthoriseUnpair(): Unit = runBlocking {
        val peers = listOf(companion.asPeer())
        val unlockSession = ChallengeSession.start(ChallengePurpose.UNLOCK, ownPublicKey)
        val response = companion.respondTo(unlockSession)

        val unpairSession = unlockSession.copy(purpose = ChallengePurpose.UNPAIR)
        val (outcome, _) = manager.applyResponse(response.encode(), unpairSession, peers)

        assertEquals(ScanOutcome.BadSignature, outcome)
    }

    /**
     * A response arriving after the round's deadline is refused.
     *
     * The requester's own session is the only place an expiry is enforced, so this is the check
     * that actually bounds a round.
     */
    @Test
    fun expiredSessionsAreRefused(): Unit = runBlocking {
        val peers = listOf(companion.asPeer())
        val session = ChallengeSession.start(
            purpose = ChallengePurpose.UNLOCK,
            requesterPublicKey = ownPublicKey,
            nowMillis = System.currentTimeMillis() - 10_000,
            lifetimeMillis = 1_000,
        )
        val (outcome, _) = manager.applyResponse(companion.respondTo(session).encode(), session, peers)
        assertEquals(ScanOutcome.Expired, outcome)
    }

    /**
     * This device acting as a companion produces a signature the requester can verify.
     *
     * The other side of every other test here: those check what this device accepts, this checks
     * what it emits — rebuilding the payload exactly as the requesting phone would.
     */
    @Test
    fun approvingProducesAVerifiableSignature(): Unit = runBlocking {
        val requester = SoftwarePeer("Requester")
        val challenge = QrPayload.Challenge(
            nonce = PairingProtocol.newNonce(),
            requesterFingerprint = PairedPeer.fingerprintOf(requester.publicKey),
            purpose = ChallengePurpose.UNLOCK,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + 120,
        )

        val (outcome, response) = manager.approveChallenge(challenge.encode())
        assertEquals(ScanOutcome.Accepted, outcome)
        assertNotNull(response)

        // The requester would verify it exactly like this.
        val expected = PairingProtocol.payload(
            purpose = ChallengePurpose.UNLOCK,
            nonce = challenge.nonce,
            requesterFingerprint = PairedPeer.fingerprintOf(requester.publicKey),
            signerFingerprint = PairedPeer.fingerprintOf(ownPublicKey),
        )
        assertTrue(keyStore.verify(ownPublicKey, expected, response!!.signature))
    }

    /**
     * A challenge that reads as expired by *this* device's clock is still signed.
     *
     * The regression this exists for: a locked phone whose clock is behind stamps a deadline that
     * has already passed by the companion's clock. Refusing here made such a phone impossible to
     * open — every retry produced another already-dead code — and bought nothing, since the field
     * is unauthenticated and the requester enforces its own session anyway.
     */
    @Test
    fun approvesAChallengeWhoseDeadlineHasAlreadyPassedHere(): Unit = runBlocking {
        val challenge = QrPayload.Challenge(
            nonce = PairingProtocol.newNonce(),
            requesterFingerprint = PairedPeer.fingerprintOf(SoftwarePeer("R").publicKey),
            purpose = ChallengePurpose.UNLOCK,
            // A day behind: far more than any tolerance would have covered.
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 - 86_400,
        )
        val (outcome, response) = manager.approveChallenge(challenge.encode())
        assertEquals(ScanOutcome.Accepted, outcome)
        assertNotNull(response)
    }

    /**
     * Approving an unlock records that the requesting device depends on this phone's key.
     *
     * Pairing is one-directional, so a companion is never told it has become one. Approving is the
     * only moment it finds out, and this is what stops the phone holding the key from being wiped
     * or released as if it were free.
     */
    @Test
    fun approvingRecordsThatThisPhoneIsDependedOn(): Unit = runBlocking {
        val locked = SoftwarePeer("Locked phone")
        val fingerprint = PairedPeer.fingerprintOf(locked.publicKey).encodeBase64()

        assertFalse(
            "Nothing should depend on this phone yet",
            repository.readCachedGuardianships().contains(fingerprint),
        )

        val (outcome, _) = manager.approveChallenge(challengeFrom(locked, ChallengePurpose.UNLOCK))
        assertEquals(ScanOutcome.Accepted, outcome)
        assertTrue(
            "Approving an unlock must record the dependency",
            repository.readCachedGuardianships().contains(fingerprint),
        )
    }

    /**
     * Approving a removal drops that dependency again.
     *
     * The other side of [approvingRecordsThatThisPhoneIsDependedOn]: without it, a phone would stay
     * locked into guardian status forever, unable to release or relax its own hardening.
     */
    @Test
    fun approvingARemovalDropsTheDependency(): Unit = runBlocking {
        val locked = SoftwarePeer("Locked phone")
        val fingerprint = PairedPeer.fingerprintOf(locked.publicKey).encodeBase64()

        manager.approveChallenge(challengeFrom(locked, ChallengePurpose.UNLOCK))
        assertTrue(repository.readCachedGuardianships().contains(fingerprint))

        manager.approveChallenge(challengeFrom(locked, ChallengePurpose.UNPAIR))
        assertFalse(
            "A removal must free this phone again",
            repository.readCachedGuardianships().contains(fingerprint),
        )
    }

    /**
     * Builds an encoded challenge as if another device had issued it.
     *
     * @param requester the device asking to be opened.
     * @param purpose what the signature would authorise.
     * @return the encoded challenge, two minutes from expiry.
     */
    private fun challengeFrom(requester: SoftwarePeer, purpose: ChallengePurpose): String =
        QrPayload.Challenge(
            nonce = PairingProtocol.newNonce(),
            requesterFingerprint = PairedPeer.fingerprintOf(requester.publicKey),
            purpose = purpose,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + 120,
        ).encode()

    /**
     * A device cannot pair with itself.
     *
     * The peer list is what the unlock check runs against, so a device listed as its own companion
     * could sign its own approval.
     */
    @Test
    fun refusesToPairWithItself(): Unit = runBlocking {
        val own = QrPayload.Pairing(publicKey = ownPublicKey, label = "Me")
        assertEquals(ScanOutcome.SelfPairing, manager.acceptPairing(own.encode(), ownPublicKey))
    }

    /**
     * Pairing is refused on a device that is not device owner.
     *
     * It must be, or "Clear data" would undo it. Only meaningful on an unprovisioned device — on a
     * provisioned one the refusal cannot happen by definition, so the test is skipped rather than
     * made to pass by weakening the assertion.
     */
    @Test
    fun refusesToPairWithoutDeviceOwner(): Unit = runBlocking {
        assumeFalse("Needs a device where Killit is not device owner", dpc.isDeviceOwner())

        val theirs = QrPayload.Pairing(publicKey = companion.publicKey, label = "Companion")
        assertEquals(ScanOutcome.NotDeviceOwner, manager.acceptPairing(theirs.encode(), ownPublicKey))
    }

    /**
     * On a provisioned device, a pairing lands in durable storage and survives a cleared cache.
     *
     * The counterpart to [refusesToPairWithoutDeviceOwner]: pairing succeeds and lands where
     * "Clear data" cannot reach it. This is the property the whole design rests on, so the test
     * goes on to wipe the local cache and check reconciliation brings the companion back.
     */
    @Test
    fun pairingIsWrittenDurablyWhenDeviceOwner(): Unit = runBlocking {
        assumeTrue("Needs a provisioned device", dpc.isDeviceOwner())

        val theirs = QrPayload.Pairing(publicKey = companion.publicKey, label = "Companion")
        assertEquals(ScanOutcome.Accepted, manager.acceptPairing(theirs.encode(), ownPublicKey))

        val durablePeers = durable.readPeers()
        assertEquals("The companion must survive in system storage", 1, durablePeers.size)
        assertTrue(durablePeers.single().publicKey.contentEquals(companion.publicKey))

        // Wiping only the local cache is what "Clear data" does; reconciliation must bring it back.
        prefs.writePeers(emptyList())
        assertEquals(
            "A cleared local cache must be restored from durable storage",
            1,
            repository.reconcilePeers().size,
        )

        assertEquals(ScanOutcome.AlreadyPaired, manager.acceptPairing(theirs.encode(), ownPublicKey))
    }

    /** All three scan entry points report a non-Killit code as such rather than failing obscurely. */
    @Test
    fun ignoresCodesThatAreNotKillitPayloads(): Unit = runBlocking {
        val session = ChallengeSession.start(ChallengePurpose.UNLOCK, ownPublicKey)
        assertEquals(
            ScanOutcome.NotAKillitCode,
            manager.applyResponse("https://example.com", session, emptyList()).first,
        )
        assertEquals(ScanOutcome.NotAKillitCode, manager.acceptPairing("random text", ownPublicKey))
        assertEquals(ScanOutcome.NotAKillitCode, manager.approveChallenge("1234567890").first)
    }

    /**
     * A valid Killit code of the wrong type is reported as such.
     *
     * Scanning a pairing code where a response is expected must say so, not fail silently — the two
     * mistakes have completely different fixes.
     */
    @Test
    fun reportsTheWrongKindOfKillitCode(): Unit = runBlocking {
        val session = ChallengeSession.start(ChallengePurpose.UNLOCK, ownPublicKey)
        val pairingCode = QrPayload.Pairing(companion.publicKey, "Companion").encode()
        assertEquals(ScanOutcome.WrongKind, manager.applyResponse(pairingCode, session, emptyList()).first)
    }

    /**
     * A companion simulated purely in software, so a test can hold a private key that the Android
     * Keystore would never hand out.
     *
     * @param label what this peer calls itself, as it would appear in the peer list.
     */
    private class SoftwarePeer(val label: String) {
        /** Generated in-process, so this test can sign as the other phone. */
        private val keyPair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        /** The compressed point, in the same encoding a real peer's would arrive in. */
        val publicKey: ByteArray =
            PeerKeyStore.encodePoint((keyPair.public as java.security.interfaces.ECPublicKey).w)

        /**
         * Renders this peer as a stored companion.
         *
         * @return the peer as the peer list would hold it.
         */
        fun asPeer() = PairedPeer(publicKey = publicKey, label = label, pairedAtMillis = 0L)

        /**
         * Signs a challenge as this peer would.
         *
         * Builds the payload from the session's own fields, exactly as `PairingManager` does on the
         * real companion side — so a test that passes here would pass against a second handset.
         *
         * @param session the round being approved.
         * @return the response payload, with the signature converted to the raw wire form.
         */
        fun respondTo(session: ChallengeSession): QrPayload.Response {
            val payload = PairingProtocol.payload(
                purpose = session.purpose,
                nonce = session.nonce,
                requesterFingerprint = session.requesterFingerprint,
                signerFingerprint = PairedPeer.fingerprintOf(publicKey),
            )
            val der = java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }
            // A real companion sends `r||s`, not the DER the JCA hands back.
            val signature = PeerKeyStore.derToRaw(der)!!
            return QrPayload.Response(PairedPeer.fingerprintOf(publicKey), signature)
        }
    }
}
