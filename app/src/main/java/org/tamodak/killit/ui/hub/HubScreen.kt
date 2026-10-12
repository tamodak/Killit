package org.tamodak.killit.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.data.AppLanguage
import org.tamodak.killit.ui.KillitUiState
import org.tamodak.killit.ui.LanguagePicker
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitRule
import org.tamodak.killit.ui.theme.KillitBackground
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitBorderDim
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitTextFaint
import org.tamodak.killit.ui.theme.KillitTextMuted

/**
 * The first screen (PLAN.md §15.3): this phone, and the phones it guards. Either card asks for the
 * passkey on the way in (J1).
 *
 * Before the passkey only counts are shown, never an app's or a person's name (I5): whoever holds
 * the phone learns that there is something waiting, not what.
 *
 * @param onMyPhone opens My phone, through the gate.
 * @param onGuard opens Phones I guard, through the gate.
 */
@Composable
fun HubScreen(
    state: KillitUiState,
    pairing: PairingUiState,
    onMyPhone: () -> Unit,
    onGuard: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    var pickingLanguage by remember { mutableStateOf(false) }
    if (pickingLanguage) {
        LanguagePicker(
            current = state.language,
            onPick = {
                onLanguageChange(it)
                pickingLanguage = false
            },
            onDismiss = { pickingLanguage = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KillitBackground)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KillitGutter, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = KillitForeground,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .border(2.dp, KillitBorderDim, RectangleShape)
                    .clickable { pickingLanguage = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = KillitIcons.Globe,
                    contentDescription = stringResource(R.string.home_language),
                    tint = KillitForeground,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        KillitRule()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KillitGutter, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            MyPhoneCard(state, pairing, onMyPhone)
            GuardCard(pairing, onGuard)
        }
    }
}

@Composable
private fun MyPhoneCard(state: KillitUiState, pairing: PairingUiState, onClick: () -> Unit) {
    val guardians = state.guardianCount ?: pairing.guardians.size
    val (status, accent) = when {
        // Default-blocking needs both; until then nothing on this phone is protected.
        !state.isDeviceOwner || state.lockType == null -> stringResource(R.string.hub_my_phone_setup) to KillitRed
        guardians == 0 -> stringResource(R.string.hub_my_phone_protected) to KillitGreen
        else -> pluralStringResource(R.plurals.hub_my_phone_guardians, guardians, guardians) to KillitGreen
    }
    val lines = buildList {
        if (state.isDeviceOwner && state.waitingApps.isNotEmpty()) {
            add(pluralStringResource(R.plurals.home_waiting_apps, state.waitingApps.size, state.waitingApps.size))
        }
        val asking = pairing.outgoing.count { it.request.state.isOpen }
        if (asking > 0) add(pluralStringResource(R.plurals.hub_my_requests_open, asking, asking))
    }
    HubCard(
        icon = KillitIcons.Shield,
        title = stringResource(R.string.hub_my_phone),
        status = status,
        lines = lines,
        accent = accent,
        onClick = onClick,
    )
}

@Composable
private fun GuardCard(pairing: PairingUiState, onClick: () -> Unit) {
    val wards = pairing.wards.size
    val waiting = pairing.waitingForMe.size
    val status = if (wards == 0) {
        stringResource(R.string.hub_guard_none)
    } else {
        pluralStringResource(R.plurals.hub_guard_phones, wards, wards)
    }
    val lines = buildList {
        if (waiting > 0) add(pluralStringResource(R.plurals.hub_guard_waiting, waiting, waiting))
        if (pairing.unknownMessages > 0) add(stringResource(R.string.update_needed))
    }
    HubCard(
        icon = KillitIcons.People,
        title = stringResource(R.string.hub_guard),
        status = status,
        lines = lines,
        accent = if (waiting > 0) KillitBlue else KillitBorderDim,
        onClick = onClick,
    )
}

/** One of the Hub's two cards: a large bordered target, the border coloured by its state. */
@Composable
private fun HubCard(
    icon: ImageVector,
    title: String,
    status: String,
    lines: List<String>,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(3.dp, accent, RectangleShape)
            .clickable(onClick = onClick)
            .padding(22.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (accent == KillitBorderDim) KillitForeground else accent,
            modifier = Modifier.size(40.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = KillitForeground)
            Text(text = status, style = MaterialTheme.typography.bodyMedium, color = KillitTextMuted)
            lines.forEach { Text(text = it, style = MaterialTheme.typography.bodySmall, color = KillitTextFaint) }
        }
        Icon(
            imageVector = KillitIcons.ChevronRight,
            contentDescription = null,
            tint = KillitForeground,
            modifier = Modifier.size(22.dp),
        )
    }
}
