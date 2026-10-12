package org.tamodak.killit.protocol

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import java.security.GeneralSecurityException

/*
 * End-to-end envelopes (SPEC.md §10): sign, pad, encrypt; and the reverse.
 *
 * Sign-then-encrypt: the signature sits inside the ciphertext, so the server can't see who
 * signed, and the signed bytes name both the sender and the recipient, so a recipient can't
 * re-encrypt a message and pass it off as sent to someone else. Every plaintext pads to one size,
 * so the server learns nothing from lengths either.
 */

/** The only message version this implementation reads or writes. */
const val MESSAGE_VERSION = 1

/** Length of a message id. */
const val MESSAGE_ID_BYTES = 16

/** `version ‖ kind ‖ messageId ‖ pairId ‖ senderFp ‖ recipientFp ‖ createdAt`. */
const val HEADER_BYTES = 1 + 1 + MESSAGE_ID_BYTES + PAIR_ID_BYTES + FINGERPRINT_BYTES + FINGERPRINT_BYTES + 8

/** The largest inner that fits beside its length prefix and signature. */
const val MAX_INNER_BYTES = PLAINTEXT_BYTES - 2 - SIGNATURE_BYTES

/** The largest body that fits behind the header. */
const val MAX_BODY_BYTES = MAX_INNER_BYTES - HEADER_BYTES

/**
 * The fixed header of every inner message (SPEC.md §10.2).
 *
 * @param kind any `u8`: an unknown kind is kept, not refused (SPEC.md §10.3 step 9).
 * @param createdAt the sender's clock, in milliseconds since the epoch.
 */
class MessageHeader(
    val version: Int,
    val kind: Int,
    val messageId: ByteArray,
    val pairId: ByteArray,
    val senderFp: ByteArray,
    val recipientFp: ByteArray,
    val createdAt: Long,
)

/** An inner message split into its header and its still-encoded body. */
class Inner(val header: MessageHeader, val body: ByteArray)

/** Encodes `inner = header ‖ body`. */
fun encodeInner(header: MessageHeader, body: ByteArray): ByteArray {
    require(body.size <= MAX_BODY_BYTES) { "message body too long" }
    return ByteWriter()
        .u8(header.version)
        .u8(header.kind)
        .fixed(header.messageId, MESSAGE_ID_BYTES)
        .fixed(header.pairId, PAIR_ID_BYTES)
        .fixed(header.senderFp, FINGERPRINT_BYTES)
        .fixed(header.recipientFp, FINGERPRINT_BYTES)
        .u64(header.createdAt)
        .raw(body)
        .finish()
}

/**
 * Splits an inner message into header and body. Any version and kind are returned as they are:
 * deciding what an unknown one means is the caller's job (SPEC.md §10.3 step 9).
 */
fun decodeInner(inner: ByteArray): Decoded<Inner> = decodeWith {
    if (inner.size < HEADER_BYTES || inner.size > MAX_INNER_BYTES) throw DecodeException("inner length out of range")
    val reader = ByteReader(inner)
    val header = MessageHeader(
        version = reader.u8(),
        kind = reader.u8(),
        messageId = reader.fixed(MESSAGE_ID_BYTES),
        pairId = reader.fixed(PAIR_ID_BYTES),
        senderFp = reader.fixed(FINGERPRINT_BYTES),
        recipientFp = reader.fixed(FINGERPRINT_BYTES),
        createdAt = reader.u64(),
    )
    Inner(header, reader.rest())
}

/** The bytes a message signature covers: `label("KILLIT-MSG-v1") ‖ inner`. */
fun messageSignedBytes(inner: ByteArray): ByteArray = concat(Labels.MESSAGE, inner)

/** Signs an inner message with the sender's identity key. */
fun signInner(signer: Signer, inner: ByteArray): ByteArray = signer.sign(messageSignedBytes(inner))

/** True when [signature] covers [inner] under the sender's [signPk]. */
fun verifyInner(signPk: ByteArray, inner: ByteArray, signature: ByteArray): Boolean =
    P256.verify(signPk, messageSignedBytes(inner), signature)

/** Builds the padded plaintext: `u16 innerLength ‖ inner ‖ signature ‖ zeros` to 8192 bytes. */
fun padPlaintext(inner: ByteArray, signature: ByteArray): ByteArray {
    require(inner.size in HEADER_BYTES..MAX_INNER_BYTES) { "inner length out of range" }
    require(signature.size == SIGNATURE_BYTES) { "signature must be 64 bytes" }
    val plaintext = ByteArray(PLAINTEXT_BYTES)
    u16(inner.size).copyInto(plaintext, 0)
    inner.copyInto(plaintext, 2)
    signature.copyInto(plaintext, 2 + inner.size)
    return plaintext
}

/** A padded plaintext split back into the inner message and its signature. */
class Unpadded(val inner: ByteArray, val signature: ByteArray)

/** Splits a padded plaintext back into inner and signature, refusing non-zero padding. */
fun unpadPlaintext(plaintext: ByteArray): Decoded<Unpadded> = decodeWith {
    if (plaintext.size != PLAINTEXT_BYTES) throw DecodeException("plaintext must be 8192 bytes")
    val reader = ByteReader(plaintext)
    val length = reader.u16()
    if (length < HEADER_BYTES || length > MAX_INNER_BYTES) throw DecodeException("inner length out of range")
    val inner = reader.fixed(length)
    val signature = reader.fixed(SIGNATURE_BYTES)
    if (reader.rest().any { it != 0.toByte() }) throw DecodeException("non-zero padding")
    Unpadded(inner, signature)
}

/** The HPKE `info`: `label("KILLIT-ENV-v1") ‖ senderFp ‖ recipientFp`. */
fun envelopeInfo(senderFp: ByteArray, recipientFp: ByteArray): ByteArray =
    ByteWriter()
        .raw(Labels.ENVELOPE)
        .fixed(senderFp, FINGERPRINT_BYTES)
        .fixed(recipientFp, FINGERPRINT_BYTES)
        .finish()

/**
 * Signs, pads and encrypts a message (SPEC.md §10.1).
 *
 * @param recipient an encrypter for the recipient's verified `encPk` ([Hpke.encrypter]).
 * @throws GeneralSecurityException when signing or encryption fails, as for a peer key that is a
 *   small-order point; the message is then simply not sent.
 */
fun sealMessage(header: MessageHeader, body: ByteArray, signer: Signer, recipient: HybridEncrypt): ByteArray {
    val inner = encodeInner(header, body)
    val plaintext = padPlaintext(inner, signInner(signer, inner))
    val envelope = recipient.encrypt(plaintext, envelopeInfo(header.senderFp, header.recipientFp))
    check(envelope.size == ENVELOPE_BYTES) { "the HPKE suite produced an envelope of ${envelope.size} bytes" }
    return envelope
}

/** A message that decrypted, unpadded, and whose header names the right parties. Not yet verified. */
class OpenedMessage(val inner: ByteArray, val signature: ByteArray, val header: MessageHeader, val body: ByteArray)

/**
 * Steps 1–4 of SPEC.md §10.3: size, HPKE-Open, padding, and a header whose sender is [fromFp] and
 * whose recipient is this phone. The signature, pair, replay and kind checks need the phone's own
 * state and come after this.
 *
 * @param decrypter this phone's HPKE key ([Hpke.decrypter]).
 * @param fromFp the sender the server named; it is also bound into the HPKE `info`, so an envelope
 *   the server misattributes doesn't even decrypt.
 */
fun openEnvelope(envelope: ByteArray, decrypter: HybridDecrypt, ownFp: ByteArray, fromFp: ByteArray): Decoded<OpenedMessage> =
    decodeWith {
        if (envelope.size != ENVELOPE_BYTES) throw DecodeException("envelope must be 8240 bytes")
        val plaintext = try {
            decrypter.decrypt(envelope, envelopeInfo(fromFp, ownFp))
        } catch (e: GeneralSecurityException) {
            throw DecodeException("HPKE authentication failed")
        }
        val unpadded = unpadPlaintext(plaintext).valueOrThrow()
        val inner = decodeInner(unpadded.inner).valueOrThrow()
        if (!inner.header.senderFp.contentEquals(fromFp)) throw DecodeException("sender does not match")
        if (!inner.header.recipientFp.contentEquals(ownFp)) throw DecodeException("recipient does not match")
        OpenedMessage(unpadded.inner, unpadded.signature, inner.header, inner.body)
    }
