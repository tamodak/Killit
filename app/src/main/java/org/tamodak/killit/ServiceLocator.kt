package org.tamodak.killit

import android.content.Context
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.AppInventory
import org.tamodak.killit.data.CredentialStore
import org.tamodak.killit.data.DurableStore
import org.tamodak.killit.data.LockPreferences
import org.tamodak.killit.data.LockRepository
import org.tamodak.killit.pairing.PairingManager
import org.tamodak.killit.pairing.PeerKeyStore

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

    /** Companion-device pairing: key material, QR payloads, challenge/response. */
    lateinit var pairingManager: PairingManager
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
        lockRepository = LockRepository(
            // Local cache and pre-provisioning bootstrap.
            prefs = LockPreferences(appContext),
            // Master copy once Killit is device owner; survives "Clear data".
            durable = DurableStore(devicePolicyController),
            // Salted SHA-256 of the passkey.
            credentials = CredentialStore(),
        )
        pairingManager = PairingManager(
            repository = lockRepository,
            keyStore = PeerKeyStore(),
        )

        initialised = true
        KillitLog.i(KillitLog.APP, "Dependency graph ready")
    }
}
