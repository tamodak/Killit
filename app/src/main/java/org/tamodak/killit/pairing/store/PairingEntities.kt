package org.tamodak.killit.pairing.store

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/*
 * Pairing's tables (Room schema version 4).
 *
 * Entities holding byte arrays are plain classes, because a generated `equals` would compare the
 * arrays by identity; the few that are data classes compare their arrays by content. Every byte
 * string is stored as a BLOB exactly as the protocol defines it, never re-encoded.
 */

/**
 * One pairing: this phone and a peer, with a direction (SPEC.md §6.4). Two phones that guard each
 * other have two pairings, with two pair ids.
 *
 * @param pairId the ceremony's pair id; every message names it.
 * @param peerFp the peer's fingerprint: its address, from the bundle it revealed.
 * @param peerSignPk the key the peer's signatures verify under.
 * @param peerEncPk the key envelopes to the peer are sealed to.
 * @param peerEncSig the peer's signature binding [peerEncPk] to [peerSignPk] (SPEC.md §3.2), kept
 *   so a move can hand the whole bundle on and the new phone can check it again.
 * @param myRole this phone's side.
 * @param peerName the name the peer gave itself; it can't be edited here, so STATUS uses it (§11.1).
 * @param nickname what this phone calls the peer (A5); starts as [peerName].
 * @param peerModel the peer's `Build.MANUFACTURER` and `Build.MODEL`.
 * @param appManagement the protected side's app-management setting, as stated at pairing.
 * @param unpairChances how many more times this phone may ask to remove this guardian (G8);
 *   meaningful only when [myRole] is [PairRole.PROTECTED].
 * @param lastSeenDaysAgo whole days since the peer last talked to the server, when known.
 * @param silenceAlerted whether the current silence has already been announced (H8).
 * @param manageSeq the `seq` of the last MANAGE this phone sent the peer it guards (SPEC.md
 *   §11.11); the next one is higher.
 */
@Entity(tableName = "pairings", indices = [Index("peer_fp")])
class PairingEntity(
    @PrimaryKey
    @ColumnInfo(name = "pair_id", typeAffinity = ColumnInfo.BLOB) val pairId: ByteArray,
    @ColumnInfo(name = "peer_fp", typeAffinity = ColumnInfo.BLOB) val peerFp: ByteArray,
    @ColumnInfo(name = "peer_sign_pk", typeAffinity = ColumnInfo.BLOB) val peerSignPk: ByteArray,
    @ColumnInfo(name = "peer_enc_pk", typeAffinity = ColumnInfo.BLOB) val peerEncPk: ByteArray,
    @ColumnInfo(name = "peer_enc_sig", typeAffinity = ColumnInfo.BLOB) val peerEncSig: ByteArray,
    @ColumnInfo(name = "my_role") val myRole: PairRole,
    @ColumnInfo(name = "peer_name") val peerName: String,
    @ColumnInfo(name = "nickname") val nickname: String,
    @ColumnInfo(name = "peer_model") val peerModel: String,
    @ColumnInfo(name = "app_management") val appManagement: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "unpair_chances") val unpairChances: Int,
    @ColumnInfo(name = "last_seen_days") val lastSeenDaysAgo: Int?,
    @ColumnInfo(name = "silence_alerted") val silenceAlerted: Boolean,
    @ColumnInfo(name = "manage_seq") val manageSeq: Long = 0L,
)

/**
 * The pairing ceremony in progress, if any (SPEC.md §6). At most one row.
 *
 * It is kept on disk because the ceremony outlives a visit to the app: comparing the safety code
 * means leaving Killit to make a call, which locks it, and on a small phone the call can cost
 * Killit its process.
 *
 * @param myNonce this phone's ceremony nonce; secret until its reveal is published.
 * @param myReveal `bundle ‖ nonce`, fixed once, so a retried reveal sends the same bytes.
 * @param commit the joiner's commitment: the joiner's own, or the one the creator saw *before*
 *   revealing, which is the copy its reveal is checked against (SPEC.md §6.3).
 * @param inviteVersion the last invite version seen, for the long poll.
 * @param nickname what the user chose to call the peer, once chosen.
 * @param purpose a pairing, or a move (SPEC.md §6.5). A move uses [myRole] for nothing.
 * @param moveStage a move's progress once linked.
 * @param moveId the move's id (SPEC.md §11.14): chosen by the old phone, learnt by the new one.
 * @param moveReadyAt when the new phone said READY: the old phone may commit for 30 minutes
 *   after it arrived, and the new phone may give up an hour after it was sent.
 * @param movePeerModel old phone: the new phone's model, which every HANDOVER names.
 * @param moveGuardians how many of the moving pairings are guardians of the phone moving.
 * @param moveWards how many are phones it guards.
 */
@Entity(tableName = "ceremony")
data class CeremonyEntity(
    @PrimaryKey @ColumnInfo(name = "invite_id") val inviteId: String,
    @ColumnInfo(name = "side") val side: CeremonySide,
    @ColumnInfo(name = "my_role") val myRole: PairRole,
    @ColumnInfo(name = "self_name") val selfName: String,
    @ColumnInfo(name = "code") val code: String?,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "my_nonce", typeAffinity = ColumnInfo.BLOB) val myNonce: ByteArray,
    @ColumnInfo(name = "my_reveal", typeAffinity = ColumnInfo.BLOB) val myReveal: ByteArray,
    @ColumnInfo(name = "commit", typeAffinity = ColumnInfo.BLOB) val commit: ByteArray?,
    @ColumnInfo(name = "peer_reveal", typeAffinity = ColumnInfo.BLOB) val peerReveal: ByteArray?,
    @ColumnInfo(name = "pair_id", typeAffinity = ColumnInfo.BLOB) val pairId: ByteArray?,
    @ColumnInfo(name = "safety_code") val safetyCode: String?,
    @ColumnInfo(name = "stage") val stage: CeremonyStage,
    @ColumnInfo(name = "failure") val failure: CeremonyFailure?,
    @ColumnInfo(name = "invite_version") val inviteVersion: String?,
    @ColumnInfo(name = "peer_name") val peerName: String?,
    @ColumnInfo(name = "peer_model") val peerModel: String?,
    @ColumnInfo(name = "nickname") val nickname: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "purpose") val purpose: CeremonyPurpose = CeremonyPurpose.PAIR,
    @ColumnInfo(name = "move_stage") val moveStage: MoveStage? = null,
    @ColumnInfo(name = "move_id", typeAffinity = ColumnInfo.BLOB) val moveId: ByteArray? = null,
    @ColumnInfo(name = "move_ready_at") val moveReadyAt: Long? = null,
    @ColumnInfo(name = "move_peer_model") val movePeerModel: String? = null,
    @ColumnInfo(name = "move_guardians") val moveGuardians: Int = 0,
    @ColumnInfo(name = "move_wards") val moveWards: Int = 0,
) {
    private fun fields(): Array<Any?> = arrayOf(
        inviteId, side, myRole, selfName, code, expiresAt, myNonce, myReveal, commit, peerReveal, pairId,
        safetyCode, stage, failure, inviteVersion, peerName, peerModel, nickname, createdAt,
        purpose, moveStage, moveId, moveReadyAt, movePeerModel, moveGuardians, moveWards,
    )

    /** Compares by content, byte arrays included, unlike the generated implementation. */
    override fun equals(other: Any?): Boolean = other is CeremonyEntity && fields().contentDeepEquals(other.fields())

    override fun hashCode(): Int = fields().contentDeepHashCode()
}

/**
 * A request this phone sent its guardians: UNBLOCK_APP, UNPAIR, or the GUARDIAN_UNBLOCK it relays
 * for a guardian who is unblocking an app (G2). A live UNLOCK lives only in memory (PLAN.md §3.4)
 * and never gets a row.
 *
 * @param chancesLeft the chances the request was sent with, counting itself (D4, G8); 0 for a
 *   GUARDIAN_UNBLOCK, which uses none.
 * @param timerEndsAt UNPAIR only: when the guardian is removed unless someone denies (B1, B2).
 * @param applied whether an approval has been carried out, so one cut short is carried out later.
 * @param proposerPairId GUARDIAN_UNBLOCK only: the pairing with the guardian whose MANAGE started it.
 */
@Entity(tableName = "outgoing_requests")
class OutgoingRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id", typeAffinity = ColumnInfo.BLOB) val requestId: ByteArray,
    @ColumnInfo(name = "type") val type: RequestType,
    @ColumnInfo(name = "package_name") val packageName: String?,
    @ColumnInfo(name = "cert_digest", typeAffinity = ColumnInfo.BLOB) val certDigest: ByteArray?,
    @ColumnInfo(name = "app_label") val appLabel: String?,
    @ColumnInfo(name = "installer") val installer: String?,
    @ColumnInfo(name = "first_installed") val firstInstalled: Long?,
    @ColumnInfo(name = "icon", typeAffinity = ColumnInfo.BLOB) val icon: ByteArray?,
    @ColumnInfo(name = "target_pair_id", typeAffinity = ColumnInfo.BLOB) val targetPairId: ByteArray?,
    @ColumnInfo(name = "target_fp", typeAffinity = ColumnInfo.BLOB) val targetFp: ByteArray?,
    @ColumnInfo(name = "explanation") val explanation: String,
    @ColumnInfo(name = "chances_left") val chancesLeft: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "timer_ends_at") val timerEndsAt: Long?,
    @ColumnInfo(name = "state") val state: RequestState,
    @ColumnInfo(name = "cancel_cause") val cancelCause: CancelCause?,
    @ColumnInfo(name = "closed_at") val closedAt: Long?,
    @ColumnInfo(name = "applied") val applied: Boolean,
    @ColumnInfo(name = "proposer_pair_id", typeAffinity = ColumnInfo.BLOB) val proposerPairId: ByteArray? = null,
)

/** One guardian's copy of an outgoing request, and that guardian's answer. */
@Entity(
    tableName = "request_copies",
    primaryKeys = ["request_id", "pair_id"],
    foreignKeys = [
        ForeignKey(
            entity = OutgoingRequestEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pair_id")],
)
class RequestCopyEntity(
    @ColumnInfo(name = "request_id", typeAffinity = ColumnInfo.BLOB) val requestId: ByteArray,
    @ColumnInfo(name = "pair_id", typeAffinity = ColumnInfo.BLOB) val pairId: ByteArray,
    @ColumnInfo(name = "guardian_fp", typeAffinity = ColumnInfo.BLOB) val guardianFp: ByteArray,
    @ColumnInfo(name = "vote") val vote: Vote,
    @ColumnInfo(name = "note") val note: String,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
)

/** An outgoing request with every guardian's copy. */
class OutgoingRequestWithCopies(
    @Embedded val request: OutgoingRequestEntity,
    @Relation(parentColumn = "request_id", entityColumn = "request_id") val copies: List<RequestCopyEntity>,
)

/**
 * A request a protected phone sent this phone, as its guardian.
 *
 * @param sessionNonce UNLOCK only: what a decision must bind (SPEC.md §11.4).
 * @param senderCreatedAt the protected phone's clock when it sent the request.
 * @param myVote this phone's answer; [Vote.WAITING] until it gives one.
 * @param statusEntries every guardian's answer, from the last STATUS, encoded by [StatusEntries].
 * @param remindedAt when the reminder for it last went out (D9).
 * @param proposerFp GUARDIAN_UNBLOCK only: the guardian unblocking the app, which may be this phone.
 * @param proposerName GUARDIAN_UNBLOCK only: that guardian's own name (SPEC.md §11.12).
 * @param proposedByMe GUARDIAN_UNBLOCK only: this phone proposed it, so it has approved already and
 *   only follows the others' answers.
 */
@Entity(tableName = "incoming_requests", primaryKeys = ["pair_id", "request_id"])
class IncomingRequestEntity(
    @ColumnInfo(name = "pair_id", typeAffinity = ColumnInfo.BLOB) val pairId: ByteArray,
    @ColumnInfo(name = "request_id", typeAffinity = ColumnInfo.BLOB) val requestId: ByteArray,
    @ColumnInfo(name = "type") val type: RequestType,
    @ColumnInfo(name = "package_name") val packageName: String?,
    @ColumnInfo(name = "cert_digest", typeAffinity = ColumnInfo.BLOB) val certDigest: ByteArray?,
    @ColumnInfo(name = "app_label") val appLabel: String?,
    @ColumnInfo(name = "installer") val installer: String?,
    @ColumnInfo(name = "first_installed") val firstInstalled: Long?,
    @ColumnInfo(name = "icon", typeAffinity = ColumnInfo.BLOB) val icon: ByteArray?,
    @ColumnInfo(name = "session_nonce", typeAffinity = ColumnInfo.BLOB) val sessionNonce: ByteArray?,
    @ColumnInfo(name = "target_pair_id", typeAffinity = ColumnInfo.BLOB) val targetPairId: ByteArray?,
    @ColumnInfo(name = "target_fp", typeAffinity = ColumnInfo.BLOB) val targetFp: ByteArray?,
    @ColumnInfo(name = "timer_ends_at") val timerEndsAt: Long?,
    @ColumnInfo(name = "explanation") val explanation: String,
    @ColumnInfo(name = "chances_left") val chancesLeft: Int?,
    @ColumnInfo(name = "guardian_count") val guardianCount: Int,
    @ColumnInfo(name = "sender_created_at") val senderCreatedAt: Long,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "my_vote") val myVote: Vote,
    @ColumnInfo(name = "my_note") val myNote: String,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
    @ColumnInfo(name = "state") val state: RequestState,
    @ColumnInfo(name = "cancel_cause") val cancelCause: CancelCause?,
    @ColumnInfo(name = "status_entries") val statusEntries: String,
    @ColumnInfo(name = "reminded_at") val remindedAt: Long?,
    @ColumnInfo(name = "proposer_fp", typeAffinity = ColumnInfo.BLOB) val proposerFp: ByteArray? = null,
    @ColumnInfo(name = "proposer_name") val proposerName: String? = null,
    @ColumnInfo(name = "proposed_by_me") val proposedByMe: Boolean = false,
)

/**
 * How many more UNBLOCK_APP requests an app may have denied (D4). Kept per package and signing
 * certificate, and copied to durable storage, so neither reinstalling nor Clear data resets it.
 */
@Entity(tableName = "app_chances", primaryKeys = ["package_name", "cert_digest"])
class AppChancesEntity(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "cert_digest", typeAffinity = ColumnInfo.BLOB) val certDigest: ByteArray,
    @ColumnInfo(name = "chances_left") val chancesLeft: Int,
)

/**
 * A message id already accepted from a sender (SPEC.md §10.3 step 7). Kept for at least 400 days;
 * anything older than 365 days is refused by its date instead.
 */
@Entity(tableName = "seen_messages", primaryKeys = ["sender_fp", "message_id"])
class SeenMessageEntity(
    @ColumnInfo(name = "sender_fp", typeAffinity = ColumnInfo.BLOB) val senderFp: ByteArray,
    @ColumnInfo(name = "message_id", typeAffinity = ColumnInfo.BLOB) val messageId: ByteArray,
    @ColumnInfo(name = "seen_at") val seenAt: Long,
)

/**
 * A sealed envelope waiting to be sent. Envelopes are sealed when the message is created, so what
 * is queued is exactly what goes out.
 *
 * @param kind the message kind, for the log; it never leaves the phone unencrypted.
 */
@Entity(tableName = "outbox", indices = [Index("recipient_fp")])
class OutboxEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "recipient_fp", typeAffinity = ColumnInfo.BLOB) val recipientFp: ByteArray,
    @ColumnInfo(name = "envelope", typeAffinity = ColumnInfo.BLOB) val envelope: ByteArray,
    @ColumnInfo(name = "kind") val kind: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A link to delete once no pairing is left with the peer and every message to it has gone out
 * (SPEC.md §7).
 */
@Entity(tableName = "link_deletions")
class LinkDeletionEntity(
    @PrimaryKey
    @ColumnInfo(name = "peer_fp", typeAffinity = ColumnInfo.BLOB) val peerFp: ByteArray,
    @ColumnInfo(name = "requested_at") val requestedAt: Long,
)

/**
 * A message of a kind or version this Killit doesn't understand (SPEC.md §10.3 step 9). Kept as
 * received and shown as "Update Killit to see this"; never answered.
 */
@Entity(tableName = "unknown_messages")
class UnknownMessageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "pair_id", typeAffinity = ColumnInfo.BLOB) val pairId: ByteArray?,
    @ColumnInfo(name = "sender_fp", typeAffinity = ColumnInfo.BLOB) val senderFp: ByteArray,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "kind") val kind: Int,
    @ColumnInfo(name = "inner", typeAffinity = ColumnInfo.BLOB) val inner: ByteArray,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

/**
 * One line of the local history (I10). Text fields hold what the line needs to be written in the
 * phone's own language later (E-F3); which ones are set depends on [type].
 *
 * @param pairId the pairing it is about, when one is; kept after the pairing ends.
 * @param peerName the peer's nickname when it happened, so the line survives the pairing.
 * @param value a code: the request type, protection option, or how a guardian left.
 * @param time a second time the line mentions, such as when a release completes.
 */
@Entity(tableName = "events", indices = [Index("pair_id"), Index("at")])
class EventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "pair_id", typeAffinity = ColumnInfo.BLOB) val pairId: ByteArray?,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "type") val type: EventType,
    @ColumnInfo(name = "peer_name") val peerName: String?,
    @ColumnInfo(name = "app_label") val appLabel: String?,
    @ColumnInfo(name = "package_name") val packageName: String?,
    @ColumnInfo(name = "other_name") val otherName: String?,
    @ColumnInfo(name = "value") val value: Int?,
    @ColumnInfo(name = "flag") val flag: Boolean?,
    @ColumnInfo(name = "time") val time: Long?,
)

/**
 * A live unlock that was sent and not yet closed. The session nonce is never stored (§11.4); this
 * row only lets the next start tell the guardians the request is gone if the process died with
 * the request still open.
 */
@Entity(tableName = "pending_unlocks")
class PendingUnlockEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id", typeAffinity = ColumnInfo.BLOB) val requestId: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Protected side: the highest MANAGE `seq` applied from one guardian for one package (SPEC.md
 * §11.11). A command with a lower or equal one is stale, so a replayed or reordered MANAGE can't
 * undo a newer one.
 */
@Entity(tableName = "manage_seqs", primaryKeys = ["pair_id", "package_name"])
class ManageSeqEntity(
    @ColumnInfo(name = "pair_id", typeAffinity = ColumnInfo.BLOB) val pairId: ByteArray,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "seq") val seq: Long,
)

/**
 * Guardian side: the app list this phone last asked a guarded phone for (SPEC.md §11.10). Only
 * pages of that list are kept; any other list is ignored.
 *
 * @param completedAt when the whole list arrived; null while pages are missing.
 * @param failed true when the pages broke a rule, so the list was dropped.
 */
@Entity(tableName = "ward_lists")
class WardListEntity(
    @PrimaryKey
    @ColumnInfo(name = "pair_id", typeAffinity = ColumnInfo.BLOB) val pairId: ByteArray,
    @ColumnInfo(name = "list_id", typeAffinity = ColumnInfo.BLOB) val listId: ByteArray,
    @ColumnInfo(name = "requested_at") val requestedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "failed") val failed: Boolean,
)

/** Guardian side: one page of the list being received, its body kept as it was signed. */
@Entity(tableName = "ward_list_pages", primaryKeys = ["pair_id", "page"])
class WardListPageEntity(
    @ColumnInfo(name = "pair_id", typeAffinity = ColumnInfo.BLOB) val pairId: ByteArray,
    @ColumnInfo(name = "page") val page: Int,
    @ColumnInfo(name = "body", typeAffinity = ColumnInfo.BLOB) val body: ByteArray,
)

/**
 * Guardian side: one app of a guarded phone, from its last complete list, kept current by the
 * notices of what guardians changed (SPEC.md §11.9 APP_MANAGED). Dropped when the phone turns
 * management off: the list was only ever shared for managing.
 *
 * @param icon 16 × 16 RGBA8888, or null.
 * @param pendingBlock what this phone asked for and hasn't seen happen yet: true for a block,
 *   false for an unblock, null when nothing is pending.
 */
@Entity(tableName = "ward_apps", primaryKeys = ["pair_id", "package_name"])
class WardAppEntity(
    @ColumnInfo(name = "pair_id", typeAffinity = ColumnInfo.BLOB) val pairId: ByteArray,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "cert_digest", typeAffinity = ColumnInfo.BLOB) val certDigest: ByteArray,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "installer") val installer: String,
    @ColumnInfo(name = "first_installed") val firstInstalled: Long,
    @ColumnInfo(name = "state") val state: WardAppState,
    @ColumnInfo(name = "icon", typeAffinity = ColumnInfo.BLOB) val icon: ByteArray?,
    @ColumnInfo(name = "pending_block") val pendingBlock: Boolean?,
)

/**
 * New phone of a move: one MOVE_STATE page as it arrived (SPEC.md §11.14), its body kept as it was
 * signed. The pages are put together and checked when the last one comes, and taken into use only
 * once the old phone commits.
 */
@Entity(tableName = "move_pages")
class MovePageEntity(
    @PrimaryKey @ColumnInfo(name = "page") val page: Int,
    @ColumnInfo(name = "body", typeAffinity = ColumnInfo.BLOB) val body: ByteArray,
)
