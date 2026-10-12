package org.tamodak.killit.protocol

/*
 * Message bodies (SPEC.md §11): one strict encoder and decoder per kind, and the DECISION check
 * that rebuilds signed bytes from the protected phone's own stored request (SPEC.md §11.6).
 */

/** Message kinds (SPEC.md §11). A header's kind is any `u8`; these are the ones this version knows. */
object Kind {
    const val PAIR_OFFER = 0x01
    const val PAIR_ANSWER = 0x02
    const val UNBLOCK_APP = 0x10
    const val UNLOCK = 0x11
    const val UNPAIR = 0x12
    const val DECISION = 0x13
    const val STATUS = 0x14
    const val LEAVE = 0x15
    const val UNPAIRED = 0x16
    const val NOTICE = 0x20

    /** True for a kind this version understands. Anything else is kept and shown as "Update Killit". */
    fun isKnown(kind: Int): Boolean = allowedSender(kind) != null

    /**
     * Who may send a kind, by role in the pairing the header names (SPEC.md §10.3 step 8); null
     * for a kind this version doesn't know.
     */
    fun allowedSender(kind: Int): Sender? = when (kind) {
        PAIR_OFFER -> Sender.CREATOR
        PAIR_ANSWER -> Sender.JOINER
        UNBLOCK_APP, UNLOCK, UNPAIR, STATUS, UNPAIRED -> Sender.PROTECTED
        DECISION, LEAVE -> Sender.GUARDIAN
        NOTICE -> Sender.EITHER
        else -> null
    }
}

/** The side of a pairing a kind may come from. The ceremony kinds go by who created the invite. */
enum class Sender { CREATOR, JOINER, GUARDIAN, PROTECTED, EITHER }

/** An enumeration with a fixed wire code. Names may change freely; codes never do. */
interface WireCode {
    val code: Int
}

/** A phone's role in a pairing. */
enum class Role(override val code: Int) : WireCode { GUARDIAN(1), PROTECTED(2) }

/** A PAIR_ANSWER's answer. */
enum class Answer(override val code: Int) : WireCode { ACCEPT(1), DECLINE(2) }

/** Why a PAIR_ANSWER declines; [NONE] exactly when it accepts. */
enum class DeclineReason(override val code: Int) : WireCode { NONE(0), USER(1), ROLE_CONFLICT(2) }

/** A guardian's verdict. */
enum class Verdict(override val code: Int) : WireCode { APPROVE(1), DENY(2) }

/** Where a request stands. */
enum class Outcome(override val code: Int) : WireCode { OPEN(0), APPROVED(1), DENIED(2), CANCELLED(3) }

/** Why a request was cancelled; [NONE] exactly when it wasn't. */
enum class CancelReason(override val code: Int) : WireCode {
    NONE(0),
    WITHDRAWN(1),
    APP_UNINSTALLED(2),
    RESOLVED_BY_UNLOCK(3),
    GUARDIAN_REMOVED(4),
}

/** One guardian's answer so far, in a STATUS. */
enum class GuardianState(override val code: Int) : WireCode { WAITING(0), APPROVED(1), DENIED(2) }

/** How an UNPAIR ended in the guardian's removal. */
enum class UnpairedHow(override val code: Int) : WireCode { ALL_APPROVED(1), TIMER(2), RELEASED(3) }

/** How a guardian stopped being one, in NOTICE GUARDIAN_REMOVED. */
enum class RemovedHow(override val code: Int) : WireCode { LEFT(1), UNPAIRED(2) }

/** The protection options of NOTICE PROTECTION_CHANGED, in `HardeningConfig` order. */
enum class ProtectionOption(override val code: Int) : WireCode {
    UNINSTALL(1),
    FORCE_STOP(2),
    SAFE_BOOT(3),
    FACTORY_RESET(4),
    APPS_CONTROL(5),
    DATE_TIME(6),
}

/** The requests a guardian decides on, by their message kind. */
enum class RequestKind(override val code: Int) : WireCode {
    UNBLOCK_APP(Kind.UNBLOCK_APP),
    UNLOCK(Kind.UNLOCK),
    UNPAIR(Kind.UNPAIR),
}

/** NOTICE codes (SPEC.md §11.9). */
object NoticeCode {
    const val APP_INSTALLED = 0x0001
    const val GUARDIAN_ADDED = 0x0002
    const val GUARDIAN_REMOVED = 0x0003
    const val RELEASE_STARTED = 0x0004
    const val RELEASE_CANCELLED = 0x0005
    const val PROTECTION_CHANGED = 0x0006
    const val MANAGEMENT_CHANGED = 0x0007
}

/** Length of a request id. */
const val REQUEST_ID_BYTES = 16

/** Length of an UNLOCK session nonce. */
const val SESSION_NONCE_BYTES = 32

/** Length of a signing-certificate digest. */
const val CERT_DIGEST_BYTES = 32

/** An app icon is this many pixels on each side. */
const val ICON_SIDE = 32

/** 32 × 32 RGBA8888. */
const val ICON_BYTES = ICON_SIDE * ICON_SIDE * 4

/** The most guardians a request can count. */
const val MAX_GUARDIANS = 50

/** The most entries in one STATUS. */
const val MAX_STATUS_ENTRIES = 40

/** The most chances an UNBLOCK_APP or UNPAIR can have left. */
const val MAX_CHANCES = 3

/** An app, as a request binds it: its package and signing-certificate digest. */
class AppRef(val packageName: String, val certDigest: ByteArray)

/**
 * An app with what a guardian sees of it (PLAN.md §5.4).
 *
 * @param installer the installer's package name, or empty when unknown or sideloaded.
 * @param icon 32 × 32 RGBA8888 pixels, rows top to bottom, not premultiplied; null when there's none.
 */
class AppInfo(
    val packageName: String,
    val certDigest: ByteArray,
    val label: String,
    val installer: String,
    val firstInstalled: Long,
    val icon: ByteArray?,
) {
    /** What a decision on this app binds. */
    val ref: AppRef get() = AppRef(packageName, certDigest)
}

/** What a DECISION binds: the request's own target, by kind (SPEC.md §11.6). */
sealed interface DecisionTarget {
    val requestKind: RequestKind

    /** An UNBLOCK_APP's app, down to its signing certificate. */
    class App(val app: AppRef) : DecisionTarget {
        override val requestKind: RequestKind get() = RequestKind.UNBLOCK_APP
    }

    /** An UNLOCK's session: a decision for a session the phone has left counts for nothing. */
    class Session(val sessionNonce: ByteArray) : DecisionTarget {
        override val requestKind: RequestKind get() = RequestKind.UNLOCK
    }

    /** An UNPAIR's target pairing. */
    class Pairing(val targetPairId: ByteArray) : DecisionTarget {
        override val requestKind: RequestKind get() = RequestKind.UNPAIR
    }
}

/** One guardian in a STATUS. */
class StatusEntry(val guardianFp: ByteArray, val guardianName: String, val state: GuardianState)

/** A NOTICE's body (SPEC.md §11.9). */
sealed interface NoticeBody {
    val code: Int

    class AppInstalled(val app: AppInfo, val blocked: Boolean) : NoticeBody {
        override val code: Int get() = NoticeCode.APP_INSTALLED
    }

    class GuardianAdded(val guardianFp: ByteArray, val guardianName: String) : NoticeBody {
        override val code: Int get() = NoticeCode.GUARDIAN_ADDED
    }

    class GuardianRemoved(val guardianFp: ByteArray, val guardianName: String, val how: RemovedHow) : NoticeBody {
        override val code: Int get() = NoticeCode.GUARDIAN_REMOVED
    }

    class ReleaseStarted(val completesAt: Long) : NoticeBody {
        override val code: Int get() = NoticeCode.RELEASE_STARTED
    }

    data object ReleaseCancelled : NoticeBody {
        override val code: Int get() = NoticeCode.RELEASE_CANCELLED
    }

    class ProtectionChanged(val option: ProtectionOption, val enabled: Boolean) : NoticeBody {
        override val code: Int get() = NoticeCode.PROTECTION_CHANGED
    }

    class ManagementChanged(val enabled: Boolean) : NoticeBody {
        override val code: Int get() = NoticeCode.MANAGEMENT_CHANGED
    }

    /** A code this version doesn't know. Received ones are kept with their parameters unparsed; none is ever sent. */
    class Unknown(override val code: Int, val params: ByteArray) : NoticeBody
}

/** A decoded message body, one class per kind (SPEC.md §11). */
sealed interface MessageBody {
    val kind: Int

    class PairOffer(
        val senderRole: Role,
        val selfName: String,
        val deviceModel: String,
        /** The protected phone's app-management setting (G14); always false from a guardian. */
        val appManagement: Boolean,
    ) : MessageBody {
        override val kind: Int get() = Kind.PAIR_OFFER
    }

    class PairAnswer(
        val answer: Answer,
        val reason: DeclineReason,
        /** Empty in a decline. */
        val selfName: String,
        /** Empty in a decline. */
        val deviceModel: String,
        val appManagement: Boolean,
    ) : MessageBody {
        override val kind: Int get() = Kind.PAIR_ANSWER
    }

    class UnblockApp(
        val requestId: ByteArray,
        val app: AppInfo,
        val explanation: String,
        val chancesLeft: Int,
        val guardianCount: Int,
    ) : MessageBody {
        override val kind: Int get() = Kind.UNBLOCK_APP
    }

    class Unlock(
        val requestId: ByteArray,
        val sessionNonce: ByteArray,
        val explanation: String,
        val guardianCount: Int,
    ) : MessageBody {
        override val kind: Int get() = Kind.UNLOCK
    }

    class Unpair(
        val requestId: ByteArray,
        val targetPairId: ByteArray,
        val targetFp: ByteArray,
        val explanation: String,
        val chancesLeft: Int,
        val guardianCount: Int,
        val timerEndsAt: Long,
    ) : MessageBody {
        override val kind: Int get() = Kind.UNPAIR
    }

    class Decision(
        val requestId: ByteArray,
        val target: DecisionTarget,
        val decision: Verdict,
        val note: String,
    ) : MessageBody {
        override val kind: Int get() = Kind.DECISION
    }

    class Status(
        val requestId: ByteArray,
        val requestKind: RequestKind,
        val outcome: Outcome,
        val reason: CancelReason,
        val guardians: List<StatusEntry>,
    ) : MessageBody {
        override val kind: Int get() = Kind.STATUS
    }

    data object Leave : MessageBody {
        override val kind: Int get() = Kind.LEAVE
    }

    class Unpaired(val how: UnpairedHow) : MessageBody {
        override val kind: Int get() = Kind.UNPAIRED
    }

    class Notice(val notice: NoticeBody) : MessageBody {
        override val kind: Int get() = Kind.NOTICE
    }
}

// ------------------------------------------------------------------------------------------------
// Shared structures

/** Writes an enumeration's code as a `u8`. */
private fun ByteWriter.code(value: WireCode): ByteWriter = u8(value.code)

/** Reads a `u8` and refuses anything but the codes of [allowed]. */
private fun <E : WireCode> ByteReader.oneOf(allowed: Collection<E>, what: String): E {
    val value = u8()
    return allowed.firstOrNull { it.code == value } ?: throw DecodeException("invalid $what")
}

/** Reads a `u8` in `[min, max]`. */
private fun ByteReader.inRange(min: Int, max: Int, what: String): Int {
    val value = u8()
    if (value < min || value > max) throw DecodeException("$what out of range")
    return value
}

private fun ByteWriter.appRef(app: AppRef): ByteWriter = packageName(app.packageName).fixed(app.certDigest, CERT_DIGEST_BYTES)

private fun ByteReader.appRef(): AppRef = AppRef(packageName(), fixed(CERT_DIGEST_BYTES))

private fun ByteWriter.appInfo(app: AppInfo): ByteWriter {
    appRef(app.ref)
    text(app.label, TextRule.APP_LABEL)
    packageName(app.installer, allowEmpty = true)
    u64(app.firstInstalled)
    val icon = app.icon
    return if (icon == null) u8(0) else u8(1).fixed(icon, ICON_BYTES)
}

private fun ByteReader.appInfo(): AppInfo {
    val ref = appRef()
    val label = text(TextRule.APP_LABEL)
    val installer = packageName(allowEmpty = true)
    val firstInstalled = u64()
    val icon = when (u8()) {
        0 -> null
        1 -> fixed(ICON_BYTES)
        else -> throw DecodeException("invalid icon format")
    }
    return AppInfo(ref.packageName, ref.certDigest, label, installer, firstInstalled, icon)
}

private fun checkCount(value: Int, min: Int, max: Int, what: String) {
    require(value in min..max) { "$what out of range" }
}

// ------------------------------------------------------------------------------------------------
// Encoding

/**
 * Encodes a message body (SPEC.md §11).
 *
 * @throws IllegalArgumentException for a value its decoder would refuse. The body is decoded again
 *   before it is returned, so nothing that a receiver would reject is ever signed.
 */
fun encodeBody(body: MessageBody): ByteArray {
    val writer = ByteWriter()
    when (body) {
        is MessageBody.PairOffer -> {
            require(!(body.senderRole == Role.GUARDIAN && body.appManagement)) { "a guardian has no app-management setting" }
            writer.code(body.senderRole)
                .text(body.selfName, TextRule.SELF_NAME)
                .text(body.deviceModel, TextRule.DEVICE_MODEL)
                .bool(body.appManagement)
        }
        is MessageBody.PairAnswer -> {
            val accept = body.answer == Answer.ACCEPT
            require(accept == (body.reason == DeclineReason.NONE)) { "ACCEPT has no reason; DECLINE needs one" }
            require(accept || !body.appManagement) { "a decline carries no app-management setting" }
            writer.code(body.answer).code(body.reason)
                .text(body.selfName, if (accept) TextRule.SELF_NAME else TextRule.EMPTY)
                .text(body.deviceModel, if (accept) TextRule.DEVICE_MODEL else TextRule.EMPTY)
                .bool(body.appManagement)
        }
        is MessageBody.UnblockApp -> {
            checkCount(body.chancesLeft, 1, MAX_CHANCES, "chancesLeft")
            checkCount(body.guardianCount, 1, MAX_GUARDIANS, "guardianCount")
            writer.fixed(body.requestId, REQUEST_ID_BYTES)
                .appInfo(body.app)
                .text(body.explanation, TextRule.REQUIRED_EXPLANATION)
                .u8(body.chancesLeft)
                .u8(body.guardianCount)
        }
        is MessageBody.Unlock -> {
            checkCount(body.guardianCount, 1, MAX_GUARDIANS, "guardianCount")
            writer.fixed(body.requestId, REQUEST_ID_BYTES)
                .fixed(body.sessionNonce, SESSION_NONCE_BYTES)
                .text(body.explanation, TextRule.OPTIONAL_EXPLANATION)
                .u8(body.guardianCount)
        }
        is MessageBody.Unpair -> {
            checkCount(body.chancesLeft, 1, MAX_CHANCES, "chancesLeft")
            checkCount(body.guardianCount, 1, MAX_GUARDIANS, "guardianCount")
            writer.fixed(body.requestId, REQUEST_ID_BYTES)
                .fixed(body.targetPairId, PAIR_ID_BYTES)
                .fixed(body.targetFp, FINGERPRINT_BYTES)
                .text(body.explanation, TextRule.REQUIRED_EXPLANATION)
                .u8(body.chancesLeft)
                .u8(body.guardianCount)
                .u64(body.timerEndsAt)
        }
        is MessageBody.Decision -> {
            writer.fixed(body.requestId, REQUEST_ID_BYTES)
            writer.decisionTarget(body.target)
            writer.code(body.decision).text(body.note, TextRule.NOTE)
        }
        is MessageBody.Status -> {
            require((body.outcome == Outcome.CANCELLED) == (body.reason != CancelReason.NONE)) {
                "only CANCELLED carries a reason, and it always does"
            }
            checkCount(body.guardians.size, 0, MAX_STATUS_ENTRIES, "guardian list")
            writer.fixed(body.requestId, REQUEST_ID_BYTES)
                .code(body.requestKind)
                .code(body.outcome)
                .code(body.reason)
                .u8(body.guardians.size)
            for (entry in body.guardians) {
                writer.fixed(entry.guardianFp, FINGERPRINT_BYTES)
                    .text(entry.guardianName, TextRule.SELF_NAME)
                    .code(entry.state)
            }
        }
        MessageBody.Leave -> Unit
        is MessageBody.Unpaired -> writer.code(body.how)
        is MessageBody.Notice -> writer.notice(body.notice)
    }
    val bytes = writer.finish()
    val check = decodeBody(body.kind, bytes)
    require(check is Decoded.Ok) { "body would be refused: ${(check as Decoded.Rejected).reason}" }
    return bytes
}

private fun ByteWriter.decisionTarget(target: DecisionTarget): ByteWriter {
    code(target.requestKind)
    return when (target) {
        is DecisionTarget.App -> appRef(target.app)
        is DecisionTarget.Session -> fixed(target.sessionNonce, SESSION_NONCE_BYTES)
        is DecisionTarget.Pairing -> fixed(target.targetPairId, PAIR_ID_BYTES)
    }
}

private fun ByteWriter.notice(notice: NoticeBody): ByteWriter {
    require(notice !is NoticeBody.Unknown) { "unknown notices can be received but not sent" }
    u16(notice.code)
    return when (notice) {
        is NoticeBody.AppInstalled -> appInfo(notice.app).bool(notice.blocked)
        is NoticeBody.GuardianAdded -> fixed(notice.guardianFp, FINGERPRINT_BYTES).text(notice.guardianName, TextRule.SELF_NAME)
        is NoticeBody.GuardianRemoved ->
            fixed(notice.guardianFp, FINGERPRINT_BYTES).text(notice.guardianName, TextRule.SELF_NAME).code(notice.how)
        is NoticeBody.ReleaseStarted -> u64(notice.completesAt)
        NoticeBody.ReleaseCancelled -> this
        is NoticeBody.ProtectionChanged -> code(notice.option).bool(notice.enabled)
        is NoticeBody.ManagementChanged -> bool(notice.enabled)
        is NoticeBody.Unknown -> this
    }
}

// ------------------------------------------------------------------------------------------------
// Decoding

/**
 * Decodes the body of a known kind. Every field is validated; trailing bytes are refused. An
 * unknown kind is a rejection here: callers check [Kind.isKnown] first and keep unknown messages
 * for "Update Killit" (SPEC.md §10.3 step 9).
 */
fun decodeBody(kind: Int, bytes: ByteArray): Decoded<MessageBody> = decodeWith {
    val reader = ByteReader(bytes)
    val body = reader.body(kind)
    reader.end()
    body
}

private fun ByteReader.body(kind: Int): MessageBody = when (kind) {
    Kind.PAIR_OFFER -> {
        val senderRole = oneOf(Role.entries, "role")
        val selfName = text(TextRule.SELF_NAME)
        val deviceModel = text(TextRule.DEVICE_MODEL)
        val appManagement = bool()
        if (senderRole == Role.GUARDIAN && appManagement) throw DecodeException("a guardian has no app-management setting")
        MessageBody.PairOffer(senderRole, selfName, deviceModel, appManagement)
    }
    Kind.PAIR_ANSWER -> {
        val answer = oneOf(Answer.entries, "answer")
        val accept = answer == Answer.ACCEPT
        val reason = oneOf(
            if (accept) listOf(DeclineReason.NONE) else listOf(DeclineReason.USER, DeclineReason.ROLE_CONFLICT),
            "decline reason",
        )
        val selfName = text(if (accept) TextRule.SELF_NAME else TextRule.EMPTY)
        val deviceModel = text(if (accept) TextRule.DEVICE_MODEL else TextRule.EMPTY)
        val appManagement = bool()
        if (!accept && appManagement) throw DecodeException("a decline carries no app-management setting")
        MessageBody.PairAnswer(answer, reason, selfName, deviceModel, appManagement)
    }
    Kind.UNBLOCK_APP -> MessageBody.UnblockApp(
        requestId = fixed(REQUEST_ID_BYTES),
        app = appInfo(),
        explanation = text(TextRule.REQUIRED_EXPLANATION),
        chancesLeft = inRange(1, MAX_CHANCES, "chancesLeft"),
        guardianCount = inRange(1, MAX_GUARDIANS, "guardianCount"),
    )
    Kind.UNLOCK -> MessageBody.Unlock(
        requestId = fixed(REQUEST_ID_BYTES),
        sessionNonce = fixed(SESSION_NONCE_BYTES),
        explanation = text(TextRule.OPTIONAL_EXPLANATION),
        guardianCount = inRange(1, MAX_GUARDIANS, "guardianCount"),
    )
    Kind.UNPAIR -> MessageBody.Unpair(
        requestId = fixed(REQUEST_ID_BYTES),
        targetPairId = fixed(PAIR_ID_BYTES),
        targetFp = fixed(FINGERPRINT_BYTES),
        explanation = text(TextRule.REQUIRED_EXPLANATION),
        chancesLeft = inRange(1, MAX_CHANCES, "chancesLeft"),
        guardianCount = inRange(1, MAX_GUARDIANS, "guardianCount"),
        timerEndsAt = u64(),
    )
    Kind.DECISION -> MessageBody.Decision(
        requestId = fixed(REQUEST_ID_BYTES),
        target = decisionTarget(),
        decision = oneOf(Verdict.entries, "decision"),
        note = text(TextRule.NOTE),
    )
    Kind.STATUS -> {
        val requestId = fixed(REQUEST_ID_BYTES)
        val requestKind = oneOf(RequestKind.entries, "request kind")
        val outcome = oneOf(Outcome.entries, "outcome")
        val reason = oneOf(
            if (outcome == Outcome.CANCELLED) CancelReason.entries - CancelReason.NONE else listOf(CancelReason.NONE),
            "cancel reason",
        )
        val count = inRange(0, MAX_STATUS_ENTRIES, "guardian list")
        val guardians = List(count) {
            StatusEntry(
                guardianFp = fixed(FINGERPRINT_BYTES),
                guardianName = text(TextRule.SELF_NAME),
                state = oneOf(GuardianState.entries, "state"),
            )
        }
        MessageBody.Status(requestId, requestKind, outcome, reason, guardians)
    }
    Kind.LEAVE -> MessageBody.Leave
    Kind.UNPAIRED -> MessageBody.Unpaired(oneOf(UnpairedHow.entries, "how"))
    Kind.NOTICE -> MessageBody.Notice(notice())
    else -> throw DecodeException("unknown kind")
}

private fun ByteReader.decisionTarget(): DecisionTarget = when (oneOf(RequestKind.entries, "request kind")) {
    RequestKind.UNBLOCK_APP -> DecisionTarget.App(appRef())
    RequestKind.UNLOCK -> DecisionTarget.Session(fixed(SESSION_NONCE_BYTES))
    RequestKind.UNPAIR -> DecisionTarget.Pairing(fixed(PAIR_ID_BYTES))
}

private fun ByteReader.notice(): NoticeBody = when (val code = u16()) {
    NoticeCode.APP_INSTALLED -> NoticeBody.AppInstalled(appInfo(), bool())
    NoticeCode.GUARDIAN_ADDED -> NoticeBody.GuardianAdded(fixed(FINGERPRINT_BYTES), text(TextRule.SELF_NAME))
    NoticeCode.GUARDIAN_REMOVED ->
        NoticeBody.GuardianRemoved(fixed(FINGERPRINT_BYTES), text(TextRule.SELF_NAME), oneOf(RemovedHow.entries, "how"))
    NoticeCode.RELEASE_STARTED -> NoticeBody.ReleaseStarted(u64())
    NoticeCode.RELEASE_CANCELLED -> NoticeBody.ReleaseCancelled
    NoticeCode.PROTECTION_CHANGED -> NoticeBody.ProtectionChanged(oneOf(ProtectionOption.entries, "protection option"), bool())
    NoticeCode.MANAGEMENT_CHANGED -> NoticeBody.ManagementChanged(bool())
    else -> NoticeBody.Unknown(code, rest())
}

// ------------------------------------------------------------------------------------------------
// DECISION verification (SPEC.md §11.6)

/**
 * What the protected phone stored when it sent a request to one guardian.
 *
 * @param pairId the pairing with the guardian this copy went to.
 * @param guardianSignPk the guardian's identity key, from the pairing.
 * @param ownFp the protected phone's own fingerprint.
 */
class StoredRequest(
    val requestId: ByteArray,
    val target: DecisionTarget,
    val pairId: ByteArray,
    val guardianFp: ByteArray,
    val guardianSignPk: ByteArray,
    val ownFp: ByteArray,
)

/** The part of a verified DECISION the protected phone acts on. */
class VerifiedDecision(val decision: Verdict, val note: String)

/**
 * Verifies a DECISION against the request it claims to answer.
 *
 * Only the verdict and the note are taken from the received message. Everything the guardian
 * agreed to (which request, which app and certificate, which unlock session, which pairing, who
 * signed, for whom) is rebuilt from [stored], so the signature can only verify if the guardian
 * signed exactly that.
 *
 * @param message a message that passed [openEnvelope], so its header parties are already checked.
 * @return the verdict and note, or a rejection when anything differs or the signature fails.
 */
fun verifyDecision(stored: StoredRequest, message: OpenedMessage): Decoded<VerifiedDecision> = decodeWith {
    if (message.header.kind != Kind.DECISION) throw DecodeException("not a decision")
    val received = decodeBody(Kind.DECISION, message.body).valueOrThrow() as MessageBody.Decision
    val rebuiltHeader = MessageHeader(
        version = MESSAGE_VERSION,
        kind = Kind.DECISION,
        messageId = message.header.messageId,
        pairId = stored.pairId,
        senderFp = stored.guardianFp,
        recipientFp = stored.ownFp,
        createdAt = message.header.createdAt,
    )
    val rebuiltBody = encodeBody(MessageBody.Decision(stored.requestId, stored.target, received.decision, received.note))
    val rebuilt = encodeInner(rebuiltHeader, rebuiltBody)
    if (!verifyInner(stored.guardianSignPk, rebuilt, message.signature)) throw DecodeException("decision does not match the request")
    VerifiedDecision(received.decision, received.note)
}
