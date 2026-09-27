package org.tamodak.killit

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.ui.KillitAppUi
import org.tamodak.killit.ui.theme.KillitTheme

/**
 * The only user-facing activity. Everything inside is Compose; this class exists to set two window
 * flags and host the composition.
 */
class MainActivity : ComponentActivity() {

    /**
     * Applies the two window flags Killit depends on, then installs the composition.
     *
     * Ordering matters here: both flags are set before `setContent` so that the first frame is
     * already screenshot-protected and already laid out edge to edge.
     *
     * @param savedInstanceState standard Android instance state; Killit keeps no UI state in it,
     *   since authentication deliberately dies with the process.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KillitLog.d(KillitLog.UI) {
            "MainActivity.onCreate (restored=${savedInstanceState != null})"
        }

        // Keeps the passkey out of screenshots and the recents thumbnail. Set before setContent so
        // the very first frame is already protected — a flag applied later leaves one frame
        // capturable.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        // Mandatory from targetSdk 35 on Android 15+, and no longer opt-out-able at targetSdk 36.
        // Screens that draw their own chrome rather than using a Scaffold apply the resulting
        // insets themselves via Modifier.safeDrawingPadding().
        //
        // Both bars are forced dark: Killit's palette is fixed dark, so letting them follow the
        // system setting puts dark icons on a near-black background in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            KillitTheme {
                KillitAppUi()
            }
        }
    }

    /**
     * Traces the activity leaving the foreground.
     *
     * Logged because the gate re-locks on ON_STOP: when someone reports "it asked for my passkey
     * again out of nowhere", this line and the matching `Vm` line are the pair that explains it.
     */
    override fun onStop() {
        super.onStop()
        KillitLog.d(KillitLog.UI) { "MainActivity.onStop (isFinishing=$isFinishing)" }
    }
}
