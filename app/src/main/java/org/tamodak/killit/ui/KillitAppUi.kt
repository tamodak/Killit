package org.tamodak.killit.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.tamodak.killit.R
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.ui.apps.AppListScreen
import org.tamodak.killit.ui.gate.AuthGateScreen
import org.tamodak.killit.ui.home.HomeScreen
import org.tamodak.killit.ui.setup.CredentialSetupScreen
import org.tamodak.killit.ui.tamper.TamperedScreen
import org.tamodak.killit.ui.theme.KillitGreen

/**
 * Screen host.
 *
 * A handful of screens in a linear flow, so a plain sealed-interface state machine does the job a
 * navigation library would otherwise add a dependency for. The trade-off: there is no back stack
 * and no deep linking, so each screen that needs a back gesture installs its own [BackHandler].
 *
 * Screens split into two groups for window insets. [HomeScreen] and [AppListScreen] use a
 * `Scaffold`, which applies the system bar insets itself. The rest draw their own layout and apply
 * `Modifier.safeDrawingPadding()` directly — mandatory since edge-to-edge became non-optional at
 * targetSdk 36.
 *
 * @param viewModel owns every piece of state below and every action the screens can take.
 *   Parameterised so a preview or test can supply its own.
 */
@Composable
fun KillitAppUi(
    viewModel: KillitViewModel = viewModel(factory = KillitViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ProvideAppLanguage(state.language) {
        KillitAppContent(state = state, viewModel = viewModel)
    }
}

/**
 * Everything below the language provider.
 *
 * Split out so that [ProvideAppLanguage] wraps the whole screen host rather than each screen: the
 * toast text below resolves strings too, and a provider placed any deeper would leave it speaking
 * the device's language while the screens speak the user's.
 *
 * @param state the current UI state.
 * @param viewModel the source of every action the screens can take.
 */
@Composable
private fun KillitAppContent(state: KillitUiState, viewModel: KillitViewModel) {
    val context = LocalContext.current

    // Tells the ViewModel when Killit is in the foreground: only then may a proved passkey unlock
    // the session, and leaving locks it. Start/stop rather than resume/pause, so a permission
    // dialog or a partially covering activity does not re-lock the session. The stop callback also
    // runs if this content leaves the composition, which locks — the safe direction.
    LifecycleStartEffect(viewModel) {
        viewModel.onForeground()
        onStopOrDispose { viewModel.lockOnBackground() }
    }

    // Messages are modelled rather than pre-rendered so their text stays in strings.xml; the
    // resource lookup happens here, in composition, where a Context is available.
    val messageText = when (val message = state.message) {
        UiMessage.Saved -> stringResource(R.string.apps_saved)
        UiMessage.PasskeySet -> stringResource(R.string.setup_saved)
        is UiMessage.Error -> message.text
        null -> null
    }
    LaunchedEffect(messageText) {
        messageText?.let {
            KillitLog.d(KillitLog.UI) { "Toast: $it" }
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    // One line per screen change — the cheapest way to reconstruct a user's path through the app
    // from a log dump.
    LaunchedEffect(state.screen) {
        KillitLog.i(KillitLog.UI, "Screen -> ${state.screen}")
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (state.screen) {
            // No inset padding needed: a centred spinner can never be occluded by a system bar.
            Screen.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = KillitGreen) }

            Screen.Tampered -> TamperedScreen()

            Screen.Setup -> CredentialSetupScreen(
                enabled = !state.busy,
                onConfirmed = viewModel::setCredential,
            )

            Screen.ChangePasskey -> {
                // Same screen as Setup, but cancellable — there is already a passkey to go back to.
                BackHandler { viewModel.goHome() }
                CredentialSetupScreen(
                    enabled = !state.busy,
                    onConfirmed = viewModel::setCredential,
                    onCancel = viewModel::goHome,
                )
            }

            Screen.Gate -> {
                val lockType = state.lockType
                if (lockType != null) {
                    AuthGateScreen(
                        lockType = lockType,
                        enabled = !state.busy,
                        feedback = state.gateFeedback,
                        onSubmit = viewModel::submitGate,
                    )
                } else {
                    // Nothing to check against; fall through to setting a passkey. Renders nothing
                    // for the one frame it takes the effect to run.
                    LaunchedEffect(Unit) {
                        KillitLog.w(KillitLog.UI, "Gate reached with no lock type; redirecting to Setup")
                        viewModel.navigateTo(Screen.Setup)
                    }
                }
            }

            Screen.Home -> HomeScreen(
                state = state,
                onManageApps = { viewModel.navigateTo(Screen.Apps) },
                onChangePasskey = { viewModel.navigateTo(Screen.ChangePasskey) },
                onHardeningChange = viewModel::setHardening,
                onProvisionViaShizuku = viewModel::provisionViaShizuku,
                onDismissShizukuOutcome = viewModel::dismissShizukuOutcome,
                onRefreshStatus = viewModel::refreshOwnerStatus,
                onRequestRelease = viewModel::requestRelease,
                onCancelRelease = viewModel::cancelRelease,
                onReleaseDeviceOwner = viewModel::releaseDeviceOwner,
                onLanguageChange = viewModel::setLanguage,
            )

            Screen.Apps -> AppListScreen(
                state = state,
                onToggle = viewModel::toggleSelection,
                onSave = viewModel::save,
                onDiscard = viewModel::discardSelection,
                onBack = viewModel::goHome,
                onDismissFailures = viewModel::dismissSaveFailures,
                onKeepBlocked = viewModel::keepWaitingAppsBlocked,
                iconLoader = viewModel::iconFor,
            )
        }
    }
}
