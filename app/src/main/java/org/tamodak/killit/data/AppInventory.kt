package org.tamodak.killit.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.tamodak.killit.core.KillitLog
import java.text.Collator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One installed package as shown in the picker.
 *
 * @param packageName the identity Killit suspends by; unique and stable across renames.
 * @param label the user-visible application name, already resolved from the package manager.
 * @param isSystem preinstalled or an update to a preinstalled app; drives the Installed/System tab
 *   split. Killit can suspend either, so this is presentation only.
 * @param isLaunchable whether the package has a launcher entry. Packages without one are hidden
 *   behind the "show all" toggle, because a list of 400 service packages is not browsable.
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isLaunchable: Boolean,
)

/**
 * Reads the installed package list and decodes launcher icons.
 *
 * Killit's own package is excluded — Android refuses to suspend it anyway, and offering the
 * checkbox would be misleading.
 *
 * Requires `QUERY_ALL_PACKAGES`: Killit has to show every installed app so the user can choose what
 * to block, which a `<queries>` declaration cannot express.
 *
 * @param context any context; only its application context is retained.
 * @param ioDispatcher where package-manager calls and icon decoding run. Injectable so tests can
 *   supply a deterministic dispatcher.
 */
class AppInventory(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Held rather than the passed context, so this class can outlive any activity. */
    private val appContext = context.applicationContext

    /** The package manager every query below goes through. */
    private val pm = appContext.packageManager

    /** Killit's own package name, filtered out of [load]. */
    private val ownPackage = appContext.packageName

    /**
     * Decoded icons, keyed by package name.
     *
     * [LruCache] is internally synchronised, which matters because [icon] is called concurrently
     * from one coroutine per visible list row.
     */
    private val iconCache = LruCache<String, ImageBitmap>(ICON_CACHE_ENTRIES)

    /**
     * Loads the full package list.
     *
     * Sorted by label using a locale-aware [Collator] so Turkish, German and Swedish labels sort
     * the way a speaker of those languages expects rather than by UTF-16 code unit.
     *
     * Timed because on a device with several hundred packages this is one of the slower startup
     * steps, and it runs before the app list can be shown.
     *
     * @return every installed package except Killit itself, sorted by label.
     */
    suspend fun load(): List<AppEntry> = withContext(ioDispatcher) {
        KillitLog.timed(KillitLog.APPS, "load installed packages") {
            val collator = Collator.getInstance()
            val installed = installedApplications()
            val launchable = pm.queryLaunchablePackages()

            val entries = installed
                .asSequence()
                .filter { it.packageName != ownPackage }
                .map { info ->
                    AppEntry(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        isSystem = info.flags and SYSTEM_FLAGS != 0,
                        isLaunchable = info.packageName in launchable,
                    )
                }
                .sortedWith(compareBy(collator) { it.label })
                .toList()

            KillitLog.i(
                KillitLog.APPS,
                "Loaded ${entries.size} packages " +
                    "(${entries.count { it.isSystem }} system, " +
                    "${entries.count { it.isLaunchable }} launchable), " +
                    "excluding Killit itself",
            )
            entries
        }
    }

    /**
     * Decodes one launcher icon.
     *
     * Adaptive icons are expensive to rasterise, so results are cached and the work always happens
     * off the main thread — a device can easily have 400 packages, and scrolling the list would
     * otherwise decode continuously.
     *
     * Traced at VERBOSE rather than DEBUG: one line per row would drown everything else.
     *
     * @param packageName the package whose icon is wanted.
     * @return the decoded icon, or null if the package has none that can be rasterised — usually
     *   because it was uninstalled since [load] ran.
     */
    suspend fun icon(packageName: String): ImageBitmap? = withContext(ioDispatcher) {
        iconCache.get(packageName)?.let { cached ->
            KillitLog.v(KillitLog.APPS) { "icon cache hit: $packageName" }
            return@withContext cached
        }

        val bitmap = runCatching {
            pm.getApplicationIcon(packageName)
                .toBitmap(width = ICON_PX, height = ICON_PX)
                .asImageBitmap()
        }.getOrElse { error ->
            // Usually the package was uninstalled between load() and this row scrolling into view.
            KillitLog.v(KillitLog.APPS) { "icon decode failed for $packageName: ${error.javaClass.simpleName}" }
            null
        }

        bitmap?.also {
            iconCache.put(packageName, it)
            KillitLog.v(KillitLog.APPS) { "icon decoded and cached: $packageName" }
        }
    }

    /**
     * Reads every installed application across both platform overloads.
     *
     * The flagged and non-flagged overloads do the same thing; Android just deprecated the int
     * form in Tiramisu. Failure returns an empty list so the UI degrades to "no apps" rather than
     * crashing.
     *
     * @return every installed application, or an empty list if the query failed.
     */
    @Suppress("DEPRECATION")
    private fun installedApplications(): List<ApplicationInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getInstalledApplications(0)
        }
    }.getOrElse { error ->
        KillitLog.e(KillitLog.APPS, "getInstalledApplications failed; showing an empty list", error)
        emptyList()
    }

    private companion object {
        /** Decode size in pixels. Rows render at 40dp, so this covers up to ~2.4x density. */
        const val ICON_PX = 96

        /** Cache depth. Comfortably more than fits on screen, so scrolling back never re-decodes. */
        const val ICON_CACHE_ENTRIES = 256

        /** An updated system app keeps FLAG_SYSTEM off but gains FLAG_UPDATED_SYSTEM_APP. */
        const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}

/**
 * Resolves which packages have a launcher entry, in **two** queries rather than one per package.
 *
 * `getLaunchIntentForPackage` is the obvious call, but it crosses a binder each time — 167 round
 * trips on a typical device, and measurably the second-largest cost of building the app list. The
 * platform can answer the same question for all packages at once, so it is asked once.
 *
 * Both categories are queried because `getLaunchIntentForPackage` itself falls back from LAUNCHER
 * to INFO, and matching that keeps every caller's idea of "launchable" the same as that call's.
 * Blocking: call it on a background dispatcher.
 *
 * @return the launchable package names, or an empty set if the query failed.
 */
internal fun PackageManager.queryLaunchablePackages(): Set<String> = runCatching {
    KillitLog.timed(KillitLog.APPS, "query launchable packages") {
        val categories = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_INFO)
        categories.flatMapTo(mutableSetOf()) { category ->
            @Suppress("DEPRECATION")
            queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .map { it.activityInfo.packageName }
        }
    }
}.getOrElse { error ->
    KillitLog.w(KillitLog.APPS, "queryIntentActivities failed; treating nothing as launchable", error)
    emptySet()
}
