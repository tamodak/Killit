package org.tamodak.killit.admin

import kotlinx.coroutines.flow.first
import org.tamodak.killit.BuildConfig
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.LockRepository

/**
 * The device policies that come with having guardians.
 *
 * - **Debugging is off (PLAN.md H2).** adb can install, uninstall and reconfigure around Killit,
 *   so while anyone guards this phone, adb and developer options are disallowed. Debug builds
 *   leave them on ([BuildConfig.BLOCK_DEBUGGING_WHILE_GUARDED]): a developer's phone needs adb.
 * - **The clock stays locked (I1).** The UNPAIR timer and the release delay are measured on the
 *   wall clock, so "Block date & time changes" is switched on when a guardian arrives, and
 *   Settings keeps it on while any remains.
 *
 * Losing the last guardian gives debugging back; the clock stays as the user last set it.
 *
 * @param dpc applies the policies.
 * @param repository where the hardening toggles are kept, so the clock lock is remembered.
 */
class GuardedPolicies(
    private val dpc: DevicePolicyController,
    private val repository: LockRepository,
) {
    /**
     * Applies what having guardians, or not having any, means. Safe to repeat.
     *
     * @param guarded whether this phone has at least one guardian.
     */
    suspend fun apply(guarded: Boolean) {
        if (!dpc.isDeviceOwner()) return
        if (BuildConfig.BLOCK_DEBUGGING_WHILE_GUARDED) dpc.setDebuggingBlocked(guarded)
        if (!guarded) return
        val hardening = repository.hardening.first()
        if (hardening.blockDateTime) return
        val locked = hardening.copy(blockDateTime = true)
        repository.setHardening(locked)
        dpc.applyHardening(locked)
        KillitLog.i(KillitLog.DPC, "The phone has guardians; date and time changes are blocked (I1)")
    }
}
