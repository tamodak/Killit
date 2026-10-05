package org.tamodak.killit.protection

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.queryLaunchablePackages
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads installed packages the way default-blocking needs them: with the certificates they are
 * signed with.
 *
 * Needs `QUERY_ALL_PACKAGES`, like the app list: a decision about every installed package needs to
 * see every installed package.
 *
 * A package whose certificates cannot be read is left out, so it is neither blocked nor approved.
 * The package manager always has them for an installed package, so this only happens to one that
 * is being removed at that moment — and blocking something on a failed read could just as well hit
 * a part of the system.
 *
 * @param context any context; only its application context is retained.
 * @param ioDispatcher where package-manager calls run.
 */
class PackageScanner(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** The package manager every query goes through. */
    private val pm = context.applicationContext.packageManager

    /**
     * Reads every installed package.
     *
     * One query for the list and two for launcher entries, rather than per-package calls: this
     * runs on every check, and a phone can have several hundred packages.
     *
     * @return every package whose certificates could be read.
     */
    suspend fun scanAll(): List<InstalledPackage> = withContext(ioDispatcher) {
        KillitLog.timed(KillitLog.GUARD, "scan all packages") {
            val launchable = pm.queryLaunchablePackages()
            installedPackages().mapNotNull { it.toInstalledPackage(it.packageName in launchable) }
        }
    }

    /**
     * Reads one package.
     *
     * @param packageName the package to read.
     * @return the package, or null when it is not installed or its certificates cannot be read.
     */
    suspend fun scan(packageName: String): InstalledPackage? = withContext(ioDispatcher) {
        val info = runCatching { packageInfo(packageName) }.getOrElse { error ->
            KillitLog.d(KillitLog.GUARD) { "scan($packageName): not installed (${error.javaClass.simpleName})" }
            return@withContext null
        }
        info.toInstalledPackage(isLaunchable = pm.getLaunchIntentForPackage(packageName) != null)
    }

    /**
     * Builds the snapshot of one package.
     *
     * @param isLaunchable whether the package has a launcher entry, resolved by the caller.
     * @return the snapshot, or null when the certificates cannot be read.
     */
    private fun PackageInfo.toInstalledPackage(isLaunchable: Boolean): InstalledPackage? {
        val app = applicationInfo ?: return null
        val signers = signers()
        if (signers == null) {
            KillitLog.w(KillitLog.GUARD, "No signing certificates for $packageName; leaving it alone")
            return null
        }
        return InstalledPackage(
            packageName = packageName,
            certDigest = SigningDigest.ofSigners(signers.current),
            lineage = signers.lineage.map(SigningDigest::of),
            isSystem = app.flags and SYSTEM_FLAGS != 0,
            isLaunchable = isLaunchable,
            isOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && app.isResourceOverlay,
            isEnabled = app.enabled,
        )
    }

    /**
     * The certificates a package is signed with, as DER encodings.
     *
     * From Android 9 the platform also keeps the rotation history of a single signing key, which is
     * what lets an approved app move to a new key without counting as new. Below that, and for apps
     * signed by several certificates, there is no history.
     *
     * @return the current signers and the rotation history, or null when there are no signers.
     */
    @Suppress("DEPRECATION") // `signatures` is the only source below Android 9.
    private fun PackageInfo.signers(): Signers? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo ?: return null
            val current = info.apkContentsSigners.orEmpty().map { it.toByteArray() }
            if (current.isEmpty()) return null
            val lineage = if (info.hasMultipleSigners()) {
                emptyList()
            } else {
                info.signingCertificateHistory.orEmpty().map { it.toByteArray() }
            }
            return Signers(current, lineage)
        }
        val current = signatures.orEmpty().map { it.toByteArray() }
        return if (current.isEmpty()) null else Signers(current, emptyList())
    }

    /**
     * Reads every installed package with its certificates.
     *
     * @return the packages, or an empty list when the query failed — which makes the check that
     *   asked do nothing, rather than act on a partial picture.
     */
    @Suppress("DEPRECATION") // The int-flag overload is the only one below Android 13.
    private fun installedPackages(): List<PackageInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(SIGNING_FLAG.toLong()))
        } else {
            pm.getInstalledPackages(SIGNING_FLAG)
        }
    }.getOrElse { error ->
        KillitLog.e(KillitLog.GUARD, "getInstalledPackages failed; skipping this check", error)
        emptyList()
    }

    /**
     * Reads one installed package with its certificates.
     *
     * @param packageName the package to read.
     * @return the package.
     * @throws PackageManager.NameNotFoundException when it is not installed.
     */
    @Suppress("DEPRECATION") // The int-flag overload is the only one below Android 13.
    private fun packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(SIGNING_FLAG.toLong()))
        } else {
            pm.getPackageInfo(packageName, SIGNING_FLAG)
        }

    /**
     * A package's signing certificates.
     *
     * @param current the certificates the APK is signed with now.
     * @param lineage the rotation history, oldest first, the current certificate included.
     */
    private class Signers(val current: List<ByteArray>, val lineage: List<ByteArray>)

    private companion object {
        /** An updated system app keeps FLAG_SYSTEM off but gains FLAG_UPDATED_SYSTEM_APP. */
        const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP

        /** Asks for signing certificates, with rotation history from Android 9. */
        @Suppress("DEPRECATION")
        val SIGNING_FLAG = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
    }
}
