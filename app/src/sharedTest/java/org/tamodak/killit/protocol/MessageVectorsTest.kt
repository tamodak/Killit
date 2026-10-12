package org.tamodak.killit.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Envelopes and message bodies (SPEC.md §10, §11), against `messages.json`. */
class MessageVectorsTest {
    private val vectors = Vectors.load("messages.json")
    private val messages = vectors.array("messages").map { it.obj }

    /** Each message's inner, header and signed bytes are what the reference produced, and its signature verifies. */
    @Test
    fun innersMatch() {
        for (message in messages) {
            val name = message.str("name")
            val from = Vectors.phone(message.str("from"))
            val to = Vectors.phone(message.str("to"))
            val inner = decodeInner(message.hex("inner")).ok(name)
            assertEquals(name, MESSAGE_VERSION, inner.header.version)
            assertEquals(name, message.int("kind"), inner.header.kind)
            assertArrayEquals(name, message.hex("messageId"), inner.header.messageId)
            assertArrayEquals(name, message.hex("pairId"), inner.header.pairId)
            assertArrayEquals(name, from.fp, inner.header.senderFp)
            assertArrayEquals(name, to.fp, inner.header.recipientFp)
            assertEquals(name, message.long("createdAt"), inner.header.createdAt)
            assertArrayEquals(name, message.hex("body"), inner.body)
            assertArrayEquals(name, message.hex("inner"), encodeInner(inner.header, inner.body))
            assertArrayEquals(name, message.hex("signedBytes"), messageSignedBytes(message.hex("inner")))
            assertTrue(name, verifyInner(from.signPk, message.hex("inner"), message.hex("signature")))
        }
    }

    /** Known bodies decode to the reference's fields and encode back to the same bytes; unknown kinds are kept. */
    @Test
    fun bodiesMatch() {
        for (message in messages) {
            val name = message.str("name")
            val kind = message.int("kind")
            assertEquals(name, message.bool("known"), Kind.isKnown(kind))
            if (!Kind.isKnown(kind)) {
                assertTrue(name, decodeBody(kind, message.hex("body")) is Decoded.Rejected)
                continue
            }
            val body = decodeBody(kind, message.hex("body")).ok(name)
            assertEquals(name, message.getValue("fields"), body.toJson())
            if (body is MessageBody.Notice && body.notice is NoticeBody.Unknown) continue // kept, never sent
            assertArrayEquals(name, message.hex("body"), encodeBody(body))
        }
    }

    /** Every reference envelope opens with the recipient's key, to the reference's inner and signature. */
    @Test
    fun envelopesOpen() {
        for (message in messages) {
            val name = message.str("name")
            val from = Vectors.phone(message.str("from"))
            val to = Vectors.phone(message.str("to"))
            val opened = openEnvelope(message.hex("envelope"), to.decrypter, to.fp, from.fp).ok(name)
            assertArrayEquals(name, message.hex("inner"), opened.inner)
            assertArrayEquals(name, message.hex("signature"), opened.signature)
            assertArrayEquals(name, message.hex("body"), opened.body)
        }
    }

    /** What Killit seals, Killit opens and verifies: signing, padding and HPKE compose correctly. */
    @Test
    fun sealedMessagesOpen() {
        for (message in messages) {
            val name = message.str("name")
            val from = Vectors.phone(message.str("from"))
            val to = Vectors.phone(message.str("to"))
            val inner = decodeInner(message.hex("inner")).ok(name)
            val envelope = sealMessage(inner.header, inner.body, from.signer, Hpke.encrypter(to.encPk))
            assertEquals(name, ENVELOPE_BYTES, envelope.size)
            val opened = openEnvelope(envelope, to.decrypter, to.fp, from.fp).ok(name)
            assertArrayEquals(name, message.hex("inner"), opened.inner)
            assertTrue(name, verifyInner(from.signPk, opened.inner, opened.signature))
        }
    }

    /** Each tampered envelope fails at the step the vectors name (SPEC.md §10.3). */
    @Test
    fun rejectedEnvelopes() {
        for (element in vectors.array("rejectedEnvelopes")) {
            val case = element.obj
            val name = case.str("name")
            val recipient = Vectors.phone(case.str("recipient"))
            val from = Vectors.phone(case.str("from"))
            val result = openEnvelope(case.hex("envelope"), recipient.decrypter, recipient.fp, from.fp)
            when (case.str("stage")) {
                "open" -> assertTrue(name, result is Decoded.Rejected)
                "signature" -> {
                    val opened = result.ok(name)
                    assertFalse(name, verifyInner(from.signPk, opened.inner, opened.signature))
                }
                else -> throw AssertionError("unknown stage in $name")
            }
        }
    }

    /** Malformed bodies are refused. */
    @Test
    fun rejectedBodies() {
        for (element in vectors.array("rejectedBodies")) {
            val case = element.obj
            assertTrue(case.str("name"), decodeBody(case.int("kind"), case.hex("body")) is Decoded.Rejected)
        }
    }

    /** A DECISION verifies only against the very request it answers (SPEC.md §11.6). */
    @Test
    fun decisionChecks() {
        for (element in vectors.array("decisionChecks")) {
            val case = element.obj
            val name = case.str("name")
            val message = messages.first { it.str("name") == case.str("message") }
            val inner = decodeInner(message.hex("inner")).ok(name)
            val opened = OpenedMessage(message.hex("inner"), message.hex("signature"), inner.header, inner.body)
            val stored = case.obj("storedRequest")
            val request = StoredRequest(
                requestId = stored.hex("requestId"),
                target = stored.obj("target").toDecisionTarget(),
                pairId = stored.hex("pairId"),
                guardianFp = stored.hex("guardianFp"),
                guardianSignPk = Vectors.phone(stored.str("guardian")).signPk,
                ownFp = stored.hex("ownFp"),
            )
            val result = verifyDecision(request, opened)
            assertEquals(name, case.bool("valid"), result is Decoded.Ok)
            if (result is Decoded.Ok) {
                val fields = message.obj("fields")
                assertEquals(name, fields.int("decision"), result.value.decision.code)
                assertEquals(name, fields.str("note"), result.value.note)
            }
        }
    }

    /** Encoders refuse what decoders would refuse, so a phone never signs a body its peer rejects. */
    @Test
    fun encodersRefuseInvalidBodies() {
        val invalid = listOf(
            MessageBody.PairOffer(Role.GUARDIAN, "Mom", "", appManagement = true),
            MessageBody.PairAnswer(Answer.ACCEPT, DeclineReason.USER, "Ali", "", appManagement = false),
            MessageBody.PairAnswer(Answer.DECLINE, DeclineReason.USER, "Ali", "", appManagement = false),
            MessageBody.Unlock(ByteArray(16), ByteArray(32), "", guardianCount = 0),
            MessageBody.Status(ByteArray(16), RequestKind.UNLOCK, Outcome.OPEN, CancelReason.WITHDRAWN, emptyList()),
            MessageBody.Notice(NoticeBody.Unknown(0x0100, byteArrayOf(1))),
            MessageBody.Decision(ByteArray(16), DecisionTarget.Session(ByteArray(32)), Verdict.DENY, " padded"),
        )
        for (body in invalid) {
            assertTrue(body::class.simpleName!!, runCatching { encodeBody(body) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun JsonObject.toDecisionTarget(): DecisionTarget = when (int("requestKind")) {
        Kind.UNBLOCK_APP -> DecisionTarget.App(AppRef(obj("app").str("packageName"), obj("app").hex("certDigest")))
        Kind.UNLOCK -> DecisionTarget.Session(hex("sessionNonce"))
        Kind.UNPAIR -> DecisionTarget.Pairing(hex("targetPairId"))
        else -> throw AssertionError("not a request kind")
    }
}

// ------------------------------------------------------------------------------------------------
// Decoded bodies rendered the way the reference's vector generator writes them: field names as in
// SPEC.md §11, byte strings as lowercase hex, enumerations as their codes.

private fun ByteArray.json() = JsonPrimitive(toHex())

private fun AppRef.toJson() = buildJsonObject {
    put("packageName", packageName)
    put("certDigest", certDigest.json())
}

private fun AppInfo.toJson() = buildJsonObject {
    put("packageName", packageName)
    put("certDigest", certDigest.json())
    put("label", label)
    put("installer", installer)
    put("firstInstalled", firstInstalled)
    put("icon", icon?.json() ?: JsonNull)
}

private fun DecisionTarget.toJson() = buildJsonObject {
    put("requestKind", requestKind.code)
    when (this@toJson) {
        is DecisionTarget.App -> put("app", app.toJson())
        is DecisionTarget.Session -> put("sessionNonce", sessionNonce.json())
        is DecisionTarget.Pairing -> put("targetPairId", targetPairId.json())
    }
}

private fun NoticeBody.toJson() = buildJsonObject {
    put("code", code)
    when (val notice = this@toJson) {
        is NoticeBody.AppInstalled -> {
            put("app", notice.app.toJson())
            put("blocked", notice.blocked)
        }
        is NoticeBody.GuardianAdded -> {
            put("guardianFp", notice.guardianFp.json())
            put("guardianName", notice.guardianName)
        }
        is NoticeBody.GuardianRemoved -> {
            put("guardianFp", notice.guardianFp.json())
            put("guardianName", notice.guardianName)
            put("how", notice.how.code)
        }
        is NoticeBody.ReleaseStarted -> put("completesAt", notice.completesAt)
        NoticeBody.ReleaseCancelled -> Unit
        is NoticeBody.ProtectionChanged -> {
            put("option", notice.option.code)
            put("enabled", notice.enabled)
        }
        is NoticeBody.ManagementChanged -> put("enabled", notice.enabled)
        is NoticeBody.Unknown -> {
            put("unknown", true)
            put("params", notice.params.json())
        }
    }
}

private fun MessageBody.toJson(): JsonElement = buildJsonObject {
    put("kind", kind)
    when (val body = this@toJson) {
        is MessageBody.PairOffer -> {
            put("senderRole", body.senderRole.code)
            put("selfName", body.selfName)
            put("deviceModel", body.deviceModel)
            put("appManagement", body.appManagement)
        }
        is MessageBody.PairAnswer -> {
            put("answer", body.answer.code)
            put("reason", body.reason.code)
            put("selfName", body.selfName)
            put("deviceModel", body.deviceModel)
            put("appManagement", body.appManagement)
        }
        is MessageBody.UnblockApp -> {
            put("requestId", body.requestId.json())
            put("app", body.app.toJson())
            put("explanation", body.explanation)
            put("chancesLeft", body.chancesLeft)
            put("guardianCount", body.guardianCount)
        }
        is MessageBody.Unlock -> {
            put("requestId", body.requestId.json())
            put("sessionNonce", body.sessionNonce.json())
            put("explanation", body.explanation)
            put("guardianCount", body.guardianCount)
        }
        is MessageBody.Unpair -> {
            put("requestId", body.requestId.json())
            put("targetPairId", body.targetPairId.json())
            put("targetFp", body.targetFp.json())
            put("explanation", body.explanation)
            put("chancesLeft", body.chancesLeft)
            put("guardianCount", body.guardianCount)
            put("timerEndsAt", body.timerEndsAt)
        }
        is MessageBody.Decision -> {
            put("requestId", body.requestId.json())
            put("target", body.target.toJson())
            put("decision", body.decision.code)
            put("note", body.note)
        }
        is MessageBody.Status -> {
            put("requestId", body.requestId.json())
            put("requestKind", body.requestKind.code)
            put("outcome", body.outcome.code)
            put("reason", body.reason.code)
            put(
                "guardians",
                JsonArray(
                    body.guardians.map { entry ->
                        buildJsonObject {
                            put("guardianFp", entry.guardianFp.json())
                            put("guardianName", entry.guardianName)
                            put("state", entry.state.code)
                        }
                    },
                ),
            )
        }
        MessageBody.Leave -> Unit
        is MessageBody.Unpaired -> put("how", body.how.code)
        is MessageBody.Notice -> put("notice", body.notice.toJson())
    }
}
