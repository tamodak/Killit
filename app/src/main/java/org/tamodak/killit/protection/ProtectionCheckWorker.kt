package org.tamodak.killit.protection

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.tamodak.killit.ServiceLocator
import org.tamodak.killit.core.KillitLog

/**
 * The periodic safety net behind [ProtectionService].
 *
 * The service's receiver catches installs as they happen, but only while the service runs. If the
 * system ever kills it, or an install lands in a gap, this check finds the new package within
 * minutes and starts the service again. WorkManager keeps the schedule across reboots and process
 * death, which is why it is used rather than a timer in the service itself.
 *
 * @param context the application context WorkManager supplies.
 * @param params WorkManager's parameters; unused beyond the superclass.
 */
class ProtectionCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Checks every package and makes sure the service is running, or stops everything when
     * protection is no longer active.
     *
     * @return success always: a check that found nothing to do has still done its job.
     */
    override suspend fun doWork(): Result {
        val guard = ServiceLocator.packageGuard
        if (!guard.isActive()) {
            KillitLog.i(KillitLog.GUARD, "Periodic check found protection inactive; stopping it")
            Protection.stop(applicationContext)
            return Result.success()
        }
        guard.checkAll("periodic check")
        Protection.ensureRunning(applicationContext, guard)
        return Result.success()
    }
}
