package org.tamodak.killit.ui.myphone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.tamodak.killit.R
import org.tamodak.killit.pairing.LiveUnlock
import org.tamodak.killit.pairing.store.Vote
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTextArea
import org.tamodak.killit.ui.components.PeerBadge
import org.tamodak.killit.ui.components.textFits
import org.tamodak.killit.ui.formatDuration
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitTextFaint

/**
 * The live unlock (PLAN.md §3.4, §15.4): the request goes out, and this screen waits with the user
 * until every guardian has answered.
 *
 * The screen stays on (C1), there is no time limit while it is in front (C2), and leaving it —
 * back, home, a call — withdraws the request (H12). Once every guardian approves, the gate opens
 * on its own, for as long as the user stays in Killit (§3.5).
 *
 * @param follow keeps a long poll open for answers while the screen is shown.
 */
@Composable
fun UnlockWaitingScreen(
    pairing: PairingUiState,
    onBack: () -> Unit,
    onStart: (String) -> Unit,
    onDismissResult: () -> Unit,
    onRefreshAvailability: () -> Unit,
    follow: suspend () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) {
        onRefreshAvailability()
        follow()
    }
    BackHandler(onBack = onBack)

    var explanation by rememberSaveable { mutableStateOf("") }

    KillitScreen(title = stringResource(R.string.unlock_title), onBack = onBack) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            when (val unlock = pairing.unlock) {
                is LiveUnlock.State.Waiting -> {
                    KillitPanel(borderColor = KillitBlue, spacing = 8.dp) {
                        KillitSectionTitle(stringResource(R.string.unlock_waiting_title))
                        KillitBody(stringResource(R.string.unlock_waiting_body))
                    }
                    unlock.guardians.forEach { GuardianVoteRow(it, pairing) }
                }

                is LiveUnlock.State.Denied -> {
                    KillitPanel(borderColor = KillitRed, spacing = 8.dp) {
                        KillitSectionTitle(stringResource(R.string.unlock_denied_title))
                        CooldownText(unlock.cooldownUntil)
                    }
                    unlock.guardians.forEach { GuardianVoteRow(it, pairing) }
                    KillitButton(text = stringResource(R.string.ok), onClick = onDismissResult)
                }

                is LiveUnlock.State.Approved -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = KillitGreen, modifier = Modifier.size(24.dp))
                        KillitBody(stringResource(R.string.unlock_approved), color = KillitForeground)
                    }
                }

                LiveUnlock.State.Idle -> {
                    KillitBody(stringResource(R.string.unlock_intro))
                    when (val availability = pairing.unlockAvailability) {
                        is LiveUnlock.Availability.CoolingDown -> KillitPanel(borderColor = KillitBlue) {
                            CooldownText(availability.until, onOver = onRefreshAvailability)
                        }
                        LiveUnlock.Availability.DailyLimitReached -> KillitPanel(borderColor = KillitBlue) {
                            KillitBody(stringResource(R.string.unlock_daily_limit), color = KillitForeground)
                        }
                        else -> {
                            KillitTextArea(
                                value = explanation,
                                onValueChange = { explanation = it },
                                placeholder = stringResource(R.string.unlock_explanation_hint),
                                rule = TextRule.OPTIONAL_EXPLANATION,
                                minHeight = 90.dp,
                            )
                            KillitPrimaryButton(
                                text = stringResource(R.string.unlock_send),
                                onClick = { onStart(explanation) },
                                enabled = !pairing.busy && textFits(explanation, TextRule.OPTIONAL_EXPLANATION) &&
                                    availability == LiveUnlock.Availability.Ready,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One guardian and their answer so far: a badge, a name and a mark. */
@Composable
private fun GuardianVoteRow(vote: LiveUnlock.GuardianVote, pairing: PairingUiState) {
    val fp = pairing.pairings.firstOrNull { it.pairId.contentEquals(vote.pairId) }?.peerFp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (fp != null) PeerBadge(fp = fp, size = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = vote.name, style = MaterialTheme.typography.titleSmall, color = KillitForeground)
            if (vote.note.isNotEmpty()) {
                Text(text = vote.note, style = MaterialTheme.typography.bodySmall, color = KillitTextFaint)
            }
        }
        when (vote.vote) {
            Vote.WAITING -> Icon(KillitIcons.Clock, contentDescription = stringResource(R.string.vote_waiting), tint = KillitTextFaint, modifier = Modifier.size(24.dp))
            Vote.APPROVED -> Icon(KillitIcons.Check, contentDescription = stringResource(R.string.vote_approved), tint = KillitGreen, modifier = Modifier.size(24.dp))
            Vote.DENIED -> Icon(KillitIcons.Close, contentDescription = stringResource(R.string.vote_denied), tint = KillitRed, modifier = Modifier.size(24.dp))
        }
    }
}

/** "You can ask again in 4m 12s", counting down live. */
@Composable
private fun CooldownText(until: Long, onOver: () -> Unit = {}) {
    var now by remember(until) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (now < until) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
        onOver()
    }
    if (now < until) {
        KillitBody(stringResource(R.string.unlock_cooldown, formatDuration(until - now)), color = KillitForeground)
    }
}
