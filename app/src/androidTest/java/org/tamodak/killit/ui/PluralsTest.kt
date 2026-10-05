package org.tamodak.killit.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.tamodak.killit.R
import org.tamodak.killit.data.AppLanguage
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every quantity string formats cleanly in every language Killit ships.
 *
 * Lint checks that each language has the quantities its grammar needs, but not that the text of
 * each form is a valid format string. A broken placeholder in one translation would only show up
 * when that language meets that count, and then as a crash on screen, so this resolves every form
 * the way the UI does.
 */
@RunWith(AndroidJUnit4::class)
class PluralsTest {

    /** Resources are re-resolved per language from this context. */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Formats each plural for counts that reach every quantity category of every shipped language. */
    @Test
    fun everyPluralFormatsInEveryLanguage() {
        AppLanguage.entries.mapNotNull { it.localeList() }.forEach { locales ->
            val configuration = Configuration(context.resources.configuration).apply { setLocales(locales) }
            val resources = context.createConfigurationContext(configuration).resources
            PLURALS.forEach { id ->
                COUNTS.forEach { count ->
                    val text = resources.getQuantityString(id, count, count)
                    assertFalse(
                        "$locales ${resources.getResourceEntryName(id)} [$count] left a placeholder: $text",
                        text.contains('%'),
                    )
                }
            }
        }
    }

    private companion object {
        /** Every plural resource the app defines. */
        val PLURALS = listOf(
            R.plurals.gate_wrong,
            R.plurals.home_blocked_count,
            R.plurals.apps_save_pending,
        )

        /** Reaches zero, one, two, few and many in Arabic, and one, few and many in Russian. */
        val COUNTS = listOf(0, 1, 2, 3, 5, 11, 21, 101)
    }
}
