package org.tamodak.killit.admin

import kotlinx.coroutines.flow.first
import org.tamodak.killit.BuildConfig
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.LockRepository
import org.tamodak.killit.protocol.ProtectionOption

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
 * Losing the last guardian gives debugging back; the clock stays as the user last set it. A phone
 * whose guardians moved to a new phone counts as guarded: it is frozen (PLAN.md §3.10).
 *
 * A move also carries the protection options from the old phone to the new one, as MOVE_STATE's
 * bits: bit (option − 1) for each option that is on (SPEC.md §11.14).
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

    /** This phone's protection options, as a move hands them on. */
    suspend fun protectionBits(): Int {
        val hardening = repository.hardening.first()
        return options(hardening).fold(0) { bits, (option, on) -> if (on) bits or option.bit else bits }
    }

    /**
     * Takes over the protection options a move brought, with the clock locked, as it always is on
     * a phone with guardians (I1).
     */
    suspend fun adoptProtection(bits: Int) {
        if (!dpc.isDeviceOwner()) return
        fun on(option: ProtectionOption) = bits and option.bit != 0
        val adopted = HardeningConfig(
            blockUninstall = on(ProtectionOption.UNINSTALL),
            blockForceStop = on(ProtectionOption.FORCE_STOP),
            blockSafeBoot = on(ProtectionOption.SAFE_BOOT),
            blockFactoryReset = on(ProtectionOption.FACTORY_RESET),
            blockAppsControl = on(ProtectionOption.APPS_CONTROL),
            blockDateTime = true,
        )
        repository.setHardening(adopted)
        dpc.applyHardening(adopted)
        KillitLog.i(KillitLog.DPC, "Took the old phone's protection options over")
    }

    private val ProtectionOption.bit: Int get() = 1 shl (code - 1)

    private fun options(config: HardeningConfig) = listOf(
        ProtectionOption.UNINSTALL to config.blockUninstall,
        ProtectionOption.FORCE_STOP to config.blockForceStop,
        ProtectionOption.SAFE_BOOT to config.blockSafeBoot,
        ProtectionOption.FACTORY_RESET to config.blockFactoryReset,
        ProtectionOption.APPS_CONTROL to config.blockAppsControl,
        ProtectionOption.DATE_TIME to config.blockDateTime,
    )
}
