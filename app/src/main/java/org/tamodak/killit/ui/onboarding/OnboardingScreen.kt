package org.tamodak.killit.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.data.AppLanguage
import org.tamodak.killit.ui.LanguageList
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitRule
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.theme.KillitBackground
import org.tamodak.killit.ui.theme.KillitBorderDim
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitIcons

/** One card after the language: what it is about, in a title and a few lines. */
private class Card(val icon: ImageVector, @param:StringRes val title: Int, @param:StringRes val body: Int)

private val Cards = listOf(
    Card(KillitIcons.Lock, R.string.onboarding_what_title, R.string.onboarding_what_body),
    Card(KillitIcons.Shield, R.string.onboarding_protect_title, R.string.onboarding_protect_body),
    Card(KillitIcons.People, R.string.onboarding_guard_title, R.string.onboarding_guard_body),
)

/**
 * The first launch (PLAN.md §15.9): the language, pre-selected from the system, then three short
 * cards — what Killit does, protecting this phone, guarding someone — which can be skipped.
 *
 * Nothing here asks for a role: both are always on the Hub (H1), and the passkey is made the first
 * time either of its cards needs it.
 *
 * @param language the language in force.
 * @param onLanguage applies a picked language at once, so the cards that follow are in it.
 * @param onDone leaves for the Hub, finished or skipped.
 */
@Composable
fun OnboardingScreen(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, onDone: () -> Unit) {
    // 0 is the language; 1 to 3 are the cards.
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step-- }
    val last = Cards.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KillitBackground)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KillitGutter, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (step == 0) {
                Text(text = stringResource(R.string.onboarding_language), style = MaterialTheme.typography.headlineSmall, color = KillitForeground)
                LanguageList(current = language, onPick = onLanguage)
            } else {
                val card = Cards[step - 1]
                Box(
                    modifier = Modifier.size(72.dp).border(3.dp, KillitGreen, RectangleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = card.icon, contentDescription = null, tint = KillitGreen, modifier = Modifier.size(40.dp))
                }
                Text(text = stringResource(card.title), style = MaterialTheme.typography.headlineSmall, color = KillitForeground)
                KillitBody(stringResource(card.body), color = KillitForeground)
            }
        }
        KillitRule()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KillitGutter, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepMarks(current = step, count = last + 1)
            Spacer(modifier = Modifier.weight(1f))
            if (step in 1 until last) KillitTextButton(text = stringResource(R.string.onboarding_skip), onClick = onDone)
            KillitPrimaryButton(
                text = stringResource(if (step == last) R.string.onboarding_start else R.string.onboarding_next),
                onClick = { if (step == last) onDone() else step++ },
            )
        }
    }
}

/** Where the user is among the steps: one square each, the current one filled. */
@Composable
private fun StepMarks(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .border(2.dp, if (index == current) KillitGreen else KillitBorderDim, RectangleShape)
                    .background(if (index == current) KillitGreen else KillitBackground),
            )
        }
    }
}
