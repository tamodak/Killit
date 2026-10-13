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
    const val LIST_REQUEST = 0x30
    const val APP_LIST = 0x31
    const val MANAGE = 0x32
    const val GUARDIAN_UNBLOCK = 0x33
    const val HANDOVER = 0x34
    const val MOVE_REQUEST = 0x35
    const val MOVE_STATE = 0x36
    const val MOVE_ANSWER = 0x37
    const val MOVE_DONE = 0x38

    /** True for a kind this version understands. Anything else is kept and shown as "Update Killit". */
    fun isKnown(kind: Int): Boolean = allowedSender(kind) != null

    /**
     * Who may send a kind (SPEC.md §10.3 step 8): by role in the pairing the header names, or, for
     * the ceremony kinds, by part in the ceremony. Null for a kind this version doesn't know.
     */
    fun allowedSender(kind: Int): Sender? = when (kind) {
        PAIR_OFFER, MOVE_REQUEST, MOVE_ANSWER -> Sender.CREATOR
        PAIR_ANSWER, MOVE_STATE, MOVE_DONE -> Sender.JOINER
        UNBLOCK_APP, UNLOCK, UNPAIR, STATUS, UNPAIRED, APP_LIST, GUARDIAN_UNBLOCK -> Sender.PROTECTED
        DECISION, LEAVE, LIST_REQUEST, MANAGE -> Sender.GUARDIAN
        NOTICE, HANDOVER -> Sender.EITHER
        else -> null
    }

    /**
     * True for the kinds two phones exchange in a ceremony, before any pairing exists between
     * them: their header names the ceremony's pair id (SPEC.md §10.3 step 5).
     */
    fun isCeremonyKind(kind: Int): Boolean = allowedSender(kind).let { it == Sender.CREATOR || it == Sender.JOINER }
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
    GUARDIAN_UNBLOCK(Kind.GUARDIAN_UNBLOCK),
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
    const val APP_MANAGED = 0x0008
    const val GUARDIAN_MOVED = 0x0009
}

/** An app's state in an APP_LIST or a MOVE_STATE (SPEC.md §11.10, §11.14). */
enum class AppState(override val code: Int) : WireCode {
    ALLOWED(1),
    BLOCKED(2),

    /** Blocked by default-blocking; nobody has decided about it yet. */
    NEW(3),
}

/** What a MANAGE asks for one app (SPEC.md §11.11). */
enum class Desired(override val code: Int) : WireCode { ALLOWED(1), BLOCKED(2) }

/** What NOTICE APP_MANAGED reports. [REFUSED]: Android wouldn't suspend the package. */
enum class AppChange(override val code: Int) : WireCode { BLOCKED(1), UNBLOCKED(2), REFUSED(3) }

/** A MOVE_ANSWER's answer (SPEC.md §11.14). */
enum class MoveAnswerCode(override val code: Int) : WireCode { READY(1), DECLINE(2) }

/** Why a MOVE_ANSWER declines; [NONE] exactly when it is READY. */
enum class MoveDeclineReason(override val code: Int) : WireCode {
    NONE(0),
    USER(1),
    NOT_DEVICE_OWNER(2),
    HAS_PAIRINGS(3),
    INVALID(4),
}

/** How a move ended, from the old phone's side. */
enum class MoveOutcome(override val code: Int) : WireCode { COMMITTED(1), ABANDONED(2) }

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

/** An APP_LIST icon is this many pixels on each side (SPEC.md §11.10). */
const val LIST_ICON_SIDE = 16

/** 16 × 16 RGBA8888. */
const val LIST_ICON_BYTES = LIST_ICON_SIDE * LIST_ICON_SIDE * 4

/** The `iconFormat` codes: a request's 32 × 32 icon, and a list's 16 × 16 one. */
const val ICON_FORMAT_REQUEST = 1
const val ICON_FORMAT_LIST = 2

/** Length of an APP_LIST's list id and of a move's move id. */
const val LIST_ID_BYTES = 16
const val MOVE_ID_BYTES = 16

/** Apps in one MANAGE: 25 of the longest still fit in one body. */
const val MAX_MANAGE_APPS = 25

/** The longest app list, and the most pairings and apps one move carries (SPEC.md §13). */
const val MAX_LISTED_APPS = 1000
const val MAX_MOVED_PAIRINGS = 100
const val MAX_MOVED_APPS = 4000

/** The protection options are bits 0–5 of MOVE_STATE's `protection`, bit (option − 1) each. */
const val PROTECTION_BITS = 0b0011_1111

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

/**
 * One app in an APP_LIST (SPEC.md §11.10).
 *
 * @param installer the installer's package name, or empty when unknown or sideloaded.
 * @param icon 16 × 16 RGBA8888 pixels, rows top to bottom, not premultiplied; null when there's none.
 */
class ListedApp(
    val packageName: String,
    val certDigest: ByteArray,
    val label: String,
    val installer: String,
    val firstInstalled: Long,
    val state: AppState,
    val icon: ByteArray?,
) {
    val ref: AppRef get() = AppRef(packageName, certDigest)
}

/** One app a MANAGE changes, and what it should be (SPEC.md §11.11). */
class ManageEntry(val app: AppRef, val desired: Desired)

/**
 * One pairing a move hands over, as the old phone holds it (SPEC.md §11.14).
 *
 * @param role the old phone's side of the pairing.
 * @param appManagement the peer's setting when the old phone guards it; always false otherwise.
 * @param unpairChances the old phone's chances to remove this guardian (G8); always 0 when the old
 *   phone is the guardian.
 */
class MovedPairing(
    val pairId: ByteArray,
    val role: Role,
    val peer: Bundle,
    val peerName: String,
    val nickname: String,
    val peerModel: String,
    val appManagement: Boolean,
    val unpairChances: Int,
    val createdAt: Long,
)

/** One decision about an app a move hands over, with the chances the app has left (D4). */
class MovedApp(val app: AppRef, val state: AppState, val chancesLeft: Int)

/** What a DECISION binds: the request's own target, by kind (SPEC.md §11.6). */
sealed interface DecisionTarget {
    val requestKind: RequestKind

    /**
     * The app of an UNBLOCK_APP or a GUARDIAN_UNBLOCK, down to its signing certificate. The kind
     * is part of what is signed, so an approval of one can't count for the other.
     */
    class App(val app: AppRef, override val requestKind: RequestKind = RequestKind.UNBLOCK_APP) : DecisionTarget {
        init {
            require(requestKind == RequestKind.UNBLOCK_APP || requestKind == RequestKind.GUARDIAN_UNBLOCK) { "not a request about an app" }
        }
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

    /** A guardian's MANAGE changed an app; [guardianName] is that guardian's own name. */
    class AppManaged(
        val app: AppRef,
        val label: String,
        val change: AppChange,
        val guardianFp: ByteArray,
        val guardianName: String,
    ) : NoticeBody {
        override val code: Int get() = NoticeCode.APP_MANAGED
    }

    /** One of the other guardians moved to a new phone, now called [guardianFp] (B6). */
    class GuardianMoved(val guardianFp: ByteArray, val guardianName: String) : NoticeBody {
        override val code: Int get() = NoticeCode.GUARDIAN_MOVED
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

    class ListRequest(val listId: ByteArray) : MessageBody {
        override val kind: Int get() = Kind.LIST_REQUEST
    }

    /** One page of the list that answers a LIST_REQUEST. */
    class AppList(val listId: ByteArray, val page: Int, val pageCount: Int, val apps: List<ListedApp>) : MessageBody {
        override val kind: Int get() = Kind.APP_LIST
    }

    class Manage(val seq: Long, val entries: List<ManageEntry>) : MessageBody {
        override val kind: Int get() = Kind.MANAGE
    }

    class GuardianUnblock(
        val requestId: ByteArray,
        val app: AppInfo,
        val proposerFp: ByteArray,
        val proposerName: String,
        val guardianCount: Int,
    ) : MessageBody {
        override val kind: Int get() = Kind.GUARDIAN_UNBLOCK
    }

    class Handover(val newBundle: Bundle, val deviceModel: String) : MessageBody {
        override val kind: Int get() = Kind.HANDOVER
    }

    class MoveRequest(val deviceModel: String, val deviceOwner: Boolean) : MessageBody {
        override val kind: Int get() = Kind.MOVE_REQUEST
    }

    /** One page of a move's state; [protection] holds bit (option − 1) for each option that is on. */
    class MoveState(
        val moveId: ByteArray,
        val page: Int,
        val pageCount: Int,
        val appManagement: Boolean,
        val protection: Int,
        val pairings: List<MovedPairing>,
        val apps: List<MovedApp>,
    ) : MessageBody {
        override val kind: Int get() = Kind.MOVE_STATE
    }

    class MoveAnswer(val moveId: ByteArray, val answer: MoveAnswerCode, val reason: MoveDeclineReason) : MessageBody {
        override val kind: Int get() = Kind.MOVE_ANSWER
    }

    class MoveDone(val moveId: ByteArray, val outcome: MoveOutcome) : MessageBody {
        override val kind: Int get() = Kind.MOVE_DONE
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

private fun ByteWriter.listedApp(app: ListedApp): ByteWriter {
    appRef(app.ref)
    text(app.label, TextRule.APP_LABEL)
    packageName(app.installer, allowEmpty = true)
    u64(app.firstInstalled)
    code(app.state)
    val icon = app.icon
    return if (icon == null) u8(0) else u8(ICON_FORMAT_LIST).fixed(icon, LIST_ICON_BYTES)
}

private fun ByteReader.listedApp(): ListedApp {
    val ref = appRef()
    val label = text(TextRule.APP_LABEL)
    val installer = packageName(allowEmpty = true)
    val firstInstalled = u64()
    val state = oneOf(AppState.entries, "app state")
    val icon = when (u8()) {
        0 -> null
        ICON_FORMAT_LIST -> fixed(LIST_ICON_BYTES)
        else -> throw DecodeException("invalid icon format")
    }
    return ListedApp(ref.packageName, ref.certDigest, label, installer, firstInstalled, state, icon)
}

private fun ByteWriter.movedPairing(pairing: MovedPairing): ByteWriter =
    fixed(pairing.pairId, PAIR_ID_BYTES)
        .code(pairing.role)
        .raw(encodeBundle(pairing.peer))
        .text(pairing.peerName, TextRule.SELF_NAME)
        .text(pairing.nickname, TextRule.SELF_NAME)
        .text(pairing.peerModel, TextRule.DEVICE_MODEL)
        .bool(pairing.appManagement)
        .u8(pairing.unpairChances)
        .u64(pairing.createdAt)

private fun ByteReader.movedPairing(): MovedPairing {
    val pairId = fixed(PAIR_ID_BYTES)
    val role = oneOf(Role.entries, "role")
    val peer = bundle()
    val peerName = text(TextRule.SELF_NAME)
    val nickname = text(TextRule.SELF_NAME)
    val peerModel = text(TextRule.DEVICE_MODEL)
    // Both fields belong to the protected side of the pairing: the setting is the peer's when the
    // old phone guards it, and the chances to remove a guardian are the old phone's when it is the
    // one guarded. Each is zero on the other side.
    val appManagement = bool()
    if (role == Role.PROTECTED && appManagement) throw DecodeException("the guarded side states no app-management setting")
    val unpairChances = inRange(0, if (role == Role.PROTECTED) MAX_CHANCES else 0, "unpairChances")
    return MovedPairing(pairId, role, peer, peerName, nickname, peerModel, appManagement, unpairChances, u64())
}

/** Refuses a list that names one package twice. */
private fun checkUniquePackages(packageNames: List<String>) {
    if (packageNames.toSet().size != packageNames.size) throw DecodeException("a package appears twice")
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
        is MessageBody.ListRequest -> writer.fixed(body.listId, LIST_ID_BYTES)
        is MessageBody.AppList -> {
            checkCount(body.pageCount, 1, 255, "pageCount")
            checkCount(body.page, 0, body.pageCount - 1, "page")
            checkCount(body.apps.size, if (body.pageCount == 1) 0 else 1, 255, "apps on a page")
            writer.fixed(body.listId, LIST_ID_BYTES).u8(body.page).u8(body.pageCount).u8(body.apps.size)
            body.apps.forEach { writer.listedApp(it) }
        }
        is MessageBody.Manage -> {
            require(body.seq >= 1) { "seq out of range" }
            checkCount(body.entries.size, 1, MAX_MANAGE_APPS, "apps in a MANAGE")
            writer.u64(body.seq).u8(body.entries.size)
            body.entries.forEach { writer.appRef(it.app).code(it.desired) }
        }
        is MessageBody.GuardianUnblock -> {
            checkCount(body.guardianCount, 1, MAX_GUARDIANS, "guardianCount")
            writer.fixed(body.requestId, REQUEST_ID_BYTES)
                .appInfo(body.app)
                .fixed(body.proposerFp, FINGERPRINT_BYTES)
                .text(body.proposerName, TextRule.SELF_NAME)
                .u8(body.guardianCount)
        }
        is MessageBody.Handover -> writer.raw(encodeBundle(body.newBundle)).text(body.deviceModel, TextRule.DEVICE_MODEL)
        is MessageBody.MoveRequest -> writer.text(body.deviceModel, TextRule.DEVICE_MODEL).bool(body.deviceOwner)
        is MessageBody.MoveState -> {
            checkCount(body.pageCount, 1, 255, "pageCount")
            checkCount(body.page, 0, body.pageCount - 1, "page")
            checkCount(body.pairings.size, 0, 255, "pairings on a page")
            checkCount(body.apps.size, 0, 0xFFFF, "apps on a page")
            writer.fixed(body.moveId, MOVE_ID_BYTES)
                .u8(body.page)
                .u8(body.pageCount)
                .bool(body.appManagement)
                .u8(body.protection)
                .u8(body.pairings.size)
            body.pairings.forEach { writer.movedPairing(it) }
            writer.u16(body.apps.size)
            body.apps.forEach { writer.appRef(it.app).code(it.state).u8(it.chancesLeft) }
        }
        is MessageBody.MoveAnswer -> {
            require((body.answer == MoveAnswerCode.READY) == (body.reason == MoveDeclineReason.NONE)) {
                "READY has no reason; DECLINE needs one"
            }
            writer.fixed(body.moveId, MOVE_ID_BYTES).code(body.answer).code(body.reason)
        }
        is MessageBody.MoveDone -> writer.fixed(body.moveId, MOVE_ID_BYTES).code(body.outcome)
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
        is NoticeBody.AppManaged ->
            appRef(notice.app)
                .text(notice.label, TextRule.APP_LABEL)
                .code(notice.change)
                .fixed(notice.guardianFp, FINGERPRINT_BYTES)
                .text(notice.guardianName, TextRule.SELF_NAME)
        is NoticeBody.GuardianMoved -> fixed(notice.guardianFp, FINGERPRINT_BYTES).text(notice.guardianName, TextRule.SELF_NAME)
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
    Kind.LIST_REQUEST -> MessageBody.ListRequest(fixed(LIST_ID_BYTES))
    Kind.APP_LIST -> {
        val listId = fixed(LIST_ID_BYTES)
        val page = u8()
        val pageCount = inRange(1, 255, "pageCount")
        if (page >= pageCount) throw DecodeException("page out of range")
        // An empty page only stands for an empty list, which has a single page.
        val count = inRange(if (pageCount == 1) 0 else 1, 255, "apps on a page")
        val apps = List(count) { listedApp() }
        checkUniquePackages(apps.map { it.packageName })
        MessageBody.AppList(listId, page, pageCount, apps)
    }
    Kind.MANAGE -> {
        val seq = u64()
        if (seq < 1) throw DecodeException("seq out of range")
        val count = inRange(1, MAX_MANAGE_APPS, "apps in a MANAGE")
        val entries = List(count) { ManageEntry(appRef(), oneOf(Desired.entries, "desired")) }
        checkUniquePackages(entries.map { it.app.packageName })
        MessageBody.Manage(seq, entries)
    }
    Kind.GUARDIAN_UNBLOCK -> MessageBody.GuardianUnblock(
        requestId = fixed(REQUEST_ID_BYTES),
        app = appInfo(),
        proposerFp = fixed(FINGERPRINT_BYTES),
        proposerName = text(TextRule.SELF_NAME),
        guardianCount = inRange(1, MAX_GUARDIANS, "guardianCount"),
    )
    Kind.HANDOVER -> MessageBody.Handover(bundle(), text(TextRule.DEVICE_MODEL))
    Kind.MOVE_REQUEST -> MessageBody.MoveRequest(text(TextRule.DEVICE_MODEL), bool())
    Kind.MOVE_STATE -> {
        val moveId = fixed(MOVE_ID_BYTES)
        val page = u8()
        val pageCount = inRange(1, 255, "pageCount")
        if (page >= pageCount) throw DecodeException("page out of range")
        val appManagement = bool()
        val protection = u8()
        if (protection and PROTECTION_BITS.inv() != 0) throw DecodeException("unknown protection option")
        val pairings = List(u8()) { movedPairing() }
        val apps = List(u16()) { MovedApp(appRef(), oneOf(AppState.entries, "app state"), inRange(0, MAX_CHANCES, "chancesLeft")) }
        if (pairings.map { it.pairId.toHex() }.toSet().size != pairings.size) throw DecodeException("a pairing appears twice")
        checkUniquePackages(apps.map { it.app.packageName })
        MessageBody.MoveState(moveId, page, pageCount, appManagement, protection, pairings, apps)
    }
    Kind.MOVE_ANSWER -> {
        val moveId = fixed(MOVE_ID_BYTES)
        val answer = oneOf(MoveAnswerCode.entries, "answer")
        val reason = oneOf(
            if (answer == MoveAnswerCode.READY) listOf(MoveDeclineReason.NONE) else MoveDeclineReason.entries - MoveDeclineReason.NONE,
            "decline reason",
        )
        MessageBody.MoveAnswer(moveId, answer, reason)
    }
    Kind.MOVE_DONE -> MessageBody.MoveDone(fixed(MOVE_ID_BYTES), oneOf(MoveOutcome.entries, "outcome"))
    else -> throw DecodeException("unknown kind")
}

private fun ByteReader.decisionTarget(): DecisionTarget = when (val kind = oneOf(RequestKind.entries, "request kind")) {
    RequestKind.UNBLOCK_APP, RequestKind.GUARDIAN_UNBLOCK -> DecisionTarget.App(appRef(), kind)
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
    NoticeCode.APP_MANAGED -> NoticeBody.AppManaged(
        app = appRef(),
        label = text(TextRule.APP_LABEL),
        change = oneOf(AppChange.entries, "change"),
        guardianFp = fixed(FINGERPRINT_BYTES),
        guardianName = text(TextRule.SELF_NAME),
    )
    NoticeCode.GUARDIAN_MOVED -> NoticeBody.GuardianMoved(fixed(FINGERPRINT_BYTES), text(TextRule.SELF_NAME))
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
