package org.tamodak.killit.admin

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The check that keeps unstorable values out of the durable copy.
 *
 * Instrumented only because [Bundle] has no working JVM implementation; it needs no device owner.
 */
@RunWith(AndroidJUnit4::class)
class RestrictionTypesTest {

    /** Every type the system can persist passes, at the top level and inside nested bundles. */
    @Test
    fun persistableTypesPass() {
        val nested = Bundle().apply { putInt("count", 2) }
        val bundle = Bundle().apply {
            putBoolean("flag", true)
            putInt("number", 7)
            putString("text", "value")
            putString("absent", null)
            putStringArray("choices", arrayOf("a", "b"))
            putBundle("nested", nested)
            putParcelableArray("list", arrayOf(Bundle(nested), Bundle()))
        }

        assertEquals(emptyList<String>(), DevicePolicyController.unsupportedRestrictionKeys(bundle))
    }

    /** A long is reported wherever it sits, with the path that leads to it. */
    @Test
    fun longsAreReportedWithTheirPath() {
        val bundle = Bundle().apply {
            putLong("top", 1L)
            putBundle("nested", Bundle().apply { putLong("inner", 2L) })
            putParcelableArray("list", arrayOf(Bundle().apply { putLong("deep", 3L) }))
        }

        assertEquals(
            listOf("list[0]/deep", "nested/inner", "top"),
            DevicePolicyController.unsupportedRestrictionKeys(bundle).sorted(),
        )
    }
}
