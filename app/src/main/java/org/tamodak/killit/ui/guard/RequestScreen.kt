package org.tamodak.killit.ui.guard

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.tamodak.killit.R
import org.tamodak.killit.pairing.store.CancelCause
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.RequestState
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.pairing.store.StatusEntries
import org.tamodak.killit.pairing.store.Vote
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.toHex
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.components.CautiousChoice
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTextArea
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.components.PeerBadge
import org.tamodak.killit.ui.components.RemoteAppIcon
import org.tamodak.killit.ui.components.formatDate
import org.tamodak.killit.ui.components.formatWhen
import org.tamodak.killit.ui.components.textFits
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitTextFaint

/**
 * One request from a guarded phone (PLAN.md §15.6), with what the guardian needs to judge it: the
 * app's name, package and installer, because an app chooses its own name (§5.4); the explanation;
 * the other guardians' answers (G10); an optional note (G9). Deny is the wider button (E1).
 *
 * An unlock request says what approving means: full control until the user leaves Killit. A
 * guardian's unblock that needs the others too (G2) names that guardian; on its proposer's own
 * phone it only shows how the others are answering.
 */
@Composable
fun RequestScreen(
    pairIdHex: String,
    requestIdHex: String,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onDecide: (Boolean, String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val from = pairing.pairing(pairIdHex)
    val request = pairing.incoming.firstOrNull { it.pairId.toHex() == pairIdHex && it.requestId.toHex() == requestIdHex }
    var note by rememberSaveable(requestIdHex) { mutableStateOf("") }
    var showNote by rememberSaveable(requestIdHex) { mutableStateOf(false) }

    KillitScreen(title = from?.nickname ?: stringResource(R.string.hub_guard), onBack = onBack) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (from == null || request == null) {
                KillitBody(stringResource(R.string.request_gone))
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PeerBadge(fp = from.peerFp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = requestSummary(request, from), style = MaterialTheme.typography.titleMedium, color = KillitForeground)
                    Text(text = formatWhen(request.receivedAt), style = MaterialTheme.typography.bodySmall, color = KillitTextFaint)
                }
            }

            when (request.type) {
                RequestType.UNBLOCK_APP -> AppDetails(request)
                RequestType.GUARDIAN_UNBLOCK -> {
                    AppDetails(request)
                    KillitPanel(borderColor = KillitBlue, spacing = 6.dp) {
                        val body = if (request.proposedByMe) R.string.request_guardian_unblock_mine_body else R.string.request_guardian_unblock_body
                        KillitBody(stringResource(body), color = KillitForeground)
                    }
                }
                RequestType.UNLOCK -> KillitPanel(borderColor = KillitBlue, spacing = 6.dp) {
                    KillitBody(stringResource(R.string.request_unlock_meaning, from.nickname), color = KillitForeground)
                }
                RequestType.UNPAIR -> KillitPanel(spacing = 6.dp) {
                    val target = pairing.pairings.firstOrNull { it.pairId.contentEquals(request.targetPairId ?: ByteArray(0)) }
                    if (target == null && request.targetPairId?.contentEquals(from.pairId) != true) {
                        KillitBody(stringResource(R.string.request_unpair_target_unknown), color = KillitForeground)
                    }
                    KillitBody(stringResource(R.string.request_unpair_timer, formatDate(request.timerEndsAt ?: 0L)), color = KillitForeground)
                }
            }

            if (request.explanation.isNotEmpty()) {
                KillitPanel(spacing = 6.dp) { KillitBody("“${request.explanation}”", color = KillitForeground) }
            }
            val chances = request.chancesLeft
            if (chances != null) KillitBody(pluralStringResource(R.plurals.request_chances, chances, chances))

            OthersPanel(request)

            when {
                !request.state.isOpen -> ClosedPanel(request)
                request.proposedByMe -> Unit
                request.myVote != Vote.WAITING -> KillitBody(
                    stringResource(if (request.myVote == Vote.APPROVED) R.string.request_you_approved else R.string.request_you_denied),
                    color = KillitForeground,
                )
                else -> {
                    if (showNote) {
                        KillitTextArea(
                            value = note,
                            onValueChange = { note = it },
                            placeholder = stringResource(R.string.request_note_hint),
                            rule = TextRule.NOTE,
                            minHeight = 80.dp,
                        )
                    } else {
                        KillitTextButton(text = stringResource(R.string.request_add_note), onClick = { showNote = true })
                    }
                    CautiousChoice(
                        cautious = stringResource(R.string.request_deny),
                        onCautious = { onDecide(false, note) },
                        bold = stringResource(R.string.request_approve),
                        onBold = { onDecide(true, note) },
                        enabled = !pairing.busy && textFits(note, TextRule.NOTE),
                    )
                }
            }
        }
    }
}

/** What the guardian is approving: a package, not a verified identity (§5.4). */
@Composable
private fun AppDetails(request: IncomingRequestEntity) {
    val context = LocalContext.current
    val packageName = request.packageName.orEmpty()
    KillitPanel(spacing = 8.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteAppIcon(rgba = request.icon)
            Column {
                Text(text = request.appLabel?.ifEmpty { null } ?: packageName, style = MaterialTheme.typography.titleMedium, color = KillitForeground)
                Text(text = packageName, style = MaterialTheme.typography.bodySmall, color = KillitTextFaint)
            }
        }
        val installer = request.installer.orEmpty()
        KillitBody(
            when (installer) {
                "" -> stringResource(R.string.request_installer_unknown)
                "com.android.vending" -> stringResource(R.string.request_installer_play)
                else -> stringResource(R.string.request_installer_other, installer)
            },
        )
        KillitBody(stringResource(R.string.request_package_note))
        KillitTextButton(
            text = stringResource(R.string.request_view_play),
            color = KillitBlue,
            onClick = {
                val uri = "https://play.google.com/store/apps/details?id=$packageName".toUri()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: ActivityNotFoundException) {
                    // No browser and no store: nothing to open it with.
                }
            },
        )
    }
}

/** How every guardian has answered so far, as the protected phone's last STATUS said (G10). */
@Composable
private fun OthersPanel(request: IncomingRequestEntity) {
    val entries = StatusEntries.decode(request.statusEntries)
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KillitSectionTitle(stringResource(R.string.request_guardians))
        entries.forEach { entry ->
            val (text, color) = when (entry.vote) {
                Vote.WAITING -> stringResource(R.string.vote_waiting) to KillitTextFaint
                Vote.APPROVED -> stringResource(R.string.vote_approved) to KillitGreen
                Vote.DENIED -> stringResource(R.string.vote_denied) to KillitRed
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = entry.name, style = MaterialTheme.typography.bodyMedium, color = KillitForeground, modifier = Modifier.weight(1f))
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}

@Composable
private fun ClosedPanel(request: IncomingRequestEntity) {
    val text = closedText(request) ?: return
    KillitPanel(spacing = 6.dp) { KillitBody(text, color = KillitForeground) }
}

/** How a request ended, or null while it is open. */
@Composable
internal fun closedText(request: IncomingRequestEntity): String? = when (request.state) {
    RequestState.OPEN -> null
    RequestState.APPROVED -> stringResource(R.string.request_closed_approved)
    RequestState.DENIED -> stringResource(R.string.request_closed_denied)
    RequestState.CANCELLED -> when (request.cancelCause) {
        CancelCause.APP_UNINSTALLED -> stringResource(R.string.request_closed_uninstalled)
        CancelCause.RESOLVED_BY_UNLOCK -> stringResource(R.string.request_closed_resolved)
        CancelCause.GUARDIAN_REMOVED -> stringResource(R.string.request_closed_guardian_removed)
        CancelCause.WITHDRAWN, null -> stringResource(R.string.request_closed_withdrawn)
    }
}
