package org.tamodak.killit.pairing.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Access to pairing's tables. Every function is main-safe; Room runs the queries. */
@Dao
interface PairingDao {

    // ---------------------------------------------------------------- pairings

    @Query("SELECT * FROM pairings ORDER BY created_at")
    fun observePairings(): Flow<List<PairingEntity>>

    @Query("SELECT * FROM pairings ORDER BY created_at")
    suspend fun pairings(): List<PairingEntity>

    @Query("SELECT * FROM pairings WHERE pair_id = :pairId")
    suspend fun pairing(pairId: ByteArray): PairingEntity?

    @Query("SELECT * FROM pairings WHERE peer_fp = :peerFp")
    suspend fun pairingsWith(peerFp: ByteArray): List<PairingEntity>

    /** This phone's guardians: the pairings in which it is the protected side. */
    @Query("SELECT * FROM pairings WHERE my_role = 'PROTECTED' ORDER BY created_at")
    suspend fun guardians(): List<PairingEntity>

    /** The phones this phone guards: the pairings in which it is the guardian. */
    @Query("SELECT * FROM pairings WHERE my_role = 'GUARDIAN' ORDER BY created_at")
    suspend fun wards(): List<PairingEntity>

    @Upsert
    suspend fun upsertPairing(pairing: PairingEntity)

    @Query("DELETE FROM pairings WHERE pair_id = :pairId")
    suspend fun deletePairing(pairId: ByteArray)

    @Query("UPDATE pairings SET nickname = :nickname WHERE pair_id = :pairId")
    suspend fun setNickname(pairId: ByteArray, nickname: String)

    @Query("UPDATE pairings SET unpair_chances = :chances WHERE pair_id = :pairId")
    suspend fun setUnpairChances(pairId: ByteArray, chances: Int)

    @Query("UPDATE pairings SET last_seen_days = :days WHERE peer_fp = :peerFp")
    suspend fun setLastSeen(peerFp: ByteArray, days: Int)

    @Query("UPDATE pairings SET silence_alerted = :alerted WHERE pair_id = :pairId")
    suspend fun setSilenceAlerted(pairId: ByteArray, alerted: Boolean)

    // ---------------------------------------------------------------- ceremony

    @Query("SELECT * FROM ceremony LIMIT 1")
    fun observeCeremony(): Flow<CeremonyEntity?>

    @Query("SELECT * FROM ceremony LIMIT 1")
    suspend fun ceremony(): CeremonyEntity?

    /** Replaces the ceremony row: there is never more than one. */
    @Transaction
    suspend fun replaceCeremony(ceremony: CeremonyEntity) {
        deleteCeremony()
        insertCeremony(ceremony)
    }

    @Upsert
    suspend fun updateCeremony(ceremony: CeremonyEntity)

    @Insert
    suspend fun insertCeremony(ceremony: CeremonyEntity)

    @Query("DELETE FROM ceremony")
    suspend fun deleteCeremony()

    // ---------------------------------------------------------------- outgoing requests

    @Transaction
    @Query("SELECT * FROM outgoing_requests ORDER BY created_at DESC")
    fun observeOutgoing(): Flow<List<OutgoingRequestWithCopies>>

    @Transaction
    @Query("SELECT * FROM outgoing_requests WHERE request_id = :requestId")
    suspend fun outgoing(requestId: ByteArray): OutgoingRequestWithCopies?

    @Transaction
    @Query("SELECT * FROM outgoing_requests WHERE state = 'OPEN' ORDER BY created_at")
    suspend fun openOutgoing(): List<OutgoingRequestWithCopies>

    @Query("SELECT * FROM outgoing_requests WHERE state = 'OPEN' AND type = 'UNBLOCK_APP' AND package_name = :packageName")
    suspend fun openUnblockFor(packageName: String): OutgoingRequestEntity?

    @Query("SELECT * FROM outgoing_requests WHERE state = 'OPEN' AND type = 'UNPAIR' AND target_pair_id = :targetPairId")
    suspend fun openUnpairFor(targetPairId: ByteArray): OutgoingRequestEntity?

    /** Approved requests whose effect hasn't been carried out yet. */
    @Query("SELECT * FROM outgoing_requests WHERE state = 'APPROVED' AND applied = 0")
    suspend fun unappliedApprovals(): List<OutgoingRequestEntity>

    @Query("UPDATE outgoing_requests SET applied = 1 WHERE request_id = :requestId")
    suspend fun markApplied(requestId: ByteArray)

    @Insert
    suspend fun insertOutgoing(request: OutgoingRequestEntity)

    @Query(
        "UPDATE outgoing_requests SET state = :state, cancel_cause = :cause, closed_at = :closedAt " +
            "WHERE request_id = :requestId",
    )
    suspend fun closeOutgoing(requestId: ByteArray, state: RequestState, cause: CancelCause?, closedAt: Long)

    @Upsert
    suspend fun upsertCopies(copies: List<RequestCopyEntity>)

    @Query(
        "UPDATE request_copies SET vote = :vote, note = :note, decided_at = :decidedAt " +
            "WHERE request_id = :requestId AND pair_id = :pairId",
    )
    suspend fun setVote(requestId: ByteArray, pairId: ByteArray, vote: Vote, note: String, decidedAt: Long)

    @Query("DELETE FROM request_copies WHERE request_id = :requestId AND pair_id = :pairId")
    suspend fun deleteCopy(requestId: ByteArray, pairId: ByteArray)

    @Query("DELETE FROM outgoing_requests WHERE state != 'OPEN' AND closed_at < :before")
    suspend fun pruneOutgoing(before: Long)

    // ---------------------------------------------------------------- incoming requests

    @Query("SELECT * FROM incoming_requests ORDER BY received_at DESC")
    fun observeIncoming(): Flow<List<IncomingRequestEntity>>

    @Query("SELECT * FROM incoming_requests WHERE pair_id = :pairId AND request_id = :requestId")
    suspend fun incoming(pairId: ByteArray, requestId: ByteArray): IncomingRequestEntity?

    @Query("SELECT * FROM incoming_requests WHERE state = 'OPEN' AND my_vote = 'WAITING'")
    suspend fun waitingForMe(): List<IncomingRequestEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIncoming(request: IncomingRequestEntity): Long

    @Query(
        "UPDATE incoming_requests SET my_vote = :vote, my_note = :note, decided_at = :decidedAt " +
            "WHERE pair_id = :pairId AND request_id = :requestId",
    )
    suspend fun setMyVote(pairId: ByteArray, requestId: ByteArray, vote: Vote, note: String, decidedAt: Long)

    @Query(
        "UPDATE incoming_requests SET state = :state, cancel_cause = :cause, status_entries = :entries " +
            "WHERE pair_id = :pairId AND request_id = :requestId",
    )
    suspend fun setIncomingStatus(pairId: ByteArray, requestId: ByteArray, state: RequestState, cause: CancelCause?, entries: String)

    @Query("UPDATE incoming_requests SET reminded_at = :at WHERE pair_id = :pairId AND request_id = :requestId")
    suspend fun setReminded(pairId: ByteArray, requestId: ByteArray, at: Long)

    @Query("DELETE FROM incoming_requests WHERE pair_id = :pairId")
    suspend fun deleteIncomingFor(pairId: ByteArray)

    @Query("DELETE FROM incoming_requests WHERE state != 'OPEN' AND received_at < :before")
    suspend fun pruneIncoming(before: Long)

    // ---------------------------------------------------------------- app chances

    @Query("SELECT * FROM app_chances")
    suspend fun appChances(): List<AppChancesEntity>

    @Query("SELECT * FROM app_chances")
    fun observeAppChances(): Flow<List<AppChancesEntity>>

    @Query("SELECT chances_left FROM app_chances WHERE package_name = :packageName AND cert_digest = :certDigest")
    suspend fun appChances(packageName: String, certDigest: ByteArray): Int?

    @Upsert
    suspend fun upsertAppChances(rows: List<AppChancesEntity>)

    @Query("DELETE FROM app_chances")
    suspend fun deleteAppChances()

    // ---------------------------------------------------------------- seen message ids

    @Query("SELECT COUNT(*) FROM seen_messages WHERE sender_fp = :senderFp AND message_id = :messageId")
    suspend fun seenCount(senderFp: ByteArray, messageId: ByteArray): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(seen: SeenMessageEntity)

    @Query("DELETE FROM seen_messages WHERE seen_at < :before")
    suspend fun pruneSeen(before: Long)

    // ---------------------------------------------------------------- outbox

    @Insert
    suspend fun enqueue(message: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY id LIMIT :limit")
    suspend fun outbox(limit: Int): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeOutboxSize(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE recipient_fp = :peerFp")
    suspend fun outboxCountFor(peerFp: ByteArray): Int

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun deleteOutbox(ids: List<Long>)

    @Query("DELETE FROM outbox WHERE recipient_fp = :peerFp")
    suspend fun deleteOutboxFor(peerFp: ByteArray)

    // ---------------------------------------------------------------- link deletions

    @Upsert
    suspend fun requestLinkDeletion(deletion: LinkDeletionEntity)

    @Query("SELECT * FROM link_deletions")
    suspend fun linkDeletions(): List<LinkDeletionEntity>

    @Query("DELETE FROM link_deletions WHERE peer_fp = :peerFp")
    suspend fun deleteLinkDeletion(peerFp: ByteArray)

    // ---------------------------------------------------------------- unknown messages

    @Insert
    suspend fun insertUnknown(message: UnknownMessageEntity)

    @Query("SELECT * FROM unknown_messages ORDER BY received_at DESC")
    fun observeUnknown(): Flow<List<UnknownMessageEntity>>

    @Query("DELETE FROM unknown_messages WHERE pair_id = :pairId")
    suspend fun deleteUnknownFor(pairId: ByteArray)

    @Query("DELETE FROM unknown_messages WHERE received_at < :before")
    suspend fun pruneUnknown(before: Long)

    // ---------------------------------------------------------------- history

    @Insert
    suspend fun insertEvent(event: EventEntity)

    @Query("SELECT * FROM events ORDER BY at DESC")
    fun observeEvents(): Flow<List<EventEntity>>

    @Query("DELETE FROM events WHERE at < :before")
    suspend fun pruneEvents(before: Long)

    // ---------------------------------------------------------------- pending unlocks

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingUnlock(unlock: PendingUnlockEntity)

    @Query("SELECT * FROM pending_unlocks")
    suspend fun pendingUnlocks(): List<PendingUnlockEntity>

    @Query("DELETE FROM pending_unlocks WHERE request_id = :requestId")
    suspend fun deletePendingUnlock(requestId: ByteArray)

    // ---------------------------------------------------------------- everything

    /** Forgets every pairing and everything that hangs off one: used when device owner is released. */
    @Transaction
    suspend fun clearAll() {
        clearPairings()
        deleteCeremony()
        clearOutgoing()
        clearIncoming()
        deleteAppChances()
        clearPendingUnlocks()
    }

    @Query("DELETE FROM pairings")
    suspend fun clearPairings()

    @Query("DELETE FROM outgoing_requests")
    suspend fun clearOutgoing()

    @Query("DELETE FROM incoming_requests")
    suspend fun clearIncoming()

    @Query("DELETE FROM pending_unlocks")
    suspend fun clearPendingUnlocks()
}
