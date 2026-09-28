package org.tamodak.killit.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import org.tamodak.killit.R
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.AppLanguage
import java.util.Locale

/**
 * Resolves every string below it in [language].
 *
 * ### Why not the platform's per-app language
 *
 * `LocaleManager` (API 33) and `AppCompatDelegate.setApplicationLocales` both apply a language by
 * **recreating the activity**, and Killit cannot afford that: `MainActivity.onStop` is what drives
 * `KillitViewModel.lockOnBackground`, so a recreation would throw the user back to the gate and
 * make changing language look like being logged out. Overriding the composition locals instead
 * re-resolves the strings in place, with no activity lifecycle involved and no state lost.
 *
 * The cost is that the choice reaches Compose and nothing else — but Killit has no notifications
 * and its label is a proper noun, so Compose is the only thing with a language to speak.
 *
 * ### What has to be provided together
 *
 * `stringResource` reads `LocalContext.current.resources` and touches `LocalConfiguration` purely
 * so that a configuration change invalidates it. Providing one without the other gives either
 * strings that never change or a recomposition that changes nothing, so both move as a pair —
 * along with [LocalLayoutDirection], which Compose otherwise takes from the host view and would
 * leave laying Arabic out left to right.
 *
 * @param language what to show; [AppLanguage.SYSTEM] passes the device's own configuration through
 *   untouched.
 * @param content the UI to localise.
 */
@Composable
fun ProvideAppLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val baseDirection = LocalLayoutDirection.current
    val locales = language.localeList()

    // A ContextWrapper rather than the context createConfigurationContext returns, so that the
    // activity stays reachable down the baseContext chain: a bare configuration context would hide
    // it from anything below that walks the chain looking for the host Activity.
    val context = remember(base, baseConfiguration, locales) {
        if (locales == null) {
            base
        } else {
            KillitLog.i(KillitLog.UI, "UI language -> ${language.name} (${language.tag})")
            val configuration = Configuration(baseConfiguration).apply { setLocales(locales) }
            LocalisedContext(base, base.createConfigurationContext(configuration).resources)
        }
    }

    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides remember(context) { context.resources.configuration },
        LocalLayoutDirection provides if (locales == null) baseDirection else locales[0].direction(),
        content = content,
    )
}

/**
 * What the picker shows for a language.
 *
 * @return the endonym, or the translated "system default" wording for [AppLanguage.SYSTEM], which
 *   is the one entry naming a behaviour rather than a language.
 */
@Composable
fun AppLanguage.label(): String =
    if (this == AppLanguage.SYSTEM) stringResource(R.string.language_system) else endonym

/**
 * The activity, with one method answered differently.
 *
 * @param base the context to defer to for everything else, so the activity stays reachable.
 * @param localised the resources built for the chosen language.
 */
private class LocalisedContext(base: Context, private val localised: Resources) : ContextWrapper(base) {
    /**
     * @return the localised resources, which is the whole point of this wrapper.
     */
    override fun getResources(): Resources = localised
}

/**
 * Reports which way a locale is written.
 *
 * @return [LayoutDirection.Rtl] for Arabic and the other right-to-left scripts, otherwise
 *   [LayoutDirection.Ltr].
 */
private fun Locale.direction(): LayoutDirection =
    if (TextUtils.getLayoutDirectionFromLocale(this) == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
