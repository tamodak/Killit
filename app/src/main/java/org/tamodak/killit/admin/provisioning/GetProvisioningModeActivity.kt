package org.tamodak.killit.admin.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import org.tamodak.killit.core.KillitLog

/**
 * Required for QR / Android Enterprise provisioning on Android 11+.
 *
 * Partway through provisioning the system starts this activity to ask the DPC which mode it
 * supports. Killit only makes sense on a fully managed device — a work profile has no authority
 * over the personal side, which is what the user wants blocked — so it answers immediately and
 * finishes. **Without this activity the QR flow fails outright.**
 *
 * Guarded by `BIND_DEVICE_ADMIN` in the manifest so only the system can start it.
 *
 * The lint warnings about `EXTRA_PROVISIONING_MODE` and `PROVISIONING_MODE_FULLY_MANAGED_DEVICE`
 * requiring API 29 while `minSdk` is 26 are safe to ignore: both are compile-time String/int
 * constants that get inlined, and this activity is only ever launched by a system that already
 * understands them.
 */
class GetProvisioningModeActivity : Activity() {

    /**
     * Answers the system's provisioning-mode query and finishes immediately.
     *
     * There is no UI: the activity exists only to return an extra, so it sets a result and closes
     * before a frame is drawn.
     *
     * @param savedInstanceState standard Android instance state; unused, since nothing survives
     *   between the query and the answer.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // What the system says it is willing to accept (API 31+). Killit does not choose from it —
        // it supports exactly one mode — but logging it turns "provisioning failed" into something
        // diagnosable, because a mismatch here is invisible from the UI.
        val allowed = intent?.getIntegerArrayListExtra(EXTRA_ALLOWED_MODES)
        KillitLog.i(
            KillitLog.ADMIN,
            "GET_PROVISIONING_MODE: system allows $allowed; answering FULLY_MANAGED_DEVICE",
        )

        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
        setResult(RESULT_OK, result)
        finish()
    }

    private companion object {
        /**
         * `DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES`, spelled out rather
         * than referenced so the class still compiles against `minSdk` 26.
         */
        const val EXTRA_ALLOWED_MODES =
            "android.app.extra.PROVISIONING_ALLOWED_PROVISIONING_MODES"
    }
}
