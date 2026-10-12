package org.tamodak.killit.ui.myphone

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.ui.KillitUiState
import org.tamodak.killit.ui.apps.AppIcon
import org.tamodak.killit.ui.components.CautiousChoice
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitCheckbox
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitTextFaint

/**
 * Blocking more apps without the guardians (D7): blocking is the stricter direction, so a guarded
 * phone may do it alone, after one confirmation that it can't undo it alone. Cancel is the wider
 * button.
 *
 * @param onBlock blocks the chosen apps; its callback reports those Android refused.
 */
@Composable
fun BlockMoreScreen(
    state: KillitUiState,
    busy: Boolean,
    onBack: () -> Unit,
    onBlock: (Set<String>, (List<String>) -> Unit) -> Unit,
    iconLoader: suspend (String) -> ImageBitmap?,
) {
    BackHandler(onBack = onBack)
    var chosen by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirming by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<List<String>?>(null) }
    val allowed = state.apps
        .filter { it.packageName !in state.blocked && (!it.isSystem || it.isLaunchable) }
        .sortedBy { it.label.lowercase() }

    KillitScreen(
        title = stringResource(R.string.my_phone_block_more),
        onBack = onBack,
        bottomBar = {
            Column(modifier = Modifier.padding(horizontal = KillitGutter, vertical = 14.dp)) {
                KillitPrimaryButton(
                    text = pluralStringResource(R.plurals.block_more_button, chosen.size, chosen.size),
                    onClick = { confirming = true },
                    enabled = chosen.isNotEmpty() && !busy,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KillitBody(stringResource(R.string.block_more_intro))
            allowed.forEach { app ->
                val checked = app.packageName in chosen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { chosen = if (checked) chosen - app.packageName else chosen + app.packageName }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(packageName = app.packageName, iconLoader = iconLoader)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = app.label, style = MaterialTheme.typography.bodyLarge, color = KillitForeground)
                        Text(text = app.packageName, style = MaterialTheme.typography.bodySmall, color = KillitTextFaint)
                    }
                    KillitCheckbox(checked = checked, size = 28.dp)
                }
            }
        }
    }

    if (confirming) {
        KillitDialog(
            title = pluralStringResource(R.plurals.block_more_confirm_title, chosen.size, chosen.size),
            body = stringResource(R.string.block_more_confirm_body),
            accent = KillitRed,
            onDismiss = { confirming = false },
        ) {
            CautiousChoice(
                cautious = stringResource(R.string.cancel),
                onCautious = { confirming = false },
                bold = stringResource(R.string.block_more_confirm),
                onBold = {
                    confirming = false
                    onBlock(chosen) { failures ->
                        refused = failures
                        chosen = emptySet()
                    }
                },
                boldAccent = KillitRed,
            )
        }
    }

    refused?.let { failures ->
        if (failures.isEmpty()) {
            refused = null
            onBack()
        } else {
            KillitDialog(
                title = stringResource(R.string.apps_failed_title),
                body = stringResource(R.string.apps_failed_body) + "\n\n" + failures.joinToString("\n"),
                onDismiss = { refused = null },
                dismissText = stringResource(R.string.ok),
            )
        }
    }
}
