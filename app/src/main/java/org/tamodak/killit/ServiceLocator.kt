package org.tamodak.killit

import android.content.Context
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.AppInventory
import org.tamodak.killit.data.CredentialStore
import org.tamodak.killit.data.DurableStore
import org.tamodak.killit.data.KnownPackagesStore
import org.tamodak.killit.data.LockPreferences
import org.tamodak.killit.data.LockRepository
import org.tamodak.killit.data.db.KillitDatabase
import org.tamodak.killit.protection.PackageGuard
import org.tamodak.killit.protection.PackageScanner
import org.tamodak.killit.protection.ProtectionNotifications

/**
 * Hand-rolled dependency graph.
 *
 * The app has one screen stack and a handful of singletons, so a DI framework would be more
 * machinery than the whole feature set. The trade-off is real and worth naming: because this is a
 * global `object`, a test cannot substitute fakes for these singletons. The classes it builds all
 * take their collaborators and dispatchers as constructor parameters, so they *are* individually
 * testable — it is only the graph itself that is fixed.
 *
 * Everything here is constructed eagerly from `Application.onCreate`, so construction must stay
 * cheap: no disk, no binder, no network. Each class defers its real work to its first suspending
 * call.
 */
object ServiceLocator {

    /**
     * Guards [init] against rebuilding the graph. `@Volatile` because the flag is written under the
     * `init` lock but read from whichever thread touches the graph first.
     */
    @Volatile
    private var initialised = false

    /** Every call into `DevicePolicyManager`: suspension, hardening, provisioning state. */
    lateinit var devicePolicyController: DevicePolicyController
        private set

    /** The passkey record and its reconciliation across the local and durable stores. */
    lateinit var lockRepository: LockRepository
        private set

    /** Installed-package inventory backing the app-selection list. */
    lateinit var appInventory: AppInventory
        private set

    /** Default-blocking: blocks every app installed after protection started. */
    lateinit var packageGuard: PackageGuard
        private set

    /** The protection service's notification and the blocked-apps one. */
    lateinit var protectionNotifications: ProtectionNotifications
        private set

    /**
     * Builds the graph.
     *
     * Idempotent and `@Synchronized`, so a second call — from a test, or from a second process
     * attaching to the same Application class — is a no-op rather than a rebuild that would hand
     * out two DataStore instances over one file.
     *
     * @param context any context; only its application context is retained.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialised) {
            KillitLog.d(KillitLog.APP) { "ServiceLocator.init called again; already initialised" }
            return
        }
        val appContext = context.applicationContext

        devicePolicyController = DevicePolicyController(appContext)
        appInventory = AppInventory(appContext)
        // Exactly one of each per process. The durable store's lock only serialises writers that
        // share it, and Room expects a single database instance.
        val durable = DurableStore(devicePolicyController)
        val database = KillitDatabase.create(appContext)
        lockRepository = LockRepository(
            // Local cache and pre-provisioning bootstrap.
            prefs = LockPreferences(appContext),
            // Master copy once Killit is device owner; survives "Clear data".
            durable = durable,
            // Argon2id hashing of the passkey.
            credentials = CredentialStore(),
        )
        protectionNotifications = ProtectionNotifications(appContext, language = { lockRepository.language() })
        packageGuard = PackageGuard(
            context = appContext,
            dpc = devicePolicyController,
            scanner = PackageScanner(appContext),
            store = KnownPackagesStore(database.knownPackages(), durable),
            lockRepository = lockRepository,
            notifications = protectionNotifications,
        )

        initialised = true
        KillitLog.i(KillitLog.APP, "Dependency graph ready")
    }
}
