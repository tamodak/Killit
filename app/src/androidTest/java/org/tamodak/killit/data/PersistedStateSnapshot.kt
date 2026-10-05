package org.tamodak.killit.data

import android.os.Bundle
import org.tamodak.killit.admin.DevicePolicyController

/**
 * Everything Killit persists that a test may overwrite, captured so it can be put back.
 *
 * Instrumented tests run against the real stores of whatever device they are on, which is often a
 * developer's own phone with Killit provisioned. Without this, a test that sets a passkey would
 * replace that phone's passkey in both stores — so not even the durable copy could restore the real
 * one — and a test of the release delay would cancel a countdown that was really running.
 *
 * @param prefs the local store the snapshot was taken from.
 * @param dpc the gateway to the durable copy.
 * @param record the local credential record, or null when there was none.
 * @param release the local release request, or null when there was none.
 * @param restrictions the whole durable bundle, or null when Killit was not device owner.
 */
class PersistedStateSnapshot private constructor(
    private val prefs: LockPreferences,
    private val dpc: DevicePolicyController,
    private val record: CredentialRecord?,
    private val release: ReleaseRequest?,
    private val restrictions: Bundle?,
) {

    /** Writes every captured value back, and removes local values that were not there before. */
    suspend fun restore() {
        if (record != null) prefs.writeRecord(record) else prefs.clearRecord()
        if (release != null) prefs.writeReleaseRequest(release) else prefs.clearReleaseRequest()
        restrictions?.let { dpc.writeSelfRestrictions(it) }
    }

    companion object {
        /**
         * Captures the current state of both stores.
         *
         * @param prefs the local store.
         * @param dpc the gateway to the durable copy.
         * @return a snapshot whose [restore] puts that state back.
         */
        suspend fun take(prefs: LockPreferences, dpc: DevicePolicyController) = PersistedStateSnapshot(
            prefs = prefs,
            dpc = dpc,
            record = prefs.readRecord(),
            release = prefs.readReleaseRequest(),
            restrictions = dpc.readSelfRestrictions(),
        )
    }
}
