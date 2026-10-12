package org.tamodak.killit

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.net.toUri
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.protocol.isPackageName
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
     * The activity is exported as the launcher entry, so any app could send these extras; all they
     * can choose is which screen follows the gate, never whether the gate is shown, and the screen
     * is checked against what the session has earned like any other.
     *
     * @param intent the intent the activity was started or re-delivered with.
     */
    private fun handleDestination(intent: Intent?) {
        val screen = when (val destination = intent?.let(Destination::from)) {
            null, Destination.Start -> return
            Destination.Apps -> Screen.Apps
            Destination.MyPhone -> Screen.MyPhone
            Destination.Guardians -> Screen.Guardians
            Destination.Guard -> Screen.Guard
            is Destination.AskUnblockApp -> Screen.AskUnblockApp(destination.packageName)
            is Destination.Request -> Screen.Request(destination.pairIdHex, destination.requestIdHex)
            is Destination.Phone -> Screen.Phone(destination.pairIdHex)
        }
        viewModel.openAfterUnlock(screen)
    }

    /** Where a notification asks Killit to go once the passkey has been entered. */
    sealed interface Destination {
        /** The Hub, with nothing to open after it. */
        data object Start : Destination

        /** The app list; on a phone with guardians, the apps to ask them about. */
        data object Apps : Destination

        data object MyPhone : Destination

        data object Guardians : Destination

        /** Phones I guard: the requests waiting for an answer. */
        data object Guard : Destination

        data class AskUnblockApp(val packageName: String) : Destination

        data class Request(val pairIdHex: String, val requestIdHex: String) : Destination

        data class Phone(val pairIdHex: String) : Destination

        companion object {
            /** Reads a destination from an intent's extras, or null when they name none or don't parse. */
            fun from(intent: Intent): Destination? {
                val pairId = intent.getStringExtra(EXTRA_PAIR_ID)?.takeIf { it.isHex(PAIR_ID_HEX) }
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf { it.isHex(REQUEST_ID_HEX) }
                val packageName = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf(::isPackageName)
                return when (intent.getStringExtra(EXTRA_DESTINATION)) {
                    "apps" -> Apps
                    "my_phone" -> MyPhone
                    "guardians" -> Guardians
                    "guard" -> Guard
                    "ask_unblock" -> packageName?.let(::AskUnblockApp)
                    "request" -> if (pairId != null && requestId != null) Request(pairId, requestId) else null
                    "phone" -> pairId?.let(::Phone)
                    else -> null
                }
            }

            private fun String.isHex(length: Int) = this.length == length && all { it in '0'..'9' || it in 'a'..'f' }
        }
    }

    companion object {
        private const val EXTRA_DESTINATION = "org.tamodak.killit.extra.DESTINATION"
        private const val EXTRA_PAIR_ID = "org.tamodak.killit.extra.PAIR_ID"
        private const val EXTRA_REQUEST_ID = "org.tamodak.killit.extra.REQUEST_ID"
        private const val EXTRA_PACKAGE = "org.tamodak.killit.extra.PACKAGE"

        /** A pairing id and a request id, as lowercase hex (SPEC.md §1). */
        private const val PAIR_ID_HEX = 64
        private const val REQUEST_ID_HEX = 32

        /**
         * Opens Killit for a notification's tap: the passkey first, as always, then [destination].
         *
         * Immutable, as every pending intent should be unless something must fill it in later.
         * Each destination gets its own intent data, so two notifications never share one pending
         * intent and open each other's screens.
         */
        fun open(context: Context, destination: Destination): PendingIntent {
            val (name, extras) = when (destination) {
                Destination.Start -> null to emptyMap()
                Destination.Apps -> "apps" to emptyMap()
                Destination.MyPhone -> "my_phone" to emptyMap()
                Destination.Guardians -> "guardians" to emptyMap()
                Destination.Guard -> "guard" to emptyMap()
                is Destination.AskUnblockApp -> "ask_unblock" to mapOf(EXTRA_PACKAGE to destination.packageName)
                is Destination.Request -> "request" to mapOf(EXTRA_PAIR_ID to destination.pairIdHex, EXTRA_REQUEST_ID to destination.requestIdHex)
                is Destination.Phone -> "phone" to mapOf(EXTRA_PAIR_ID to destination.pairIdHex)
            }
            val key = listOfNotNull(name, *extras.values.toTypedArray()).joinToString("/")
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData("killit-open:$key".toUri())
            if (name != null) intent.putExtra(EXTRA_DESTINATION, name)
            extras.forEach { (extra, value) -> intent.putExtra(extra, value) }
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
