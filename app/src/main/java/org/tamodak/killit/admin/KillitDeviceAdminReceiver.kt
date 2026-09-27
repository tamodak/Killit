package org.tamodak.killit.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.tamodak.killit.core.KillitLog

/**
 * Device admin entry point.
 *
 * Killit never uses the classic device-admin policies (password rules, remote wipe, camera
 * disable). This receiver exists because **a device owner has to be an admin component**: its
 * flattened name is what gets passed to `dpm set-device-owner`, embedded in the QR provisioning
 * payload, and handed to every `DevicePolicyManager` call as the calling admin.
 *
 * Because it is only an identity, the callbacks below are pure instrumentation — but they are the
 * only signal that provisioning actually reached the receiver, which makes them the first thing to
 * check when `dpm set-device-owner` reports success yet Killit still shows "Not a Device Owner".
 */
class KillitDeviceAdminReceiver : DeviceAdminReceiver() {

    /**
     * Fired once the component becomes an active admin — i.e. provisioning got this far.
     *
     * @param context the receiver context.
     * @param intent the broadcast that triggered this callback. Unused; Killit reads no extras.
     */
    override fun onEnabled(context: Context, intent: Intent) {
        KillitLog.i(KillitLog.ADMIN, "Device admin ENABLED (${componentName(context).flattenToString()})")
    }

    /**
     * Fired when the admin is removed, e.g. by `adb shell dpm remove-active-admin`.
     *
     * That is the documented recovery route out of the tampered state, so seeing this line is
     * expected during recovery rather than a fault.
     *
     * @param context the receiver context.
     * @param intent the broadcast that triggered this callback. Unused; Killit reads no extras.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        KillitLog.i(KillitLog.ADMIN, "Device admin DISABLED — Killit can no longer block or unblock")
    }

    companion object {
        /**
         * The admin component name, used everywhere an admin identity is required.
         *
         * Built from the application context so it always carries the real `applicationId`,
         * including under a build variant that rewrites it.
         *
         * @param context any context; only its application context is used.
         * @return the component to pass as the calling admin.
         */
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, KillitDeviceAdminReceiver::class.java)
    }
}
