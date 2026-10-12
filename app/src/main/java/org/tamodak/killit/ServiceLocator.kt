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
import org.tamodak.killit.net.ApiClient
import org.tamodak.killit.pairing.DeviceKeys
import org.tamodak.killit.pairing.GuardAppBlocker
import org.tamodak.killit.pairing.PairingEngine
import org.tamodak.killit.pairing.PairingNotifier
import org.tamodak.killit.pairing.PairingPreferences
import org.tamodak.killit.pairing.PairingStore
import org.tamodak.killit.pairing.PairingSync
import org.tamodak.killit.pairing.Registration
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

    /** `Build.MANUFACTURER` and `Build.MODEL`, fitted to the protocol's rule for them. */
    private fun deviceModel(): String =
        org.tamodak.killit.protocol.fitText(
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            org.tamodak.killit.protocol.TextRule.DEVICE_MODEL,
        )

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

    /** This phone's identity keys. */
    lateinit var deviceKeys: DeviceKeys
        private set

    /** Registers the identity with the server the first time pairing needs it. */
    lateinit var registration: Registration
        private set

    /** Small local facts about pairing: the registered key, the last name used. */
    lateinit var pairingPreferences: PairingPreferences
        private set

    /** Guardians, guarded phones, and the requests between them. */
    lateinit var pairing: PairingEngine
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

        // Pairing. Nothing here touches the disk or the network until it is first used.
        deviceKeys = DeviceKeys(appContext)
        pairingPreferences = PairingPreferences(appContext)
        val api = ApiClient(
            baseUrl = BuildConfig.API_BASE_URL,
            audience = BuildConfig.API_AUDIENCE,
            identity = { deviceKeys.current() },
        )
        val pairingStore = PairingStore(database.pairing(), durable)
        registration = Registration(deviceKeys, api, pairingPreferences, hasPairings = { database.pairing().pairings().isNotEmpty() })
        pairing = PairingEngine(
            store = pairingStore,
            server = api,
            identity = { registration.ensureRegistered() },
            apps = GuardAppBlocker(appContext, packageGuard, PackageScanner(appContext)),
            durable = durable,
            notifier = object : PairingNotifier {},
            config = PairingEngine.Config(
                deviceModel = deviceModel(),
                unpairTimerMillis = BuildConfig.UNPAIR_TIMER_MILLIS,
                unlockCooldownMillis = BuildConfig.UNLOCK_COOLDOWN_MILLIS,
                unlocksPerDay = BuildConfig.UNLOCKS_PER_DAY,
                silenceAlertMillis = BuildConfig.SILENCE_ALERT_MILLIS,
            ),
            canBeProtected = { devicePolicyController.isDeviceOwner() },
            onQueued = { PairingSync.soon(appContext) },
        )
        packageGuard.listener = PackageGuard.Listener { pairing.onAppsBlocked(it) }

        initialised = true
        KillitLog.i(KillitLog.APP, "Dependency graph ready")
    }
}
