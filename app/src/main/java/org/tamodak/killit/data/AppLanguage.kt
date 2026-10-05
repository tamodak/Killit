package org.tamodak.killit.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * The languages Killit ships, and the one that defers to the device.
 *
 * ### Why the names are not in strings.xml
 *
 * [endonym] is each language written in itself, and it stays that way whatever the app is currently
 * showing. Someone who opens Killit and finds it in a language they cannot read has to be able to
 * pick their own out of the list; translating "German" into Arabic would defeat the only job the
 * list has. [SYSTEM] is the exception — it names a behaviour rather than a language, so its label
 * comes from `strings.xml` like any other piece of UI text.
 *
 * ### Ordering
 *
 * Declaration order is the order the picker shows. [SYSTEM] and [ENGLISH] lead — one is the default
 * and the other is the language the strings are authored in — and the rest follow by endonym, Latin
 * scripts first, because a list sorted by a name the reader cannot decipher is not sorted at all.
 *
 * The enum *name* is what gets persisted, so these constants must not be renamed without a
 * migration; an unrecognised stored value falls back to [DEFAULT].
 *
 * ### Indonesian, and why `tag` and `qualifier` differ
 *
 * Android rewrites the modern code `id` to the legacy `in` — `Locale.forLanguageTag("id").language`
 * returns `"in"` there — and `values-in` is the folder the strings must live in. A desktop JVM from
 * 17 onwards normalises the *other* way, turning `in` into `id`, so this mapping cannot be asserted
 * from a unit test and is spelled out here instead. Do not "fix" the tag to `in`: `forLanguageTag`
 * would then hand Android a locale it rewrites back, and the folder would stop being found.
 *
 * @param tag the BCP-47 tag, empty for [SYSTEM].
 * @param endonym the language's name in itself. Empty for [SYSTEM], which has no language to name.
 * @param qualifier the `res/values-*` suffix holding this language's strings. Equal to [tag] for
 *   every language but Indonesian; `AppLanguageTest` checks the folder actually exists, so the two
 *   cannot drift apart unnoticed.
 */
enum class AppLanguage(val tag: String, val endonym: String, val qualifier: String = tag) {
    /** Whatever the device is set to, falling back to English for anything Killit has no strings for. */
    SYSTEM("", ""),

    /** The language every string is authored in; every other entry is a translation of it. */
    ENGLISH("en", "English"),

    INDONESIAN("id", "Bahasa Indonesia", qualifier = "in"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    PORTUGUESE("pt", "Português"),
    TURKISH("tr", "Türkçe"),
    RUSSIAN("ru", "Русский"),
    ARABIC("ar", "العربية");

    /**
     * The locales to resolve strings against.
     *
     * @return the single chosen locale, or null for [SYSTEM] — where the answer is "leave the
     *   configuration alone", which is not the same as any particular locale.
     */
    fun localeList(): LocaleList? =
        if (tag.isEmpty()) null else LocaleList(Locale.forLanguageTag(tag))

    /**
     * A context whose resources speak this language, for text built outside Compose.
     *
     * Compose gets the language from `ProvideAppLanguage`; notifications are built by a service with
     * no composition, so they resolve their strings through this instead.
     *
     * @param context the context to base it on.
     * @return [context] itself for [SYSTEM]; otherwise a configuration context in this language.
     */
    fun applyTo(context: Context): Context {
        val locales = localeList() ?: return context
        val configuration = Configuration(context.resources.configuration).apply { setLocales(locales) }
        return context.createConfigurationContext(configuration)
    }

    companion object {
        /** Chosen when nothing is stored: follow the device, which is what a user expects to happen. */
        val DEFAULT = SYSTEM

        /**
         * Parses a persisted name.
         *
         * @param name the stored enum name, or null when nothing has been written yet.
         * @return the matching constant, or [DEFAULT] for anything unrecognised or absent.
         */
        fun fromName(name: String?): AppLanguage =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
