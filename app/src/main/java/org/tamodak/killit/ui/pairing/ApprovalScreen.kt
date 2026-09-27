package org.tamodak.killit.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.data.CameraFacing
import org.tamodak.killit.ui.KillitUiState
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitScreen

/**
 * Acting as somebody else's companion: read their challenge, hand back a signature.
 *
 * Reachable whether or not this phone has companions of its own. Pairing is one-directional — A
 * adding B changes nothing on B — so a phone that is not itself QR-locked still has to be able to
 * approve, and it can do so from its own locked gate.
 *
 * @param state supplies the signature to show back and the last scan outcome.
 * @param onApprovalRequestScanned called with the text of a scanned challenge.
 * @param onCameraFacingChange called when the user switches cameras.
 * @param onDone leaves the screen, returning to Home or the gate depending on whether this session
 *   is authenticated.
 * @param modifier applied to the screen.
 */
@Composable
fun ApprovalScreen(
    state: KillitUiState,
    onApprovalRequestScanned: (String) -> Unit,
    onCameraFacingChange: (CameraFacing) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onDone() }

    val response = state.approvalResponse

    KillitScreen(
        title = stringResource(R.string.qr_choose_approve),
        modifier = modifier,
        onBack = onDone,
    ) {
        Column(
            modifier = Modifier.padding(
                start = KillitGutter,
                end = KillitGutter,
                top = 24.dp,
                bottom = 40.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (response == null) {
                FlowStepHeader(
                    step = 1,
                    of = 2,
                    title = stringResource(R.string.qr_approve_scan_title),
                    instruction = stringResource(R.string.qr_approve_intro),
                )
                ScanFeedback(state.scanOutcome)
                ScannerPanel(
                    onScanned = onApprovalRequestScanned,
                    cameraFacing = state.cameraFacing,
                    onCameraFacingChange = onCameraFacingChange,
                )
                KillitButton(text = stringResource(R.string.cancel), onClick = onDone)
            } else {
                FlowStepHeader(
                    step = 2,
                    of = 2,
                    title = stringResource(R.string.qr_approve_show_title),
                    instruction = stringResource(R.string.qr_approve_ready),
                )
                BigQrPanel(
                    payload = response,
                    contentDescription = stringResource(R.string.qr_approve_code_description),
                )
                KillitPrimaryButton(text = stringResource(R.string.done), onClick = onDone)
            }
        }
    }
}
