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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.EventType
import org.tamodak.killit.protocol.ProtectionOption
import org.tamodak.killit.protocol.RemovedHow
import org.tamodak.killit.protocol.toHex
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.components.CautiousChoice
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitDangerButton
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.components.formatDate
import org.tamodak.killit.ui.components.formatWhen
import org.tamodak.killit.ui.myphone.PeerHeader
import org.tamodak.killit.ui.myphone.RenameDialog
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitTextFaint

/**
 * One guarded phone (PLAN.md §15.6): its name on this phone, its requests, its history (I10), and
 * Stop guarding — immediate and needing nobody's approval, so it is confirmed with Cancel the
 * wider button.
 */
@Composable
fun PhoneScreen(
    pairIdHex: String,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onOpenRequest: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onLeave: (String) -> Unit,
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

            val requests = pairing.incoming.filter { it.pairId.contentEquals(ward.pairId) }
            if (requests.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KillitSectionTitle(stringResource(R.string.guard_requests))
                    requests.forEach { request ->
                        RequestCard(request, ward, pairing) { onOpenRequest(pairIdHex, request.requestId.toHex()) }
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
internal fun eventText(event: EventEntity): String {
    val app = event.appLabel?.ifEmpty { null } ?: event.packageName.orEmpty()
    return when (event.type) {
        EventType.PAIRED -> stringResource(R.string.event_paired)
        EventType.GUARDIAN_LEFT -> stringResource(R.string.event_left)
        EventType.GUARDIAN_REMOVED -> if (event.otherName != null) {
            stringResource(
                if (event.value == RemovedHow.LEFT.code) R.string.event_other_left else R.string.event_other_removed,
                event.otherName,
            )
        } else {
            stringResource(R.string.event_guardian_removed)
        }
        EventType.REMOVED_AS_GUARDIAN -> stringResource(R.string.event_removed_you)
        EventType.APP_INSTALLED -> stringResource(if (event.flag == true) R.string.event_app_installed_blocked else R.string.event_app_installed, app)
        EventType.GUARDIAN_ADDED -> stringResource(R.string.event_guardian_added, event.otherName.orEmpty())
        EventType.RELEASE_STARTED -> stringResource(R.string.event_release_started, formatDate(event.time ?: 0L))
        EventType.RELEASE_CANCELLED -> stringResource(R.string.event_release_cancelled)
        EventType.PROTECTION_CHANGED -> stringResource(
            if (event.flag == true) R.string.event_protection_on else R.string.event_protection_off,
            protectionName(event.value),
        )
        EventType.MANAGEMENT_CHANGED -> stringResource(if (event.flag == true) R.string.event_management_on else R.string.event_management_off)
        EventType.REQUEST_APPROVED -> stringResource(R.string.event_request_approved, app)
        EventType.REQUEST_DENIED -> stringResource(R.string.event_request_denied, app)
        EventType.REQUEST_CANCELLED -> stringResource(R.string.event_request_cancelled, app)
        EventType.UNKNOWN_NOTICE -> stringResource(R.string.update_needed)
    }
}

@Composable
private fun protectionName(code: Int?): String = stringResource(
    when (ProtectionOption.entries.firstOrNull { it.code == code }) {
        ProtectionOption.UNINSTALL -> R.string.harden_uninstall
        ProtectionOption.FORCE_STOP -> R.string.harden_force_stop
        ProtectionOption.SAFE_BOOT -> R.string.harden_safe_boot
        ProtectionOption.FACTORY_RESET -> R.string.harden_factory_reset
        ProtectionOption.APPS_CONTROL -> R.string.harden_apps_control
        ProtectionOption.DATE_TIME, null -> R.string.harden_date_time
    },
)
