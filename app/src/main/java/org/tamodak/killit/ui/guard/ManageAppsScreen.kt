package org.tamodak.killit.ui.guard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.tamodak.killit.R
import org.tamodak.killit.pairing.store.WardAppEntity
import org.tamodak.killit.pairing.store.WardAppState
import org.tamodak.killit.protocol.AppRef
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitCheckbox
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitField
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitRule
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.components.RemoteAppIcon
import org.tamodak.killit.ui.components.formatWhen
import org.tamodak.killit.ui.theme.KillitBackground
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitDivider
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitRowDivider
import org.tamodak.killit.ui.theme.KillitTextFaint

/**
 * Managing a guarded phone's apps (PLAN.md §15.6, SPEC.md §11.10–§11.11): the phone's own list,
 * asked for when the screen opens, with a checkbox per app. Ticked means blocked.
 *
 * Changes wait here until "Send": blocking takes effect on the phone as soon as it reads the
 * command, unblocking needs every guardian, which the screen says up front. An app the phone is
 * already asking about is marked, and changing it answers that request. Sent changes show as
 * pending until the phone reports them done.
 *
 * The list is the phone's own, so its labels and icons are the apps' own choices (§5.4): the
 * package name is always shown under the label.
 *
 * @param onRequest asks the phone for a fresh list.
 * @param onSend sends the changes: each app with true to block it, false to unblock it.
 * @param follow keeps up with the phone's answer while the screen is shown.
 */
@Composable
fun ManageAppsScreen(
    pairIdHex: String,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onRequest: () -> Unit,
    onSend: (List<Pair<AppRef, Boolean>>) -> Unit,
    follow: suspend () -> Unit,
) {
    val ward = pairing.pairing(pairIdHex)
    val list = pairing.wardLists.firstOrNull { it.pairId.contentEquals(ward?.pairId ?: ByteArray(0)) }
    val apps = pairing.wardApps.filter { it.pairId.contentEquals(ward?.pairId ?: ByteArray(0)) }
    // What the user changed, by package: true to block, false to unblock. Cleared when sent.
    var changes by remember(pairIdHex) { mutableStateOf(mapOf<String, Boolean>()) }
    var query by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(pairIdHex) {
        // A fresh list each visit, unless one was asked for moments ago: each answer is dozens of
        // envelopes, and the phone answers only a few an hour (SPEC.md §11.10).
        val askedAt = list?.requestedAt ?: 0L
        if (System.currentTimeMillis() - askedAt > RECENT_MILLIS) onRequest()
    }
    LaunchedEffect(pairIdHex) { follow() }

    val leave = { if (changes.isNotEmpty()) confirmDiscard = true else onBack() }
    BackHandler(onBack = leave)

    // What the phone is asking about right now, so changing it answers that request.
    val asked = pairing.incoming
        .filter { it.pairId.contentEquals(ward?.pairId ?: ByteArray(0)) && it.state.isOpen && it.isAboutApp() }
        .mapNotNull { it.packageName }
        .toSet()

    KillitScreen(
        title = stringResource(R.string.phone_manage_apps),
        onBack = leave,
        scrollable = false,
        bottomBar = {
            if (changes.isNotEmpty()) {
                SendBar(count = changes.size, enabled = !pairing.busy) {
                    val toSend = apps.mapNotNull { app -> changes[app.packageName]?.let { AppRef(app.packageName, app.certDigest) to it } }
                    onSend(toSend)
                    changes = emptyMap()
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = KillitGutter, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ward == null || !ward.appManagement) {
                KillitBody(ward?.let { stringResource(R.string.phone_manage_off, it.nickname) } ?: stringResource(R.string.guardians_gone))
                return@Column
            }
            KillitBody(stringResource(R.string.manage_intro), color = KillitForeground)
            ListStatus(listRequestedAt = list?.requestedAt, completedAt = list?.completedAt, failed = list?.failed == true, onRequest = onRequest)
            if (apps.isNotEmpty()) {
                KillitField(value = query, onValueChange = { query = it }, placeholder = stringResource(R.string.apps_search), leadingIcon = KillitIcons.Search)
            }
        }
        KillitRule()

        if (ward == null || !ward.appManagement) return@KillitScreen
        val visible = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        if (visible.isEmpty()) {
            KillitBody(
                stringResource(if (apps.isEmpty()) R.string.manage_none else R.string.apps_empty),
                modifier = Modifier.padding(KillitGutter),
            )
            return@KillitScreen
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = KillitGutter, vertical = 8.dp),
        ) {
            listOf(
                R.string.apps_section_new to visible.filter { it.state == WardAppState.NEW },
                R.string.apps_section_blocked to visible.filter { it.state == WardAppState.BLOCKED },
                R.string.apps_section_allowed to visible.filter { it.state == WardAppState.ALLOWED },
            ).forEach { (title, section) ->
                if (section.isEmpty()) return@forEach
                item(key = "section:$title") {
                    KillitSectionTitle(text = stringResource(title), modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                }
                items(section, key = { it.packageName }) { app ->
                    val blockedNow = app.state != WardAppState.ALLOWED
                    val wanted = changes[app.packageName]
                    WardAppRow(
                        app = app,
                        checked = wanted ?: blockedNow,
                        asked = app.packageName in asked,
                        enabled = !pairing.busy,
                        onToggle = {
                            val block = !(wanted ?: blockedNow)
                            // Back to what is applied is no change at all.
                            changes = if (block == blockedNow) changes - app.packageName else changes + (app.packageName to block)
                        },
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        KillitDialog(
            title = stringResource(R.string.apps_discard_title),
            body = stringResource(R.string.apps_discard_body),
            accent = KillitRed,
            onDismiss = { confirmDiscard = false },
            confirmText = stringResource(R.string.discard),
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            dismissText = stringResource(R.string.cancel),
        )
    }
}

/** Where the phone's list stands: on its way, overdue, broken, or from when, with "Ask again". */
@Composable
private fun ListStatus(listRequestedAt: Long?, completedAt: Long?, failed: Boolean, onRequest: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(listRequestedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(TICK_MILLIS)
        }
    }
    val waiting = listRequestedAt != null && completedAt == null && !failed
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (waiting) CircularProgressIndicator(color = KillitBlue, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        val text = when {
            failed -> stringResource(R.string.manage_failed)
            waiting && now - (listRequestedAt ?: now) > OVERDUE_MILLIS -> stringResource(R.string.manage_no_answer)
            waiting -> stringResource(R.string.manage_asking)
            completedAt != null -> formatWhen(completedAt, now)
            else -> null
        }
        if (text != null) KillitBody(text, color = if (failed) KillitRed else KillitTextFaint, modifier = Modifier.weight(1f))
        if (!waiting || now - (listRequestedAt ?: now) > OVERDUE_MILLIS) {
            KillitTextButton(text = stringResource(R.string.manage_ask_again), onClick = onRequest, color = KillitBlue)
        }
    }
}

/** One of the phone's apps: its icon and label as the phone sent them, its package, its checkbox. */
@Composable
private fun WardAppRow(app: WardAppEntity, checked: Boolean, asked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RemoteAppIcon(rgba = app.icon, size = 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label.ifEmpty { app.packageName },
                    style = MaterialTheme.typography.bodyLarge,
                    color = KillitForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = KillitTextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val note = when {
                    app.pendingBlock == true -> stringResource(R.string.manage_blocking)
                    app.pendingBlock == false -> stringResource(R.string.manage_unblocking)
                    asked -> stringResource(R.string.manage_asked)
                    else -> null
                }
                if (note != null) Text(text = note, style = MaterialTheme.typography.bodySmall, color = KillitBlue)
            }
            KillitCheckbox(checked = checked, enabled = enabled)
        }
        KillitRule(color = KillitRowDivider)
    }
}

/** The pinned bar that sends the changes, with how many there are. */
@Composable
private fun SendBar(count: Int, enabled: Boolean, onSend: () -> Unit) {
    Column {
        KillitRule(color = KillitDivider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KillitBackground)
                .padding(horizontal = KillitGutter, vertical = 16.dp),
        ) {
            KillitPrimaryButton(
                text = pluralStringResource(R.plurals.manage_send, count, count),
                onClick = onSend,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A list asked for this recently isn't asked for again on opening the screen. */
private const val RECENT_MILLIS = 2L * 60 * 1000

/** How long the phone may take before the screen says it hasn't answered. */
private const val OVERDUE_MILLIS = 30_000L

/** How often the status line looks at the clock. */
private const val TICK_MILLIS = 5_000L
