package org.tamodak.killit.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import org.tamodak.killit.core.KillitLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only place in the app that touches [DevicePolicyManager].
 *
 * ### What "blocking an app" means
 *
 * Blocking is *package suspension*, not a Killit-side overlay or accessibility trick. The OS
 * enforces it, so it survives reboots and Killit being killed, and only the admin that applied it
 * can lift it. That is what makes Killit hard to walk around — and also why Killit's own gate is the
 * thing that has to be secure.
 *
 * ### Why every call is wrapped in runCatching
 *
 * Before provisioning, none of these operations are permitted and the platform throws
 * `SecurityException`. The UI is deliberately browsable in that state — the app list works as a
 * read-only preview so the user can see what Killit will manage before committing to a factory
 * reset — so each call degrades to a documented default instead of crashing.
 *
 * ### Threading
 *
 * Every public function is a main-safe `suspend` function. All of them cross a binder to the
 * device policy service, which is far too slow for the main thread, so the dispatcher is owned
 * here rather than left to each caller to remember. [ioDispatcher] is injectable so tests can
 * substitute a deterministic one.
 *
 * @param context any context; only its application context is retained.
 * @param ioDispatcher where every binder call runs.
 */
class DevicePolicyController(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Held rather than the passed context, so this class can outlive any activity. */
    private val appContext = context.applicationContext

    /** The device policy service. Every call below crosses a binder to it. */
    private val dpm = appContext.getSystemService(DevicePolicyManager::class.java)

    /** Killit's admin identity, passed as the calling admin on every policy call. */
    private val admin = KillitDeviceAdminReceiver.componentName(appContext)

    /** Killit's own package. Both the target of self-restrictions and never suspendable. */
    val ownPackage: String = appContext.packageName

    /**
     * Reports whether Killit currently holds device owner.
     *
     * @return true when provisioning has completed and the policies below are permitted.
     */
    suspend fun isDeviceOwner(): Boolean = withContext(ioDispatcher) { isDeviceOwnerBlocking() }

    // ---------------------------------------------------------------- blocking

    /**
     * Reports which of the given packages are currently suspended.
     *
     * There is no "list all suspended packages" API, so this probes each one — **one binder round
     * trip per package**. On a device with 400 apps that is 400 IPCs, which is why it is confined
     * to [ioDispatcher] and timed: it is the most likely cause of a slow app list.
     *
     * @param candidates the packages to probe, normally the whole inventory.
     * @return the blocked subset, or an empty set when Killit is not device owner.
     */
    suspend fun blockedPackages(candidates: Collection<String>): Set<String> =
        withContext(ioDispatcher) {
            KillitLog.timed(KillitLog.DPC, "blockedPackages (${candidates.size} probes)") {
                blockedPackagesBlocking(candidates)
            }
        }

    /**
     * Moves the device to exactly [desired], given the currently blocked set.
     *
     * Android refuses to suspend some packages (the launcher, Killit itself, and others it treats
     * as critical) and reports them in the return value of `setPackagesSuspended`. Those names are
     * passed straight back to the caller — Killit does not try to predict the outcome beforehand,
     * because which packages are protected is internal to the platform and varies by version and
     * OEM.
     *
     * @param desired the packages that should end up blocked.
     * @param current the packages blocked now, so only the difference is applied.
     * @return which packages the platform refused, or the error that stopped the whole operation.
     */
    suspend fun applyBlocklist(desired: Set<String>, current: Set<String>): BlocklistResult =
        withContext(ioDispatcher) {
            KillitLog.timed(KillitLog.DPC, "applyBlocklist") {
                applyBlocklistBlocking(desired, current)
            }
        }

    // ---------------------------------------------------------------- hardening

    /**
     * Applies every anti-tamper policy in a config, and makes sure no second profile can be made.
     *
     * Individual failures are logged, not fatal: a policy the platform or OEM will not accept
     * should not cost the other five, and there is nothing the user could do about it anyway.
     *
     * @param config the toggles to apply. Each maps 1:1 to a call below.
     */
    suspend fun applyHardening(config: HardeningConfig) = withContext(ioDispatcher) {
        KillitLog.timed(KillitLog.DPC, "applyHardening") {
            if (applyHardeningBlocking(config)) ensureProfilesBlocked()
        }
    }

    // ---------------------------------------------------------------- durable storage

    /**
     * Reads the application restrictions Killit sets on itself.
     *
     * The device policy service keeps these in system storage rather than in the app's data
     * directory, so they outlive "Clear data" — see [org.tamodak.killit.data.DurableStore] for why
     * that matters.
     *
     * @return the restrictions bundle, or null when Killit is not device owner or the read failed.
     */
    suspend fun readSelfRestrictions(): Bundle? = withContext(ioDispatcher) {
        if (!isDeviceOwnerBlocking()) {
            KillitLog.d(KillitLog.DPC) { "readSelfRestrictions skipped: not device owner" }
            return@withContext null
        }
        runCatching { dpm.getApplicationRestrictions(admin, ownPackage) }
            .onSuccess { bundle ->
                KillitLog.d(KillitLog.DPC) { "readSelfRestrictions: ${bundle?.size() ?: 0} keys" }
            }
            .getOrElse { error ->
                KillitLog.e(KillitLog.DPC, "readSelfRestrictions failed", error)
                null
            }
    }

    /**
     * Writes the application restrictions Killit sets on itself.
     *
     * The bundle replaces what was stored, which is why callers read, merge and write back rather
     * than writing a bundle holding only the keys they own.
     *
     * A bundle holding a value type the system cannot persist is refused here rather than passed
     * on. The device policy service would accept it, but system_server would then discard the whole
     * write and report the failure only in its own log, leaving the caller believing the durable copy
     * was updated. See [unsupportedRestrictionKeys].
     *
     * @param bundle the full restrictions to store.
     * @return true when the write succeeded; false when Killit is not device owner, the bundle holds
     *   a type that cannot be persisted, or the call failed.
     */
    suspend fun writeSelfRestrictions(bundle: Bundle): Boolean = withContext(ioDispatcher) {
        if (!isDeviceOwnerBlocking()) {
            KillitLog.d(KillitLog.DPC) { "writeSelfRestrictions skipped: not device owner" }
            return@withContext false
        }
        val unsupported = unsupportedRestrictionKeys(bundle)
        if (unsupported.isNotEmpty()) {
            KillitLog.e(KillitLog.DPC, "writeSelfRestrictions refused: unsupported value types at $unsupported")
            return@withContext false
        }
        KillitLog.timed(KillitLog.DPC, "writeSelfRestrictions (${bundle.size()} keys)") {
            runCatching { dpm.setApplicationRestrictions(admin, ownPackage, bundle) }
                .onFailure { KillitLog.e(KillitLog.DPC, "writeSelfRestrictions failed", it) }
                .isSuccess
        }
    }

    // ---------------------------------------------------------------- teardown

    /**
     * Unblocks everything, drops the hardening, then releases device owner.
     *
     * **The order matters.** Once Killit is no longer the admin it can no longer lift its own
     * suspensions, and there is no second chance: re-provisioning needs a factory reset. Each step
     * is logged so a partial teardown can be diagnosed after the fact.
     *
     * @param candidates every package to consider unblocking, normally the whole inventory.
     * @return true when device owner was given up. An incomplete unblock is logged but does not
     *   stop the release — the user asked to be let out, and refusing would strand them.
     */
    @Suppress("DEPRECATION")
    suspend fun releaseDeviceOwner(candidates: Collection<String>): Boolean =
        withContext(ioDispatcher) {
            if (!isDeviceOwnerBlocking()) {
                KillitLog.w(KillitLog.DPC, "releaseDeviceOwner called while not device owner")
                return@withContext false
            }

            KillitLog.i(KillitLog.DPC, "Releasing device owner: step 1/3, unblocking every package")
            val stillBlocked = blockedPackagesBlocking(candidates)
            val unblockResult = applyBlocklistBlocking(desired = emptySet(), current = stillBlocked)
            if (!unblockResult.isSuccess) {
                // Not fatal, but the user needs to know some apps may stay suspended with no admin
                // left to lift them.
                KillitLog.e(
                    KillitLog.DPC,
                    "Unblock incomplete before release: failed=${unblockResult.failedToUnblock} " +
                        "error=${unblockResult.error}",
                )
            }

            KillitLog.i(KillitLog.DPC, "Releasing device owner: step 2/3, clearing hardening")
            applyHardeningBlocking(
                HardeningConfig(
                    blockUninstall = false,
                    blockForceStop = false,
                    blockSafeBoot = false,
                    blockFactoryReset = false,
                    blockAppsControl = false,
                    blockDateTime = false,
                )
            )

            KillitLog.i(KillitLog.DPC, "Releasing device owner: step 3/3, clearDeviceOwnerApp")
            runCatching { dpm.clearDeviceOwnerApp(ownPackage) }
                .onSuccess { KillitLog.i(KillitLog.DPC, "Device owner released") }
                .onFailure { KillitLog.e(KillitLog.DPC, "clearDeviceOwnerApp failed", it) }
                .isSuccess
        }

    // ---------------------------------------------------------------- blocking internals
    //
    // Everything below already runs on ioDispatcher, so these call each other directly rather than
    // nesting redundant withContext blocks.

    /**
     * Reports device-owner status without switching dispatcher.
     *
     * @return true when Killit holds device owner. A throwing platform is read as "not owner",
     *   which fails towards refusing to apply policy rather than towards attempting it.
     */
    private fun isDeviceOwnerBlocking(): Boolean =
        runCatching { dpm.isDeviceOwnerApp(ownPackage) }
            .getOrElse { error ->
                KillitLog.w(KillitLog.DPC, "isDeviceOwnerApp threw; assuming not owner", error)
                false
            }

    /**
     * Probes one package's suspended state.
     *
     * @param packageName the package to probe.
     * @return true when it is currently suspended.
     */
    private fun isBlockedBlocking(packageName: String): Boolean =
        runCatching { dpm.isPackageSuspended(admin, packageName) }
            .getOrElse {
                // Thrown for packages uninstalled since the inventory was taken. Common, not news.
                KillitLog.v(KillitLog.DPC) { "isPackageSuspended($packageName) threw; treating as unblocked" }
                false
            }

    /**
     * Probes every candidate, the work behind [blockedPackages].
     *
     * @param candidates the packages to probe.
     * @return the blocked subset, or an empty set when Killit is not device owner.
     */
    private fun blockedPackagesBlocking(candidates: Collection<String>): Set<String> {
        if (!isDeviceOwnerBlocking()) {
            KillitLog.d(KillitLog.DPC) { "blockedPackages: not device owner, reporting none blocked" }
            return emptySet()
        }
        val blocked = candidates.filterTo(mutableSetOf()) { isBlockedBlocking(it) }
        KillitLog.d(KillitLog.DPC) { "blockedPackages: ${blocked.size} of ${candidates.size} are suspended" }
        return blocked
    }

    /**
     * Applies the blocklist difference, the work behind [applyBlocklist].
     *
     * @param desired the packages that should end up blocked.
     * @param current the packages blocked now.
     * @return which packages the platform refused, or the error that stopped the whole operation.
     */
    private fun applyBlocklistBlocking(desired: Set<String>, current: Set<String>): BlocklistResult {
        if (!isDeviceOwnerBlocking()) {
            KillitLog.w(KillitLog.DPC, "applyBlocklist refused: $NOT_DEVICE_OWNER")
            return BlocklistResult(error = NOT_DEVICE_OWNER)
        }

        val toBlock = (desired - current).toTypedArray()
        val toUnblock = (current - desired).toTypedArray()
        KillitLog.i(KillitLog.DPC, "applyBlocklist: +${toBlock.size} to block, -${toUnblock.size} to unblock")
        KillitLog.d(KillitLog.DPC) { "  block=${toBlock.toList()} unblock=${toUnblock.toList()}" }

        return runCatching {
            val blockFailures = suspendPackages(toBlock, suspended = true)
            val unblockFailures = suspendPackages(toUnblock, suspended = false)
            if (blockFailures.isNotEmpty()) {
                KillitLog.w(KillitLog.DPC, "Android refused to suspend: $blockFailures")
            }
            if (unblockFailures.isNotEmpty()) {
                KillitLog.w(KillitLog.DPC, "Android refused to unsuspend: $unblockFailures")
            }
            BlocklistResult(
                failedToBlock = blockFailures,
                failedToUnblock = unblockFailures,
            )
        }.getOrElse { error ->
            KillitLog.e(KillitLog.DPC, "applyBlocklist failed outright", error)
            BlocklistResult(error = error.message ?: error::class.java.simpleName)
        }
    }

    /**
     * Sets the suspended state on a batch of packages in one call.
     *
     * @param packages the packages to change. An empty array short-circuits, since the platform
     *   call is a binder round trip either way.
     * @param suspended the state to set.
     * @return the packages whose state could NOT be set as requested.
     */
    private fun suspendPackages(packages: Array<String>, suspended: Boolean): List<String> {
        if (packages.isEmpty()) return emptyList()
        return dpm.setPackagesSuspended(admin, packages, suspended)?.toList().orEmpty()
    }

    /**
     * Applies every policy in a config, the work behind [applyHardening].
     *
     * Also used by [releaseDeviceOwner] to lift everything, which is why the profile restrictions
     * live in [ensureProfilesBlocked] instead: they are never lifted.
     *
     * @param config the toggles to apply.
     * @return true when the policies were applied; false when Killit is not device owner.
     */
    private fun applyHardeningBlocking(config: HardeningConfig): Boolean {
        if (!isDeviceOwnerBlocking()) {
            KillitLog.d(KillitLog.DPC) { "applyHardening skipped: not device owner" }
            return false
        }
        KillitLog.i(KillitLog.DPC, "Applying hardening: $config")

        setUninstallBlocked(config.blockUninstall)
        setUserControlDisabled(config.blockForceStop)
        setRestriction(UserManager.DISALLOW_SAFE_BOOT, config.blockSafeBoot)
        setRestriction(UserManager.DISALLOW_FACTORY_RESET, config.blockFactoryReset)
        // Adding a second user is another route to a de facto reset, so it rides the same toggle.
        setRestriction(UserManager.DISALLOW_ADD_USER, config.blockFactoryReset)
        setRestriction(UserManager.DISALLOW_APPS_CONTROL, config.blockAppsControl)
        // Without this, the release delay can be skipped by moving the clock forward.
        setDateTimeLocked(config.blockDateTime)
        return true
    }

    /**
     * Stops the user setting the date and time by hand.
     *
     * `DISALLOW_CONFIG_DATE_TIME` exists from Android 9. On Android 8 the same effect comes from
     * requiring automatic time: the clock then only follows the network, and the date and time
     * settings cannot be changed.
     *
     * @param locked true to lock the clock, false to give the settings back.
     */
    private fun setDateTimeLocked(locked: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, locked)
            return
        }
        @Suppress("DEPRECATION") // Deprecated from Android 11; this branch only runs on Android 8.
        runCatching { dpm.setAutoTimeRequired(admin, locked) }
            .onSuccess { KillitLog.d(KillitLog.DPC) { "setAutoTimeRequired($locked) ok" } }
            .onFailure { KillitLog.w(KillitLog.DPC, "setAutoTimeRequired($locked) failed", it) }
    }

    /**
     * Makes sure no second profile can be created on this device.
     *
     * Killit can only suspend packages for the user it manages, so apps installed in another
     * profile — a work profile, or Android 15's private space — would be out of its reach. The
     * platform already turns both restrictions on for a device owner, and from Android 11 a fully
     * managed device cannot have a work profile at all; setting them here covers builds that skip
     * those defaults. They are only ever added, never cleared, because no configuration of Killit
     * wants either profile to exist.
     */
    private fun ensureProfilesBlocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") // Deprecated because Android 11 made it moot; set only below 11.
            setRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            setRestriction(UserManager.DISALLOW_ADD_PRIVATE_PROFILE, true)
        }
    }

    /**
     * Blocks or allows uninstalling Killit itself.
     *
     * @param blocked true to prevent uninstallation.
     */
    private fun setUninstallBlocked(blocked: Boolean) {
        runCatching { dpm.setUninstallBlocked(admin, ownPackage, blocked) }
            .onSuccess { KillitLog.d(KillitLog.DPC) { "setUninstallBlocked($blocked) ok" } }
            .onFailure { KillitLog.w(KillitLog.DPC, "setUninstallBlocked($blocked) failed", it) }
    }

    /**
     * Greys out Force stop for Killit. No-op below Android 11:
     * `setUserControlDisabledPackages` did not exist yet.
     *
     * @param disabled true to disable user control. Passing false clears the list rather than
     *   removing one entry, which is correct because Killit is the only package it ever holds.
     */
    private fun setUserControlDisabled(disabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            KillitLog.d(KillitLog.DPC) { "setUserControlDisabled skipped: needs API 30, running ${Build.VERSION.SDK_INT}" }
            return
        }
        val packages = if (disabled) listOf(ownPackage) else emptyList()
        runCatching { dpm.setUserControlDisabledPackages(admin, packages) }
            .onSuccess { KillitLog.d(KillitLog.DPC) { "setUserControlDisabledPackages($disabled) ok" } }
            .onFailure { KillitLog.w(KillitLog.DPC, "setUserControlDisabledPackages($disabled) failed", it) }
    }

    /**
     * Adds or clears one user restriction.
     *
     * @param key a `UserManager.DISALLOW_*` constant.
     * @param enabled true to impose the restriction, false to lift it.
     */
    private fun setRestriction(key: String, enabled: Boolean) {
        runCatching {
            if (enabled) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
        }
            .onSuccess { KillitLog.d(KillitLog.DPC) { "userRestriction $key=$enabled ok" } }
            .onFailure { KillitLog.w(KillitLog.DPC, "userRestriction $key=$enabled failed", it) }
    }

    companion object {
        /** Reported in [BlocklistResult.error] when policy is attempted before provisioning. */
        const val NOT_DEVICE_OWNER = "Killit is not the device owner"

        /**
         * Lists the keys whose values the system cannot persist as application restrictions.
         *
         * Restrictions are stored as XML that knows exactly six value types: `boolean`, `int`,
         * `String`, `String[]`, `Bundle` and `Bundle[]` (`null` is stored as an empty string).
         * `setApplicationRestrictions` accepts any bundle, but any other type — a `long` is the
         * easy one to reach for — makes system_server abandon the whole write.
         *
         * @param bundle the restrictions about to be written. Nested bundles are checked as well.
         * @param prefix the path of [bundle] inside the outermost one, for the report.
         * @return the offending keys as `outer/inner` paths; empty when the bundle can be stored.
         */
        internal fun unsupportedRestrictionKeys(bundle: Bundle, prefix: String = ""): List<String> =
            bundle.keySet().flatMap { key ->
                val path = prefix + key
                // There is no typed accessor for "whatever this key holds", only the deprecated one.
                @Suppress("DEPRECATION")
                when (val value = bundle.get(key)) {
                    null, is Boolean, is Int, is String -> emptyList()
                    is Bundle -> unsupportedRestrictionKeys(value, "$path/")
                    is Array<*> -> when {
                        value.isArrayOf<String>() -> emptyList()
                        value.all { it is Bundle } -> value.withIndex().flatMap { (index, element) ->
                            unsupportedRestrictionKeys(element as Bundle, "$path[$index]/")
                        }
                        else -> listOf(path)
                    }
                    else -> listOf(path)
                }
            }
    }
}

/**
 * Outcome of applying a blocklist.
 *
 * @param failedToBlock packages Android refused to suspend — they are still usable and their
 *   checkboxes must go back to unchecked.
 * @param failedToUnblock packages Android refused to unsuspend — still blocked despite the user
 *   asking otherwise.
 * @param error set when the whole operation failed rather than individual packages.
 */
data class BlocklistResult(
    val failedToBlock: List<String> = emptyList(),
    val failedToUnblock: List<String> = emptyList(),
    val error: String? = null,
) {
    /** True only when every requested change took effect and nothing failed outright. */
    val isSuccess: Boolean
        get() = error == null && failedToBlock.isEmpty() && failedToUnblock.isEmpty()
}
