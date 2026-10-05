package org.tamodak.killit.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.core.KillitLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The clear-data-proof copy really survives a round trip through the device policy service.
 *
 * The system persists application restrictions as XML and only understands some value types. A
 * value of any other type makes the whole write fail inside system_server, while the call itself
 * returns normally, so nothing short of writing and reading back through the real service can catch
 * it. These tests therefore need Killit to be device owner, and are skipped on a device where it is
 * not.
 */
@RunWith(AndroidJUnit4::class)
class DurableStoreTest {

    /** The instrumentation context, which runs as Killit and so as the device owner. */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The real policy controller: the round trip through system_server is what is under test. */
    private val dpc = DevicePolicyController(context)

    /** The store under test. */
    private val durable = DurableStore(dpc)

    /** What the stores held before the test, put back by [tearDown]. */
    private var snapshot: PersistedStateSnapshot? = null

    /** Skips unless Killit is device owner, then saves the device's own state. */
    @Before
    fun setUp() = runBlocking {
        KillitLog.verbose = true
        assumeTrue("Needs Killit as device owner", durable.isAvailable())
        snapshot = PersistedStateSnapshot.take(LockPreferences(context), dpc)
    }

    /** Puts the device's own restrictions back. */
    @After
    fun tearDown() = runBlocking { snapshot?.restore() ?: Unit }

    /**
     * Every field of the credential record comes back as written, the lockout deadline included.
     *
     * The deadline is the field most likely to be lost: it is a wall-clock time, and the
     * restrictions format has no 64-bit integer type.
     */
    @Test
    fun credentialRecordSurvivesARoundTrip(): Unit = runBlocking {
        val record = CredentialRecord(
            lockType = LockType.PIN,
            salt = ByteArray(16) { it.toByte() },
            hash = ByteArray(32) { (it * 7).toByte() },
            failedAttempts = 3,
            lockoutUntilMillis = 1_760_000_000_123L,
        )

        assertTrue("The write must be accepted", durable.write(record))

        assertEquals(record, durable.read())
    }

    /** The release countdown comes back as written, so Clear data cannot reset it. */
    @Test
    fun releaseRequestSurvivesARoundTrip(): Unit = runBlocking {
        val request = ReleaseRequest(
            requestedAtMillis = 1_760_000_000_000L,
            availableAtMillis = 1_761_209_600_000L,
        )

        assertTrue("The write must be accepted", durable.writeReleaseRequest(request))

        assertEquals(request, durable.readReleaseRequest())
    }
}
