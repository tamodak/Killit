package org.tamodak.killit.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            MessageBody.AppList(ByteArray(16), page = 1, pageCount = 2, apps = emptyList()),
            MessageBody.AppList(ByteArray(16), page = 2, pageCount = 2, apps = emptyList()),
            MessageBody.Manage(seq = 0, entries = listOf(ManageEntry(AppRef("com.example", ByteArray(32)), Desired.BLOCKED))),
            MessageBody.Manage(seq = 1, entries = emptyList()),
            MessageBody.Manage(
                seq = 1,
                entries = List(2) { ManageEntry(AppRef("com.example", ByteArray(32)), Desired.BLOCKED) },
            ),
            MessageBody.MoveAnswer(ByteArray(16), MoveAnswerCode.READY, MoveDeclineReason.USER),
            MessageBody.MoveAnswer(ByteArray(16), MoveAnswerCode.DECLINE, MoveDeclineReason.NONE),
            MessageBody.MoveState(ByteArray(16), page = 0, pageCount = 1, appManagement = false, protection = 0x40, emptyList(), emptyList()),
        )
        for (body in invalid) {
            assertTrue(body::class.simpleName!!, runCatching { encodeBody(body) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    /** Each case puts APP_LIST pages back together to the reference's outcome (SPEC.md §11.10). */
    @Test
    fun listAssemblies() {
        for (element in vectors.array("listAssemblies")) {
            val case = element.obj
            val name = case.str("name")
            val pages = case.array("pages").map { decodeBody(Kind.APP_LIST, it.jsonPrimitive.content.fromHex()).ok(name) as MessageBody.AppList }
            val result = assembleAppList(pages)
            assertEquals(name, case.str("outcome"), result.outcome())
            if (result is Assembly.Complete) {
                assertEquals(name, case.array("packages").map { it.jsonPrimitive.content }, result.value.map { it.packageName })
            }
        }
    }

    /** Each case puts MOVE_STATE pages back together to the reference's outcome (SPEC.md §11.14). */
    @Test
    fun moveAssemblies() {
        for (element in vectors.array("moveAssemblies")) {
            val case = element.obj
            val name = case.str("name")
            val pages = case.array("pages").map { decodeBody(Kind.MOVE_STATE, it.jsonPrimitive.content.fromHex()).ok(name) as MessageBody.MoveState }
            val result = assembleMoveState(pages, Vectors.phone(case.str("newPhone")).fp, Vectors.phone(case.str("oldPhone")).fp)
            assertEquals(name, case.str("outcome"), result.outcome())
            if (result is Assembly.Complete) {
                assertEquals(name, case.array("pairIds").map { it.jsonPrimitive.content }, result.value.pairings.map { it.pairId.toHex() })
                assertEquals(name, case.array("packages").map { it.jsonPrimitive.content }, result.value.apps.map { it.app.packageName })
            }
        }
    }

    /** What packing produces always fits, and comes back whole. */
    @Test
    fun pagingRoundTrips() {
        val apps = List(60) { i ->
            ListedApp("org.example.app${59 - i}", ByteArray(32) { i.toByte() }, "App $i", "com.android.vending", 0L, AppState.ALLOWED, ByteArray(LIST_ICON_BYTES))
        }
        val pages = pageAppList(ByteArray(16), apps)
        assertTrue(pages.size > 1)
        pages.forEach { assertTrue(encodeBody(it).size <= MAX_BODY_BYTES) }
        val assembled = assembleAppList(pages.map { decodeBody(Kind.APP_LIST, encodeBody(it)).ok() as MessageBody.AppList })
        assertEquals(apps.map { it.packageName }.sorted(), (assembled as Assembly.Complete).value.map { it.packageName })

        val b = Vectors.phone("B")
        val bundle = decodeBundle(b.bundle).ok()
        val pairings = List(40) { i ->
            MovedPairing(ByteArray(32) { i.toByte() }, Role.GUARDIAN, bundle, "Ali", "Ali", "", appManagement = false, unpairChances = 0, createdAt = 0L)
        }
        val state = MovedState(ByteArray(16), appManagement = false, protection = 0, pairings = pairings, apps = emptyList())
        val movePages = pageMoveState(state)
        assertTrue(movePages.size > 1)
        movePages.forEach { assertTrue(encodeBody(it).size <= MAX_BODY_BYTES) }
        val moved = assembleMoveState(movePages, Vectors.phone("D").fp, Vectors.phone("A").fp)
        assertEquals(pairings.map { it.pairId.toHex() }, (moved as Assembly.Complete).value.pairings.map { it.pairId.toHex() })
    }

    private fun Assembly<*>.outcome(): String = when (this) {
        is Assembly.Complete -> "complete"
        is Assembly.Incomplete -> "incomplete"
        is Assembly.Invalid -> "invalid"
    }

    private fun JsonObject.toDecisionTarget(): DecisionTarget = when (val kind = int("requestKind")) {
        Kind.UNBLOCK_APP, Kind.GUARDIAN_UNBLOCK -> DecisionTarget.App(
            AppRef(obj("app").str("packageName"), obj("app").hex("certDigest")),
            RequestKind.entries.first { it.code == kind },
        )
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

private fun Bundle.toJson() = buildJsonObject {
    put("signPk", signPk.json())
    put("encPk", encPk.json())
    put("encSig", encSig.json())
}

private fun ListedApp.toJson() = buildJsonObject {
    put("packageName", packageName)
    put("certDigest", certDigest.json())
    put("label", label)
    put("installer", installer)
    put("firstInstalled", firstInstalled)
    put("state", state.code)
    put("icon", icon?.json() ?: JsonNull)
}

private fun MovedPairing.toJson() = buildJsonObject {
    put("pairId", pairId.json())
    put("role", role.code)
    put("peer", peer.toJson())
    put("peerName", peerName)
    put("nickname", nickname)
    put("peerModel", peerModel)
    put("appManagement", appManagement)
    put("unpairChances", unpairChances)
    put("createdAt", createdAt)
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
        is NoticeBody.AppManaged -> {
            put("app", notice.app.toJson())
            put("label", notice.label)
            put("change", notice.change.code)
            put("guardianFp", notice.guardianFp.json())
            put("guardianName", notice.guardianName)
        }
        is NoticeBody.GuardianMoved -> {
            put("guardianFp", notice.guardianFp.json())
            put("guardianName", notice.guardianName)
        }
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
        is MessageBody.ListRequest -> put("listId", body.listId.json())
        is MessageBody.AppList -> {
            put("listId", body.listId.json())
            put("page", body.page)
            put("pageCount", body.pageCount)
            put("apps", JsonArray(body.apps.map { it.toJson() }))
        }
        is MessageBody.Manage -> {
            put("seq", body.seq)
            put(
                "entries",
                JsonArray(
                    body.entries.map { entry ->
                        buildJsonObject {
                            put("packageName", entry.app.packageName)
                            put("certDigest", entry.app.certDigest.json())
                            put("desired", entry.desired.code)
                        }
                    },
                ),
            )
        }
        is MessageBody.GuardianUnblock -> {
            put("requestId", body.requestId.json())
            put("app", body.app.toJson())
            put("proposerFp", body.proposerFp.json())
            put("proposerName", body.proposerName)
            put("guardianCount", body.guardianCount)
        }
        is MessageBody.Handover -> {
            put("newBundle", body.newBundle.toJson())
            put("deviceModel", body.deviceModel)
        }
        is MessageBody.MoveRequest -> {
            put("deviceModel", body.deviceModel)
            put("deviceOwner", body.deviceOwner)
        }
        is MessageBody.MoveState -> {
            put("moveId", body.moveId.json())
            put("page", body.page)
            put("pageCount", body.pageCount)
            put("appManagement", body.appManagement)
            put("protection", body.protection)
            put("pairings", JsonArray(body.pairings.map { it.toJson() }))
            put(
                "apps",
                JsonArray(
                    body.apps.map { app ->
                        buildJsonObject {
                            put("packageName", app.app.packageName)
                            put("certDigest", app.app.certDigest.json())
                            put("state", app.state.code)
                            put("chancesLeft", app.chancesLeft)
                        }
                    },
                ),
            )
        }
        is MessageBody.MoveAnswer -> {
            put("moveId", body.moveId.json())
            put("answer", body.answer.code)
            put("reason", body.reason.code)
        }
        is MessageBody.MoveDone -> {
            put("moveId", body.moveId.json())
            put("outcome", body.outcome.code)
        }
    }
}
