package org.tamodak.killit.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.LockPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hardening toggles reach the platform on every supported Android version.
 *
 * Reads the effective state back through the public getters rather than trusting that the calls
 * succeeded, because several of the policies are version-specific and a wrong branch fails
 * silently. Needs Killit as device owner; the device's own stored toggles are applied again
 * afterwards.
 *
 * On Android 14's first release (fixed in QPR1) [dateAndTimeFollowTheToggle] fails once the device
 * has rebooted with the restriction set: that build restores a device owner's restrictions as ones
 * no admin owns, so clearing them has no effect. The stock `android-34` emulator image is that
 * build; run this on a freshly wiped one.
 */
@RunWith(AndroidJUnit4::class)
class HardeningTest {

    /** The instrumentation context, which runs as Killit and so as the device owner. */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The controller under test. */
    private val dpc = DevicePolicyController(context)

    /** Where the effective user restrictions are read back from. */
    private val userManager = context.getSystemService(UserManager::class.java)

    /** Where the pre-Android 9 date and time policy is read back from. */
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)

    /** Skips unless Killit is device owner. */
    @Before
    fun setUp() = runBlocking {
        KillitLog.verbose = true
        assumeTrue("Needs Killit as device owner", dpc.isDeviceOwner())
    }

    /** Puts back the toggles the user actually chose. */
    @After
    fun tearDown() = runBlocking {
        dpc.applyHardening(LockPreferences(context).hardening.first())
    }

    /** With the date and time toggle on, the clock cannot be set by hand; off, it can again. */
    @Test
    fun dateAndTimeFollowTheToggle(): Unit = runBlocking {
        dpc.applyHardening(HardeningConfig(blockDateTime = true))
        assertTrue("The clock must be locked", isClockLocked())

        dpc.applyHardening(HardeningConfig(blockDateTime = false))
        assertEquals("The clock must be settable again", false, isClockLocked())
    }

    /** No second profile can be created, whatever the toggles say. */
    @Test
    fun profilesStayBlockedWithEveryToggleOff(): Unit = runBlocking {
        dpc.applyHardening(
            HardeningConfig(
                blockUninstall = false,
                blockForceStop = false,
                blockSafeBoot = false,
                blockFactoryReset = false,
                blockAppsControl = false,
                blockDateTime = false,
            )
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            assertTrue(userManager.hasUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            assertTrue(userManager.hasUserRestriction(UserManager.DISALLOW_ADD_PRIVATE_PROFILE))
        }
    }

    /**
     * Reads whether the user is prevented from setting the clock, the way this Android version
     * enforces it.
     *
     * @return true when the date and time cannot be changed by hand.
     */
    private fun isClockLocked(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            userManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME)
        } else {
            @Suppress("DEPRECATION")
            dpm.autoTimeRequired
        }
}
