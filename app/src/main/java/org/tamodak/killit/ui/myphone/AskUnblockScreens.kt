package org.tamodak.killit.ui.myphone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.data.PackageStatus
import org.tamodak.killit.pairing.store.OutgoingRequestWithCopies
import org.tamodak.killit.pairing.store.RequestState
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.pairing.store.Vote
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.toHex
import org.tamodak.killit.ui.KillitUiState
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.apps.AppIcon
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTextArea
import org.tamodak.killit.ui.components.formatWhen
import org.tamodak.killit.ui.components.textFits
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitBorderDim
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitTextFaint

/**
 * A blocked app this phone may ask its guardians about, with where its asking stands.
 *
 * @property open the request on its way, if any.
 * @property last the most recent request, open or closed.
 */
private class AskableApp(
    val packageName: String,
    val label: String,
    val isNew: Boolean,
    val chances: Int,
    val open: OutgoingRequestWithCopies?,
    val last: OutgoingRequestWithCopies?,
) {
    val lastDenied: Boolean get() = last?.request?.state == RequestState.DENIED
}

/** Every blocked app on the phone, newest decisions first, with its request state (§3.6). */
private fun askableApps(state: KillitUiState, pairing: PairingUiState): List<AskableApp> {
    val requests = pairing.outgoing.filter { it.request.type == RequestType.UNBLOCK_APP }
    return state.apps
        .filter { it.packageName in state.blocked }
        .mapNotNull { app ->
            val known = pairing.known[app.packageName] ?: return@mapNotNull null
            if (!known.status.isBlocked) return@mapNotNull null
            val mine = requests.filter { it.request.packageName == app.packageName }
            AskableApp(
                packageName = app.packageName,
                label = app.label,
                isNew = known.status == PackageStatus.PENDING,
                chances = pairing.chancesFor(app.packageName, known.certDigest),
                open = mine.firstOrNull { it.request.state.isOpen },
                last = mine.firstOrNull(),
            )
        }
        .sortedWith(compareByDescending<AskableApp> { it.isNew }.thenBy { it.label.lowercase() })
}

/**
 * The blocked apps this phone can ask about (PLAN.md §15.4): each with its chances (D4) and
 * whether a request is out.
 */
@Composable
fun AskUnblockListScreen(
    state: KillitUiState,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    iconLoader: suspend (String) -> ImageBitmap?,
) {
    BackHandler(onBack = onBack)
    val apps = askableApps(state, pairing)
    KillitScreen(title = stringResource(R.string.my_phone_ask_unblock), onBack = onBack) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KillitBody(stringResource(R.string.ask_unblock_intro))
            if (apps.isEmpty()) KillitBody(stringResource(R.string.ask_unblock_none), color = KillitForeground)
            apps.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(3.dp, if (app.open != null) KillitBlue else KillitBorderDim, RectangleShape)
                        .clickable { onOpen(app.packageName) }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(packageName = app.packageName, iconLoader = iconLoader)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = app.label, style = MaterialTheme.typography.titleSmall, color = KillitForeground)
                        Text(
                            text = askState(app),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (app.chances == 0 && app.open == null) KillitRed else KillitTextFaint,
                        )
                    }
                    if (app.isNew) Text(text = stringResource(R.string.apps_new_chip), style = MaterialTheme.typography.labelSmall, color = KillitBlue)
                }
            }
        }
    }
}

@Composable
private fun askState(app: AskableApp): String = when {
    app.open != null -> stringResource(R.string.ask_state_waiting)
    app.chances == 0 -> stringResource(R.string.ask_state_no_chances)
    app.lastDenied -> pluralStringResource(R.plurals.ask_state_denied, app.chances, app.chances)
    else -> pluralStringResource(R.plurals.ask_chances_left, app.chances, app.chances)
}

/**
 * Asking about one app (§3.6): the explanation, 10–500 characters (D3), goes to every guardian
 * with the app's name, package and installer. Nothing else needs the passkey again (J1).
 *
 * @param onSend sends the request; [onSent] runs once every guardian has it queued.
 */
@Composable
fun AskUnblockFormScreen(
    packageName: String,
    state: KillitUiState,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onSend: (String, String) -> Unit,
    onWithdraw: (String) -> Unit,
    iconLoader: suspend (String) -> ImageBitmap?,
) {
    BackHandler(onBack = onBack)
    val app = askableApps(state, pairing).firstOrNull { it.packageName == packageName }
    var explanation by rememberSaveable(packageName) { mutableStateOf("") }
    val label = state.apps.firstOrNull { it.packageName == packageName }?.label ?: packageName

    KillitScreen(title = label, onBack = onBack) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName = packageName, iconLoader = iconLoader)
                Column {
                    Text(text = label, style = MaterialTheme.typography.titleMedium, color = KillitForeground)
                    Text(text = packageName, style = MaterialTheme.typography.bodySmall, color = KillitTextFaint)
                }
            }

            val open = app?.open
            val last = app?.last
            // The guardians' notes on a denial are the only place they explain themselves (G9).
            if (open == null && last != null && app.lastDenied) {
                KillitPanel(borderColor = KillitRed, spacing = 10.dp) {
                    KillitSectionTitle(stringResource(R.string.ask_last_denied))
                    GuardianAnswers(last, pairing)
                }
            }
            when {
                app == null -> KillitBody(stringResource(R.string.ask_unblock_not_blocked), color = KillitForeground)
                open != null -> OpenRequestPanel(open, pairing, onWithdraw)
                app.chances == 0 -> KillitPanel(borderColor = KillitRed) {
                    KillitBody(stringResource(R.string.ask_state_no_chances_long), color = KillitForeground)
                }
                else -> {
                    KillitBody(pluralStringResource(R.plurals.ask_chances_left, app.chances, app.chances), color = KillitForeground)
                    KillitTextArea(
                        value = explanation,
                        onValueChange = { explanation = it },
                        placeholder = stringResource(R.string.ask_unblock_hint),
                        rule = TextRule.REQUIRED_EXPLANATION,
                    )
                    KillitBody(stringResource(R.string.ask_unblock_what_they_see))
                    KillitPrimaryButton(
                        text = stringResource(R.string.ask_send),
                        onClick = { onSend(packageName, explanation) },
                        enabled = !pairing.busy && textFits(explanation, TextRule.REQUIRED_EXPLANATION),
                    )
                }
            }
        }
    }
}

/** A request on its way: each guardian's answer so far, and the way to withdraw it (B3). */
@Composable
internal fun OpenRequestPanel(request: OutgoingRequestWithCopies, pairing: PairingUiState, onWithdraw: (String) -> Unit) {
    KillitPanel(borderColor = KillitBlue, spacing = 10.dp) {
        KillitSectionTitle(stringResource(R.string.ask_state_waiting))
        Text(
            text = formatWhen(request.request.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = KillitTextFaint,
        )
        GuardianAnswers(request, pairing)
        KillitButton(text = stringResource(R.string.ask_withdraw), onClick = { onWithdraw(request.request.requestId.toHex()) })
    }
}

/** Each guardian's answer to a request, with the note they added (G9). */
@Composable
private fun GuardianAnswers(request: OutgoingRequestWithCopies, pairing: PairingUiState) {
    request.copies.forEach { copy ->
        // A guardian who has left since keeps its answer, under a placeholder name.
        val name = pairing.pairings.firstOrNull { it.pairId.contentEquals(copy.pairId) }?.nickname ?: "?"
        val (text, color) = when (copy.vote) {
            Vote.WAITING -> stringResource(R.string.vote_waiting) to KillitTextFaint
            Vote.APPROVED -> stringResource(R.string.vote_approved) to KillitGreen
            Vote.DENIED -> stringResource(R.string.vote_denied) to KillitRed
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = name, style = MaterialTheme.typography.bodyMedium, color = KillitForeground, modifier = Modifier.weight(1f))
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        if (copy.note.isNotEmpty()) KillitBody("“${copy.note}”")
    }
}
