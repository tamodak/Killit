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
import org.tamodak.killit.pairing.RequestEngine
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.ui.apps.AppListScreen
import org.tamodak.killit.ui.gate.AuthGateScreen
import org.tamodak.killit.ui.guard.GuardScreen
import org.tamodak.killit.ui.guard.GuardSettingsScreen
import org.tamodak.killit.ui.guard.PhoneScreen
import org.tamodak.killit.ui.guard.RequestScreen
import org.tamodak.killit.ui.hub.HubScreen
import org.tamodak.killit.ui.myphone.AskRemoveScreen
import org.tamodak.killit.ui.myphone.AskUnblockFormScreen
import org.tamodak.killit.ui.myphone.AskUnblockListScreen
import org.tamodak.killit.ui.myphone.BlockMoreScreen
import org.tamodak.killit.ui.myphone.GuardiansScreen
import org.tamodak.killit.ui.myphone.MyPhoneScreen
import org.tamodak.killit.ui.myphone.SettingsScreen
import org.tamodak.killit.ui.myphone.UnlockWaitingScreen
import org.tamodak.killit.ui.myphone.requestStatusByApp
import org.tamodak.killit.ui.onboarding.OnboardingScreen
import org.tamodak.killit.ui.pairing.WizardScreen
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
 * Every screen applies the system bar insets once: those built on `KillitScreen` get
 * `Modifier.safeDrawingPadding()` from it, and the few that draw their own chrome (the Hub, the
 * gate) apply it themselves — mandatory since edge-to-edge became non-optional at targetSdk 36.
 *
 * @param viewModel decides which screen may be shown, and owns every action outside pairing.
 *   Parameterised so a preview or test can supply its own.
 * @param pairingViewModel the pairing screens' state and actions.
 */
@Composable
fun KillitAppUi(
    viewModel: KillitViewModel = viewModel(factory = KillitViewModel.Factory),
    pairingViewModel: PairingViewModel = viewModel(factory = PairingViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pairing by pairingViewModel.state.collectAsStateWithLifecycle()

    ProvideAppLanguage(state.language) {
        KillitAppContent(state = state, pairing = pairing, viewModel = viewModel, pairingViewModel = pairingViewModel)
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
 * @param pairing the pairing screens' state.
 * @param viewModel decides what may be shown, and every action outside pairing.
 * @param pairingViewModel every pairing action.
 */
@Composable
private fun KillitAppContent(
    state: KillitUiState,
    pairing: PairingUiState,
    viewModel: KillitViewModel,
    pairingViewModel: PairingViewModel,
) {
    val context = LocalContext.current

    // Tells the ViewModel when Killit is in the foreground: only then may a proved passkey unlock
    // the session, and leaving locks it. Start/stop rather than resume/pause, so a permission
    // dialog or a partially covering activity does not re-lock the session. The stop callback also
    // runs if this content leaves the composition, which locks — the safe direction.
    LifecycleStartEffect(viewModel) {
        viewModel.onForeground()
        // Coming back to Killit reads the mailbox (PLAN.md §7), whichever screen it shows.
        pairingViewModel.refresh()
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
    val pairingText = pairing.message?.let { pairingMessageText(it) }
    LaunchedEffect(pairingText) {
        pairingText?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            pairingViewModel.consumeMessage()
        }
    }

    // One line per screen change — the cheapest way to reconstruct a user's path through the app
    // from a log dump. Opening a screen that shows pairing state also fetches what arrived, so
    // nothing on it is older than the moment it opened.
    LaunchedEffect(state.screen) {
        KillitLog.i(KillitLog.UI, "Screen -> ${state.screen}")
        if (state.screen.showsPairing()) pairingViewModel.refresh()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Screens(state, pairing, viewModel, pairingViewModel)
    }
}

/** Draws the current screen. Each one names its own way back; see [Screen.parent]. */
@Composable
private fun Screens(
    state: KillitUiState,
    pairing: PairingUiState,
    viewModel: KillitViewModel,
    pairingViewModel: PairingViewModel,
) {
    when (val screen = state.screen) {
        // No inset padding needed: a centred spinner can never be occluded by a system bar.
        Screen.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = KillitGreen) }

        Screen.Tampered -> TamperedScreen()

        Screen.Onboarding -> OnboardingScreen(
            language = state.language,
            onLanguage = viewModel::setLanguage,
            onDone = viewModel::finishOnboarding,
        )

        Screen.Hub -> HubScreen(
            state = state,
            pairing = pairing,
            onMyPhone = { viewModel.openSection(Screen.MyPhone) },
            onGuard = { viewModel.openSection(Screen.Guard) },
            onLanguageChange = viewModel::setLanguage,
        )

        Screen.Setup -> {
            BackHandler { viewModel.back() }
            CredentialSetupScreen(
                enabled = !state.busy,
                onConfirmed = viewModel::setCredential,
                onCancel = viewModel::back,
            )
        }

        is Screen.ChangePasskey -> {
            BackHandler { viewModel.back() }
            CredentialSetupScreen(
                enabled = !state.busy,
                onConfirmed = viewModel::setCredential,
                changing = true,
                onCancel = viewModel::back,
            )
        }

        Screen.Gate -> {
            BackHandler { viewModel.back() }
            val lockType = state.lockType
            if (lockType != null) {
                AuthGateScreen(
                    lockType = lockType,
                    enabled = !state.busy,
                    feedback = state.gateFeedback,
                    onSubmit = viewModel::submitGate,
                    forGuardian = state.gateForGuardian,
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

        Screen.MyPhone -> MyPhoneScreen(
            state = state,
            pairing = pairing,
            onBack = viewModel::back,
            onManageApps = { viewModel.navigateTo(Screen.Apps) },
            onGuardians = { viewModel.navigateTo(Screen.Guardians) },
            onSettings = { viewModel.navigateTo(Screen.Settings) },
            onUnlock = { viewModel.navigateTo(Screen.UnlockWaiting) },
            onAskUnblock = { viewModel.navigateTo(Screen.AskUnblock) },
            onBlockMore = { viewModel.navigateTo(Screen.BlockMore) },
            onProvisionViaShizuku = viewModel::provisionViaShizuku,
            onDismissShizukuOutcome = viewModel::dismissShizukuOutcome,
            onRefreshStatus = viewModel::refreshOwnerStatus,
        )

        Screen.Settings -> SettingsScreen(
            state = state,
            onBack = viewModel::back,
            onHardeningChange = viewModel::setHardening,
            onChangePasskey = { viewModel.navigateTo(Screen.ChangePasskey(Screen.Settings)) },
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
            onBack = viewModel::back,
            onDismissFailures = viewModel::dismissSaveFailures,
            onKeepBlocked = viewModel::keepWaitingAppsBlocked,
            iconLoader = viewModel::iconFor,
            requestStatus = requestStatusByApp(pairing),
        )

        Screen.Guardians -> GuardiansScreen(
            pairing = pairing,
            canAddGuardian = state.isDeviceOwner,
            onBack = viewModel::back,
            onAdd = { viewModel.navigateTo(Screen.Wizard(PairRole.PROTECTED)) },
            onAskRemove = { viewModel.navigateTo(Screen.AskRemove(it)) },
            onWithdraw = pairingViewModel::withdraw,
            onRename = pairingViewModel::rename,
        )

        is Screen.AskRemove -> AskRemoveScreen(
            pairIdHex = screen.pairId,
            pairing = pairing,
            onBack = viewModel::back,
            onSend = { pairId, text -> pairingViewModel.askToRemove(pairId, text) { viewModel.back() } },
        )

        Screen.UnlockWaiting -> UnlockWaitingScreen(
            pairing = pairing,
            onBack = viewModel::back,
            onStart = pairingViewModel::startUnlock,
            onDismissResult = pairingViewModel::dismissUnlockResult,
            onRefreshAvailability = pairingViewModel::refreshUnlockAvailability,
            follow = pairingViewModel::followUnlock,
        )

        Screen.AskUnblock -> AskUnblockListScreen(
            state = state,
            pairing = pairing,
            onBack = viewModel::back,
            onOpen = { viewModel.navigateTo(Screen.AskUnblockApp(it)) },
            iconLoader = viewModel::iconFor,
        )

        is Screen.AskUnblockApp -> AskUnblockFormScreen(
            packageName = screen.packageName,
            state = state,
            pairing = pairing,
            onBack = viewModel::back,
            onSend = { packageName, text -> pairingViewModel.askToUnblock(packageName, text) { viewModel.back() } },
            onWithdraw = pairingViewModel::withdraw,
            iconLoader = viewModel::iconFor,
        )

        Screen.BlockMore -> BlockMoreScreen(
            state = state,
            busy = pairing.busy,
            onBack = viewModel::back,
            onBlock = { packages, onDone ->
                pairingViewModel.blockMore(packages) { refused ->
                    viewModel.refreshOwnerStatus()
                    onDone(refused)
                }
            },
            iconLoader = viewModel::iconFor,
        )

        Screen.Guard -> GuardScreen(
            pairing = pairing,
            onBack = viewModel::back,
            onOpenRequest = { pairId, requestId -> viewModel.navigateTo(Screen.Request(pairId, requestId)) },
            onOpenPhone = { viewModel.navigateTo(Screen.Phone(it)) },
            onGuardNew = { viewModel.navigateTo(Screen.Wizard(PairRole.GUARDIAN)) },
            onSettings = { viewModel.navigateTo(Screen.GuardSettings) },
            onAcknowledgeNotice = pairingViewModel::acknowledgeGuardianNotice,
        )

        is Screen.Request -> RequestScreen(
            pairIdHex = screen.pairId,
            requestIdHex = screen.requestId,
            pairing = pairing,
            onBack = viewModel::back,
            onDecide = { approve, note -> pairingViewModel.decide(screen.pairId, screen.requestId, approve, note) { viewModel.back() } },
        )

        is Screen.Phone -> PhoneScreen(
            pairIdHex = screen.pairId,
            pairing = pairing,
            onBack = viewModel::back,
            onOpenRequest = { pairId, requestId -> viewModel.navigateTo(Screen.Request(pairId, requestId, parent = screen)) },
            onRename = pairingViewModel::rename,
            onLeave = { pairId -> pairingViewModel.leave(pairId) { viewModel.back() } },
        )

        Screen.GuardSettings -> GuardSettingsScreen(
            language = state.language,
            onBack = viewModel::back,
            onLanguageChange = viewModel::setLanguage,
            onChangePasskey = { viewModel.navigateTo(Screen.ChangePasskey(Screen.GuardSettings)) },
        )

        is Screen.Wizard -> WizardScreen(
            role = screen.role,
            isDeviceOwner = state.isDeviceOwner,
            pairing = pairing,
            onBack = viewModel::back,
            onStart = { name, code -> pairingViewModel.startCeremony(screen.role, name, code) },
            onConfirm = pairingViewModel::confirmCode,
            onReject = pairingViewModel::rejectCode,
            onCancel = pairingViewModel::cancelCeremony,
            onNickname = pairingViewModel::setNickname,
            onDismiss = pairingViewModel::dismissCeremony,
            onOpenSetup = { viewModel.navigateTo(Screen.MyPhone) },
            follow = pairingViewModel::followCeremony,
        )
    }
}

/** True for the screens whose content comes from pairing, which sync when they open. */
private fun Screen.showsPairing(): Boolean = when (this) {
    Screen.Hub, Screen.MyPhone, Screen.Guardians, Screen.AskUnblock, is Screen.AskUnblockApp,
    Screen.Guard, is Screen.Request, is Screen.Phone -> true
    else -> false
}

/** The words for a pairing message, in the app's language. */
@Composable
private fun pairingMessageText(message: PairingMessage): String = when (message) {
    PairingMessage.Offline -> stringResource(R.string.msg_offline)
    is PairingMessage.CantPair -> stringResource(R.string.msg_cant_pair)
    PairingMessage.IdentityLost -> stringResource(R.string.msg_identity_lost)
    PairingMessage.CodeNotFound -> stringResource(R.string.wizard_failed_code)
    PairingMessage.RateLimited -> stringResource(R.string.msg_rate_limited)
    PairingMessage.Sent -> stringResource(R.string.msg_sent)
    is PairingMessage.NotSent -> stringResource(
        when (message.result) {
            RequestEngine.AskResult.NoGuardians -> R.string.msg_no_guardians
            RequestEngine.AskResult.BadExplanation -> R.string.msg_bad_explanation
            RequestEngine.AskResult.NotBlocked -> R.string.ask_unblock_not_blocked
            RequestEngine.AskResult.NotInstalled -> R.string.ask_unblock_not_blocked
            RequestEngine.AskResult.AlreadyOpen -> R.string.ask_state_waiting
            RequestEngine.AskResult.NoChancesLeft -> R.string.ask_state_no_chances
            RequestEngine.AskResult.NotAGuardian -> R.string.guardians_gone
            is RequestEngine.AskResult.Sent -> R.string.msg_sent
        },
    )
}
