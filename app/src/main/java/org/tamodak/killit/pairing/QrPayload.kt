package org.tamodak.killit.pairing

import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.PairedPeer

/**
 * Everything Killit ever puts in a QR code, and the wire format for it.
 *
 * ### Why a hand-rolled format
 *
 * The three payloads are read by a camera pointed at another phone's screen, so the encoding is
 * chosen to keep the code sparse enough to scan quickly at arm's length rather than to be
 * self-describing. Two things follow from that:
 *
 * - **Base32, not Base64.** QR's alphanumeric mode covers `A-Z` and `0-9` and encodes them at
 *   5.5 bits per character; Base64's mixed case and `+/=` force byte mode at 8 bits, producing a
 *   visibly denser code from the same data.
 * - **Fixed-width fields, no delimiters.** Every field except the trailing label and expiry has a
 *   length known at compile time, so the decoders slice by offset. Nothing is spent on separators
 *   or a header.
 *
 * Each payload starts with a two-character prefix so [decode] can tell them apart, and so a QR code
 * from something else entirely is rejected on the first two characters.
 *
 * ### Decoding is total
 *
 * No decoder throws. A payload that is the wrong length, holds characters outside the Base32
 * alphabet, or carries a field of the wrong size comes back as null, and the scanner simply keeps
 * looking. That matters because this code runs on every frame the camera produces, most of which
 * contain no valid payload at all.
 */
sealed interface QrPayload {

    /**
     * An offer to pair: "here is who I am, add me as a companion".
     *
     * Shown by the device that wants to become a companion, scanned by the device being locked.
     * Carries no signature — this is the introduction, and the user physically holding both phones
     * is what authorises it.
     *
     * @param publicKey the offering device's compressed P-256 point.
     * @param label what that device calls itself, truncated to [LABEL_MAX_BYTES] on encode.
     */
    data class Pairing(val publicKey: ByteArray, val label: String) : QrPayload {
        /**
         * Compares by content, which the generated implementation would not do.
         *
         * @param other the value to compare against.
         * @return true when both fields match, the key by content.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Pairing) return false
            return publicKey.contentEquals(other.publicKey) && label == other.label
        }

        /**
         * Hashes by content, to stay consistent with [equals].
         *
         * @return a hash derived from the key's contents rather than its identity.
         */
        override fun hashCode(): Int = 31 * publicKey.contentHashCode() + label.hashCode()
    }

    /**
     * A request for a companion's approval: "sign this to let me through".
     *
     * Shown by the locked device, scanned by the companion. The fields are exactly what
     * [PairingProtocol.payload] binds into the signed bytes, so a companion that scans this can
     * reconstruct the payload without being told it separately.
     *
     * @param nonce fresh per session and single-use, so a photographed approval cannot be replayed.
     * @param requesterFingerprint identifies the device asking, so an approval collected for one
     *   phone cannot be presented to another that shares the same companion.
     * @param purpose what the signature will authorise; keeps an unlock approval from doubling as
     *   consent to be unpaired.
     * @param expiresAtEpochSeconds when this challenge stops being accepted, in seconds since the
     *   epoch. Seconds rather than milliseconds because the value is Base36-encoded into the QR
     *   code, and three fewer digits is three fewer characters to scan.
     */
    data class Challenge(
        val nonce: ByteArray,
        val requesterFingerprint: ByteArray,
        val purpose: ChallengePurpose,
        val expiresAtEpochSeconds: Long,
    ) : QrPayload {
        /**
         * Compares by content, which the generated implementation would not do.
         *
         * @param other the value to compare against.
         * @return true when every field matches, byte arrays by content.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Challenge) return false
            return nonce.contentEquals(other.nonce) &&
                requesterFingerprint.contentEquals(other.requesterFingerprint) &&
                purpose == other.purpose &&
                expiresAtEpochSeconds == other.expiresAtEpochSeconds
        }

        /**
         * Hashes by content, to stay consistent with [equals].
         *
         * @return a hash derived from the byte arrays' contents rather than their identities.
         */
        override fun hashCode(): Int {
            var result = nonce.contentHashCode()
            result = 31 * result + requesterFingerprint.contentHashCode()
            result = 31 * result + purpose.hashCode()
            result = 31 * result + expiresAtEpochSeconds.hashCode()
            return result
        }
    }

    /**
     * A companion's approval of a [Challenge].
     *
     * Shown by the companion, scanned back by the locked device. Carries no copy of what was
     * signed: the locked device still holds the challenge it issued, and rebuilding the payload
     * from its own copy is what makes the signature meaningful.
     *
     * @param signerFingerprint identifies which companion signed, so one peer's approval cannot be
     *   counted twice against a requirement for two.
     * @param signature the ECDSA signature over [PairingProtocol.payload], in the fixed-width form
     *   [PeerKeyStore] produces.
     */
    data class Response(val signerFingerprint: ByteArray, val signature: ByteArray) : QrPayload {
        /**
         * Compares by content, which the generated implementation would not do.
         *
         * @param other the value to compare against.
         * @return true when both fields match by content.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Response) return false
            return signerFingerprint.contentEquals(other.signerFingerprint) &&
                signature.contentEquals(other.signature)
        }

        /**
         * Hashes by content, to stay consistent with [equals].
         *
         * @return a hash derived from the byte arrays' contents rather than their identities.
         */
        override fun hashCode(): Int =
            31 * signerFingerprint.contentHashCode() + signature.contentHashCode()
    }

    /**
     * Encodes this payload for display in a QR code.
     *
     * Fields are concatenated at fixed widths behind a two-character prefix; see the class
     * documentation for why there are no delimiters.
     *
     * @return the encoded string, using only characters in QR's alphanumeric mode.
     */
    fun encode(): String = when (this) {
        is Pairing -> buildString {
            append(PREFIX_PAIRING)
            append(publicKey.encodeBase32())
            append(label.truncateUtf8(LABEL_MAX_BYTES).encodeBase32())
        }

        is Challenge -> buildString {
            append(PREFIX_CHALLENGE)
            append(nonce.encodeBase32())
            append(requesterFingerprint.encodeBase32())
            append(purpose.code)
            append(expiresAtEpochSeconds.toString(RADIX).uppercase())
        }

        is Response -> buildString {
            append(PREFIX_RESPONSE)
            append(signerFingerprint.encodeBase32())
            append(signature.encodeBase32())
        }
    }

    companion object {
        // Payload prefixes. Two characters, so a QR code from anything else is rejected before any
        // decoding work happens.
        private const val PREFIX_PAIRING = "KP"
        private const val PREFIX_CHALLENGE = "KC"
        private const val PREFIX_RESPONSE = "KR"

        /** Width of every prefix above; the point every decoder starts slicing from. */
        private const val PREFIX_CHARS = 2

        /**
         * Base for the challenge expiry. 36 is the largest radix that stays inside QR's
         * alphanumeric mode once uppercased, so it is the shortest encoding available here.
         */
        private const val RADIX = 36

        /**
         * Cap on the encoded device label.
         *
         * The label is the only variable-length field in any payload, so it is the only one that
         * can push a QR code into a denser version that is slower to scan. Sixteen bytes is enough
         * for a name that distinguishes two phones.
         */
        const val LABEL_MAX_BYTES = 16

        // Field widths in encoded characters, derived from the byte widths their owners declare so
        // that changing a key or signature size cannot leave the decoders slicing at stale offsets.
        private val keyChars = base32Length(PeerKeyStore.PUBLIC_KEY_BYTES)
        private val nonceChars = base32Length(PairingProtocol.NONCE_BYTES)
        private val fingerprintChars = base32Length(PairedPeer.FINGERPRINT_BYTES)
        private val signatureChars = base32Length(PeerKeyStore.SIGNATURE_BYTES)

        /**
         * Decodes whatever the camera just read.
         *
         * Dispatches on the two-character prefix, so a QR code belonging to something else costs
         * one comparison to reject.
         *
         * @param raw the scanned text. Surrounding whitespace is tolerated.
         * @return the payload, or null if this is not a Killit code or does not parse.
         */
        fun decode(raw: String): QrPayload? {
            val text = raw.trim()
            return when {
                text.startsWith(PREFIX_PAIRING) -> decodePairing(text)
                text.startsWith(PREFIX_CHALLENGE) -> decodeChallenge(text)
                text.startsWith(PREFIX_RESPONSE) -> decodeResponse(text)
                else -> null
            }
        }

        /**
         * Decodes a [Pairing] payload.
         *
         * The label runs to the end of the string rather than carrying a length, since it is the
         * last field. Its decoded size is still checked, so a payload claiming a longer label than
         * [encode] would ever produce is rejected rather than stored.
         *
         * @param text the scanned string, prefix included.
         * @return the payload, or null if it is truncated, unparseable, or holds a wrong-sized key.
         */
        private fun decodePairing(text: String): Pairing? {
            val labelStart = PREFIX_CHARS + keyChars
            if (text.length < labelStart) return malformed(PREFIX_PAIRING, text.length)

            val publicKey = text.substring(PREFIX_CHARS, labelStart).decodeBase32()
                ?: return malformed(PREFIX_PAIRING, text.length)
            if (publicKey.size != PeerKeyStore.PUBLIC_KEY_BYTES) return null

            val label = text.substring(labelStart).decodeBase32()
                ?: return malformed(PREFIX_PAIRING, text.length)
            if (label.size > LABEL_MAX_BYTES) return null

            return Pairing(publicKey = publicKey, label = String(label, Charsets.UTF_8))
        }

        /**
         * Decodes a [Challenge] payload.
         *
         * The expiry is variable-width Base36 and runs to the end, so the length check is a lower
         * bound rather than an equality. A negative expiry is rejected outright: it can only come
         * from a corrupt or hand-made payload, and would read as long expired.
         *
         * @param text the scanned string, prefix included.
         * @return the payload, or null if any field is truncated, unparseable, or the wrong size.
         */
        private fun decodeChallenge(text: String): Challenge? {
            val fingerprintStart = PREFIX_CHARS + nonceChars
            val purposeAt = fingerprintStart + fingerprintChars
            if (text.length <= purposeAt + 1) return malformed(PREFIX_CHALLENGE, text.length)

            val nonce = text.substring(PREFIX_CHARS, fingerprintStart).decodeBase32()
                ?: return malformed(PREFIX_CHALLENGE, text.length)
            if (nonce.size != PairingProtocol.NONCE_BYTES) return null

            val fingerprint = text.substring(fingerprintStart, purposeAt).decodeBase32()
                ?: return malformed(PREFIX_CHALLENGE, text.length)
            if (fingerprint.size != PairedPeer.FINGERPRINT_BYTES) return null

            val purpose = ChallengePurpose.entries.firstOrNull { it.code == text[purposeAt] }
                ?: return malformed(PREFIX_CHALLENGE, text.length)
            val expiry = text.substring(purposeAt + 1).toLongOrNull(RADIX)
                ?: return malformed(PREFIX_CHALLENGE, text.length)
            if (expiry < 0) return null

            return Challenge(
                nonce = nonce,
                requesterFingerprint = fingerprint,
                purpose = purpose,
                expiresAtEpochSeconds = expiry,
            )
        }

        /**
         * Decodes a [Response] payload.
         *
         * Every field here is fixed-width, so the length is checked for equality rather than as a
         * lower bound — a response with anything appended is not one this app produced.
         *
         * @param text the scanned string, prefix included.
         * @return the payload, or null if the length is wrong or either field fails to decode.
         */
        private fun decodeResponse(text: String): Response? {
            val signatureStart = PREFIX_CHARS + fingerprintChars
            if (text.length != signatureStart + signatureChars) {
                return malformed(PREFIX_RESPONSE, text.length)
            }

            val fingerprint = text.substring(PREFIX_CHARS, signatureStart).decodeBase32()
                ?: return malformed(PREFIX_RESPONSE, text.length)
            if (fingerprint.size != PairedPeer.FINGERPRINT_BYTES) return null

            val signature = text.substring(signatureStart).decodeBase32()
                ?: return malformed(PREFIX_RESPONSE, text.length)
            if (signature.size != PeerKeyStore.SIGNATURE_BYTES) return null

            return Response(signerFingerprint = fingerprint, signature = signature)
        }

        /**
         * Truncates a label to a byte budget without splitting a character.
         *
         * Cutting UTF-8 at an arbitrary byte would leave a dangling continuation byte, which comes
         * back from the decoder as a replacement character — so a device named in Turkish, Greek or
         * Japanese would show a corrupt last glyph in the peer list. The loop walks back off any
         * continuation bytes (`10xxxxxx`) to the start of the character being cut.
         *
         * @param maxBytes the budget, normally [LABEL_MAX_BYTES].
         * @return the UTF-8 bytes, no longer than the budget and ending on a character boundary.
         */
        private fun String.truncateUtf8(maxBytes: Int): ByteArray {
            val bytes = toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return bytes
            var end = maxBytes
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return bytes.copyOf(end)
        }

        /**
         * Logs a rejected payload and returns null, so the decoders can bail out in one expression.
         *
         * Traced at DEBUG rather than WARN: the scanner feeds every camera frame through [decode],
         * so partial reads of a code still coming into focus are routine, not faults.
         *
         * @param prefix which payload type failed, for the log line.
         * @param length how long the offending string was. The content is never logged — a
         *   challenge or response is not secret, but logging scanned material by default is a habit
         *   worth not having.
         * @return always null, typed to whatever the caller returns.
         */
        private fun <T> malformed(prefix: String, length: Int): T? {
            KillitLog.d(KillitLog.PAIRING) { "Malformed $prefix payload ($length chars)" }
            return null
        }
    }
}
