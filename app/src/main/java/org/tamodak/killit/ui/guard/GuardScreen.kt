package org.tamodak.killit.ui.guard

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.tamodak.killit.R
import org.tamodak.killit.pairing.PairingTexts
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.pairing.store.Vote
import org.tamodak.killit.protocol.toHex
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitRow
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.PeerBadge
import org.tamodak.killit.ui.components.RemoteAppIcon
import org.tamodak.killit.ui.components.formatLastSeen
import org.tamodak.killit.ui.components.formatWhen
import org.tamodak.killit.ui.myphone.NotificationPermissionPanel
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitBorderDim
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitTextFaint
import org.tamodak.killit.ui.theme.KillitTextMuted

/**
 * Phones I guard (PLAN.md §15.6): the requests waiting for this phone's answer, live unlocks first,
 * then the phones themselves, and the way to guard another one.
 *
 * A guardian phone has to hear about requests to be any use, so notifications and the exemption
 * from battery optimisation are asked for here (H9). The first time the phone guards someone, the
 * passkey's new weight is explained and must be acknowledged (§15.11).
 */
@Composable
fun GuardScreen(
    pairing: PairingUiState,
    onBack: () -> Unit,
    onOpenRequest: (String, String) -> Unit,
    onOpenPhone: (String) -> Unit,
    onGuardNew: () -> Unit,
    onSettings: () -> Unit,
    onAcknowledgeNotice: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val waiting = pairing.waitingForMe.sortedWith(compareByDescending<IncomingRequestEntity> { it.type == RequestType.UNLOCK }.thenBy { it.receivedAt })

    KillitScreen(title = stringResource(R.string.hub_guard), onBack = onBack) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (pairing.wards.isNotEmpty()) {
                NotificationPermissionPanel()
                BatteryPanel()
            }
            if (pairing.unknownMessages > 0) {
                KillitPanel(borderColor = KillitBlue) { KillitBody(stringResource(R.string.update_needed_long), color = KillitForeground) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KillitSectionTitle(stringResource(R.string.guard_requests))
                if (waiting.isEmpty()) KillitBody(stringResource(R.string.guard_requests_none))
                waiting.forEach { request ->
                    val from = pairing.pairings.firstOrNull { it.pairId.contentEquals(request.pairId) } ?: return@forEach
                    RequestCard(request, from) { onOpenRequest(request.pairId.toHex(), request.requestId.toHex()) }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KillitSectionTitle(stringResource(R.string.guard_phones))
                if (pairing.wards.isEmpty()) KillitBody(stringResource(R.string.hub_guard_none))
                pairing.wards.forEach { ward ->
                    val pending = pairing.waitingForMe.count { it.pairId.contentEquals(ward.pairId) }
                    WardRow(ward, pending) { onOpenPhone(ward.pairId.toHex()) }
                }
                KillitRow(title = stringResource(R.string.guard_new), icon = KillitIcons.Plus, onClick = onGuardNew)
                KillitRow(title = stringResource(R.string.my_phone_settings), icon = KillitIcons.Settings, onClick = onSettings)
            }
        }
    }

    if (pairing.wards.isNotEmpty() && !pairing.guardianNoticeSeen) {
        GuardianNoticeDialog(onAcknowledge = onAcknowledgeNotice)
    }
}

/**
 * One request in a list (PLAN.md §15.6): who, what, a line of the explanation, when, and where it
 * stands. An app is named by its package too, since its label is its own choice (§5.4). A live
 * unlock is marked only while it is open, since its requester waits on screen.
 */
@Composable
internal fun RequestCard(request: IncomingRequestEntity, from: PairingEntity, onClick: () -> Unit) {
    val live = request.type == RequestType.UNLOCK && request.state.isOpen
    val standing = closedText(request) ?: when (request.myVote) {
        Vote.APPROVED -> stringResource(R.string.request_you_approved)
        Vote.DENIED -> stringResource(R.string.request_you_denied)
        Vote.WAITING -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(3.dp, if (live) KillitBlue else KillitBorderDim, RectangleShape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerBadge(fp = from.peerFp, size = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = requestSummary(request, from), style = MaterialTheme.typography.titleSmall, color = KillitForeground)
            if (request.type == RequestType.UNBLOCK_APP) {
                Text(text = request.packageName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = KillitTextFaint)
            }
            if (request.explanation.isNotEmpty()) {
                Text(
                    text = "“${request.explanation}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = KillitTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (live) stringResource(R.string.request_waiting_now) else formatWhen(request.receivedAt),
                style = MaterialTheme.typography.bodySmall,
                color = if (live) KillitBlue else KillitTextFaint,
            )
            if (standing != null) Text(text = standing, style = MaterialTheme.typography.bodySmall, color = KillitTextMuted)
        }
        if (request.type == RequestType.UNBLOCK_APP) RemoteAppIcon(rgba = request.icon, size = 36.dp)
    }
}

/** "Ali wants to unblock TikTok", "…wants to unlock Killit", "…wants to remove Dad / you". */
@Composable
internal fun requestSummary(request: IncomingRequestEntity, from: PairingEntity): String =
    PairingTexts.request(LocalContext.current, request, from)

@Composable
private fun WardRow(ward: PairingEntity, pending: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(3.dp, if (pending > 0) KillitBlue else KillitBorderDim, RectangleShape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerBadge(fp = ward.peerFp)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = ward.nickname, style = MaterialTheme.typography.titleMedium, color = KillitForeground)
            val details = listOfNotNull(ward.peerModel.takeIf { it.isNotEmpty() }, formatLastSeen(ward.lastSeenDaysAgo))
            if (details.isNotEmpty()) Text(text = details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = KillitTextFaint)
            if (pending > 0) Text(text = pluralStringResource(R.plurals.hub_guard_waiting, pending, pending), style = MaterialTheme.typography.bodySmall, color = KillitBlue)
        }
    }
}

/**
 * Asks to be left out of battery optimisation (H9): a guardian that sleeps through a request is
 * no guardian. Read again on every return, since the answer is given in Settings.
 */
@SuppressLint("BatteryLife") // Killit isn't on Google Play; a guardian must be reachable (PLAN.md H9).
@Composable
private fun BatteryPanel() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(context.isBatteryExempt()) }
    LifecycleResumeEffect(Unit) {
        exempt = context.isBatteryExempt()
        onPauseOrDispose { }
    }
    if (exempt) return
    KillitPanel(borderColor = KillitBlue, spacing = 12.dp) {
        KillitSectionTitle(stringResource(R.string.guard_battery_title))
        KillitBody(stringResource(R.string.guard_battery_body))
        KillitButton(
            text = stringResource(R.string.home_notifications_allow),
            onClick = {
                val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                try {
                    context.startActivity(ask)
                } catch (e: ActivityNotFoundException) {
                    // Some builds drop the prompt; the list it would have changed is still there.
                    try {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        // Nothing to open: the panel stays, and the phone works as well as it can.
                    }
                }
            },
        )
    }
}

private fun Context.isBatteryExempt(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

/**
 * §15.11: the first time this phone guards someone, its passkey becomes the key to every answer
 * it gives. There is no reset (I3), so this is said plainly and acknowledged.
 */
@Composable
internal fun GuardianNoticeDialog(onAcknowledge: () -> Unit) {
    KillitDialog(
        title = stringResource(R.string.guardian_notice_title),
        body = stringResource(R.string.guardian_notice_body),
        onDismiss = {},
        confirmText = stringResource(R.string.guardian_notice_ok),
        onConfirm = onAcknowledge,
        accent = KillitBlue,
    )
}
