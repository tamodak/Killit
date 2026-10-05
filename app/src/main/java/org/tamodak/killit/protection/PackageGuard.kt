package org.tamodak.killit.protection

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.tamodak.killit.admin.BlocklistResult
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.KnownPackage
import org.tamodak.killit.data.KnownPackagesStore
import org.tamodak.killit.data.LockRepository
import org.tamodak.killit.data.PackageStatus
import org.tamodak.killit.protection.NewPackagePolicy.Decision
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Default-blocking: every package installed after protection starts is blocked until someone
 * decides otherwise.
 *
 * ### When it is active
 *
 * Only while Killit is device owner *and* a passkey is set. Before provisioning nothing can be
 * blocked; and blocking a new app before there is a passkey would make a fresh install look like
 * one whose data was cleared — apps blocked, no passkey — which sends the user to the tampered
 * screen.
 *
 * ### What it does
 *
 * The first check after it becomes active takes a snapshot: everything installed is approved, or
 * recorded as blocked if Killit already blocks it. After that, [NewPackagePolicy] decides about
 * each package, and this class carries the decisions out — blocking, recording, and telling the
 * user. It never unblocks anything; only the user's choices in the app list do (see
 * [applySelection]).
 *
 * ### Concurrency
 *
 * The protection service's receiver, the periodic check and the app list can all act at once, so
 * every operation holds [lock]. Each reads the list, decides and writes back as one step, which
 * keeps two of them from undoing each other.
 *
 * @param context any context; only its application context is retained, for app labels.
 * @param dpc blocks packages.
 * @param scanner reads installed packages with their certificates.
 * @param store what has been decided, in both stores.
 * @param lockRepository answers whether a passkey is set.
 * @param notifications tells the user what was blocked.
 * @param ioDispatcher where app labels are resolved.
 * @param clock the wall clock decisions are dated with.
 */
class PackageGuard(
    context: Context,
    private val dpc: DevicePolicyController,
    private val scanner: PackageScanner,
    private val store: KnownPackagesStore,
    private val lockRepository: LockRepository,
    private val notifications: ProtectionNotifications,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Held rather than the passed context, so this class can outlive any activity. */
    private val appContext = context.applicationContext

    /** Killit's own package, which is never judged. */
    private val ownPackage = appContext.packageName

    /** Serialises every operation; see the class documentation. */
    private val lock = Mutex()

    /** Every decision made so far, re-emitted on every change. Backs the app list. */
    val packages: Flow<Map<String, KnownPackage>> = store.packages

    /**
     * Reports whether default-blocking should be running.
     *
     * @return true when Killit is device owner and a passkey is set.
     */
    suspend fun isActive(): Boolean = dpc.isDeviceOwner() && lockRepository.hasCredential()

    /**
     * Checks every installed package. The safety net behind the protection service's receiver:
     * run when the service starts, on boot, when Killit is opened, and periodically.
     *
     * @param reason names the trigger in the log.
     */
    suspend fun checkAll(reason: String) = lock.withLock {
        if (!isActive()) {
            KillitLog.d(KillitLog.GUARD) { "checkAll($reason) skipped: not active" }
            return@withLock
        }
        KillitLog.i(KillitLog.GUARD, "Checking every package ($reason)")
        val installed = scanner.scanAll()
        val known = store.readAll()
        if (known.isEmpty()) takeSnapshot(installed) else carryOut(installed, known)
    }

    /**
     * Checks one package, as soon as the package manager announces it.
     *
     * @param packageName the package that was added, replaced or changed.
     */
    suspend fun checkPackage(packageName: String) = lock.withLock {
        if (!isActive()) return@withLock
        val known = store.readAll()
        if (known.isEmpty()) {
            takeSnapshot(scanner.scanAll())
            return@withLock
        }
        // Null when it was removed again before the check ran; entries outlive the app anyway.
        val pkg = scanner.scan(packageName) ?: return@withLock
        carryOut(listOf(pkg), known)
    }

    /**
     * Applies the user's choices from the app list, and records them.
     *
     * An app the user unblocks becomes approved under the certificate it has now; one they block
     * becomes blocked on purpose. Apps the platform refused to change keep their old decision.
     *
     * @param desired the packages that should end up blocked.
     * @param current the packages blocked now.
     * @return which packages the platform refused, or the error that stopped the whole operation.
     */
    suspend fun applySelection(desired: Set<String>, current: Set<String>): BlocklistResult = lock.withLock {
        val result = dpc.applyBlocklist(desired, current)
        if (!isActive()) return@withLock result

        val now = clock()
        val unblocked = (current - desired) - result.failedToUnblock.toSet()
        val blocked = (desired - current) - result.failedToBlock.toSet()
        val decisions = unblocked.map { it to PackageStatus.APPROVED } + blocked.map { it to PackageStatus.USER_BLOCKED }
        val updates = decisions.mapNotNull { (name, status) ->
            scanner.scan(name)?.let { KnownPackage(name, it.certDigest, status, now) }
        }
        store.put(updates)
        if (unblocked.isNotEmpty()) refreshWaitingNotification(store.readAll(), alert = false)
        result
    }

    /**
     * Keeps every app that is waiting for a decision blocked, and stops counting it as new.
     */
    suspend fun keepWaitingAppsBlocked() = lock.withLock {
        val now = clock()
        val waiting = store.readAll().values.filter { it.status == PackageStatus.PENDING }
        KillitLog.i(KillitLog.GUARD, "Keeping ${waiting.size} new apps blocked")
        store.put(waiting.map { it.copy(status = PackageStatus.USER_BLOCKED, sinceMillis = now) })
        refreshWaitingNotification(emptyMap(), alert = false)
    }

    /**
     * Forgets every decision, so protecting the phone again starts from a fresh snapshot. Called
     * before Killit gives up device owner, while it can still clear its durable copy.
     */
    suspend fun forget() = lock.withLock {
        store.clear()
        refreshWaitingNotification(emptyMap(), alert = false)
    }

    /**
     * Records everything installed now, without blocking anything new.
     *
     * Apps Killit already blocks become blocked on purpose; everything else is approved. Disabled
     * packages are left out, so switching one on later makes it new.
     *
     * @param installed every installed package.
     */
    private suspend fun takeSnapshot(installed: List<InstalledPackage>) {
        val enabled = installed.filter { it.isEnabled && it.packageName != ownPackage }
        val blockedNow = dpc.blockedPackages(enabled.map { it.packageName })
        val now = clock()
        store.put(
            enabled.map { pkg ->
                val status = if (pkg.packageName in blockedNow) PackageStatus.USER_BLOCKED else PackageStatus.APPROVED
                KnownPackage(pkg.packageName, pkg.certDigest, status, now)
            }
        )
        KillitLog.i(
            KillitLog.GUARD,
            "Protection started: snapshot of ${enabled.size} packages, ${blockedNow.size} of them blocked",
        )
    }

    /**
     * Decides about each package and carries the decisions out.
     *
     * Every package that must be blocked is blocked again in one call, the known ones included.
     * That is cheap — blocking a blocked package changes nothing — and it is what re-blocks an app
     * that was uninstalled and installed again.
     *
     * A new package the platform refuses to block is recorded as approved, since it is usable
     * either way; the app list shows it as allowed, and trying to block it there reports the
     * refusal.
     *
     * @param installed the packages to decide about.
     * @param known every decision made so far.
     */
    private suspend fun carryOut(installed: List<InstalledPackage>, known: Map<String, KnownPackage>) {
        val now = clock()
        val updates = mutableListOf<KnownPackage>()
        val mustBeBlocked = mutableSetOf<String>()
        val newlyBlocked = mutableListOf<KnownPackage>()

        installed.forEach { pkg ->
            when (val decision = NewPackagePolicy.decide(pkg, known[pkg.packageName], ownPackage)) {
                Decision.Ignore, Decision.Keep -> Unit
                Decision.EnforceBlock -> mustBeBlocked += pkg.packageName
                is Decision.AdoptRotatedKey -> {
                    KillitLog.i(KillitLog.GUARD, "${pkg.packageName} moved to a rotated signing key; decision kept")
                    updates += decision.updated
                    if (decision.updated.status.isBlocked) mustBeBlocked += pkg.packageName
                }
                Decision.AutoApprove -> {
                    KillitLog.i(KillitLog.GUARD, "Approved new system package $pkg")
                    updates += KnownPackage(pkg.packageName, pkg.certDigest, PackageStatus.APPROVED, now)
                }
                Decision.BlockAsNew -> {
                    KillitLog.i(KillitLog.GUARD, "Blocking new package $pkg")
                    mustBeBlocked += pkg.packageName
                    newlyBlocked += KnownPackage(pkg.packageName, pkg.certDigest, PackageStatus.PENDING, now)
                }
            }
        }

        val refused = dpc.blockPackages(mustBeBlocked).toSet()
        newlyBlocked.forEach { entry ->
            updates += if (entry.packageName in refused) entry.copy(status = PackageStatus.APPROVED) else entry
        }
        store.put(updates)

        if (newlyBlocked.any { it.packageName !in refused }) {
            refreshWaitingNotification(store.readAll(), alert = true)
        }
    }

    /**
     * Brings the blocked-apps notification in line with what is waiting now.
     *
     * @param known every decision made so far.
     * @param alert whether the update should make a sound.
     */
    private suspend fun refreshWaitingNotification(known: Map<String, KnownPackage>, alert: Boolean) {
        val waiting = known.values
            .filter { it.status == PackageStatus.PENDING }
            .sortedByDescending { it.sinceMillis }
        notifications.showWaitingApps(labelsOf(waiting.map { it.packageName }), alert)
    }

    /**
     * Resolves the names apps show under their icons.
     *
     * @param packageNames the packages to name.
     * @return their labels, in the same order; the package name for one that cannot be resolved.
     */
    private suspend fun labelsOf(packageNames: List<String>): List<String> = withContext(ioDispatcher) {
        val pm = appContext.packageManager
        packageNames.map { name ->
            runCatching { pm.getApplicationLabel(pm.applicationInfo(name)).toString() }.getOrDefault(name)
        }
    }

    /**
     * Reads an installed application's info, through whichever overload this Android version has.
     *
     * @param packageName the package to read.
     * @return its application info.
     * @throws PackageManager.NameNotFoundException when it is not installed.
     */
    @Suppress("DEPRECATION") // The int-flag overload is the only one below Android 13.
    private fun PackageManager.applicationInfo(packageName: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            getApplicationInfo(packageName, 0)
        }
}
