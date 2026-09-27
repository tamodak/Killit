package org.tamodak.killit.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.CameraFacing
import org.tamodak.killit.pairing.ScanOutcome
import org.tamodak.killit.ui.KillitUiState
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitError
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitRow
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.formatDuration
import org.tamodak.killit.ui.theme.KillitBackground
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import kotlinx.coroutines.delay

/**
 * Where the user is in the unlock flow: choose what to do, show the challenge to a companion, then
 * scan that companion's signature back.
 */
private enum class UnlockStep { Choose, ShowCode, Scan }

/**
 * The gate for a paired device: opened by companions, not by a passkey.
 *
 * ### Why approving lives on the locked gate
 *
 * When two phones are each other's companions, both are locked, and neither can open its own app
 * in order to help the other — a deadlock. So the approving side is reachable from here without
 * authenticating. That is safe: signing a challenge proves possession of this phone's private key
 * and opens nothing here, and whoever is holding the phone to scan with is by definition present.
 *
 * @param state supplies the open round, the companions still outstanding and the last scan outcome.
 * @param onStartChallenge opens a new round of collecting approvals.
 * @param onCancelChallenge abandons the round, discarding the approvals collected so far.
 * @param onResponseScanned called with the text of a scanned companion approval.
 * @param onOpenApproval switches to approving *another* device's challenge.
 * @param onDismissScanOutcome clears the explanation of why the last scan was rejected.
 * @param onCameraFacingChange called when the user switches cameras.
 * @param modifier applied to the screen.
 */
@Composable
fun QrUnlockScreen(
    state: KillitUiState,
    onStartChallenge: () -> Unit,
    onCancelChallenge: () -> Unit,
    onResponseScanned: (String) -> Unit,
    onOpenApproval: () -> Unit,
    onDismissScanOutcome: () -> Unit,
    onCameraFacingChange: (CameraFacing) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(UnlockStep.Choose) }
    val session = state.challenge

    fun goTo(next: UnlockStep) {
        KillitLog.d(KillitLog.UI) { "Unlock step $step -> $next" }
        onDismissScanOutcome()
        step = next
    }

    // Each accepted signature sends the user back to the code, ready for the next companion.
    val approvals = session?.approvals?.size ?: 0
    LaunchedEffect(approvals) {
        if (approvals > 0 && step == UnlockStep.Scan) step = UnlockStep.ShowCode
    }

    var now by remember(session?.expiresAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session?.expiresAtMillis) {
        val deadline = session?.expiresAtMillis ?: return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now >= deadline) break
            delay(1_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KillitBackground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KillitGutter, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (step == UnlockStep.Choose) {
            Icon(
                imageVector = KillitIcons.Shield,
                contentDescription = null,
                tint = KillitGreen,
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = stringResource(R.string.qr_gate_title),
                style = MaterialTheme.typography.headlineSmall,
                color = KillitForeground,
                textAlign = TextAlign.Center,
            )
            ScanFeedback(state.scanOutcome)
            KillitRow(
                title = stringResource(R.string.qr_choose_unlock),
                subtitle = stringResource(R.string.qr_choose_unlock_desc, state.peers.size),
                icon = KillitIcons.QrCode,
                iconTint = KillitGreen,
                onClick = {
                    onStartChallenge()
                    goTo(UnlockStep.ShowCode)
                },
            )
            KillitRow(
                title = stringResource(R.string.qr_choose_approve),
                subtitle = stringResource(R.string.qr_choose_approve_desc),
                icon = KillitIcons.Camera,
                onClick = onOpenApproval,
            )
            return@Column
        }

        // Starting a round has to reach the Keystore, so the step can be live for a frame or two
        // before the session behind it exists. Reading `session == null` as "go back" instead
        // would race that and swallow the button press.
        if (session == null) {
            ScanFeedback(state.scanOutcome)
            CircularProgressIndicator(color = KillitGreen)
            KillitTextButton(
                text = stringResource(R.string.cancel),
                onClick = { goTo(UnlockStep.Choose) },
            )
            return@Column
        }

        if (session.isExpired(now)) {
            KillitError(stringResource(R.string.qr_session_expired))
            KillitPrimaryButton(
                text = stringResource(R.string.qr_unlock_restart),
                onClick = {
                    onStartChallenge()
                    goTo(UnlockStep.ShowCode)
                },
            )
            KillitButton(
                text = stringResource(R.string.cancel),
                onClick = {
                    onCancelChallenge()
                    goTo(UnlockStep.Choose)
                },
            )
            return@Column
        }

        val waitingOn = state.peers.filterNot { session.hasApproval(it) }
        val nextPeer = waitingOn.firstOrNull()?.label.orEmpty()
        val progress = stringResource(
            R.string.qr_unlock_progress,
            session.approvals.size,
            state.peers.size,
            formatDuration(session.remainingMillis(now)),
        )

        when (step) {
            UnlockStep.ShowCode -> {
                val payload = remember(session) { session.toQrPayload().encode() }
                FlowStepHeader(
                    step = 1,
                    of = 2,
                    title = stringResource(R.string.qr_unlock_show_title, nextPeer),
                    instruction = stringResource(R.string.qr_unlock_show_body),
                )
                BigQrPanel(
                    payload = payload,
                    contentDescription = stringResource(R.string.qr_unlock_code_description),
                )
                KillitBody(
                    text = progress,
                    color = KillitForeground,
                    textAlign = TextAlign.Center,
                )
                // Naming who is still outstanding saves the user guessing which phone to visit next.
                if (waitingOn.size > 1) WaitingOn(labels = waitingOn.map { it.label })
                KillitPrimaryButton(
                    text = stringResource(R.string.pair_scanned_next),
                    onClick = { goTo(UnlockStep.Scan) },
                )
            }

            UnlockStep.Scan -> {
                FlowStepHeader(
                    step = 2,
                    of = 2,
                    title = stringResource(R.string.qr_unlock_scan_title, nextPeer),
                    instruction = stringResource(R.string.qr_unlock_scan_body),
                )
                ScanFeedback(state.scanOutcome)
                ScannerPanel(
                    onScanned = onResponseScanned,
                    cameraFacing = state.cameraFacing,
                    onCameraFacingChange = onCameraFacingChange,
                )
                KillitBody(
                    text = progress,
                    color = KillitForeground,
                    textAlign = TextAlign.Center,
                )
                KillitButton(
                    text = stringResource(R.string.pair_back_to_code),
                    onClick = { goTo(UnlockStep.ShowCode) },
                )
            }

            UnlockStep.Choose -> Unit
        }

        KillitTextButton(
            text = stringResource(R.string.cancel),
            onClick = {
                onCancelChallenge()
                goTo(UnlockStep.Choose)
            },
        )
    }
}

/**
 * Names the companions that have not yet approved.
 *
 * Shown by name rather than as a count, because the user's next action is to physically find those
 * particular phones.
 *
 * @param labels the outstanding companions' names.
 */
@Composable
private fun WaitingOn(labels: List<String>) {
    KillitPanel(padding = 16.dp, spacing = 6.dp) {
        KillitSectionTitle(stringResource(R.string.qr_unlock_waiting_on))
        labels.forEach { label ->
            KillitBody(text = "• $label", color = KillitForeground)
        }
    }
}

/**
 * Turns the last scan outcome into a sentence.
 *
 * A camera reads whatever is in front of it, so most scans are simply not Killit codes; those are
 * silent. Everything else names what went wrong, because "nothing happened" is the worst possible
 * feedback when two people are standing there pointing phones at each other.
 */
@Composable
internal fun ScanFeedback(outcome: ScanOutcome?) {
    val message = when (outcome) {
        null,
        ScanOutcome.NotAKillitCode,
        ScanOutcome.Accepted -> null

        ScanOutcome.WrongKind -> R.string.scan_wrong_kind
        ScanOutcome.Expired -> R.string.scan_expired
        ScanOutcome.BadSignature -> R.string.scan_bad_signature
        ScanOutcome.UnknownPeer -> R.string.scan_unknown_peer
        ScanOutcome.AlreadyApproved -> R.string.scan_already_approved
        ScanOutcome.NoPairingKey -> R.string.scan_no_key
        ScanOutcome.NotDeviceOwner -> R.string.scan_not_device_owner
        ScanOutcome.AlreadyPaired -> R.string.scan_already_paired
        ScanOutcome.SelfPairing -> R.string.scan_self_pairing
    } ?: return

    KillitPanel(borderColor = KillitRed, padding = 14.dp) {
        KillitError(stringResource(message))
    }
}
