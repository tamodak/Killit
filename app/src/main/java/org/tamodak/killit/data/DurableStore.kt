package org.tamodak.killit.data

import android.os.Bundle
import android.util.Base64
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.core.KillitLog

/**
 * The passkey's master copy, kept in the device owner's own application restrictions.
 *
 * ### Why application restrictions, of all places
 *
 * The device policy service stores restrictions in system storage rather than in
 * `/data/data/<pkg>/`, so they survive Settings -> Apps -> Killit -> Storage -> **Clear data**.
 * That matters: without it, clearing app data would erase the passkey while the OS kept every app
 * blocked, leaving a Killit that anyone could walk into and unblock.
 *
 * It is an unusual home for security material — restrictions are designed for managed
 * configuration pushed by an EMM — but Killit is its own admin, so it is the only writer and the
 * only reader. What is stored is a salted hash, never the credential.
 *
 * Only usable once Killit is device owner. Before that [isAvailable] is false and the repository
 * falls back to [LockPreferences].
 *
 * ### Value types
 *
 * The system persists restrictions as XML and only understands `boolean`, `int`, `String`,
 * `String[]`, `Bundle` and `Bundle[]`. Anything else makes system_server drop the whole write while
 * the call still returns normally, so wall-clock times — the one 64-bit value stored here — are kept
 * as decimal strings. [DevicePolicyController.writeSelfRestrictions] refuses a bundle holding any
 * other type, which turns that silent loss into a failed write the caller can see.
 *
 * Every function is `suspend` because every one is a binder call into the device policy service;
 * [DevicePolicyController] is what moves them off the main thread.
 *
 * @param dpc the gateway to `DevicePolicyManager`, which owns the dispatcher these calls run on.
 */
class DurableStore(private val dpc: DevicePolicyController) {

    /**
     * Reports whether this store can be used at all.
     *
     * @return true once Killit is device owner; false while the repository must fall back to
     *   [LockPreferences].
     */
    suspend fun isAvailable(): Boolean = dpc.isDeviceOwner()

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
     * Writes the credential record.
     *
     * Merges the record into the existing bundle rather than replacing it, so any restriction set
     * by something else survives a passkey change.
     *
     * @param record the record to persist.
     * @return true when the device policy service accepted the write.
     */
    suspend fun write(record: CredentialRecord): Boolean {
        val existing = dpc.readSelfRestrictions() ?: Bundle()
        existing.putString(KEY_LOCK_TYPE, record.lockType.name)
        existing.putString(KEY_SALT, record.salt.encodeBase64())
        existing.putString(KEY_HASH, record.hash.encodeBase64())
        existing.putInt(KEY_FAILED_ATTEMPTS, record.failedAttempts)
        existing.putMillis(KEY_LOCKOUT_UNTIL, record.lockoutUntilMillis)

        val written = dpc.writeSelfRestrictions(existing)
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
     * Writes the pending release request, starting or extending the countdown.
     *
     * @param request the request to persist.
     * @return true when the device policy service accepted the write.
     */
    suspend fun writeReleaseRequest(request: ReleaseRequest): Boolean {
        val existing = dpc.readSelfRestrictions() ?: Bundle()
        existing.putMillis(KEY_RELEASE_REQUESTED_AT, request.requestedAtMillis)
        existing.putMillis(KEY_RELEASE_AVAILABLE_AT, request.availableAtMillis)
        return dpc.writeSelfRestrictions(existing).also { written ->
            if (!written) {
                // Falling back to the local copy alone would leave the countdown resettable.
                KillitLog.e(KillitLog.DURABLE, "Release request NOT written durably; Clear data could reset it")
            }
        }
    }

    /**
     * Cancels the pending release request, leaving every other restriction in place.
     *
     * @return true when the request was removed, or when there was no bundle to remove it from.
     */
    suspend fun clearReleaseRequest(): Boolean {
        val existing = dpc.readSelfRestrictions() ?: return true
        existing.remove(KEY_RELEASE_REQUESTED_AT)
        existing.remove(KEY_RELEASE_AVAILABLE_AT)
        return dpc.writeSelfRestrictions(existing)
    }

    /**
     * Wipes the durable copy.
     *
     * Not currently called by any screen; kept as the counterpart to [write] for a future
     * "forget this device" flow.
     *
     * @return true when the device policy service accepted the write.
     */
    suspend fun clear(): Boolean {
        KillitLog.i(KillitLog.DURABLE, "Clearing the durable credential record")
        return dpc.writeSelfRestrictions(Bundle())
    }

    private companion object {
        // Restriction keys. The hash and salt entries carry a `_sha256` suffix for the same reason
        // as in LockPreferences: a record written under the old hashing scheme must read as absent
        // rather than as one that can never be verified.
        const val KEY_LOCK_TYPE = "killit_lock_type"
        const val KEY_SALT = "killit_salt_sha256"
        const val KEY_HASH = "killit_hash_sha256"
        const val KEY_FAILED_ATTEMPTS = "killit_failed_attempts"
        const val KEY_LOCKOUT_UNTIL = "killit_lockout_until"

        const val KEY_RELEASE_REQUESTED_AT = "killit_release_requested_at"
        const val KEY_RELEASE_AVAILABLE_AT = "killit_release_available_at"
    }
}

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
