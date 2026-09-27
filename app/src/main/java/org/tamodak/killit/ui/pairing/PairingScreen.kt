package org.tamodak.killit.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import org.tamodak.killit.data.PairedPeer
import org.tamodak.killit.pairing.ChallengeSession
import org.tamodak.killit.pairing.QrPayload
import org.tamodak.killit.pairing.ScanOutcome
import org.tamodak.killit.ui.KillitUiState
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitDangerButton
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitError
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitRow
import org.tamodak.killit.ui.components.KillitRule
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.formatDuration
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitRowDivider
import org.tamodak.killit.ui.theme.KillitTextFaint
import kotlinx.coroutines.delay

/**
 * Where the user is in the pairing flow.
 *
 * `Hub` is the list and the two routes out of it. `Warning` through `Added` is the locking side —
 * acknowledge the consequences, get ready, scan the other phone's code, confirm. `ShowCode` is the
 * other side, where this phone offers itself as a companion and stores nothing.
 */
private enum class PairStep { Hub, Warning, Ready, Scan, Added, ShowCode }

/**
 * Where the user is in removing a companion: show the challenge, then scan that companion's
 * signature back.
 */
private enum class RemovalStep { ShowCode, Scan }

/**
 * Manage companion devices.
 *
 * Pairing is the most consequential thing this app can do — it disables the passkey, blocks the
 * removal countdown, and can only be undone with a companion present — so the first pairing is
 * gated behind an explicit acknowledgement of exactly that.
 *
 * @param state supplies the peer list, this device's own code, and the last scan outcome.
 * @param onPairingCodeScanned called with the text of a scanned pairing offer.
 * @param onStartPeerRemoval begins removing a companion, which it then has to approve.
 * @param onCancelPeerRemoval abandons a removal in progress.
 * @param onRemovalResponseScanned called with the text of a scanned removal approval.
 * @param onDismissScanOutcome clears the explanation of why the last scan was rejected.
 * @param onCameraFacingChange called when the user switches cameras.
 * @param onBack leaves the screen.
 * @param modifier applied to the screen.
 */
@Composable
fun PairingScreen(
    state: KillitUiState,
    onPairingCodeScanned: (String) -> Unit,
    onStartPeerRemoval: (PairedPeer) -> Unit,
    onCancelPeerRemoval: () -> Unit,
    onRemovalResponseScanned: (String) -> Unit,
    onDismissScanOutcome: () -> Unit,
    onCameraFacingChange: (CameraFacing) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(PairStep.Hub) }
    var confirmRemoval by remember { mutableStateOf<PairedPeer?>(null) }
    var peersBeforeScan by rememberSaveable { mutableIntStateOf(0) }
    val ownKey = state.ownPublicKey

    fun goTo(next: PairStep) {
        KillitLog.d(KillitLog.UI) { "Pairing step $step -> $next" }
        onDismissScanOutcome()
        step = next
    }

    BackHandler { if (step == PairStep.Hub) onBack() else goTo(PairStep.Hub) }

    KillitScreen(
        title = stringResource(R.string.pair_title),
        modifier = modifier,
        onBack = { if (step == PairStep.Hub) onBack() else goTo(PairStep.Hub) },
    ) {
        Column(
            modifier = Modifier.padding(
                start = KillitGutter,
                end = KillitGutter,
                top = 24.dp,
                bottom = 40.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // The unpair round takes over the screen: the departing companion has to sign, and
            // doing that alongside the pairing flow would leave two rounds competing for the same
            // camera.
            val removalSession = state.challenge
            val removalPeer = state.peerPendingRemoval
            if (removalPeer != null) {
                // The peer is chosen before the round exists, so there is a frame or two with no
                // session to show. Falling through to the hub here would flash the wrong screen.
                if (removalSession == null) {
                    CircularProgressIndicator(color = KillitGreen)
                    KillitButton(
                        text = stringResource(R.string.cancel),
                        onClick = onCancelPeerRemoval,
                    )
                    return@Column
                }
                RemovalFlow(
                    peer = removalPeer,
                    session = removalSession,
                    scanOutcome = state.scanOutcome,
                    cameraFacing = state.cameraFacing,
                    onCameraFacingChange = onCameraFacingChange,
                    onResponseScanned = onRemovalResponseScanned,
                    onDismissScanOutcome = onDismissScanOutcome,
                    onCancel = onCancelPeerRemoval,
                )
                return@Column
            }

            when (step) {
                PairStep.Hub -> {
                    ScanFeedback(state.scanOutcome)

                    if (state.peers.isNotEmpty()) {
                        PeerList(state = state, onRemove = { confirmRemoval = it })
                    }

                    KillitBody(
                        text = stringResource(R.string.pair_hub_subtitle),
                        textAlign = TextAlign.Center,
                    )

                    if (ownKey == null) {
                        KillitError(stringResource(R.string.scan_no_key))
                    } else {
                        KillitRow(
                            title = stringResource(R.string.pair_role_lock_title),
                            subtitle = stringResource(R.string.pair_role_lock_desc),
                            icon = KillitIcons.Lock,
                            iconTint = KillitRed,
                            borderColor = KillitRed,
                            onClick = {
                                goTo(if (state.peers.isEmpty()) PairStep.Warning else PairStep.Ready)
                            },
                        )
                        KillitRow(
                            title = stringResource(R.string.pair_role_companion_title),
                            subtitle = stringResource(R.string.pair_role_companion_desc),
                            icon = KillitIcons.QrCode,
                            iconTint = KillitBlue,
                            onClick = { goTo(PairStep.ShowCode) },
                        )
                    }

                    KillitButton(text = stringResource(R.string.back), onClick = onBack)
                }

                PairStep.Warning -> FirstPairingWarning(
                    onAcknowledge = { goTo(PairStep.Ready) },
                    onBack = { goTo(PairStep.Hub) },
                )

                PairStep.Ready -> {
                    FlowStepHeader(
                        step = 1,
                        of = 2,
                        title = stringResource(R.string.pair_add_ready_title),
                        instruction = stringResource(R.string.pair_add_ready_body),
                    )
                    LockDirectionBanner(locksThisPhone = true)
                    KillitPrimaryButton(
                        text = stringResource(R.string.pair_add_ready_action),
                        onClick = {
                            peersBeforeScan = state.peers.size
                            goTo(PairStep.Scan)
                        },
                    )
                    KillitButton(
                        text = stringResource(R.string.cancel),
                        onClick = { goTo(PairStep.Hub) },
                    )
                }

                PairStep.Scan -> {
                    LaunchedEffect(state.peers.size) {
                        if (state.peers.size > peersBeforeScan) step = PairStep.Added
                    }
                    FlowStepHeader(
                        step = 2,
                        of = 2,
                        title = stringResource(R.string.pair_add_scan_title),
                        instruction = stringResource(R.string.pair_add_scan_body),
                    )
                    LockDirectionBanner(locksThisPhone = true)
                    ScanFeedback(state.scanOutcome)
                    ScannerPanel(
                        onScanned = onPairingCodeScanned,
                        cameraFacing = state.cameraFacing,
                        onCameraFacingChange = onCameraFacingChange,
                    )
                    KillitButton(
                        text = stringResource(R.string.back),
                        onClick = { goTo(PairStep.Ready) },
                    )
                }

                PairStep.Added -> {
                    Text(
                        text = stringResource(
                            R.string.pair_add_done_title,
                            state.peers.lastOrNull()?.label.orEmpty(),
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = KillitGreen,
                        textAlign = TextAlign.Center,
                    )
                    KillitBody(
                        text = stringResource(R.string.pair_add_done_body),
                        textAlign = TextAlign.Center,
                    )
                    KillitPrimaryButton(
                        text = stringResource(R.string.pair_add_another),
                        onClick = { goTo(PairStep.Ready) },
                    )
                    KillitButton(
                        text = stringResource(R.string.done),
                        onClick = { goTo(PairStep.Hub) },
                    )
                }

                PairStep.ShowCode -> {
                    if (ownKey == null) {
                        KillitError(stringResource(R.string.scan_no_key))
                    } else {
                        val payload = remember(ownKey) {
                            QrPayload.Pairing(publicKey = ownKey, label = deviceLabel()).encode()
                        }
                        Text(
                            text = stringResource(R.string.pair_show_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = KillitForeground,
                        )
                        KillitBody(
                            text = stringResource(R.string.pair_show_body),
                            textAlign = TextAlign.Center,
                        )
                        LockDirectionBanner(locksThisPhone = false)
                        BigQrPanel(
                            payload = payload,
                            contentDescription = stringResource(R.string.pair_code_description),
                        )
                    }
                    KillitButton(
                        text = stringResource(R.string.done),
                        onClick = { goTo(PairStep.Hub) },
                    )
                }
            }
        }
    }

    confirmRemoval?.let { peer ->
        KillitDialog(
            title = stringResource(R.string.pair_remove_title, peer.label),
            body = stringResource(R.string.pair_remove_body, peer.label),
            accent = KillitRed,
            onDismiss = { confirmRemoval = null },
            confirmText = stringResource(R.string.pair_remove_start),
            onConfirm = {
                confirmRemoval = null
                onStartPeerRemoval(peer)
            },
            dismissText = stringResource(R.string.cancel),
        )
    }
}

/**
 * The one-time acknowledgement before a device gains its first companion.
 *
 * Written plainly and without softening, because everything it describes is true and none of it is
 * reversible without the other phone in the room.
 */
@Composable
private fun FirstPairingWarning(onAcknowledge: () -> Unit, onBack: () -> Unit) {
    KillitPanel(borderColor = KillitRed, spacing = 16.dp) {
        KillitSectionTitle(stringResource(R.string.pair_warning_title))
        KillitBody(
            text = stringResource(R.string.pair_warning_body),
            color = KillitForeground,
        )
        KillitDangerButton(
            text = stringResource(R.string.pair_warning_accept),
            onClick = onAcknowledge,
        )
        KillitButton(text = stringResource(R.string.cancel), onClick = onBack)
    }
}

/**
 * The companions currently paired, each with a way to start removing it.
 *
 * @param state supplies the peer list.
 * @param onRemove begins removing that companion, which it then has to approve.
 */
@Composable
private fun PeerList(state: KillitUiState, onRemove: (PairedPeer) -> Unit) {
    KillitPanel(spacing = 12.dp) {
        KillitSectionTitle(stringResource(R.string.pair_list_title, state.peers.size))
        KillitBody(
            text = stringResource(R.string.pair_list_all_required),
            color = KillitTextFaint,
        )
        state.peers.forEach { peer ->
            KillitRule(color = KillitRowDivider)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = peer.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = KillitForeground,
                    modifier = Modifier.weight(1f),
                )
                KillitTextButton(
                    text = stringResource(R.string.pair_remove),
                    onClick = { onRemove(peer) },
                    color = KillitRed,
                )
            }
        }
    }
}

/** The challenge/response round that removing a companion requires. */
@Composable
private fun ColumnScope.RemovalFlow(
    peer: PairedPeer,
    session: ChallengeSession,
    scanOutcome: ScanOutcome?,
    cameraFacing: CameraFacing,
    onCameraFacingChange: (CameraFacing) -> Unit,
    onResponseScanned: (String) -> Unit,
    onDismissScanOutcome: () -> Unit,
    onCancel: () -> Unit,
) {
    var step by rememberSaveable(peer.label) { mutableStateOf(RemovalStep.ShowCode) }

    var now by remember(session.expiresAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.expiresAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= session.expiresAtMillis) break
            delay(1_000)
        }
    }

    if (session.isExpired(now)) {
        KillitError(stringResource(R.string.qr_session_expired))
        KillitButton(text = stringResource(R.string.cancel), onClick = onCancel)
        return
    }

    val remaining = formatDuration(session.remainingMillis(now))

    when (step) {
        RemovalStep.ShowCode -> {
            val payload = remember(session) { session.toQrPayload().encode() }
            FlowStepHeader(
                step = 1,
                of = 2,
                title = stringResource(R.string.pair_remove_show_title, peer.label),
                instruction = stringResource(R.string.pair_remove_show_body, peer.label, remaining),
            )
            BigQrPanel(
                payload = payload,
                contentDescription = stringResource(R.string.pair_remove_code_description),
            )
            KillitPrimaryButton(
                text = stringResource(R.string.pair_scanned_next),
                onClick = {
                    onDismissScanOutcome()
                    step = RemovalStep.Scan
                },
            )
        }

        RemovalStep.Scan -> {
            FlowStepHeader(
                step = 2,
                of = 2,
                title = stringResource(R.string.pair_remove_scan_title, peer.label),
                instruction = stringResource(R.string.pair_remove_scan_body, remaining),
            )
            ScanFeedback(scanOutcome)
            ScannerPanel(
                onScanned = onResponseScanned,
                cameraFacing = cameraFacing,
                onCameraFacingChange = onCameraFacingChange,
            )
            KillitButton(
                text = stringResource(R.string.pair_back_to_code),
                onClick = {
                    onDismissScanOutcome()
                    step = RemovalStep.ShowCode
                },
            )
        }
    }

    KillitTextButton(text = stringResource(R.string.cancel), onClick = onCancel)
}

/**
 * What this device calls itself in a pairing code, so the other user can tell two companions
 * apart. Presentational only — nothing is decided by it.
 */
private fun deviceLabel(): String =
    listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Killit" }
