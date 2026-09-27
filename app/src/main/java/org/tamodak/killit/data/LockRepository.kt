package org.tamodak.killit.data

import org.tamodak.killit.admin.HardeningConfig
import org.tamodak.killit.core.KillitLog
import kotlinx.coroutines.flow.Flow

/**
 * Fronts both credential stores and owns the lockout policy.
 *
 * ### Why there are two stores
 *
 * [DurableStore] is the master copy whenever Killit is device owner; [LockPreferences] is a cache
 * and the pre-provisioning bootstrap. The split exists because of one specific attack: Settings ->
 * Apps -> Killit -> Storage -> **Clear data** wipes the app's data directory. If the passkey lived
 * only there, clearing it would erase the passkey while the OS kept every app suspended — leaving
 * a Killit that anyone could walk into and unblock. The device policy service keeps application
 * restrictions in system storage instead, out of reach of Clear data, so the master copy survives.
 *
 * [reconcile] keeps the two in step in both directions.
 *
 * ### Threading
 *
 * Every function here is main-safe: the stores below own the dispatchers for their binder,
 * keystore and disk work, so these can be called straight from a `viewModelScope` coroutine.
 *
 * @param prefs local cache and pre-provisioning bootstrap.
 * @param durable master copy once Killit is device owner; survives "Clear data".
 * @param credentials salted SHA-256 of the passkey.
 */
class LockRepository(
    private val prefs: LockPreferences,
    private val durable: DurableStore,
    private val credentials: CredentialStore,
) {

    /** The hardening toggles, straight through from local storage. */
    val hardening: Flow<HardeningConfig> = prefs.hardening

    /** See [LockPreferences.prewarm]. Called once at process start, off the critical path. */
    suspend fun prewarm() = prefs.prewarm()

    /**
     * Persists the hardening toggles.
     *
     * @param config the full set of toggles.
     */
    suspend fun setHardening(config: HardeningConfig) {
        KillitLog.d(KillitLog.REPO) { "setHardening($config)" }
        prefs.setHardening(config)
    }

    /**
     * Syncs the two stores and returns the effective record.
     *
     * - Durable copy present: it wins, and the local cache is refreshed from it. This is what
     *   restores the passkey after "Clear data".
     * - Only a local copy: promote it to durable if Killit has become device owner since it was
     *   written.
     *
     * Called on every read, including on the verify path, so its cost shows up in unlock latency.
     *
     * @return the effective record after syncing, or null when neither store holds one.
     */
    suspend fun reconcile(): CredentialRecord? = KillitLog.timed(KillitLog.REPO, "reconcile") {
        val durableAvailable = durable.isAvailable()
        val durableRecord = if (durableAvailable) durable.read() else null
        val localRecord = prefs.readRecord()

        KillitLog.d(KillitLog.REPO) {
            "reconcile: durableAvailable=$durableAvailable " +
                "durable=${durableRecord.describe()} local=${localRecord.describe()}"
        }

        when {
            durableRecord != null -> {
                if (durableRecord != localRecord) {
                    // The usual cause is a Clear data that wiped the local copy while the durable
                    // one survived — this line is the restore actually happening.
                    KillitLog.i(KillitLog.REPO, "Local copy differs from durable; refreshing it from durable")
                    prefs.writeRecord(durableRecord)
                }
                durableRecord
            }

            localRecord != null -> {
                if (durableAvailable) {
                    KillitLog.i(KillitLog.REPO, "Promoting the local record to durable storage")
                    durable.write(localRecord)
                }
                localRecord
            }

            else -> {
                KillitLog.d(KillitLog.REPO) { "reconcile: no record in either store" }
                null
            }
        }
    }

    /**
     * Reads the effective record, reconciling the two stores first.
     *
     * @return the effective record, or null when neither store holds one.
     */
    suspend fun readRecord(): CredentialRecord? = reconcile()

    /**
     * Reports whether a passkey has been set at all.
     *
     * @return true when either store holds a record.
     */
    suspend fun hasCredential(): Boolean = readRecord() != null

    /**
     * Reads the locally cached record without touching the device policy service.
     *
     * Touches only DataStore — no binder, no device policy service. That matters at startup: the
     * first `DevicePolicyManager` call in a process costs far more than reading the record itself,
     * and knowing whether a passkey exists is all the gate needs. Callers that get a record back
     * can show the gate immediately and reconcile with durable storage behind it.
     *
     * A null answer is not conclusive — the durable copy may still hold a record that a "Clear
     * data" wiped from here — so callers must fall back to [reconcile] before concluding there is
     * no passkey.
     *
     * @return the cached record, or null if there is none.
     */
    suspend fun readCachedRecord(): CredentialRecord? = prefs.readRecord()

    /**
     * Reads the record on the unlock path, taking the fast route when it can.
     *
     * [reconcile] crosses a binder into the device policy service twice — once to ask whether
     * Killit is device owner, once to read the restrictions bundle — on every single call. None of
     * that is needed when the local cache already has the record, because the two stores can only
     * disagree in one direction: the durable copy outlives a "Clear data" that wipes the local
     * one. So a present local record is authoritative, and an absent one falls back to the full
     * reconciliation that restores it.
     *
     * This cannot be used to escape a lockout. The attempt counter and the lockout deadline are
     * fields of the record itself, so [write] puts them in *both* stores: clearing app data drops
     * the local copy, and the reconciliation below restores it — lockout still running — from
     * durable storage.
     *
     * Startup still uses the full [reconcile]: that is where the restore-after-Clear-data path has
     * to run, and where the extra binder calls cost nothing anyone notices.
     *
     * @return the record to verify against, or null when neither store holds one.
     */
    private suspend fun readForVerification(): CredentialRecord? {
        prefs.readRecord()?.let { cached ->
            KillitLog.d(KillitLog.REPO) { "Unlock read served from the local cache" }
            return cached
        }
        KillitLog.i(KillitLog.REPO, "Local cache is empty on the unlock path; reconciling with durable storage")
        return reconcile()
    }

    /**
     * Sets a new passkey, replacing any existing one and clearing the lockout counters.
     *
     * @param type which input the gate should show for this credential.
     * @param credential the normalised passkey string. Hashed with a fresh salt; never stored raw.
     */
    suspend fun setCredential(type: LockType, credential: String) {
        KillitLog.i(KillitLog.REPO, "Setting a new passkey of type $type")
        val salt = credentials.newSalt()
        val hash = credentials.hash(credential, salt)
        write(CredentialRecord(lockType = type, salt = salt, hash = hash))
        KillitLog.i(KillitLog.REPO, "Passkey set; lockout counters reset")
    }

    /**
     * Checks a credential against the stored record and applies the lockout policy.
     *
     * A wrong guess increments the attempt counter and, past [ATTEMPTS_BEFORE_LOCKOUT], starts an
     * exponential backoff; a correct one resets both.
     *
     * @param credential the normalised passkey string as entered.
     * @return which of the four outcomes the gate must show.
     */
    suspend fun verify(credential: String): VerifyResult =
        KillitLog.timed(KillitLog.REPO, "verify (full unlock path)") {
            val record = readForVerification()
            if (record == null) {
                KillitLog.i(KillitLog.REPO, "verify -> NoCredential (nothing stored)")
                return@timed VerifyResult.NoCredential
            }

            val now = System.currentTimeMillis()
            if (record.lockoutUntilMillis > now) {
                val remaining = record.lockoutUntilMillis - now
                KillitLog.i(KillitLog.REPO, "verify -> LockedOut for a further ${remaining}ms")
                return@timed VerifyResult.LockedOut(remaining)
            }

            val candidate = credentials.hash(credential, record.salt)
            if (credentials.matches(candidate, record.hash)) {
                KillitLog.i(KillitLog.REPO, "verify -> Success (attempt counter reset)")
                write(record.copy(failedAttempts = 0, lockoutUntilMillis = 0L))
                return@timed VerifyResult.Success
            }

            val attempts = record.failedAttempts + 1
            val lockoutUntil =
                if (attempts >= ATTEMPTS_BEFORE_LOCKOUT) now + backoffMillis(attempts) else 0L
            write(record.copy(failedAttempts = attempts, lockoutUntilMillis = lockoutUntil))

            if (lockoutUntil > now) {
                val remaining = lockoutUntil - now
                KillitLog.w(KillitLog.REPO, "verify -> LockedOut after $attempts failures (${remaining}ms)")
                VerifyResult.LockedOut(remaining)
            } else {
                val remainingAttempts = ATTEMPTS_BEFORE_LOCKOUT - attempts
                KillitLog.i(KillitLog.REPO, "verify -> Wrong ($attempts so far, $remainingAttempts before lockout)")
                VerifyResult.Wrong(remainingAttempts = remainingAttempts)
            }
        }

    // ---------------------------------------------------------------- paired peers

    /**
     * Reads the locally cached companion list.
     *
     * Read on the startup path, so it must not touch the device policy service — see
     * [readCachedRecord] for why that matters. [reconcilePeers] is what brings this back in step
     * with durable storage, and it runs behind the first screen.
     *
     * @return the cached companions, or an empty list when there are none.
     */
    suspend fun readCachedPeers(): List<PairedPeer> = prefs.readPeers()

    /**
     * Reads this device's own public key from the local cache.
     *
     * Read from storage rather than from the Keystore because the two can disagree: "Clear data"
     * takes the Keystore entry but the durable copy survives, and that copy is what still lets
     * companions unlock this device. See [DurableStore.readOwnPublicKey].
     *
     * @return the compressed P-256 point, or null if none is cached.
     */
    suspend fun readCachedOwnPublicKey(): ByteArray? = prefs.readOwnPublicKey()

    /**
     * Reads the locally cached set of devices this phone can open.
     *
     * On the startup path for the same reason as [readCachedPeers], so it stays off the binder.
     *
     * @return Base64 fingerprints of those devices, or an empty set when there are none.
     */
    suspend fun readCachedGuardianships(): Set<String> = prefs.readGuardianships()

    /**
     * Brings the two guardianship sets into step, durable winning.
     *
     * @return the effective set after syncing. Falls back to the local copy when Killit is not
     *   device owner and there is no durable store to consult.
     */
    suspend fun reconcileGuardianships(): Set<String> {
        if (!durable.isAvailable()) return prefs.readGuardianships()

        val durableSet = durable.readGuardianships()
        val localSet = prefs.readGuardianships()

        return when {
            durableSet.isNotEmpty() -> {
                if (durableSet != localSet) prefs.writeGuardianships(durableSet)
                durableSet
            }

            localSet.isNotEmpty() -> {
                durable.writeGuardianships(localSet)
                localSet
            }

            else -> emptySet()
        }
    }

    /**
     * Records that another device now depends on this phone's key to open.
     *
     * Written to both stores, because losing this set is what would let a phone give up device
     * owner while another device still needs it.
     *
     * @param fingerprint the dependent device's fingerprint.
     * @return the full set after the addition.
     */
    suspend fun addGuardianship(fingerprint: ByteArray): Set<String> {
        val updated = reconcileGuardianships() + fingerprint.encodeBase64()
        if (durable.isAvailable()) durable.writeGuardianships(updated)
        prefs.writeGuardianships(updated)
        KillitLog.i(KillitLog.PAIRING, "${updated.size} device(s) now depend on this phone's key")
        return updated
    }

    /**
     * Records that another device no longer depends on this phone's key.
     *
     * @param fingerprint the device that has unpaired.
     * @return the full set after the removal.
     */
    suspend fun removeGuardianship(fingerprint: ByteArray): Set<String> {
        val updated = reconcileGuardianships() - fingerprint.encodeBase64()
        if (durable.isAvailable()) durable.writeGuardianships(updated)
        prefs.writeGuardianships(updated)
        KillitLog.i(KillitLog.PAIRING, "${updated.size} device(s) still depend on this phone's key")
        return updated
    }

    /**
     * Reads which camera the QR scanner should open with.
     *
     * @return the stored preference, or [CameraFacing.DEFAULT] when none has been set.
     */
    suspend fun readCameraFacing(): CameraFacing = prefs.readCameraFacing()

    /**
     * Remembers which camera the QR scanner should open with.
     *
     * @param facing the camera the user last chose.
     */
    suspend fun setCameraFacing(facing: CameraFacing) = prefs.writeCameraFacing(facing)

    // ---------------------------------------------------------------- language

    /**
     * Reads the language the UI should be shown in.
     *
     * Local storage only: the language is a display preference, not part of what a lock is, so
     * unlike the passkey it has no reason to survive a "Clear data".
     *
     * @return the stored preference, or [AppLanguage.DEFAULT] when none has been set.
     */
    suspend fun language(): AppLanguage = prefs.readLanguage()

    /**
     * Persists the language the user picked.
     *
     * @param language the chosen language.
     */
    suspend fun setLanguage(language: AppLanguage) {
        KillitLog.d(KillitLog.REPO) { "setLanguage(${language.name})" }
        prefs.writeLanguage(language)
    }

    /**
     * Records this device's own public key in both stores; called once, when the key is created.
     *
     * @param publicKey the compressed P-256 point matching the Keystore private key.
     */
    suspend fun setOwnPublicKey(publicKey: ByteArray) {
        prefs.writeOwnPublicKey(publicKey)
        if (durable.isAvailable()) durable.writeOwnPublicKey(publicKey)
        KillitLog.i(KillitLog.PAIRING, "Own pairing identity recorded")
    }

    /**
     * Restores the own-key cache from durable storage, e.g. after a "Clear data".
     *
     * @return the effective public key after syncing, or null when neither store holds one.
     */
    suspend fun reconcileOwnPublicKey(): ByteArray? {
        if (!durable.isAvailable()) return prefs.readOwnPublicKey()
        val durableKey = durable.readOwnPublicKey()
        val localKey = prefs.readOwnPublicKey()
        return when {
            durableKey != null -> {
                if (localKey == null || !durableKey.contentEquals(localKey)) {
                    KillitLog.i(KillitLog.PAIRING, "Restoring own pairing identity from durable storage")
                    prefs.writeOwnPublicKey(durableKey)
                }
                durableKey
            }

            localKey != null -> {
                durable.writeOwnPublicKey(localKey)
                localKey
            }

            else -> null
        }
    }

    /**
     * Brings the two peer lists into step, durable winning.
     *
     * Same asymmetry as the credential: the durable copy is the one "Clear data" cannot reach, so
     * it is authoritative. A device whose data was cleared finds its companions restored here
     * rather than silently reverting to an unpaired, passkey-only state.
     *
     * @return the effective peer list after syncing.
     */
    suspend fun reconcilePeers(): List<PairedPeer> {
        if (!durable.isAvailable()) return prefs.readPeers()

        val durablePeers = durable.readPeers()
        val localPeers = prefs.readPeers()

        return when {
            durablePeers.isNotEmpty() -> {
                if (durablePeers != localPeers) {
                    KillitLog.i(KillitLog.PAIRING, "Restoring ${durablePeers.size} companions from durable storage")
                    prefs.writePeers(durablePeers)
                }
                durablePeers
            }

            localPeers.isNotEmpty() -> {
                KillitLog.i(KillitLog.PAIRING, "Promoting ${localPeers.size} companions to durable storage")
                durable.writePeers(localPeers)
                localPeers
            }

            else -> emptyList()
        }
    }

    /**
     * Adds a companion.
     *
     * Requires device owner: without durable storage the pairing would be undone by "Clear data",
     * which would turn a binding commitment into a suggestion.
     *
     * @param peer the companion to add.
     * @return true when the pairing was recorded; false if that exact public key is already paired,
     *   if Killit is not device owner, or if the durable write failed.
     */
    suspend fun addPeer(peer: PairedPeer): Boolean {
        if (!durable.isAvailable()) {
            KillitLog.w(KillitLog.PAIRING, "Refusing to pair: not device owner, so the pairing would not be durable")
            return false
        }

        val current = reconcilePeers()
        if (current.any { it.publicKey.contentEquals(peer.publicKey) }) {
            KillitLog.i(KillitLog.PAIRING, "Companion '${peer.label}' is already paired")
            return false
        }

        val updated = current + peer
        // Durable first: if that write fails there must be no local copy suggesting success.
        if (!durable.writePeers(updated)) return false
        prefs.writePeers(updated)
        KillitLog.i(KillitLog.PAIRING, "Paired with '${peer.label}'; ${updated.size} companions now required")
        return true
    }

    /**
     * Removes a companion.
     *
     * The caller is responsible for having obtained that peer's approval.
     *
     * @param publicKey the companion's compressed P-256 point.
     * @return true when a companion was removed; false if no companion held that key, or if the
     *   durable write failed.
     */
    suspend fun removePeer(publicKey: ByteArray): Boolean {
        val current = reconcilePeers()
        val updated = current.filterNot { it.publicKey.contentEquals(publicKey) }
        if (updated.size == current.size) {
            KillitLog.w(KillitLog.PAIRING, "Nothing removed: no companion with that key")
            return false
        }

        if (durable.isAvailable() && !durable.writePeers(updated)) return false
        prefs.writePeers(updated)
        KillitLog.i(KillitLog.PAIRING, "Companion removed; ${updated.size} remaining")
        return true
    }

    // ---------------------------------------------------------------- delayed release

    /**
     * Reads the outstanding release request, preferring the durable copy.
     *
     * Durable wins for the same reason it wins for the credential: it is the copy "Clear data"
     * cannot reach, and a request that could be wiped by clearing app data would make the delay
     * pointless. A durable copy found while the local one is missing is written back, so the
     * countdown survives a data wipe intact.
     *
     * @return the pending request, or null when no release has been started.
     */
    suspend fun releaseRequest(): ReleaseRequest? {
        val durableRequest = if (durable.isAvailable()) durable.readReleaseRequest() else null
        val localRequest = prefs.readReleaseRequest()

        return when {
            durableRequest != null -> {
                if (durableRequest != localRequest) {
                    KillitLog.i(KillitLog.REPO, "Restoring the release request from durable storage")
                    prefs.writeReleaseRequest(durableRequest)
                }
                durableRequest
            }

            localRequest != null -> {
                if (durable.isAvailable()) durable.writeReleaseRequest(localRequest)
                localRequest
            }

            else -> null
        }
    }

    /**
     * Starts the countdown, or returns the existing request unchanged.
     *
     * Pressing the button twice must not extend the wait — that would let a user who changed their
     * mind punish themselves further by accident, and more importantly it would let a *second*
     * person keep pushing the deadline out.
     *
     * @param delayMillis how long to wait before the release may be carried out.
     * @return the new request, or the existing one if a countdown was already running.
     */
    suspend fun requestRelease(delayMillis: Long): ReleaseRequest {
        releaseRequest()?.let { existing ->
            KillitLog.d(KillitLog.REPO) { "Release already requested; leaving the deadline alone" }
            return existing
        }

        val now = System.currentTimeMillis()
        val request = ReleaseRequest(requestedAtMillis = now, availableAtMillis = now + delayMillis)
        prefs.writeReleaseRequest(request)
        if (durable.isAvailable()) durable.writeReleaseRequest(request)

        KillitLog.i(KillitLog.REPO, "Release requested; available in ${delayMillis}ms")
        return request
    }

    /** Cancels the countdown in both stores, so the release has to be requested again from zero. */
    suspend fun cancelRelease() {
        KillitLog.i(KillitLog.REPO, "Release request cancelled")
        prefs.clearReleaseRequest()
        if (durable.isAvailable()) durable.clearReleaseRequest()
    }

    /**
     * Reports whether the release may be carried out now.
     *
     * Reads the request afresh rather than trusting a value the UI has been holding: the button
     * that calls this is the last gate before an irreversible action.
     *
     * @return true only when a request exists and its wait has elapsed.
     */
    suspend fun isReleaseAllowed(): Boolean {
        val request = releaseRequest() ?: run {
            KillitLog.w(KillitLog.REPO, "Release refused: no request outstanding")
            return false
        }
        val now = System.currentTimeMillis()
        if (request.clockWentBackwards(now)) {
            // Not an attack — winding the clock forward is — but it makes the countdown nonsense.
            KillitLog.w(KillitLog.REPO, "Clock moved backwards since the release was requested")
        }
        val ready = request.isReady(now)
        if (!ready) {
            KillitLog.i(KillitLog.REPO, "Release refused: ${request.remainingMillis(now)}ms still to wait")
        }
        return ready
    }

    /**
     * Copies a pre-provisioning passkey into durable storage.
     *
     * Called after provisioning succeeds, so a passkey set beforehand gains durable backing. A
     * durable copy that already exists is left alone — it is the authoritative one.
     */
    suspend fun promoteToDurable() {
        if (!durable.isAvailable()) {
            KillitLog.d(KillitLog.REPO) { "promoteToDurable: not device owner yet, nothing to do" }
            return
        }
        val local = prefs.readRecord()
        if (local == null) {
            KillitLog.d(KillitLog.REPO) { "promoteToDurable: no local record to promote" }
            return
        }
        if (durable.read() == null) {
            KillitLog.i(KillitLog.REPO, "Promoting the pre-provisioning passkey to durable storage")
            durable.write(local)
        } else {
            KillitLog.d(KillitLog.REPO) { "promoteToDurable: durable copy already present" }
        }
    }

    /**
     * Writes a record to both stores. The durable one is skipped when Killit is not yet device
     * owner.
     *
     * @param record the record to persist.
     */
    private suspend fun write(record: CredentialRecord) {
        KillitLog.timed(KillitLog.REPO, "write record to both stores") {
            prefs.writeRecord(record)
            if (durable.isAvailable()) durable.write(record)
        }
    }

    /**
     * Computes the lockout for a given run of failures: 30s after the threshold, doubling on each
     * further miss, capped at 30 minutes.
     *
     * The shift is clamped to 8 so the doubling cannot overflow the Long on a very long run of
     * wrong guesses; the cap would hold anyway, but relying on the cap alone would mean computing
     * a nonsense number first.
     *
     * @param attempts consecutive failures so far, including the one being handled.
     * @return how long to lock out, in milliseconds.
     */
    private fun backoffMillis(attempts: Int): Long {
        val overshoot = (attempts - ATTEMPTS_BEFORE_LOCKOUT).coerceIn(0, 8)
        val backoff = (BASE_LOCKOUT_MILLIS shl overshoot).coerceAtMost(MAX_LOCKOUT_MILLIS)
        KillitLog.d(KillitLog.REPO) { "backoff: attempts=$attempts overshoot=$overshoot -> ${backoff}ms" }
        return backoff
    }

    /**
     * Renders a record for logging.
     *
     * @return a compact, secret-free description; the hash appears only as a fingerprint.
     */
    private fun CredentialRecord?.describe(): String = when (this) {
        null -> "none"
        else -> "[$lockType hash=${KillitLog.fingerprint(hash)} " +
            "failed=$failedAttempts lockoutUntil=$lockoutUntilMillis]"
    }

    companion object {
        /** Wrong guesses tolerated before the backoff starts. Public: the gate counts down to it. */
        const val ATTEMPTS_BEFORE_LOCKOUT = 5

        /** The first lockout, doubled on each further miss by [backoffMillis]. */
        private const val BASE_LOCKOUT_MILLIS = 30_000L

        /** Ceiling on the doubling, so a forgotten passkey never locks the phone out for hours. */
        private const val MAX_LOCKOUT_MILLIS = 30 * 60 * 1000L
    }
}

/** The outcome of checking a credential. Each maps to a distinct thing the gate has to show. */
sealed interface VerifyResult {
    /** The credential matched. Attempt counters have been reset. */
    data object Success : VerifyResult

    /**
     * The credential did not match, and there are attempts left.
     *
     * @param remainingAttempts wrong guesses still allowed before a lockout begins.
     */
    data class Wrong(val remainingAttempts: Int) : VerifyResult

    /**
     * No attempt was accepted, because a lockout is in force.
     *
     * @param remainingMillis how long is left before guessing may resume.
     */
    data class LockedOut(val remainingMillis: Long) : VerifyResult

    /** Nothing is stored, so there is nothing to check against — the UI falls through to setup. */
    data object NoCredential : VerifyResult
}
