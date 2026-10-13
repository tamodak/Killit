package org.tamodak.killit.pairing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.DurableAppChances
import org.tamodak.killit.data.DurableGuardian
import org.tamodak.killit.data.DurablePairingStore
import org.tamodak.killit.data.MovedAway
import org.tamodak.killit.pairing.store.AppChancesEntity
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingDao
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.protocol.Bundle
import org.tamodak.killit.protocol.toHex

/**
 * The pairings, and the counters a protected phone must not lose, kept in two places like the
 * passkey: the local tables, and the durable copy in the device owner's application restrictions
 * that survives Clear data.
 *
 * Only what protects this phone is copied: its guardians, and the chances it has left to ask them
 * (D4, G8). Without the copy, Clear data would erase the guardians while every app stayed blocked,
 * and the passkey alone would then open everything. Pairings in which this phone is the guardian
 * aren't copied: losing them weakens nobody (PLAN.md E-E6).
 *
 * ### Which copy wins
 *
 * As for the known-package list: when the local guardians are gone but the durable copy has some,
 * Clear data happened and they are restored; when both have guardians but differ, a durable write
 * failed at some point and the local list, the more recent, is written again. A failure therefore
 * errs towards keeping a guardian, never towards losing one.
 *
 * Restored guardians still name this phone's *old* identity, which Clear data deleted, so they
 * can't be reached any more. The phone stays guarded; the way out is the local UNPAIR timer, which
 * needs nobody's answer (PLAN.md §6.4).
 *
 * @param dao the local tables.
 * @param durable the clear-data-proof copy; unavailable unless this phone is device owner, which a
 *   protected phone always is.
 */
class PairingStore(
    val dao: PairingDao,
    private val durable: DurablePairingStore,
) {
    /** Serialises every change to the guardian list or the counters and its durable copy. */
    private val lock = Mutex()

    val pairings: Flow<List<PairingEntity>> = dao.observePairings()

    /**
     * Brings the local tables and the durable copy back in step. Run before anything reads the
     * guardians: at startup, before every sync, and before the gate decides what to show.
     */
    suspend fun reconcile() = lock.withLock {
        if (!durable.isAvailable()) return@withLock
        reconcileGuardians()
        reconcileAppChances()
    }

    /** This phone's guardians. */
    suspend fun guardians(): List<PairingEntity> = dao.guardians()

    /** The phones this phone guards. */
    suspend fun wards(): List<PairingEntity> = dao.wards()

    /**
     * Adds a pairing, and copies it durably when the peer guards this phone.
     *
     * @param pairing the new pairing.
     */
    suspend fun addPairing(pairing: PairingEntity) = lock.withLock {
        dao.upsertPairing(pairing)
        if (pairing.myRole == PairRole.PROTECTED) writeGuardians()
        KillitLog.i(KillitLog.PAIR, "Pairing added: this phone is ${pairing.myRole} (${pairing.pairId.short()})")
    }

    /**
     * Removes a pairing and what only it used: requests from it, messages kept for it, and what
     * managing apps kept for it.
     *
     * @param pairId the pairing to remove.
     * @return the removed pairing, or null when there was none.
     */
    suspend fun removePairing(pairId: ByteArray): PairingEntity? = lock.withLock {
        val pairing = dao.pairing(pairId) ?: return@withLock null
        dao.deletePairing(pairId)
        dao.deleteIncomingFor(pairId)
        dao.deleteUnknownFor(pairId)
        dao.deleteManageSeqsFor(pairId)
        dao.forgetWardApps(pairId)
        if (pairing.myRole == PairRole.PROTECTED) writeGuardians()
        KillitLog.i(KillitLog.PAIR, "Pairing removed (${pairId.short()})")
        pairing
    }

    /** Sets what this phone calls a peer. */
    suspend fun setNickname(pairId: ByteArray, nickname: String) = lock.withLock {
        dao.setNickname(pairId, nickname)
        if (dao.pairing(pairId)?.myRole == PairRole.PROTECTED) writeGuardians()
    }

    /**
     * A HANDOVER (SPEC.md §11.13): the pairing now belongs to the peer's new phone. The copy of a
     * guardian is rewritten too, so Clear data restores the guardian this phone trusts now.
     */
    suspend fun replacePeer(pairId: ByteArray, peerFp: ByteArray, bundle: Bundle, model: String) = lock.withLock {
        dao.replacePeer(pairId, peerFp, bundle.signPk, bundle.encPk, bundle.encSig, model)
        if (dao.pairing(pairId)?.myRole == PairRole.PROTECTED) writeGuardians()
    }

    /** Sets how many more times this phone may ask to remove a guardian (G8). */
    suspend fun setUnpairChances(pairId: ByteArray, chances: Int) = lock.withLock {
        dao.setUnpairChances(pairId, chances)
        writeGuardians()
    }

    /**
     * The UNBLOCK_APP chances left for an app and certificate (D4).
     *
     * @return the stored count, or [MAX_CHANCES] for an app never asked about.
     */
    suspend fun appChances(packageName: String, certDigest: ByteArray): Int =
        dao.appChances(packageName, certDigest) ?: MAX_CHANCES

    /** Takes the chance counters a move brought (SPEC.md §11.14), in both copies, in one write. */
    suspend fun setAllAppChances(rows: List<AppChancesEntity>) = lock.withLock {
        if (rows.isEmpty()) return@withLock
        dao.upsertAppChances(rows)
        if (durable.isAvailable()) durable.writeAppChances(dao.appChances().map { it.toDurable() })
    }

    /** Sets the UNBLOCK_APP chances left for an app and certificate, in both copies. */
    suspend fun setAppChances(packageName: String, certDigest: ByteArray, chances: Int) = lock.withLock {
        dao.upsertAppChances(listOf(AppChancesEntity(packageName, certDigest, chances)))
        if (durable.isAvailable()) durable.writeAppChances(dao.appChances().map { it.toDurable() })
    }

    /**
     * Forgets what only a protected phone keeps: the chance counters, in both copies, and the
     * unlock limits' durable copy. Its guardians are already gone when this runs.
     */
    suspend fun clearProtection() = lock.withLock {
        dao.deleteAppChances()
        if (durable.isAvailable()) durable.clearPairing()
    }

    /**
     * Forgets every pairing and counter, in both copies: what an old phone does once its pairings
     * belong to a new one (SPEC.md §11.14).
     *
     * @param movedAway set when the phone had guardians: it stays frozen, recorded in the same
     *   durable write that forgets the guardians. If that write fails, the durable guardians come
     *   back at the next [reconcile], so the phone is never left with neither. It goes first: the
     *   screens see the guardians go from the local tables, and must find the freeze already there.
     */
    suspend fun clear(movedAway: MovedAway?) = lock.withLock {
        if (durable.isAvailable()) durable.clearPairing(movedAway)
        dao.clearAll()
        KillitLog.i(KillitLog.PAIR, "Every pairing forgotten")
    }

    private suspend fun reconcileGuardians() {
        val stored = durable.readGuardians() ?: return
        val local = dao.guardians()
        when {
            local.isEmpty() && stored.isNotEmpty() -> {
                KillitLog.w(KillitLog.PAIR, "Guardians missing locally; restoring ${stored.size} from durable storage")
                stored.forEach { dao.upsertPairing(it.toEntity()) }
            }
            !sameGuardians(local, stored) -> {
                KillitLog.i(KillitLog.PAIR, "Durable guardians are stale; rewriting ${local.size}")
                durable.writeGuardians(local.map { it.toDurable() })
            }
        }
    }

    private suspend fun reconcileAppChances() {
        val stored = durable.readAppChances() ?: return
        val local = dao.appChances()
        when {
            local.isEmpty() && stored.isNotEmpty() -> dao.upsertAppChances(stored.map { AppChancesEntity(it.packageName, it.certDigest, it.chancesLeft) })
            !sameChances(local, stored) -> durable.writeAppChances(local.map { it.toDurable() })
        }
    }

    /** Writes the local guardian list durably. Called with [lock] held. */
    private suspend fun writeGuardians() {
        if (!durable.isAvailable()) return
        durable.writeGuardians(dao.guardians().map { it.toDurable() })
    }

    private fun sameGuardians(local: List<PairingEntity>, stored: List<DurableGuardian>): Boolean =
        local.size == stored.size && local.all { l ->
            stored.any { s ->
                s.pairId.contentEquals(l.pairId) && s.nickname == l.nickname && s.unpairChances == l.unpairChances
            }
        }

    private fun sameChances(local: List<AppChancesEntity>, stored: List<DurableAppChances>): Boolean =
        local.size == stored.size && local.all { l ->
            stored.any { s -> s.packageName == l.packageName && s.certDigest.contentEquals(l.certDigest) && s.chancesLeft == l.chancesLeft }
        }

    private fun PairingEntity.toDurable() = DurableGuardian(
        pairId = pairId,
        peerFp = peerFp,
        peerSignPk = peerSignPk,
        peerEncPk = peerEncPk,
        peerEncSig = peerEncSig,
        peerName = peerName,
        nickname = nickname,
        peerModel = peerModel,
        appManagement = appManagement,
        createdAt = createdAt,
        unpairChances = unpairChances,
    )

    private fun DurableGuardian.toEntity() = PairingEntity(
        pairId = pairId,
        peerFp = peerFp,
        peerSignPk = peerSignPk,
        peerEncPk = peerEncPk,
        peerEncSig = peerEncSig,
        myRole = PairRole.PROTECTED,
        peerName = peerName,
        nickname = nickname,
        peerModel = peerModel,
        appManagement = appManagement,
        createdAt = createdAt,
        unpairChances = unpairChances,
        lastSeenDaysAgo = null,
        silenceAlerted = false,
    )

    private fun AppChancesEntity.toDurable() = DurableAppChances(packageName, certDigest, chancesLeft)

    companion object {
        /** Chances per app (D4) and per guardian to remove (G8). */
        const val MAX_CHANCES = 3
    }
}

/** A short, public prefix of an identifier for the log: fingerprints and pair ids aren't secret. */
internal fun ByteArray.short(): String = toHex().take(8)
