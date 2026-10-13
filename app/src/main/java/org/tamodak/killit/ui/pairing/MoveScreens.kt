package org.tamodak.killit.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.tamodak.killit.R
import org.tamodak.killit.pairing.MoveEngine
import org.tamodak.killit.pairing.store.CeremonyEntity
import org.tamodak.killit.pairing.store.CeremonyFailure
import org.tamodak.killit.pairing.store.CeremonyPurpose
import org.tamodak.killit.pairing.store.CeremonySide
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.MoveStage
import org.tamodak.killit.protocol.formatSafetyCode
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.components.CautiousChoice
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitField
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.formatDuration
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitRed

/*
 * Moving to a new phone (PLAN.md §3.10), one screen per phone. The new phone shows a code; the old
 * one types it; both show the same safety code, compared side by side; the old phone's user
 * confirms; and every pairing changes phones.
 *
 * As in the pairing wizard, every step is drawn from the stored ceremony, so leaving and coming
 * back picks up where the move is. Leaving gives the move up only where the engine allows it: once
 * the other phone may be committing, the move goes on without the screen.
 */

/**
 * The new phone's side, opened from the Hub. It takes guardians only when Killit protects it
 * already, and never when it has pairings of its own.
 *
 * @param canTakeGuardians device owner, with a passkey, and not frozen by a move of its own.
 * @param onOpenSetup goes to My phone, to set up device owner first.
 * @param follow keeps up with the old phone while the screen is shown.
 */
@Composable
fun MoveInScreen(
    canTakeGuardians: Boolean,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSetup: () -> Unit,
    follow: suspend () -> Unit,
) {
    LaunchedEffect(Unit) { follow() }
    val ceremony = pairing.ceremony?.takeIf { it.purpose == CeremonyPurpose.MOVE && it.side == CeremonySide.CREATOR }
    val leave = leave(ceremony, onCancel, onDismiss, onBack)
    BackHandler(onBack = leave)

    KillitScreen(title = stringResource(R.string.move_in_title), onBack = leave) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (ceremony == null) {
                MoveInIntro(canTakeGuardians, pairing, onStart, onOpenSetup)
                return@Column
            }
            when (ceremony.stage) {
                CeremonyStage.WAITING_FOR_JOINER -> ShowMoveCode(ceremony, onCancel)
                CeremonyStage.WAITING_FOR_JOINER_REVEAL, CeremonyStage.WAITING_FOR_CREATOR ->
                    Waiting(stringResource(R.string.wizard_connecting), onCancel)
                CeremonyStage.COMPARING -> CompareSideBySide(ceremony, pairing.busy, onConfirm, onReject)
                CeremonyStage.CONFIRMED -> Waiting(stringResource(R.string.wizard_waiting_confirm), onCancel)
                CeremonyStage.LINKED -> if (ceremony.moveStage == MoveStage.READY) {
                    MoveInReady(ceremony, onCancel)
                } else {
                    Waiting(stringResource(R.string.move_in_receiving), onCancel)
                }
                CeremonyStage.PAIRED -> Moved(
                    title = stringResource(R.string.move_in_done_title),
                    body = stringResource(R.string.move_in_done_body),
                    ceremony = ceremony,
                    onDone = {
                        onDismiss()
                        onBack()
                    },
                )
                CeremonyStage.FAILED -> MoveFailed(ceremony.failure, onDismiss)
            }
        }
    }
}

/**
 * The old phone's side, opened from either section's settings. A phone with guardians stays
 * frozen after it moves, which it says before the code is typed and again before the user confirms.
 *
 * @param guarded whether this phone has guardians now.
 * @param onJoin types the new phone's code.
 * @param onCommit the user confirmed the move.
 * @param onDone the move is over and seen.
 */
@Composable
fun MoveOutScreen(
    guarded: Boolean,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onJoin: (String) -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    follow: suspend () -> Unit,
) {
    LaunchedEffect(Unit) { follow() }
    val ceremony = pairing.ceremony?.takeIf { it.purpose == CeremonyPurpose.MOVE && it.side == CeremonySide.JOINER }
    val leave = leave(ceremony, onCancel, onDismiss, onBack)
    BackHandler(onBack = leave)

    KillitScreen(title = stringResource(R.string.move_out_title), onBack = leave) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (ceremony == null) {
                MoveOutIntro(guarded, pairing, onJoin)
                return@Column
            }
            when (ceremony.stage) {
                CeremonyStage.WAITING_FOR_CREATOR, CeremonyStage.WAITING_FOR_JOINER_REVEAL, CeremonyStage.WAITING_FOR_JOINER ->
                    Waiting(stringResource(R.string.wizard_connecting), onCancel)
                CeremonyStage.COMPARING -> CompareSideBySide(ceremony, pairing.busy, onConfirm, onReject)
                CeremonyStage.CONFIRMED -> Waiting(stringResource(R.string.wizard_waiting_confirm), onCancel)
                CeremonyStage.LINKED -> when (ceremony.moveStage) {
                    MoveStage.READY -> MoveOutReady(ceremony, pairing.busy, onCommit, onCancel)
                    MoveStage.COMMITTING, MoveStage.MOVED -> Progress(stringResource(R.string.move_out_moving))
                    else -> Waiting(stringResource(R.string.move_out_sending), onCancel)
                }
                CeremonyStage.PAIRED -> Moved(
                    title = stringResource(R.string.move_out_done_title),
                    body = stringResource(R.string.move_out_done_body) +
                        if (ceremony.moveGuardians > 0) "\n\n" + stringResource(R.string.move_out_done_frozen) else "",
                    ceremony = null,
                    onDone = {
                        onDismiss()
                        onDone()
                    },
                )
                CeremonyStage.FAILED -> MoveFailed(ceremony.failure, onDismiss)
            }
        }
    }
}

/** Back: gives a running move up where the engine allows it, forgets a finished one, and leaves. */
private fun leave(ceremony: CeremonyEntity?, onCancel: () -> Unit, onDismiss: () -> Unit, onBack: () -> Unit): () -> Unit = {
    when {
        ceremony == null -> Unit
        ceremony.isRunning() -> onCancel()
        else -> onDismiss()
    }
    onBack()
}

@Composable
private fun MoveInIntro(canTakeGuardians: Boolean, pairing: PairingUiState, onStart: () -> Unit, onOpenSetup: () -> Unit) {
    KillitBody(stringResource(R.string.move_in_intro), color = KillitForeground)
    if (pairing.pairings.isNotEmpty()) {
        KillitPanel(borderColor = KillitRed) { KillitBody(stringResource(R.string.move_failed_has_pairings), color = KillitForeground) }
        return
    }
    if (!canTakeGuardians) {
        KillitPanel(borderColor = KillitBlue, spacing = 10.dp) {
            KillitBody(stringResource(R.string.move_in_needs_owner), color = KillitForeground)
            KillitButton(text = stringResource(R.string.wizard_set_up), onClick = onOpenSetup)
        }
    }
    KillitPrimaryButton(text = stringResource(R.string.move_in_start), onClick = onStart, enabled = !pairing.busy)
}

/** The code the old phone types, big, with its lifetime. */
@Composable
private fun ShowMoveCode(ceremony: CeremonyEntity, onCancel: () -> Unit) {
    KillitBody(stringResource(R.string.move_in_give_code), color = KillitForeground)
    Text(
        text = ceremony.code.orEmpty(),
        style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 40.sp),
        color = KillitForeground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Countdown(until = ceremony.expiresAt)
    Waiting(stringResource(R.string.wizard_waiting_joiner), onCancel)
}

/**
 * The safety code, as in a pairing, but compared by looking: the two phones of a move lie side by
 * side (SPEC.md §6.5). "They don't match" is the wider button.
 */
@Composable
private fun CompareSideBySide(ceremony: CeremonyEntity, busy: Boolean, onConfirm: () -> Unit, onReject: () -> Unit) {
    KillitPanel(borderColor = KillitBlue, spacing = 8.dp) {
        KillitSectionTitle(stringResource(R.string.move_compare_title))
        KillitBody(stringResource(R.string.move_compare_body))
    }
    Text(
        text = formatSafetyCode(ceremony.safetyCode.orEmpty()),
        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
        color = KillitForeground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    CautiousChoice(
        cautious = stringResource(R.string.wizard_no_match),
        onCautious = onReject,
        bold = stringResource(R.string.wizard_match),
        onBold = onConfirm,
        enabled = !busy,
    )
}

/**
 * The new phone holds the state and waits for the old phone's user. It may give up only once the
 * old phone can no longer commit, an hour on, so it never drops pairings the pairs already moved.
 */
@Composable
private fun MoveInReady(ceremony: CeremonyEntity, onCancel: () -> Unit) {
    KillitPanel(borderColor = KillitBlue, spacing = 8.dp) {
        KillitSectionTitle(stringResource(R.string.move_in_ready_title))
        KillitBody(stringResource(R.string.move_in_ready_body), color = KillitForeground)
        Counts(ceremony.moveGuardians, ceremony.moveWards)
    }
    val giveUpAt = (ceremony.moveReadyAt ?: 0L) + MoveEngine.GIVE_UP_AFTER_MILLIS
    val now = clockUntil(giveUpAt)
    if (now < giveUpAt) {
        Progress(stringResource(R.string.move_in_give_up_in, formatDuration(giveUpAt - now)))
    } else {
        KillitTextButton(text = stringResource(R.string.move_in_give_up), onClick = onCancel)
    }
}

/** What moves, how long is left to confirm, and Move, the narrower button. */
@Composable
private fun MoveOutReady(ceremony: CeremonyEntity, busy: Boolean, onCommit: () -> Unit, onCancel: () -> Unit) {
    val model = ceremony.movePeerModel?.ifEmpty { null }
    val frozen = ceremony.moveGuardians > 0
    KillitPanel(borderColor = KillitBlue, spacing = 8.dp) {
        KillitSectionTitle(
            if (model != null) stringResource(R.string.move_out_ready_title, model) else stringResource(R.string.move_out_ready_title_bare),
        )
        KillitBody(stringResource(R.string.move_out_moves), color = KillitForeground)
        Counts(ceremony.moveGuardians, ceremony.moveWards)
    }
    if (frozen) KillitPanel(borderColor = KillitRed) { KillitBody(stringResource(R.string.move_out_frozen_warning), color = KillitForeground) }
    // Past the window the engine gives the move up when Move is pressed, and the screen says so.
    val until = (ceremony.moveReadyAt ?: 0L) + MoveEngine.COMMIT_WINDOW_MILLIS
    val now = clockUntil(until)
    if (now < until) KillitBody(stringResource(R.string.move_out_ready_window, formatDuration(until - now)))
    CautiousChoice(
        cautious = stringResource(R.string.cancel),
        onCautious = onCancel,
        bold = stringResource(R.string.move_out_confirm),
        onBold = onCommit,
        enabled = !busy,
        boldAccent = if (frozen) KillitRed else KillitGreen,
    )
}

/**
 * The old phone's first step: what moves, what becomes of this phone, and the code.
 *
 * @param guarded whether this phone has guardians, and so stays frozen after the move.
 */
@Composable
private fun MoveOutIntro(guarded: Boolean, pairing: PairingUiState, onJoin: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    KillitBody(stringResource(R.string.move_out_intro), color = KillitForeground)
    KillitPanel(spacing = 6.dp) {
        KillitSectionTitle(stringResource(R.string.move_out_moves))
        Counts(pairing.guardians.size, pairing.wards.size)
    }
    if (guarded) KillitPanel(borderColor = KillitRed) { KillitBody(stringResource(R.string.move_out_frozen_warning), color = KillitForeground) }
    KillitSectionTitle(stringResource(R.string.wizard_enter_code))
    KillitField(
        value = code,
        onValueChange = { code = toAsciiCode(it) },
        placeholder = "XXXX-XXXX",
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
    )
    KillitPrimaryButton(
        text = stringResource(R.string.move_out_connect),
        onClick = { onJoin(code) },
        enabled = code.isNotBlank() && pairing.pairings.isNotEmpty() && !pairing.busy,
    )
}

/**
 * The move is done.
 *
 * @param ceremony the new phone's, to say what it took over; null on the old phone, which has
 *   nothing left.
 */
@Composable
private fun Moved(title: String, body: String, ceremony: CeremonyEntity?, onDone: () -> Unit) {
    KillitPanel(borderColor = KillitGreen, spacing = 8.dp) {
        KillitSectionTitle(title)
        KillitBody(body, color = KillitForeground)
        if (ceremony != null) Counts(ceremony.moveGuardians, ceremony.moveWards)
    }
    KillitPrimaryButton(text = stringResource(R.string.wizard_done), onClick = onDone)
}

/** How a move ended without moving anything, in words a person can act on. */
@Composable
private fun MoveFailed(failure: CeremonyFailure?, onDismiss: () -> Unit) {
    KillitPanel(borderColor = KillitRed) { KillitBody(stringResource(failureText(CeremonyPurpose.MOVE, failure)), color = KillitForeground) }
    KillitButton(text = stringResource(R.string.wizard_again), onClick = onDismiss)
}

/** One line for the guardians and one for the guarded phones, whichever there are. */
@Composable
private fun Counts(guardians: Int, wards: Int) {
    if (guardians > 0) KillitBody(pluralStringResource(R.plurals.move_guardians, guardians, guardians), color = KillitForeground)
    if (wards > 0) KillitBody(pluralStringResource(R.plurals.move_wards, wards, wards), color = KillitForeground)
}

/** A step that waits and can no longer be given up: the move goes on even if the screen is left. */
@Composable
private fun Progress(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = KillitGreen, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        KillitBody(text, color = KillitForeground)
    }
}

/** The wall clock, read again every second until [until]. */
@Composable
private fun clockUntil(until: Long): Long {
    var now by remember(until) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (now < until) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}
