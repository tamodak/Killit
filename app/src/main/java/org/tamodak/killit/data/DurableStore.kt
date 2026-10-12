package org.tamodak.killit.data

import android.os.Bundle
import android.util.Base64
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.core.KillitLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The clear-data-proof copies of Killit's state, kept in the device owner's own application
 * restrictions: the passkey record, the pending release request, the known-package list, and what
 * pairing must not lose — this phone's guardians, the chances left to ask them, and the limits on
 * unlock requests.
 *
 * ### Why application restrictions, of all places
 *
 * The device policy service stores restrictions in system storage rather than in
 * `/data/data/<pkg>/`, so they survive Settings -> Apps -> Killit -> Storage -> **Clear data**.
 * That matters: without it, clearing app data would erase the passkey while the OS kept every app
 * blocked, leaving a Killit that anyone could walk into and unblock — or would forget which apps
 * were new, so the next check would approve them.
 *
 * It is an unusual home for security material — restrictions are designed for managed
 * configuration pushed by an EMM — but Killit is its own admin, so it is the only writer and the
 * only reader. What is stored is a salted hash, never the credential.
 *
 * Only usable once Killit is device owner. Before that [isAvailable] is false and the callers fall
 * back to their local stores.
 *
 * ### Value types
 *
 * The system persists restrictions as XML and only understands `boolean`, `int`, `String`,
 * `String[]`, `Bundle` and `Bundle[]`. Anything else makes system_server drop the whole write while
 * the call still returns normally, so wall-clock times — the 64-bit values stored here — are kept
 * as decimal strings. [DevicePolicyController.writeSelfRestrictions] refuses a bundle holding any
 * other type, which turns that silent loss into a failed write the caller can see.
 *
 * ### One bundle, one writer at a time
 *
 * Everything lives in a single bundle that can only be written whole, so every change is a read,
 * a merge and a write. [writeLock] runs those one at a time: the passkey's lockout counters and the
 * package guard write from different coroutines, and two merges interleaving would each put back
 * what the other had just changed.
 *
 * Every function is `suspend` because every one is a binder call into the device policy service;
 * [DevicePolicyController] is what moves them off the main thread.
 *
 * @param dpc the gateway to `DevicePolicyManager`, which owns the dispatcher these calls run on.
 */
class DurableStore(private val dpc: DevicePolicyController) : DurablePairingStore {

    /** Serialises every read-merge-write of the bundle; see the class documentation. */
    private val writeLock = Mutex()

    /**
     * Reports whether this store can be used at all.
     *
     * @return true once Killit is device owner; false while callers must fall back to their local
     *   stores.
     */
    override suspend fun isAvailable(): Boolean = dpc.isDeviceOwner()

    /**
     * Reads the credential record back.
     *
     * @return the stored record, or null if any required field is absent or unparseable — the same
     *   all-or-nothing rule [LockPreferences.readRecord] applies.
     */
    suspend fun read(): CredentialRecord? {
        val bundle = dpc.readSelfRestrictions()
        if (bundle == null) {
            KillitLog.d(KillitLog.DURABLE) { "read: no restrictions bundle (not device owner?)" }
            return null
        }

        val type = LockType.fromName(bundle.getString(KEY_LOCK_TYPE))
        if (type == null) {
            KillitLog.d(KillitLog.DURABLE) { "read: bundle holds no lock type" }
            return null
        }
        val salt = bundle.getString(KEY_SALT)?.decodeBase64()
        if (salt == null) {
            KillitLog.w(KillitLog.DURABLE, "read: lock type is $type but the salt is missing or corrupt")
            return null
        }
        val hash = bundle.getString(KEY_HASH)?.decodeBase64()
        if (hash == null) {
            KillitLog.w(KillitLog.DURABLE, "read: lock type is $type but the hash is missing or corrupt")
            return null
        }

        return CredentialRecord(
            lockType = type,
            salt = salt,
            hash = hash,
            failedAttempts = bundle.getInt(KEY_FAILED_ATTEMPTS, 0),
            lockoutUntilMillis = bundle.getMillis(KEY_LOCKOUT_UNTIL) ?: 0L,
        ).also {
            KillitLog.d(KillitLog.DURABLE) {
                "read: $type hash=${KillitLog.fingerprint(hash)} failed=${it.failedAttempts}"
            }
        }
    }

    /**
     * Writes the credential record, leaving every other entry in the bundle as it was.
     *
     * @param record the record to persist.
     * @return true when the device policy service accepted the write.
     */
    suspend fun write(record: CredentialRecord): Boolean {
        val written = update {
            putString(KEY_LOCK_TYPE, record.lockType.name)
            putString(KEY_SALT, record.salt.encodeBase64())
            putString(KEY_HASH, record.hash.encodeBase64())
            putInt(KEY_FAILED_ATTEMPTS, record.failedAttempts)
            putMillis(KEY_LOCKOUT_UNTIL, record.lockoutUntilMillis)
        }
        if (written) {
            KillitLog.d(KillitLog.DURABLE) {
                "write: ${record.lockType} hash=${KillitLog.fingerprint(record.hash)} " +
                    "failed=${record.failedAttempts}"
            }
        } else {
            // Losing this write means a later Clear data would take the passkey with it.
            KillitLog.e(KillitLog.DURABLE, "write FAILED — the durable copy is now stale")
        }
        return written
    }

    // ---------------------------------------------------------------- release request

    /**
     * Reads the pending release request as the device policy service holds it.
     *
     * This is the copy that matters: it is what stops "Clear data" from resetting the countdown,
     * which would make the whole delay decorative.
     *
     * @return the pending request, or null when no release has been started.
     */
    suspend fun readReleaseRequest(): ReleaseRequest? {
        val bundle = dpc.readSelfRestrictions() ?: return null
        val requestedAt = bundle.getMillis(KEY_RELEASE_REQUESTED_AT) ?: return null
        val availableAt = bundle.getMillis(KEY_RELEASE_AVAILABLE_AT)
        if (availableAt == null) {
            // A request with no deadline cannot be honoured; treating it as absent makes the user
            // start the wait again rather than guessing when it should end.
            KillitLog.w(KillitLog.DURABLE, "readReleaseRequest: deadline missing or corrupt; ignoring the request")
            return null
        }
        return ReleaseRequest(requestedAtMillis = requestedAt, availableAtMillis = availableAt)
    }

    /**
     * Writes the pending release request, starting the countdown.
     *
     * @param request the request to persist.
     * @return true when the device policy service accepted the write.
     */
    suspend fun writeReleaseRequest(request: ReleaseRequest): Boolean {
        val written = update {
            putMillis(KEY_RELEASE_REQUESTED_AT, request.requestedAtMillis)
            putMillis(KEY_RELEASE_AVAILABLE_AT, request.availableAtMillis)
        }
        if (!written) {
            // Falling back to the local copy alone would leave the countdown resettable.
            KillitLog.e(KillitLog.DURABLE, "Release request NOT written durably; Clear data could reset it")
        }
        return written
    }

    /**
     * Cancels the pending release request, leaving every other restriction in place.
     *
     * @return true when the device policy service accepted the write.
     */
    suspend fun clearReleaseRequest(): Boolean = update {
        remove(KEY_RELEASE_REQUESTED_AT)
        remove(KEY_RELEASE_AVAILABLE_AT)
    }

    // ---------------------------------------------------------------- known packages

    /**
     * Reads the known-package list.
     *
     * An entry with a missing or unreadable field is left out rather than failing the whole list:
     * the package it described is then simply treated as new, which blocks it — the safe direction.
     *
     * @return every readable entry, keyed by package name; empty when none were ever written; null
     *   when Killit is not device owner.
     */
    suspend fun readKnownPackages(): Map<String, KnownPackage>? {
        val bundle = dpc.readSelfRestrictions() ?: return null
        val list = bundle.getBundle(KEY_KNOWN_PACKAGES) ?: return emptyMap()
        return list.keySet()
            .mapNotNull { name ->
                val entry = list.getBundle(name)?.toKnownPackage(name)
                if (entry == null) {
                    KillitLog.w(KillitLog.DURABLE, "readKnownPackages: entry for $name is corrupt; skipped")
                }
                entry
            }
            .associateBy { it.packageName }
    }

    /**
     * Replaces the known-package list with [packages], leaving every other entry as it was.
     *
     * @param packages the complete list.
     * @return true when the device policy service accepted the write.
     */
    suspend fun writeKnownPackages(packages: Collection<KnownPackage>): Boolean {
        val written = update {
            putBundle(KEY_KNOWN_PACKAGES, Bundle().apply {
                packages.forEach { putBundle(it.packageName, it.toBundle()) }
            })
        }
        if (!written) {
            // Clear data would now restore an older list, so some decisions could be lost.
            KillitLog.e(KillitLog.DURABLE, "Known packages NOT written durably (${packages.size} entries)")
        }
        return written
    }

    /**
     * Removes the known-package list, so that protecting the phone again starts from a fresh
     * snapshot of what is installed.
     *
     * @return true when the device policy service accepted the write.
     */
    suspend fun clearKnownPackages(): Boolean = update { remove(KEY_KNOWN_PACKAGES) }

    // ---------------------------------------------------------------- pairing

    /**
     * Reads this phone's guardians.
     *
     * Without this copy, Clear data would erase the guardians while every app stayed blocked, and
     * the passkey alone would then open everything: the one outcome pairing exists to prevent. An
     * entry with a missing or unreadable field is left out, and logged loudly.
     *
     * @return every readable guardian; empty when none were ever written; null when Killit is not
     *   device owner.
     */
    override suspend fun readGuardians(): List<DurableGuardian>? {
        val bundle = dpc.readSelfRestrictions() ?: return null
        val list = bundle.getBundle(KEY_GUARDIANS) ?: return emptyList()
        return list.keySet().mapNotNull { key ->
            list.getBundle(key)?.toGuardian().also {
                if (it == null) KillitLog.e(KillitLog.DURABLE, "readGuardians: entry $key is corrupt; skipped")
            }
        }
    }

    /**
     * Replaces the guardian list with [guardians].
     *
     * @return true when the device policy service accepted the write.
     */
    override suspend fun writeGuardians(guardians: Collection<DurableGuardian>): Boolean {
        val written = update {
            putBundle(KEY_GUARDIANS, Bundle().apply { guardians.forEach { putBundle(it.pairId.encodeBase64(), it.toBundle()) } })
        }
        if (!written) KillitLog.e(KillitLog.DURABLE, "Guardians NOT written durably (${guardians.size} entries)")
        return written
    }

    /**
     * Reads the UNBLOCK_APP chances left per app and certificate (D4).
     *
     * @return every readable entry; empty when none; null when Killit is not device owner.
     */
    override suspend fun readAppChances(): List<DurableAppChances>? {
        val bundle = dpc.readSelfRestrictions() ?: return null
        val list = bundle.getBundle(KEY_APP_CHANCES) ?: return emptyList()
        return list.keySet().mapNotNull { key ->
            val packageName = key.substringBefore(CHANCES_SEPARATOR)
            val digest = key.substringAfter(CHANCES_SEPARATOR, "").decodeBase64()
            if (digest == null || !list.containsKey(key)) null else DurableAppChances(packageName, digest, list.getInt(key))
        }
    }

    /**
     * Replaces the chance counters with [chances].
     *
     * @return true when the device policy service accepted the write.
     */
    override suspend fun writeAppChances(chances: Collection<DurableAppChances>): Boolean {
        val written = update {
            putBundle(
                KEY_APP_CHANCES,
                Bundle().apply {
                    chances.forEach { putInt(it.packageName + CHANCES_SEPARATOR + it.certDigest.encodeBase64(), it.chancesLeft) }
                },
            )
        }
        if (!written) KillitLog.e(KillitLog.DURABLE, "App chances NOT written durably")
        return written
    }

    /**
     * Reads the limits on live unlock requests (C4).
     *
     * @return the limits; zeros when none were written; null when Killit is not device owner.
     */
    override suspend fun readUnlockLimits(): UnlockLimits? {
        val bundle = dpc.readSelfRestrictions() ?: return null
        return UnlockLimits(
            cooldownUntil = bundle.getMillis(KEY_UNLOCK_COOLDOWN_UNTIL) ?: 0L,
            day = bundle.getInt(KEY_UNLOCK_DAY, 0),
            count = bundle.getInt(KEY_UNLOCK_COUNT, 0),
        )
    }

    /** Writes the limits on live unlock requests. */
    override suspend fun writeUnlockLimits(limits: UnlockLimits): Boolean = update {
        putMillis(KEY_UNLOCK_COOLDOWN_UNTIL, limits.cooldownUntil)
        putInt(KEY_UNLOCK_DAY, limits.day)
        putInt(KEY_UNLOCK_COUNT, limits.count)
    }

    /**
     * Forgets everything pairing kept here. Called once this phone has no guardians and device
     * owner is being released.
     */
    override suspend fun clearPairing(): Boolean = update {
        remove(KEY_GUARDIANS)
        remove(KEY_APP_CHANCES)
        remove(KEY_UNLOCK_COOLDOWN_UNTIL)
        remove(KEY_UNLOCK_DAY)
        remove(KEY_UNLOCK_COUNT)
    }

    /**
     * Wipes every durable entry.
     *
     * Used by tests to start from nothing; the app itself clears entries one feature at a time.
     *
     * @return true when the device policy service accepted the write.
     */
    suspend fun clear(): Boolean = writeLock.withLock {
        KillitLog.i(KillitLog.DURABLE, "Clearing every durable entry")
        dpc.writeSelfRestrictions(Bundle())
    }

    /**
     * Reads the bundle, applies [edit], and writes it back, holding [writeLock] throughout.
     *
     * Nothing is written when the read fails. Writing [edit] into a fresh bundle instead would
     * replace everything else stored here — the passkey record included — with nothing.
     *
     * @param edit the change to make to the current bundle.
     * @return true when the device policy service accepted the write; false when Killit is not
     *   device owner or the bundle could not be read or written.
     */
    private suspend fun update(edit: Bundle.() -> Unit): Boolean = writeLock.withLock {
        val bundle = dpc.readSelfRestrictions() ?: return@withLock false
        bundle.edit()
        dpc.writeSelfRestrictions(bundle)
    }

    /**
     * Encodes one known package as a nested bundle.
     *
     * @return the entry's bundle; the package name is its key in the list, not a field.
     */
    private fun KnownPackage.toBundle() = Bundle().apply {
        putString(KEY_ENTRY_CERT, certDigest.encodeBase64())
        putString(KEY_ENTRY_STATUS, status.name)
        putMillis(KEY_ENTRY_SINCE, sinceMillis)
    }

    /**
     * Decodes what [toBundle] wrote.
     *
     * @param packageName the entry's key in the list.
     * @return the entry, or null when any field is missing or unreadable.
     */
    private fun Bundle.toKnownPackage(packageName: String): KnownPackage? {
        val cert = getString(KEY_ENTRY_CERT)?.decodeBase64() ?: return null
        val status = PackageStatus.fromName(getString(KEY_ENTRY_STATUS)) ?: return null
        val since = getMillis(KEY_ENTRY_SINCE) ?: return null
        return KnownPackage(packageName, cert, status, since)
    }

    private fun DurableGuardian.toBundle() = Bundle().apply {
        putString(KEY_G_PAIR_ID, pairId.encodeBase64())
        putString(KEY_G_PEER_FP, peerFp.encodeBase64())
        putString(KEY_G_SIGN_PK, peerSignPk.encodeBase64())
        putString(KEY_G_ENC_PK, peerEncPk.encodeBase64())
        putString(KEY_G_NAME, peerName)
        putString(KEY_G_NICKNAME, nickname)
        putString(KEY_G_MODEL, peerModel)
        putBoolean(KEY_G_MANAGEMENT, appManagement)
        putMillis(KEY_G_CREATED, createdAt)
        putInt(KEY_G_UNPAIR_CHANCES, unpairChances)
    }

    private fun Bundle.toGuardian(): DurableGuardian? {
        if (!containsKey(KEY_G_UNPAIR_CHANCES)) return null
        return DurableGuardian(
            pairId = getString(KEY_G_PAIR_ID)?.decodeBase64() ?: return null,
            peerFp = getString(KEY_G_PEER_FP)?.decodeBase64() ?: return null,
            peerSignPk = getString(KEY_G_SIGN_PK)?.decodeBase64() ?: return null,
            peerEncPk = getString(KEY_G_ENC_PK)?.decodeBase64() ?: return null,
            peerName = getString(KEY_G_NAME) ?: return null,
            nickname = getString(KEY_G_NICKNAME) ?: return null,
            peerModel = getString(KEY_G_MODEL) ?: return null,
            appManagement = getBoolean(KEY_G_MANAGEMENT, false),
            createdAt = getMillis(KEY_G_CREATED) ?: return null,
            unpairChances = getInt(KEY_G_UNPAIR_CHANCES),
        )
    }

    private companion object {
        const val KEY_LOCK_TYPE = "killit_lock_type"
        const val KEY_SALT = "killit_passkey_salt"
        const val KEY_HASH = "killit_passkey_hash"
        const val KEY_FAILED_ATTEMPTS = "killit_failed_attempts"
        const val KEY_LOCKOUT_UNTIL = "killit_lockout_until"

        const val KEY_RELEASE_REQUESTED_AT = "killit_release_requested_at"
        const val KEY_RELEASE_AVAILABLE_AT = "killit_release_available_at"

        // The known-package list: one nested bundle per package, keyed by package name.
        const val KEY_KNOWN_PACKAGES = "killit_known_packages"
        const val KEY_ENTRY_CERT = "cert_sha256"
        const val KEY_ENTRY_STATUS = "status"
        const val KEY_ENTRY_SINCE = "since"

        // Pairing: the guardians (one nested bundle per pairing, keyed by pair id), the chance
        // counters (one int per app and certificate) and the unlock limits.
        const val KEY_GUARDIANS = "killit_guardians"
        const val KEY_G_PAIR_ID = "pair_id"
        const val KEY_G_PEER_FP = "peer_fp"
        const val KEY_G_SIGN_PK = "sign_pk"
        const val KEY_G_ENC_PK = "enc_pk"
        const val KEY_G_NAME = "name"
        const val KEY_G_NICKNAME = "nickname"
        const val KEY_G_MODEL = "model"
        const val KEY_G_MANAGEMENT = "app_management"
        const val KEY_G_CREATED = "created"
        const val KEY_G_UNPAIR_CHANCES = "unpair_chances"
        const val KEY_APP_CHANCES = "killit_app_chances"
        const val CHANCES_SEPARATOR = "/"
        const val KEY_UNLOCK_COOLDOWN_UNTIL = "killit_unlock_cooldown_until"
        const val KEY_UNLOCK_DAY = "killit_unlock_day"
        const val KEY_UNLOCK_COUNT = "killit_unlock_count"
    }
}

/**
 * The part of [DurableStore] pairing uses. An interface so the pairing engine can be tested with
 * several phones in one process, none of which may write the real device owner's restrictions.
 */
interface DurablePairingStore {
    /** @return true once Killit is device owner, so the copy can be read and written. */
    suspend fun isAvailable(): Boolean

    suspend fun readGuardians(): List<DurableGuardian>?

    suspend fun writeGuardians(guardians: Collection<DurableGuardian>): Boolean

    suspend fun readAppChances(): List<DurableAppChances>?

    suspend fun writeAppChances(chances: Collection<DurableAppChances>): Boolean

    suspend fun readUnlockLimits(): UnlockLimits?

    suspend fun writeUnlockLimits(limits: UnlockLimits): Boolean

    suspend fun clearPairing(): Boolean
}

/**
 * One of this phone's guardians, as [DurableStore] keeps it: everything needed to go on verifying
 * the guardian's decisions and to keep counting it.
 */
class DurableGuardian(
    val pairId: ByteArray,
    val peerFp: ByteArray,
    val peerSignPk: ByteArray,
    val peerEncPk: ByteArray,
    val peerName: String,
    val nickname: String,
    val peerModel: String,
    val appManagement: Boolean,
    val createdAt: Long,
    val unpairChances: Int,
)

/** The UNBLOCK_APP chances left for one app and certificate, as [DurableStore] keeps them. */
class DurableAppChances(val packageName: String, val certDigest: ByteArray, val chancesLeft: Int)

/**
 * The limits on live unlock requests (C4).
 *
 * @param cooldownUntil no new request before this time.
 * @param day the local day [count] belongs to, as days since the epoch.
 * @param count requests sent on [day].
 */
class UnlockLimits(val cooldownUntil: Long, val day: Int, val count: Int)

/**
 * Encodes bytes for storage in a restriction bundle or preference.
 *
 * `NO_WRAP` so the encoded value stays a single line — a Bundle string, not a file.
 *
 * @return the Base64 form, on one line.
 */
internal fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

/**
 * Decodes what [encodeBase64] wrote.
 *
 * @return the decoded bytes, or null rather than throwing: a corrupt value is treated as an absent
 *   one by callers.
 */
internal fun String.decodeBase64(): ByteArray? =
    runCatching { Base64.decode(this, Base64.NO_WRAP) }.getOrElse { error ->
        KillitLog.w(KillitLog.DURABLE, "Base64 decode failed; treating the value as absent", error)
        null
    }

/**
 * Stores a wall-clock time in a restrictions bundle.
 *
 * Restrictions have no 64-bit integer type (see [DurableStore]), so the value is written as a
 * decimal string.
 *
 * @param key the restriction key.
 * @param millis the time to store, in milliseconds since the epoch.
 */
private fun Bundle.putMillis(key: String, millis: Long) = putString(key, millis.toString())

/**
 * Reads back what [putMillis] wrote.
 *
 * @param key the restriction key.
 * @return the stored time, or null when the key is absent or does not hold a decimal number.
 */
private fun Bundle.getMillis(key: String): Long? = getString(key)?.toLongOrNull()
