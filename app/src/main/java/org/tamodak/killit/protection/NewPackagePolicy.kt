package org.tamodak.killit.protection

import org.tamodak.killit.data.KnownPackage

/**
 * Decides what default-blocking does with one installed package.
 *
 * Pure: it sees only the package and what was decided about it before, so every rule below is
 * covered by JVM tests. `PackageGuard` carries the decisions out.
 *
 * ### The rules
 *
 * - A package whose name and certificate are known keeps its decision. A blocked one is blocked
 *   again, because suspension does not survive an uninstall: without this, removing a blocked app
 *   and installing it again would unblock it.
 * - A known package signed with a newer key that its old key vouched for (signing-key rotation)
 *   keeps its decision under the new key.
 * - Anything else is new — including a known name signed by an unrelated key, which is a different
 *   app wearing a familiar name. A new package is blocked, unless it is part of the system rather
 *   than an app (see [isPartOfSystem]). Every other new package is blocked whatever it is, keyboards,
 *   VPNs and launchers included.
 * - A disabled package nobody has decided about is left alone until it is enabled. Enabling it then
 *   makes it new, which is how a preinstalled app switched back on gets caught.
 */
object NewPackagePolicy {

    /** What to do with one package. */
    sealed interface Decision {
        /** Nothing: Killit itself, or a disabled package nobody has decided about. */
        data object Ignore : Decision

        /** Known and allowed; nothing to do. */
        data object Keep : Decision

        /** Known and blocked: make sure it is suspended. */
        data object EnforceBlock : Decision

        /**
         * Known, and now signed with a rotated key the old one vouched for. The decision moves to
         * the new key; if it is a block, the package is also blocked again.
         *
         * @param updated the same decision, pinned to the new certificate.
         */
        class AdoptRotatedKey(val updated: KnownPackage) : Decision

        /** New, and part of the system rather than an app: allow it and remember that. */
        data object AutoApprove : Decision

        /** New: block it, and remember it as waiting for a decision. */
        data object BlockAsNew : Decision
    }

    /**
     * Decides what to do with one package.
     *
     * @param pkg the package as installed now.
     * @param known what was decided about this package name before, or null if nothing was.
     * @param ownPackage Killit's own package, which is never judged.
     * @return the decision.
     */
    fun decide(pkg: InstalledPackage, known: KnownPackage?, ownPackage: String): Decision {
        if (pkg.packageName == ownPackage) return Decision.Ignore

        if (known != null) {
            if (known.certDigest.contentEquals(pkg.certDigest)) {
                return if (known.status.isBlocked) Decision.EnforceBlock else Decision.Keep
            }
            if (pkg.lineage.any { it.contentEquals(known.certDigest) }) {
                return Decision.AdoptRotatedKey(known.copy(certDigest = pkg.certDigest))
            }
            // Signed by a key the known one never vouched for: a different app under this name.
        }

        return when {
            !pkg.isEnabled -> Decision.Ignore
            isPartOfSystem(pkg) -> Decision.AutoApprove
            else -> Decision.BlockAsNew
        }
    }

    /**
     * Reports whether a package is part of the system rather than an app a person would use.
     *
     * Two kinds qualify: preinstalled packages with no launcher icon — the libraries and services
     * an OTA update or a component update adds — and resource overlays, which only restyle another
     * package. Blocking those could break the phone, and there is nothing in them to open. A
     * preinstalled package *with* an icon is treated like any other app.
     *
     * Static shared libraries (such as the one Chrome and WebView share) never reach this point:
     * the package manager does not list them to apps at all.
     *
     * @param pkg the package to classify.
     * @return true when it is approved without asking.
     */
    fun isPartOfSystem(pkg: InstalledPackage): Boolean =
        (pkg.isSystem && !pkg.isLaunchable) || pkg.isOverlay
}
