package org.tamodak.killit

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.ui.KillitAppUi
import org.tamodak.killit.ui.KillitViewModel
import org.tamodak.killit.ui.Screen
import org.tamodak.killit.ui.theme.KillitTheme

/**
 * The only user-facing activity. Everything inside is Compose; this class sets two window flags,
 * hosts the composition, and passes on where a notification asked to go.
 */
class MainActivity : ComponentActivity() {

    /**
     * The same ViewModel the composition uses: both read it from this activity's store. Held here
     * so an incoming intent can reach it.
     */
    private val viewModel: KillitViewModel by viewModels { KillitViewModel.Factory }

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

        // A recreated activity already handled its intent the first time round.
        if (savedInstanceState == null) handleDestination(intent)

        setContent {
            KillitTheme {
                KillitAppUi(viewModel)
            }
        }
    }

    /**
     * Receives a notification's intent while Killit is already open.
     *
     * @param intent the new intent.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDestination(intent)
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

    /**
     * Passes on where the intent asks to go once the passkey has been entered.
     *
     * The activity is exported as the launcher entry, so any app could send this extra; all it can
     * choose is which screen follows the gate, never whether the gate is shown.
     *
     * @param intent the intent the activity was started or re-delivered with.
     */
    private fun handleDestination(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_DESTINATION)) {
            DESTINATION_APPS -> viewModel.openAfterUnlock(Screen.Apps)
        }
    }

    companion object {
        /** Intent extra naming where to go once the passkey has been entered. */
        const val EXTRA_DESTINATION = "org.tamodak.killit.extra.DESTINATION"

        /** [EXTRA_DESTINATION] value for the app list. */
        const val DESTINATION_APPS = "apps"
    }
}
