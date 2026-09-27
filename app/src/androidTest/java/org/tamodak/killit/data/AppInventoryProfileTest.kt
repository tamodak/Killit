package org.tamodak.killit.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Attributes the cost of building the app list to the individual PackageManager calls behind it.
 *
 * `AppInventory.load` makes two calls per installed package, and which of them dominates decides
 * what a fix would even look like — so this measures rather than guesses.
 */
@RunWith(AndroidJUnit4::class)
class AppInventoryProfileTest {

    /** The instrumentation context, standing in for the app's own. */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The package manager under measurement. */
    private val pm: PackageManager = context.packageManager

    /**
     * Times each of the three package-manager calls separately.
     *
     * Asserts nothing — the output is the point. Read the timings with
     * `adb logcat -s KillitProfile`; the per-package `getLaunchIntentForPackage` loop is the one
     * `AppInventory` replaced with two batched queries, and this is what showed it was worth doing.
     */
    @Test
    fun profileInstalledAppScan() {
        @Suppress("DEPRECATION")
        val installed = measured("getInstalledApplications") { pm.getInstalledApplications(0) }

        val labels = measured("getApplicationLabel x${installed.size}") {
            installed.map { pm.getApplicationLabel(it).toString() }
        }

        val launchable = measured("getLaunchIntentForPackage x${installed.size}") {
            installed.count { pm.getLaunchIntentForPackage(it.packageName) != null }
        }

        Log.i(TAG, "Scanned ${installed.size} packages, $launchable launchable, ${labels.size} labels")
    }

    /**
     * Runs a block and logs how long it took.
     *
     * Uses [SystemClock.elapsedRealtime] for the same reason `KillitLog.timed` does: a clock
     * adjustment mid-measurement cannot produce a nonsense duration.
     *
     * @param label names the call in the log line.
     * @param block the call to measure.
     * @return whatever [block] returns.
     */
    private inline fun <T> measured(label: String, block: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        val result = block()
        Log.i(TAG, "$label took ${SystemClock.elapsedRealtime() - startedAt}ms")
        return result
    }

    private companion object {
        /** Its own tag, not a `KillitLog` one, so the timings can be read without app noise. */
        const val TAG = "KillitProfile"
    }
}
