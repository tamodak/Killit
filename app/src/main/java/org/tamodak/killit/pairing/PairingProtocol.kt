package org.tamodak.killit.pairing

import org.tamodak.killit.data.PairedPeer
import java.security.SecureRandom

/**
 * What a companion's signature authorises.
 *
 * @param tag goes into the signed bytes, so a signature for one purpose cannot verify against the
 *   other. Versioned, so a future change to what a purpose means invalidates old signatures rather
 *   than silently reinterpreting them.
 * @param code the single character this purpose occupies in a [QrPayload.Challenge].
 */
enum class ChallengePurpose(internal val tag: String, internal val code: Char) {
    /** Open a locked device. */
    UNLOCK("KILLIT-UNLOCK-v1", 'U'),

    /** Remove this pairing, which the peer being removed has to agree to. */
    UNPAIR("KILLIT-UNPAIR-v1", 'X'),
}

/**
 * Builds the bytes a companion actually signs.
 *
 * Both ends construct the payload here rather than each assembling its own, because a single byte
 * of disagreement between them produces signatures that never verify, with nothing on screen to
 * say why.
 *
 * ### What the payload binds, and the attacks that motivates
 *
 * ```
 * purpose tag  ||  nonce (16B)  ||  requester fingerprint (8B)  ||  signer fingerprint (8B)
 * ```
 *
 * - **purpose tag** — an unlock signature cannot be replayed to authorise an unpair. Without it,
 *   a companion who agreed to open a device once would have unknowingly also agreed to be removed
 *   as a companion.
 * - **nonce** — fresh per session and single-use, so a signature photographed once cannot be
 *   presented again tomorrow.
 * - **requester fingerprint** — ties the signature to the device that asked. Otherwise a
 *   signature collected from a companion could be shown to a *different* locked device that
 *   shares the same companion, unlocking a phone the companion never saw.
 * - **signer fingerprint** — ties the signature to the peer that produced it. This is what stops
 *   one companion's signature being counted twice to satisfy a two-companion requirement.
 */
object PairingProtocol {

    /** 128 bits, so a nonce is never guessed or repeated. */
    const val NONCE_BYTES = 16

    /**
     * Draws a fresh nonce for a new challenge.
     *
     * @return [NONCE_BYTES] bytes from [SecureRandom].
     */
    fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Assembles the exact bytes to sign or verify.
     *
     * @param purpose what the signature will authorise.
     * @param nonce the challenge nonce, from [newNonce].
     * @param requesterFingerprint fingerprint of the device asking to be unlocked.
     * @param signerFingerprint fingerprint of the companion being asked to approve.
     * @return the concatenated payload, in the order given in the class documentation.
     */
    fun payload(
        purpose: ChallengePurpose,
        nonce: ByteArray,
        requesterFingerprint: ByteArray,
        signerFingerprint: ByteArray,
    ): ByteArray {
        val tag = purpose.tag.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(
            tag.size + nonce.size + requesterFingerprint.size + signerFingerprint.size
        )
        var offset = 0
        tag.copyInto(payload, offset)
        offset += tag.size
        nonce.copyInto(payload, offset)
        offset += nonce.size
        requesterFingerprint.copyInto(payload, offset)
        offset += requesterFingerprint.size
        signerFingerprint.copyInto(payload, offset)
        return payload
    }

    /**
     * Assembles the payload for the common case where the signer is a known [PairedPeer].
     *
     * @param purpose what the signature will authorise.
     * @param nonce the challenge nonce, from [newNonce].
     * @param requesterFingerprint fingerprint of the device asking to be unlocked.
     * @param signer the companion being asked to approve; its fingerprint is derived here.
     * @return the concatenated payload, identical to what the other overload would produce.
     */
    fun payload(
        purpose: ChallengePurpose,
        nonce: ByteArray,
        requesterFingerprint: ByteArray,
        signer: PairedPeer,
    ): ByteArray = payload(purpose, nonce, requesterFingerprint, signer.fingerprint)
}
