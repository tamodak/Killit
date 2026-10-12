package org.tamodak.killit.pairing

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.protection.PackageGuard
import org.tamodak.killit.protection.PackageScanner
import org.tamodak.killit.protocol.AppInfo
import org.tamodak.killit.protocol.ICON_BYTES
import org.tamodak.killit.protocol.ICON_SIDE
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.fitText
import org.tamodak.killit.protocol.isPackageName

/**
 * [AppBlocker] on top of default-blocking: what a guardian is shown of an app (PLAN.md §5.4), and
 * the one way an approval reaches the app ([PackageGuard.approveRemotely]).
 *
 * What a guardian sees comes from this phone's package manager, and an app chooses its own label
 * and icon, so a malicious one can call itself "Calculator". The request therefore also carries the
 * package name and the installer, and the approval is pinned to the signing certificate.
 *
 * @param io where the package manager and icon rendering run.
 */
class GuardAppBlocker(
    context: Context,
    private val guard: PackageGuard,
    private val scanner: PackageScanner,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AppBlocker {
    private val appContext = context.applicationContext

    override suspend fun describe(packageName: String): AppInfo? = withContext(io) {
        val installed = scanner.scan(packageName) ?: return@withContext null
        val pm = appContext.packageManager
        val info = runCatching { pm.packageInfo(packageName) }.getOrNull() ?: return@withContext null
        val application = info.applicationInfo ?: return@withContext null
        AppInfo(
            packageName = packageName,
            certDigest = installed.certDigest,
            label = fitText(pm.getApplicationLabel(application).toString(), TextRule.APP_LABEL),
            installer = installerOf(pm, packageName),
            firstInstalled = info.firstInstallTime.coerceAtLeast(0L),
            icon = iconOf(pm, application),
        )
    }

    override suspend fun isBlocked(packageName: String): Boolean = guard.isBlocked(packageName)

    override suspend fun unblockApproved(packageName: String, certDigest: ByteArray): Boolean =
        guard.approveRemotely(packageName, certDigest)

    /** The installer's package name, or empty when unknown or sideloaded (SPEC.md §11.2). */
    private fun installerOf(pm: PackageManager, packageName: String): String {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION") // The only way to ask before Android 11.
                pm.getInstallerPackageName(packageName)
            }
        }.getOrNull()
        return installer?.takeIf(::isPackageName).orEmpty()
    }

    /**
     * Renders the app's icon as 32 × 32 RGBA, not premultiplied (SPEC.md §11.2). Raw pixels, so
     * the guardian's phone builds a bitmap without any image decoder parsing network input.
     */
    private fun iconOf(pm: PackageManager, application: ApplicationInfo): ByteArray? = runCatching {
        val bitmap = Bitmap.createBitmap(ICON_SIDE, ICON_SIDE, Bitmap.Config.ARGB_8888)
        pm.getApplicationIcon(application).apply { setBounds(0, 0, ICON_SIDE, ICON_SIDE) }.draw(Canvas(bitmap))
        val pixels = IntArray(ICON_SIDE * ICON_SIDE)
        // getPixels returns non-premultiplied sRGB ARGB, the form the protocol sends.
        bitmap.getPixels(pixels, 0, ICON_SIDE, 0, 0, ICON_SIDE, ICON_SIDE)
        bitmap.recycle()
        ByteArray(ICON_BYTES).also { out ->
            pixels.forEachIndexed { i, argb ->
                out[4 * i] = (argb ushr 16).toByte()
                out[4 * i + 1] = (argb ushr 8).toByte()
                out[4 * i + 2] = argb.toByte()
                out[4 * i + 3] = (argb ushr 24).toByte()
            }
        }
    }.onFailure { KillitLog.w(KillitLog.REQ, "Couldn't render the icon of ${application.packageName}", it) }.getOrNull()

    @Suppress("DEPRECATION") // The int-flag overload is the only one below Android 13.
    private fun PackageManager.packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            getPackageInfo(packageName, 0)
        }
}
