package org.tamodak.killit.pairing

import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.PairedPeer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * The pairing signature scheme.
 *
 * Everything here guards the same failure: two phones that cannot verify each other, discovered
 * only when someone is standing in front of a locked device. The Keystore is involved, so this has
 * to run on a real device — and specifically on the **Vestel Z20 (API 26)**, where there is no
 * StrongBox and the TEE fallback is the path actually taken.
 */
@RunWith(AndroidJUnit4::class)
class PairingCryptoTest {

    /** The real Keystore-backed identity. Not a fake — the TEE path is what is under test. */
    private val keyStore = PeerKeyStore()

    /** Turns tracing on and makes sure a key exists before any test tries to sign with it. */
    @Before
    fun setUp() = runBlocking {
        KillitLog.verbose = true
        assertTrue("Could not create the pairing key", keyStore.createKeyPair())
    }

    /** The happy path end to end: a key exists, produces a signature, and verifies its own. */
    @Test
    fun signsAndVerifiesItsOwnPayload(): Unit = runBlocking {
        val publicKey = keyStore.publicKey()
        assertNotNull("A created key must expose a public key", publicKey)
        assertEquals(
            "Expected a compressed P-256 point",
            PeerKeyStore.PUBLIC_KEY_BYTES,
            publicKey!!.size,
        )

        val payload = PairingProtocol.payload(
            purpose = ChallengePurpose.UNLOCK,
            nonce = PairingProtocol.newNonce(),
            requesterFingerprint = PairedPeer.fingerprintOf(publicKey),
            signerFingerprint = PairedPeer.fingerprintOf(publicKey),
        )

        val signature = keyStore.sign(payload)
        assertNotNull("Signing must succeed once a key exists", signature)
        assertTrue(keyStore.verify(publicKey, payload, signature!!))
    }

    /**
     * Calling create twice keeps the original key.
     *
     * Replacing it would strand every paired peer, which still holds the old one.
     */
    @Test
    fun keyCreationIsIdempotent(): Unit = runBlocking {
        val first = keyStore.publicKey()
        keyStore.createKeyPair()
        val second = keyStore.publicKey()
        assertTrue("The pairing key must not be replaced", first!!.contentEquals(second!!))
    }

    /** A valid signature does not verify against somebody else's public key. */
    @Test
    fun rejectsASignatureFromADifferentKey(): Unit = runBlocking {
        val publicKey = keyStore.publicKey()!!
        val payload = PairingProtocol.payload(
            ChallengePurpose.UNLOCK,
            PairingProtocol.newNonce(),
            PairedPeer.fingerprintOf(publicKey),
            PairedPeer.fingerprintOf(publicKey),
        )
        val signature = keyStore.sign(payload)!!

        // A different, valid P-256 public key. Verification must fail, not throw.
        val stranger = strangerPublicKey()
        assertFalse(
            "A signature must not verify against someone else's key",
            keyStore.verify(stranger, payload, signature),
        )
    }

    /**
     * Changing any bound field invalidates the signature.
     *
     * The heart of the replay protection: a signature is bound to the exact payload. Each of the
     * four fields is varied in turn, and each maps to a real attack — named at its assertion below.
     */
    @Test
    fun signatureIsBoundToEveryFieldOfThePayload(): Unit = runBlocking {
        val publicKey = keyStore.publicKey()!!
        val fingerprint = PairedPeer.fingerprintOf(publicKey)
        val nonce = PairingProtocol.newNonce()

        val original = PairingProtocol.payload(ChallengePurpose.UNLOCK, nonce, fingerprint, fingerprint)
        val signature = keyStore.sign(original)!!
        assertTrue(keyStore.verify(publicKey, original, signature))

        // Different purpose: an unlock approval must not authorise an unpair.
        val asUnpair = PairingProtocol.payload(ChallengePurpose.UNPAIR, nonce, fingerprint, fingerprint)
        assertFalse(
            "An unlock signature must not satisfy an unpair challenge",
            keyStore.verify(publicKey, asUnpair, signature),
        )

        // Different nonce: yesterday's signature must not work today.
        val otherNonce = PairingProtocol.payload(
            ChallengePurpose.UNLOCK, PairingProtocol.newNonce(), fingerprint, fingerprint,
        )
        assertFalse("A stale nonce must not verify", keyStore.verify(publicKey, otherNonce, signature))

        // Different requester: a signature collected for phone A must not open phone C.
        val otherRequester = PairingProtocol.payload(
            ChallengePurpose.UNLOCK, nonce, PairedPeer.fingerprintOf(strangerPublicKey()), fingerprint,
        )
        assertFalse(
            "A signature must not transfer to another requesting device",
            keyStore.verify(publicKey, otherRequester, signature),
        )

        // Different signer: one companion's signature must not stand in for another's.
        val otherSigner = PairingProtocol.payload(
            ChallengePurpose.UNLOCK, nonce, fingerprint, PairedPeer.fingerprintOf(strangerPublicKey()),
        )
        assertFalse(
            "A signature must not be counted for a different companion",
            keyStore.verify(publicKey, otherSigner, signature),
        )
    }

    /**
     * A malformed or off-curve public key is refused, not thrown on.
     *
     * The off-curve case is the one that matters: accepting a point that is not on P-256 is the
     * classic invalid-curve attack.
     */
    @Test
    fun rejectsMalformedPublicKeys(): Unit = runBlocking {
        // Each case below is a key an attacker could supply; verification must return false rather
        // than throw, since this runs on every scanned response.
        val payload = PairingProtocol.payload(
            ChallengePurpose.UNLOCK, PairingProtocol.newNonce(), ByteArray(8), ByteArray(8),
        )
        val signature = keyStore.sign(payload)!!

        assertFalse("Empty key", keyStore.verify(ByteArray(0), payload, signature))
        assertFalse("Wrong length", keyStore.verify(ByteArray(64), payload, signature))

        // Right length, but 0x04 is the uncompressed tag — no longer what this device speaks.
        val badTag = ByteArray(PeerKeyStore.PUBLIC_KEY_BYTES).also { it[0] = 0x04 }
        assertFalse("Uncompressed tag", keyStore.verify(badTag, payload, signature))

        // Right length and tag. x = 1 has no square root on P-256, so decompression must refuse it
        // rather than hand back whatever modPow returned. (x = 0 *is* on the curve — a tempting
        // choice for this test and a useless one.)
        val offCurve = ByteArray(PeerKeyStore.PUBLIC_KEY_BYTES).also { it[0] = 0x02; it[32] = 1 }
        assertFalse("Point not on the curve", keyStore.verify(offCurve, payload, signature))

        val outOfField = ByteArray(PeerKeyStore.PUBLIC_KEY_BYTES) { 0xFF.toByte() }
            .also { it[0] = 0x02 }
        assertFalse("x is not below the field prime", keyStore.verify(outOfField, payload, signature))
    }

    /** An empty, wrong-length or nonsense signature is refused rather than throwing. */
    @Test
    fun rejectsAGarbageSignature(): Unit = runBlocking {
        val publicKey = keyStore.publicKey()!!
        val payload = PairingProtocol.payload(
            ChallengePurpose.UNLOCK, PairingProtocol.newNonce(), ByteArray(8), ByteArray(8),
        )
        assertFalse(keyStore.verify(publicKey, payload, ByteArray(0)))
        assertFalse("Wrong length", keyStore.verify(publicKey, payload, ByteArray(72) { 0x41 }))
        assertFalse(keyStore.verify(publicKey, payload, ByteArray(PeerKeyStore.SIGNATURE_BYTES) { 0x41 }))
    }

    /**
     * Raw and DER signature encodings convert both ways, over 32 real signatures.
     *
     * The QR carries `r||s`, the platform speaks DER, and the conversion runs on every signature.
     *
     * Repeated because the interesting cases are not deterministic: roughly one signature in 256
     * has a coordinate short enough to need left-padding, and about half have a top bit set, which
     * is where DER adds a leading zero byte. A single round would pass on a broken implementation.
     */
    @Test
    fun signatureEncodingRoundTrips(): Unit = runBlocking {
        val publicKey = keyStore.publicKey()!!

        repeat(32) {
            val payload = PairingProtocol.payload(
                ChallengePurpose.UNLOCK, PairingProtocol.newNonce(), ByteArray(8), ByteArray(8),
            )
            val raw = keyStore.sign(payload)
            assertNotNull(raw)
            assertEquals(PeerKeyStore.SIGNATURE_BYTES, raw!!.size)
            assertTrue("A raw signature must verify", keyStore.verify(publicKey, payload, raw))

            val der = PeerKeyStore.rawToDer(raw)
            assertNotNull(der)
            assertTrue(
                "DER and raw must round-trip",
                PeerKeyStore.derToRaw(der!!)!!.contentEquals(raw),
            )
        }
    }

    /**
     * A real key survives compression and decompression unchanged.
     *
     * A coordinate whose top bit is set, or one with leading zeros, is where a hand-written encoder
     * silently produces the wrong length — and neither is rare enough to hope for.
     */
    @Test
    fun publicKeyEncodingRoundTrips(): Unit = runBlocking {
        val encoded = keyStore.publicKey()!!
        val decoded = PeerKeyStore.decodePublicKey(encoded)
        assertNotNull("A key this device produced must decode", decoded)
        assertTrue(
            "Re-encoding must reproduce the same bytes",
            PeerKeyStore.encodePoint(decoded!!.w).contentEquals(encoded),
        )
    }

    /**
     * All three payload types encode and decode back to equal values, using real key material.
     *
     * The pairing label deliberately carries both delimiters the storage format uses, so a payload
     * that would break the peer list on the other side fails here instead.
     */
    @Test
    fun qrPayloadsRoundTrip(): Unit = runBlocking {
        val publicKey = keyStore.publicKey()!!

        val pairing = QrPayload.Pairing(publicKey = publicKey, label = "Ali:Ev|Test")
        assertEquals(pairing, QrPayload.decode(pairing.encode()))

        val challenge = QrPayload.Challenge(
            nonce = PairingProtocol.newNonce(),
            requesterFingerprint = PairedPeer.fingerprintOf(publicKey),
            purpose = ChallengePurpose.UNPAIR,
            expiresAtEpochSeconds = 1_800_000_000L,
        )
        assertEquals(challenge, QrPayload.decode(challenge.encode()))

        val response = QrPayload.Response(
            signerFingerprint = PairedPeer.fingerprintOf(publicKey),
            signature = keyStore.sign(ByteArray(4))!!,
        )
        assertEquals(response, QrPayload.decode(response.encode()))

        // Every payload has to stay inside QR alphanumeric mode, or it silently costs a third of
        // the capacity and the code grows a version.
        val alphanumeric = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
        listOf(pairing.encode(), challenge.encode(), response.encode()).forEach { text ->
            assertTrue("Not alphanumeric-mode safe: $text", text.all { it in alphanumeric })
        }

        KillitLog.i(KillitLog.PAIRING, "QR sizes: pair=${pairing.encode().length} " +
            "challenge=${challenge.encode().length} response=${response.encode().length} chars")
    }

    /**
     * Codes that are not Killit payloads, and Killit payloads that are damaged, decode to null.
     *
     * The scanner feeds every camera frame through the decoder, so this is the common case, not the
     * exceptional one.
     */
    @Test
    fun rejectsForeignAndCorruptQrCodes() {
        assertNull(QrPayload.decode("https://example.com"))
        assertNull(QrPayload.decode(""))
        assertNull(QrPayload.decode("KILLIT-PAIR:1:aaaa:bbbb"))
        assertNull("Prefix alone", QrPayload.decode("KP"))
        assertNull("Truncated key", QrPayload.decode("KP" + "A".repeat(20)))
        assertNull("Not base32", QrPayload.decode("KP" + "1".repeat(53)))
        assertNull("Truncated challenge", QrPayload.decode("KC" + "A".repeat(20)))
        assertNull("Unknown purpose", QrPayload.decode("KC" + "A".repeat(39) + "Z" + "ZZZZZZ"))
        assertNull("Truncated response", QrPayload.decode("KR" + "A".repeat(20)))
    }

    /**
     * A session reports itself valid before its deadline and expired after.
     *
     * The only expiry that is enforced anywhere, and the reason it works: one device, one clock.
     */
    @Test
    fun sessionExpiryIsHonoured() {
        val session = ChallengeSession.start(
            purpose = ChallengePurpose.UNLOCK,
            requesterPublicKey = ByteArray(PeerKeyStore.PUBLIC_KEY_BYTES),
            nowMillis = 1_000_000L,
            lifetimeMillis = 60_000L,
        )
        assertFalse("Still valid before expiry", session.isExpired(1_030_000L))
        assertTrue("Expired afterwards", session.isExpired(1_070_000L))
    }

    /**
     * Labels holding the format's own delimiters, and empty labels, round-trip intact.
     *
     * The label is user-supplied text going into a delimited record, which is exactly why it is
     * Base64-encoded rather than written raw.
     */
    @Test
    fun peerListSerialisationSurvivesAwkwardLabels() {
        val peers = listOf(
            PairedPeer(ByteArray(PeerKeyStore.PUBLIC_KEY_BYTES) { 1 }, "Ali:Ev|Telefon\nİkinci", 1_000L),
            PairedPeer(ByteArray(PeerKeyStore.PUBLIC_KEY_BYTES) { 2 }, "", 2_000L),
        )
        val restored = PairedPeer.deserialize(PairedPeer.serialize(peers))
        assertEquals(peers, restored)
    }

    /**
     * A corrupt line costs only itself; the peers around it still load.
     *
     * Losing one entry is recoverable by re-pairing that device. Losing the whole list would leave
     * a paired phone silently unpaired, and back on its passkey.
     */
    @Test
    fun peerListDropsOnlyTheCorruptEntries() {
        val good = PairedPeer(ByteArray(PeerKeyStore.PUBLIC_KEY_BYTES) { 3 }, "Ayşe", 3_000L)
        val raw = PairedPeer.serialize(listOf(good)) + "\nnot|a|valid|entry\ngarbage"
        assertEquals(listOf(good), PairedPeer.deserialize(raw))
    }

    /**
     * Mints a second, independent P-256 key that is not in the Keystore — stands in for another
     * phone.
     *
     * @return its compressed public point, in the same encoding a real peer's would arrive in.
     */
    private fun strangerPublicKey(): ByteArray {
        val generator = java.security.KeyPairGenerator.getInstance("EC")
        generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val point = (generator.generateKeyPair().public as java.security.interfaces.ECPublicKey).w
        return PeerKeyStore.encodePoint(point)
    }
}
