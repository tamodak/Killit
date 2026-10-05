package org.tamodak.killit.protection

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.tamodak.killit.core.KillitLog
import java.util.concurrent.TimeUnit

/**
 * Starts and stops everything that keeps default-blocking running: [ProtectionService] and the
 * periodic [ProtectionCheckWorker].
 *
 * Called from every place that can make protection start or resume — opening Killit, setting the
 * passkey, becoming device owner, the phone booting, Killit being updated, the periodic check — so
 * it is safe to call repeatedly: a running service just checks again, and the periodic work is
 * scheduled once and kept.
 */
object Protection {

    /** Unique name of the periodic check, so scheduling it again keeps the existing schedule. */
    private const val PERIODIC_CHECK = "protection-check"

    /**
     * How often the periodic check runs: WorkManager's minimum. The service's receiver catches
     * installs within milliseconds; this only matters when the service is not running, so the
     * shorter the gap, the better.
     */
    private const val PERIODIC_CHECK_MINUTES = 15L

    /**
     * Starts the service and schedules the periodic check, when default-blocking should run.
     *
     * Device owners may start foreground services from the background, which is what lets the
     * boot receiver and the periodic check call this.
     *
     * @param context any context; only its application context is used.
     * @param guard answers whether protection should run.
     */
    suspend fun ensureRunning(context: Context, guard: PackageGuard) {
        if (!guard.isActive()) {
            KillitLog.d(KillitLog.GUARD) { "Protection not started: not active" }
            return
        }
        val appContext = context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(appContext, Intent(appContext, ProtectionService::class.java))
        }.onFailure { KillitLog.e(KillitLog.GUARD, "Could not start the protection service", it) }

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_CHECK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ProtectionCheckWorker>(PERIODIC_CHECK_MINUTES, TimeUnit.MINUTES).build(),
        )
    }

    /**
     * Stops the service and cancels the periodic check, for when Killit gives up device owner.
     *
     * @param context any context; only its application context is used.
     */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        KillitLog.i(KillitLog.GUARD, "Stopping protection")
        appContext.stopService(Intent(appContext, ProtectionService::class.java))
        WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_CHECK)
    }
}
