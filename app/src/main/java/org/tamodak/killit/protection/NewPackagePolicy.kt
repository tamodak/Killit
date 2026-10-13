package org.tamodak.killit.protection

import org.tamodak.killit.data.KnownPackage
import org.tamodak.killit.data.PackageStatus

/**
 * Decides what default-blocking does with one installed package, and what a move to a new phone
 * hands on and takes over.
 *
 * Pure: it sees only the packages and what was decided about them before, so every rule below is
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
 *
 * ### Moving to a new phone (PLAN.md §3.10)
 *
 * - The old phone hands on every decision except the approvals of its own system image, which
 *   another phone's system doesn't share ([decisionsToMove]).
 * - The new phone keeps them, by the same rules as its own: blocked stays blocked and approved stays
 *   approved, for the app decided about ([adoptMoved]).
 * - An app the user installed on the new phone that the old one never decided about is new, enabled
 *   or not, so a move is no way around default-blocking. The new phone's own system apps keep what
 *   was decided there.
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

    /** What taking over a moved phone's decisions comes to. */
    class MovePlan(
        /** Decisions to record: the old phone's, and the apps new here. */
        val updates: List<KnownPackage>,
        /** Installed packages to suspend: blocked on the old phone, or new here. */
        val toBlock: Set<String>,
        /** Installed packages to unsuspend: approved on the old phone. */
        val toAllow: Set<String>,
        /** The apps new here, which the guardians hear about like any install (D1). */
        val newHere: List<String>,
    )

    /**
     * The decisions an old phone hands its new phone (SPEC.md §11.14).
     *
     * @param known every decision made on this phone.
     * @param installed every installed package, which tells the system's own apart.
     * @param ownPackage Killit's own package, which is never handed on.
     */
    fun decisionsToMove(known: Collection<KnownPackage>, installed: List<InstalledPackage>, ownPackage: String): List<KnownPackage> {
        val system = installed.filter { it.isSystem }.mapTo(mutableSetOf()) { it.packageName }
        return known.filter { it.packageName != ownPackage && !(it.status == PackageStatus.APPROVED && it.packageName in system) }
    }

    /**
     * Takes over an old phone's decisions on its new phone.
     *
     * @param moved the old phone's decisions.
     * @param installed every package installed here.
     * @param now when the decisions are taken over; each is dated then.
     */
    fun adoptMoved(moved: List<KnownPackage>, installed: List<InstalledPackage>, ownPackage: String, now: Long): MovePlan {
        val decisions = moved.filter { it.packageName != ownPackage }.associateBy { it.packageName }
        val updates = mutableListOf<KnownPackage>()
        val toBlock = mutableSetOf<String>()
        val toAllow = mutableSetOf<String>()
        val newHere = mutableListOf<String>()
        for (pkg in installed) {
            val decision = decisions[pkg.packageName]
            when (val result = decide(pkg, decision, ownPackage)) {
                Decision.Keep -> {
                    updates += decision!!.copy(sinceMillis = now)
                    toAllow += pkg.packageName
                }
                Decision.EnforceBlock -> {
                    updates += decision!!.copy(sinceMillis = now)
                    toBlock += pkg.packageName
                }
                is Decision.AdoptRotatedKey -> {
                    updates += result.updated.copy(sinceMillis = now)
                    (if (result.updated.status.isBlocked) toBlock else toAllow) += pkg.packageName
                }
                // Never decided about there, or a different app under the name.
                Decision.BlockAsNew, Decision.Ignore, Decision.AutoApprove ->
                    if (pkg.packageName != ownPackage && !pkg.isSystem && !pkg.isOverlay) {
                        updates += KnownPackage(pkg.packageName, pkg.certDigest, PackageStatus.PENDING, now)
                        toBlock += pkg.packageName
                        newHere += pkg.packageName
                    }
            }
        }
        // Decisions about apps this phone doesn't have wait for them, and nothing waits on a person.
        val here = installed.mapTo(mutableSetOf()) { it.packageName }
        for (decision in decisions.values) {
            if (decision.packageName in here) continue
            val status = if (decision.status == PackageStatus.PENDING) PackageStatus.USER_BLOCKED else decision.status
            updates += decision.copy(status = status, sinceMillis = now)
        }
        return MovePlan(updates, toBlock, toAllow, newHere)
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
