package org.tamodak.killit.protection

import org.tamodak.killit.data.KnownPackage
import org.tamodak.killit.data.PackageStatus
import org.tamodak.killit.protection.NewPackagePolicy.Decision
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---------------------------------------------------------------- helpers

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
        val CERT = ByteArray(32) { 1 }
        val NEW_CERT = ByteArray(32) { 2 }
    }
}
