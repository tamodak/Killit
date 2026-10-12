package org.tamodak.killit.pairing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import org.tamodak.killit.BuildConfig
import org.tamodak.killit.ServiceLocator
import org.tamodak.killit.core.KillitLog

/**
 * The guardian's reminder about requests still waiting for its answer (PLAN.md §3.6, D9): one
 * digest a day, in the daytime, while any waits. A live unlock never needs one: it is answered
 * while its requester waits, or not at all.
 *
 * Debug builds remind once, [BuildConfig.REMINDER_DELAY_MILLIS] after a request arrives, so the
 * reminder can be seen without waiting for tomorrow (§11, "Durations per build type").
 */
object Reminders {
    private const val WORK = "pairing-reminder"

    /** When the daily digest comes, in local time. */
    private val DIGEST_AT: LocalTime = LocalTime.of(10, 0)

    /** True when reminders come once, shortly after a request, instead of daily. */
    val once: Boolean get() = BuildConfig.REMINDER_DELAY_MILLIS > 0

    /** Makes sure a reminder will come for a request that just arrived. Cheap to call again. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (once) {
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(BuildConfig.REMINDER_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            work.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
        } else {
            // Daily from the next 10:00; the worker itself skips the days nothing waits.
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(untilNextDigest().toMillis(), TimeUnit.MILLISECONDS)
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    /** Stops the reminders: this phone guards nobody any more. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    private fun untilNextDigest(): Duration {
        val now = ZonedDateTime.now()
        val today = now.with(DIGEST_AT)
        return Duration.between(now, if (today.isAfter(now)) today else today.plusDays(1))
    }
}

/**
 * Shows the reminder: every request still waiting for this phone's answer, other than live
 * unlocks. Each is reminded about once in debug builds, every day in release builds.
 *
 * @param context the application context WorkManager supplies.
 * @param params WorkManager's parameters; unused beyond the superclass.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = ServiceLocator.pairing
        if (engine.store.wards().isEmpty()) {
            Reminders.cancel(applicationContext)
            return Result.success()
        }
        val waiting = engine.remind(onlyOnce = Reminders.once)
        KillitLog.d(KillitLog.PAIR) { "Reminder: ${waiting.size} requests waiting" }
        if (waiting.isNotEmpty()) ServiceLocator.pairingNotifications.showReminder(waiting)
        return Result.success()
    }
}
