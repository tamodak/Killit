package org.tamodak.killit.ui.guard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.data.AppLanguage
import org.tamodak.killit.ui.LanguagePicker
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitRow
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.label
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitIcons

/**
 * The guardian side's settings (PLAN.md §15.2): what the passkey means now (§15.11, always
 * readable here), the language, changing the passkey, and moving to a new phone (§3.10). There is
 * no reset (I3).
 *
 * A phone with guardians of its own moves from My phone instead, where the move needs their
 * approval of an unlock: from here, a guardian's passkey would be enough.
 *
 * @param canMove whether this phone has pairings to move.
 * @param guarded whether this phone has guardians.
 */
@Composable
fun GuardSettingsScreen(
    language: AppLanguage,
    onBack: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onChangePasskey: () -> Unit,
    canMove: Boolean,
    guarded: Boolean,
    onMove: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var pickingLanguage by remember { mutableStateOf(false) }
    KillitScreen(title = stringResource(R.string.my_phone_settings), onBack = onBack) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            KillitPanel(borderColor = KillitBlue, spacing = 8.dp) {
                KillitSectionTitle(stringResource(R.string.guardian_notice_title))
                KillitBody(stringResource(R.string.guardian_notice_body), color = KillitForeground)
            }
            KillitRow(
                title = stringResource(R.string.home_language),
                subtitle = language.label(),
                icon = KillitIcons.Globe,
                onClick = { pickingLanguage = true },
            )
            KillitRow(title = stringResource(R.string.home_change_passkey), icon = KillitIcons.Key, onClick = onChangePasskey)
            when {
                !canMove -> Unit
                guarded -> KillitBody(stringResource(R.string.guard_settings_move_guarded))
                else -> KillitRow(
                    title = stringResource(R.string.settings_move),
                    subtitle = stringResource(R.string.settings_move_desc),
                    icon = KillitIcons.People,
                    onClick = onMove,
                )
            }
        }
    }
    if (pickingLanguage) {
        LanguagePicker(
            current = language,
            onPick = {
                onLanguageChange(it)
                pickingLanguage = false
            },
            onDismiss = { pickingLanguage = false },
        )
    }
}
