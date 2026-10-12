package org.tamodak.killit.pairing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.tamodak.killit.ServiceLocator
import org.tamodak.killit.core.KillitLog

/**
 * When pairing syncs in the background (PLAN.md §7): every 15 minutes while there is anything to
 * sync, soon after something was queued and couldn't be sent, and when an UNPAIR timer runs out.
 * A push only ever asks for [soon]; it never carries anything to act on.
 *
 * Every sync needs a network; WorkManager waits for one and keeps the schedule across reboots.
 */
object PairingSync {
    private const val PERIODIC = "pairing-sync-periodic"
    private const val SOON = "pairing-sync-soon"
    private const val TIMER = "pairing-sync-timer"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Syncs once, as soon as there is a network. A request made during a sync runs after it. */
    fun soon(context: Context) {
        val request = OneTimeWorkRequestBuilder<PairingSyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Keeps the 15-minute background sync scheduled. Cheap to call again. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<PairingSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Syncs when [atMillis] comes, for an UNPAIR timer; the 15-minute sync would only come later. */
    fun at(context: Context, atMillis: Long) {
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<PairingSyncWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(TIMER, ExistingWorkPolicy.REPLACE, request)
    }

    /** Stops every background sync: nothing is paired any more. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(SOON)
            cancelUniqueWork(TIMER)
        }
    }
}

/**
 * One background sync. A network failure is retried with backoff; anything else is logged and
 * left for the next periodic run, since retrying a bug only repeats it.
 *
 * @param context the application context WorkManager supplies.
 * @param params WorkManager's parameters; unused beyond the superclass.
 */
class PairingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = ServiceLocator.pairing
        if (!engine.hasWork()) {
            PairingSync.cancel(applicationContext)
            return Result.success()
        }
        return try {
            engine.sync()
            engine.requests.nextTimer()?.let { PairingSync.at(applicationContext, it) }
            Result.success()
        } catch (e: IOException) {
            KillitLog.d(KillitLog.SYNC) { "Background sync failed: ${e.message}" }
            Result.retry()
        } catch (e: Exception) {
            KillitLog.e(KillitLog.SYNC, "Background sync failed", e)
            Result.failure()
        }
    }
}
