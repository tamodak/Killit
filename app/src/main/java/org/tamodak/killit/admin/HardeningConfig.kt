package org.tamodak.killit.admin

/**
 * The anti-tamper policies Killit applies as device owner.
 *
 * Every flag maps 1:1 to a call in [DevicePolicyController.applyHardening]. Together they close
 * the routes a user could otherwise take to escape a block without the passkey; each is exposed as
 * a toggle because they all cost something in convenience, and how far to go is the owner's call.
 *
 * All default to `true` except [blockAppsControl]. A fresh install turns on everything that can
 * be aimed at Killit alone, and leaves the one device-wide restriction to the owner — starting
 * locked down is the safe direction to be wrong in, but not at the cost of the user's other apps.
 *
 * Creating a work profile or a private space is not a toggle: it is always blocked, because apps
 * in another profile are out of Killit's reach entirely (see
 * `DevicePolicyController.ensureProfilesBlocked`).
 *
 * @param blockUninstall stops Killit being uninstalled to escape the block. Scoped to Killit:
 *   `setUninstallBlocked` takes a package name.
 * @param blockForceStop greys out Force stop in Settings, and from Android 13 refuses Clear data
 *   on the same packages — `setUserControlDisabledPackages` covers both, and Killit passes only
 *   its own package. Requires Android 11 — below that the platform has no equivalent API and the
 *   toggle is shown disabled.
 * @param blockSafeBoot blocks safe mode, which would otherwise disable third-party apps on reboot,
 *   Killit included.
 * @param blockFactoryReset blocks factory reset, and with it adding a new user — a second user is
 *   another route to a de facto reset.
 * @param blockAppsControl blocks managing apps from Settings: uninstalling, disabling,
 *   force-stopping and clearing data — for **every** app on the device, not only the blocked ones.
 *   `addUserRestriction` takes no package name, so unlike the two above there is no way to aim it
 *   at Killit, and that breadth is why it defaults to `false`.
 *
 *   It is not what protects the passkey. AOSP already treats the device owner's own package as
 *   data-protected (`ProtectedPackages.isPackageDataProtected`), and from Android 13
 *   [blockForceStop] protects it too, so Clear data on Killit is refused without this. Kept as a
 *   toggle for defence in depth, and for OEM builds that honour neither.
 * @param blockDateTime blocks changing the system date and time. The delay on "remove admin" is
 *   measured against the wall clock, so winding the clock past the deadline would skip the wait
 *   entirely. This restriction is what makes that delay real rather than decorative.
 */
data class HardeningConfig(
    val blockUninstall: Boolean = true,
    val blockForceStop: Boolean = true,
    val blockSafeBoot: Boolean = true,
    val blockFactoryReset: Boolean = true,
    val blockAppsControl: Boolean = false,
    val blockDateTime: Boolean = true,
) {
    companion object {
        /** What a fresh install starts as: every scoped protection on, the device-wide one off. */
        val DEFAULT = HardeningConfig()
    }
}
