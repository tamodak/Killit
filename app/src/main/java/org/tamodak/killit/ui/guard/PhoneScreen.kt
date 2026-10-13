package org.tamodak.killit.ui.guard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.pairing.PairingTexts
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.protocol.toHex
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.components.CautiousChoice
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitDangerButton
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitRow
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.components.formatWhen
import org.tamodak.killit.ui.myphone.PeerHeader
import org.tamodak.killit.ui.myphone.RenameDialog
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitTextFaint

/**
 * One guarded phone (PLAN.md §15.6): its name on this phone, its apps when it lets its guardians
 * manage them (§3.8), its requests, its history (I10), and Stop guarding — immediate and needing
 * nobody's approval, so it is confirmed with Cancel the wider button.
 */
@Composable
fun PhoneScreen(
    pairIdHex: String,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onOpenRequest: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onLeave: (String) -> Unit,
    onManageApps: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val ward = pairing.pairing(pairIdHex)
    var renaming by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    KillitScreen(title = ward?.nickname ?: stringResource(R.string.hub_guard), onBack = onBack) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (ward == null) {
                KillitBody(stringResource(R.string.guardians_gone))
                return@Column
            }
            PeerHeader(ward)
            KillitTextButton(text = stringResource(R.string.rename), onClick = { renaming = true })

            if (ward.appManagement) {
                KillitRow(
                    title = stringResource(R.string.phone_manage_apps),
                    subtitle = stringResource(R.string.phone_manage_apps_desc),
                    icon = KillitIcons.Package,
                    onClick = onManageApps,
                )
            } else {
                KillitBody(stringResource(R.string.phone_manage_off, ward.nickname))
            }

            val requests = pairing.incoming.filter { it.pairId.contentEquals(ward.pairId) }
            if (requests.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KillitSectionTitle(stringResource(R.string.guard_requests))
                    requests.forEach { request ->
                        RequestCard(request, ward) { onOpenRequest(pairIdHex, request.requestId.toHex()) }
                    }
                }
            }

            val history = pairing.history.filter { it.pairId?.contentEquals(ward.pairId) == true }
            if (history.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KillitSectionTitle(stringResource(R.string.history))
                    history.forEach { event ->
                        Column {
                            Text(text = eventText(event), style = MaterialTheme.typography.bodyMedium, color = KillitForeground)
                            Text(text = formatWhen(event.at), style = MaterialTheme.typography.bodySmall, color = KillitTextFaint)
                        }
                    }
                }
            }

            KillitDangerButton(text = stringResource(R.string.guard_leave), onClick = { leaving = true })
        }
    }

    if (renaming && ward != null) {
        RenameDialog(
            current = ward.nickname,
            onDismiss = { renaming = false },
            onSave = {
                onRename(pairIdHex, it)
                renaming = false
            },
        )
    }

    if (leaving && ward != null) {
        KillitDialog(
            title = stringResource(R.string.guard_leave_title),
            body = stringResource(R.string.guard_leave_body, ward.nickname),
            accent = KillitRed,
            onDismiss = { leaving = false },
        ) {
            CautiousChoice(
                cautious = stringResource(R.string.cancel),
                onCautious = { leaving = false },
                bold = stringResource(R.string.guard_leave),
                onBold = {
                    leaving = false
                    onLeave(pairIdHex)
                },
                boldAccent = KillitRed,
            )
        }
    }
}

/** One history line, written on this phone in its own language (E-F3). */
@Composable
internal fun eventText(event: EventEntity): String = PairingTexts.event(LocalContext.current, event)
