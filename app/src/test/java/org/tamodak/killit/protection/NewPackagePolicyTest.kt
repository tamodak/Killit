package org.tamodak.killit.protection

import org.tamodak.killit.data.KnownPackage
import org.tamodak.killit.data.PackageStatus
import org.tamodak.killit.protection.NewPackagePolicy.Decision
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every rule of default-blocking, one case at a time. */
class NewPackagePolicyTest {

    // ---------------------------------------------------------------- new packages

    /** An ordinary app installed after protection started is blocked. */
    @Test
    fun aNewAppIsBlocked() {
        assertEquals(Decision.BlockAsNew, decide(pkg(launchable = true)))
    }

    /** Keyboards, VPNs and similar have no icon, but a user installed them: still blocked. */
    @Test
    fun aNewUserAppWithoutAnIconIsStillBlocked() {
        assertEquals(Decision.BlockAsNew, decide(pkg(launchable = false)))
    }

    /** A preinstalled library or service without an icon is approved without asking. */
    @Test
    fun aNewSystemPackageWithoutAnIconIsApproved() {
        assertEquals(Decision.AutoApprove, decide(pkg(system = true, launchable = false)))
    }

    /** A preinstalled app with an icon is treated like any other app. */
    @Test
    fun aNewSystemAppWithAnIconIsBlocked() {
        assertEquals(Decision.BlockAsNew, decide(pkg(system = true, launchable = true)))
    }

    /** A resource overlay only restyles another package, so it is approved. */
    @Test
    fun aNewOverlayIsApproved() {
        assertEquals(Decision.AutoApprove, decide(pkg(overlay = true)))
    }

    /** A disabled package nobody decided about waits until it is enabled. */
    @Test
    fun aDisabledUnknownPackageIsLeftAlone() {
        assertEquals(Decision.Ignore, decide(pkg(enabled = false, launchable = true)))
    }

    /** Killit never judges itself. */
    @Test
    fun killitItselfIsIgnored() {
        assertEquals(Decision.Ignore, decide(pkg(name = OWN_PACKAGE, launchable = true)))
    }

    // ---------------------------------------------------------------- known packages

    /** An approved app keeps its approval across updates signed with the same key. */
    @Test
    fun aKnownApprovedAppIsKept() {
        assertEquals(Decision.Keep, decide(pkg(launchable = true), known(PackageStatus.APPROVED)))
    }

    /**
     * A blocked app is blocked again, which is what stops "uninstall it and install it again"
     * from unblocking it.
     */
    @Test
    fun aKnownBlockedAppIsBlockedAgain() {
        assertEquals(Decision.EnforceBlock, decide(pkg(launchable = true), known(PackageStatus.PENDING)))
        assertEquals(Decision.EnforceBlock, decide(pkg(launchable = true), known(PackageStatus.USER_BLOCKED)))
    }

    /** A known package keeps its decision even while disabled. */
    @Test
    fun aDisabledKnownPackageKeepsItsDecision() {
        assertEquals(Decision.EnforceBlock, decide(pkg(enabled = false), known(PackageStatus.USER_BLOCKED)))
    }

    /** A rotated key the old one vouched for keeps the decision, now pinned to the new key. */
    @Test
    fun aRotatedKeyKeepsTheDecision() {
        val rotated = pkg(cert = NEW_CERT, lineage = listOf(CERT, NEW_CERT), launchable = true)

        val decision = decide(rotated, known(PackageStatus.APPROVED)) as Decision.AdoptRotatedKey

        assertArrayEquals(NEW_CERT, decision.updated.certDigest)
        assertEquals(PackageStatus.APPROVED, decision.updated.status)
    }

    /** A known name signed by an unrelated key is a different app, and therefore new. */
    @Test
    fun aKnownNameWithAnotherKeyIsNew() {
        val impostor = pkg(cert = NEW_CERT, lineage = listOf(NEW_CERT), launchable = true)

        assertEquals(Decision.BlockAsNew, decide(impostor, known(PackageStatus.APPROVED)))
    }

    // ---------------------------------------------------------------- digests

    /** One certificate's digest is its plain SHA-256, as signing tools print it. */
    @Test
    fun aSingleSignerDigestIsItsSha256() {
        val certificate = "certificate".toByteArray()
        assertArrayEquals(SigningDigest.of(certificate), SigningDigest.ofSigners(listOf(certificate)))
    }

    /** Several signers give one digest, whatever order the platform lists them in. */
    @Test
    fun aMultiSignerDigestIgnoresOrder() {
        val first = "first".toByteArray()
        val second = "second".toByteArray()

        val digest = SigningDigest.ofSigners(listOf(first, second))

        assertArrayEquals(digest, SigningDigest.ofSigners(listOf(second, first)))
        assertFalse(digest.contentEquals(SigningDigest.of(first)))
    }

    /** The system-package rule on its own, for the two shapes it accepts. */
    @Test
    fun partOfSystemCoversIconlessSystemPackagesAndOverlays() {
        assertTrue(NewPackagePolicy.isPartOfSystem(pkg(system = true, launchable = false)))
        assertTrue(NewPackagePolicy.isPartOfSystem(pkg(overlay = true, launchable = true)))
        assertFalse(NewPackagePolicy.isPartOfSystem(pkg(system = true, launchable = true)))
        assertFalse(NewPackagePolicy.isPartOfSystem(pkg(launchable = false)))
    }

    // ---------------------------------------------------------------- moving to a new phone

    /** The old phone hands on every decision but its own system's approvals, and never Killit. */
    @Test
    fun aMoveHandsOnEverythingButTheSystemsApprovals() {
        val known = listOf(
            known(PackageStatus.APPROVED),
            KnownPackage(CHROME, CERT, PackageStatus.APPROVED, 1L),
            KnownPackage(YOUTUBE, CERT, PackageStatus.USER_BLOCKED, 1L),
            KnownPackage(GONE, CERT, PackageStatus.PENDING, 1L),
            KnownPackage(OWN_PACKAGE, CERT, PackageStatus.APPROVED, 1L),
        )
        val installed = listOf(pkg(launchable = true), pkg(CHROME, system = true, launchable = true), pkg(YOUTUBE, system = true, launchable = true))

        val moved = NewPackagePolicy.decisionsToMove(known, installed, OWN_PACKAGE).map { it.packageName }

        assertEquals(setOf(PACKAGE, YOUTUBE, GONE), moved.toSet())
    }

    /** Blocked stays blocked and approved stays approved; an app still waiting goes on waiting. */
    @Test
    fun aMoveKeepsTheOldPhonesDecisions() {
        val blocked = "com.example.blocked"
        val waiting = "com.example.waiting"
        val plan = adopt(
            moved = listOf(
                known(PackageStatus.APPROVED),
                KnownPackage(blocked, CERT, PackageStatus.USER_BLOCKED, 1L),
                KnownPackage(waiting, CERT, PackageStatus.PENDING, 1L),
            ),
            installed = listOf(pkg(launchable = true), pkg(blocked, launchable = true), pkg(waiting, launchable = true)),
        )

        assertEquals(setOf(PACKAGE), plan.toAllow)
        assertEquals(setOf(blocked, waiting), plan.toBlock)
        assertTrue(plan.newHere.isEmpty())
        assertEquals(PackageStatus.PENDING, plan.status(waiting))
        assertTrue(plan.updates.all { it.sinceMillis == NOW })
    }

    /** An app installed here that the old phone never decided about is new, enabled or not. */
    @Test
    fun anAppOnlyOnTheNewPhoneIsNew() {
        val disabled = "com.example.disabled"
        val plan = adopt(moved = emptyList(), installed = listOf(pkg(launchable = true), pkg(disabled, enabled = false)))

        assertEquals(listOf(PACKAGE, disabled), plan.newHere)
        assertEquals(setOf(PACKAGE, disabled), plan.toBlock)
        assertEquals(PackageStatus.PENDING, plan.status(PACKAGE))
    }

    /** The new phone's system apps keep what was decided there, unless the old phone blocked them. */
    @Test
    fun theNewPhonesSystemAppsStayAsTheyWere() {
        val plan = adopt(
            moved = listOf(KnownPackage(YOUTUBE, CERT, PackageStatus.USER_BLOCKED, 1L)),
            installed = listOf(
                pkg(CHROME, system = true, launchable = true),
                pkg(YOUTUBE, system = true, launchable = true),
                pkg("com.example.overlay", overlay = true),
            ),
        )

        assertEquals(setOf(YOUTUBE), plan.toBlock)
        assertTrue(plan.newHere.isEmpty())
        assertNull(plan.updates.firstOrNull { it.packageName == CHROME })
    }

    /** A moved decision counts only for the app decided about, or its rotated key. */
    @Test
    fun aMovedDecisionOnlyCountsForTheAppDecidedAbout() {
        val impostor = adopt(listOf(known(PackageStatus.APPROVED)), listOf(pkg(cert = NEW_CERT, lineage = listOf(NEW_CERT), launchable = true)))
        assertEquals(listOf(PACKAGE), impostor.newHere)
        assertTrue(impostor.toAllow.isEmpty())

        val rotated = adopt(listOf(known(PackageStatus.APPROVED)), listOf(pkg(cert = NEW_CERT, lineage = listOf(CERT, NEW_CERT), launchable = true)))
        assertEquals(setOf(PACKAGE), rotated.toAllow)
        assertArrayEquals(NEW_CERT, rotated.updates.single().certDigest)
    }

    /** A decision about an app this phone doesn't have is kept for it, and waits on nobody. */
    @Test
    fun aDecisionAboutAMissingAppWaitsForIt() {
        val plan = adopt(listOf(known(PackageStatus.PENDING), KnownPackage(OWN_PACKAGE, CERT, PackageStatus.USER_BLOCKED, 1L)), installed = emptyList())

        assertEquals(PackageStatus.USER_BLOCKED, plan.status(PACKAGE))
        assertTrue(plan.toBlock.isEmpty())
        assertNull("Killit itself is never taken over", plan.updates.firstOrNull { it.packageName == OWN_PACKAGE })
    }

    // ---------------------------------------------------------------- helpers

    private fun adopt(moved: List<KnownPackage>, installed: List<InstalledPackage>) =
        NewPackagePolicy.adoptMoved(moved, installed, OWN_PACKAGE, NOW)

    private fun NewPackagePolicy.MovePlan.status(name: String) = updates.single { it.packageName == name }.status

    /** Runs the policy with Killit's real package name. */
    private fun decide(pkg: InstalledPackage, known: KnownPackage? = null) =
        NewPackagePolicy.decide(pkg, known, OWN_PACKAGE)

    /** A package with sensible defaults: a user app signed by [CERT], enabled, without an icon. */
    private fun pkg(
        name: String = PACKAGE,
        cert: ByteArray = CERT,
        lineage: List<ByteArray> = emptyList(),
        system: Boolean = false,
        launchable: Boolean = false,
        overlay: Boolean = false,
        enabled: Boolean = true,
    ) = InstalledPackage(name, cert, lineage, system, launchable, overlay, enabled)

    /** A decision about [PACKAGE] signed by [CERT]. */
    private fun known(status: PackageStatus) = KnownPackage(PACKAGE, CERT, status, sinceMillis = 1L)

    private companion object {
        const val PACKAGE = "com.example.app"
        const val OWN_PACKAGE = "org.tamodak.killit"
        const val CHROME = "com.android.chrome"
        const val YOUTUBE = "com.google.android.youtube"
        const val GONE = "com.example.gone"
        const val NOW = 5L
        val CERT = ByteArray(32) { 1 }
        val NEW_CERT = ByteArray(32) { 2 }
    }
}
