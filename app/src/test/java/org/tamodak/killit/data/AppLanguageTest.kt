package org.tamodak.killit.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language list, checked for the things that break quietly.
 *
 * A wrong tag here does not crash: the app simply falls back to English and the user's language
 * silently never appears, which is the kind of bug that ships. Runs on the JVM — only [AppLanguage]
 * `tag` and `endonym` are touched, never `localeList()`, which needs the Android framework.
 */
class AppLanguageTest {

    /**
     * The resource root, found relative to wherever the test runner was started.
     *
     * @return `app/src/main/res`, whether the working directory is the module or the repo root.
     */
    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** Every shipped language must carry a tag; [AppLanguage.SYSTEM] must not. */
    @Test
    fun onlySystemHasNoTag() {
        assertEquals("", AppLanguage.SYSTEM.tag)
        AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.forEach {
            assertTrue("${it.name} has no tag", it.tag.isNotBlank())
            assertTrue("${it.name} has no endonym", it.endonym.isNotBlank())
        }
    }

    /** Two entries resolving to the same locale would make one of them unreachable. */
    @Test
    fun tagsAndEndonymsAreUnique() {
        val shipped = AppLanguage.entries.filter { it != AppLanguage.SYSTEM }
        assertEquals(shipped.size, shipped.map { it.tag }.toSet().size)
        assertEquals(shipped.size, shipped.map { it.endonym }.toSet().size)
    }

    /**
     * The trap this list is most likely to fall into.
     *
     * Android rewrites `id` to the legacy `in` and looks for `values-in`; a desktop JVM from 17 on
     * rewrites `in` to `id` instead, so the runtime mapping cannot be asserted here. What can be
     * asserted is the thing that actually breaks: strings filed under a folder nothing selects.
     */
    @Test
    fun indonesianStringsLiveUnderTheLegacyQualifier() {
        assertEquals("id", AppLanguage.INDONESIAN.tag)
        assertEquals("in", AppLanguage.INDONESIAN.qualifier)
        assertTrue("values-id would never be selected", !resDir().resolve("values-id").exists())
    }

    /** A language in the list with no strings folder ships as silent English. */
    @Test
    fun everyShippedLanguageHasAStringsFolder() {
        AppLanguage.entries
            .filter { it != AppLanguage.SYSTEM && it != AppLanguage.ENGLISH }
            .forEach {
                val strings = resDir().resolve("values-${it.qualifier}").resolve("strings.xml")
                assertTrue("${it.name}: no ${strings.path}", strings.isFile)
            }
        // English is the base folder, with no qualifier of its own.
        assertTrue(resDir().resolve("values").resolve("strings.xml").isFile)
    }

    /** Every other language files its strings under its own tag. */
    @Test
    fun everyOtherQualifierIsItsTag() {
        AppLanguage.entries
            .filter { it != AppLanguage.SYSTEM && it != AppLanguage.INDONESIAN }
            .forEach { assertEquals(it.tag, it.qualifier) }
    }

    /** An unrecognised or absent stored name must degrade to following the device, never crash. */
    @Test
    fun unknownStoredNameFallsBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromName(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromName("KLINGON"))
        assertEquals(AppLanguage.TURKISH, AppLanguage.fromName("TURKISH"))
    }
}
