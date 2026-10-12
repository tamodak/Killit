package org.tamodak.killit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.data.AppLanguage
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitRow
import org.tamodak.killit.ui.theme.KillitBorderDim
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitIcons

/**
 * The language list, opened from the Hub's globe and from either section's settings.
 *
 * Each entry is written in its own language (see [AppLanguage]), so the list stays usable when the
 * app is currently showing one the reader cannot decipher — which is the whole situation the picker
 * exists for. It is capped and scrollable because ten entries plus a dismiss button will not fit on
 * a short screen, and a dialog that runs off the bottom hides the way out of itself.
 *
 * @param current the language in force, marked in the list.
 * @param onPick called with the chosen language.
 * @param onDismiss called when the dialog is dismissed without choosing.
 */
@Composable
fun LanguagePicker(
    current: AppLanguage,
    onPick: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    KillitDialog(
        title = stringResource(R.string.home_language),
        onDismiss = onDismiss,
        dismissText = stringResource(R.string.cancel),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppLanguage.entries.forEach { language ->
                val selected = language == current
                KillitRow(
                    title = language.label(),
                    // The tick is always in the layout and only sometimes visible: dropping the
                    // icon on unselected rows would collapse its slot and indent the one selected
                    // label 48dp past every other.
                    icon = KillitIcons.Check,
                    iconTint = if (selected) KillitGreen else Color.Transparent,
                    borderColor = if (selected) KillitGreen else KillitBorderDim,
                    background = if (selected) KillitGreen.copy(alpha = 0.10f) else Color.Transparent,
                    showChevron = false,
                    onClick = { onPick(language) },
                )
            }
        }
    }
}
