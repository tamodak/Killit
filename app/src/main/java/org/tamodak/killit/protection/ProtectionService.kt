package org.tamodak.killit.protection

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.tamodak.killit.ServiceLocator
import org.tamodak.killit.core.KillitLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps default-blocking listening for installs.
 *
 * The package manager announces installs with implicit broadcasts, and since Android 8 an app can
 * only hear those through a receiver registered at run time — a receiver declared in the manifest
 * never gets `PACKAGE_ADDED`. So the receiver lives here, in a foreground service that keeps the
 * process running. The time between an app finishing installing and someone tapping the Play
 * Store's "Open" button is all the time Killit has to block it.
 *
 * The service type is `systemExempted`, which Android reserves for a few kinds of app, device owners
 * among them — and this service only runs while Killit is one. Its notification sits in the
 * lowest-importance channel there is.
 *
 * Every broadcast is only a hint that something changed: the receiver reads the package from the
 * package manager again rather than trusting anything in the intent. The actions it listens for are
 * protected broadcasts that only the system can send, but that would not be a reason to trust them
 * more.
 */
class ProtectionService : Service() {

    /** Lives as long as the service; cancelled in [onDestroy]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Hears package installs, updates and enable-state changes, and checks the package. */
    private val packageReceiver = object : BroadcastReceiver() {
        /**
         * Checks the package the broadcast names.
         *
         * @param context the service's context.
         * @param intent the broadcast; only its package name is used.
         */
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.data?.schemeSpecificPart ?: return
            KillitLog.d(KillitLog.GUARD) { "${intent.action} for $packageName" }
            scope.launch { ServiceLocator.packageGuard.checkPackage(packageName) }
        }
    }

    /** Registers the receiver; it stays registered for the service's whole life. */
    override fun onCreate() {
        super.onCreate()
        KillitLog.i(KillitLog.GUARD, "Protection service created")
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            // A disabled app being switched back on arrives as a change.
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // Exported, because these broadcasts come from the system; see the class documentation for
        // why that does not make them trusted.
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /**
     * Enters the foreground at once, then checks every package.
     *
     * `startForeground` comes first, before any work: a service started with
     * `startForegroundService` that does not call it promptly crashes the app. The notification is
     * therefore built in the device's language and replaced by one in Killit's own language once
     * that has been read.
     *
     * @param intent unused; null when the system restarts the service.
     * @param flags unused.
     * @param startId unused.
     * @return [START_STICKY], so the system restarts the service if it has to kill it.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = ServiceLocator.protectionNotifications
        notifications.createChannels()
        ServiceCompat.startForeground(
            this,
            ProtectionNotifications.ID_PROTECTION,
            notifications.protectionNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            } else {
                0
            },
        )

        scope.launch {
            val guard = ServiceLocator.packageGuard
            if (!guard.isActive()) {
                // Device owner was given up, or the passkey is gone; nothing left to protect.
                KillitLog.i(KillitLog.GUARD, "Protection no longer active; stopping the service")
                stopSelf()
                return@launch
            }
            notifications.showInAppLanguage()
            guard.checkAll("service start")
        }
        return START_STICKY
    }

    /** Unregisters the receiver and cancels any check still running. */
    override fun onDestroy() {
        KillitLog.i(KillitLog.GUARD, "Protection service destroyed")
        unregisterReceiver(packageReceiver)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Not bindable: the service only needs to be running.
     *
     * @param intent unused.
     * @return null.
     */
    override fun onBind(intent: Intent?): IBinder? = null
}
