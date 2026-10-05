package org.tamodak.killit.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.tamodak.killit.admin.DevicePolicyController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing worth a test here: a passkey that was set must keep opening the lock, and a wrong
 * one must not.
 *
 * Runs on a device rather than the JVM because [LockPreferences] is backed by DataStore. On a device
 * where Killit is device owner the durable store takes part too; elsewhere it is inert and every
 * write goes to [prefs] alone.
 */
@RunWith(AndroidJUnit4::class)
class PasskeyFlowTest {

    /** The instrumentation context, which owns the DataStore file these tests write to. */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Exercised directly by [hashingIsSaltedAndDeterministic], and through the repository above. */
    private val credentials = CredentialStore()

    /** The local store. Emptied before every test, since it outlives the process. */
    private val prefs = LockPreferences(context)

    /** The real policy controller, so the durable copy is exercised wherever it is available. */
    private val dpc = DevicePolicyController(context)

    /** The durable store. Emptied before every test, like [prefs]. */
    private val durable = DurableStore(dpc)

    /** The repository under test. */
    private val repository = LockRepository(
        prefs = prefs,
        durable = durable,
        credentials = credentials,
    )

    /** What both stores held before the test, put back by [tearDown]. */
    private lateinit var snapshot: PersistedStateSnapshot

    /** Saves the device's own state, then starts every test from empty stores. */
    @Before
    fun setUp() = runBlocking {
        snapshot = PersistedStateSnapshot.take(prefs, dpc)
        prefs.clearRecord()
        if (durable.isAvailable()) durable.clear()
    }

    /** Puts the device's own passkey back, in both stores. */
    @After
    fun tearDown() = runBlocking { snapshot.restore() }

    /** A passkey that was set verifies, a wrong one does not, and a wrong guess costs nothing. */
    @Test
    fun setThenVerify(): Unit = runBlocking {
        repository.setCredential(LockType.PATTERN, PASSKEY)

        assertEquals(VerifyResult.Success, repository.verify(PASSKEY))
        assertTrue("A wrong passkey must be rejected", repository.verify("9-9-9-9") is VerifyResult.Wrong)
        // Still works after a wrong guess, and the wrong guess did not corrupt the record.
        assertEquals(VerifyResult.Success, repository.verify(PASSKEY))
    }

    /**
     * The attempt counter decrements on each failure and resets on success.
     *
     * The reset is what stops a lockout accumulating over weeks of ordinary use, where the odd
     * mistyped passkey is expected.
     */
    @Test
    fun wrongAttemptsCountDownThenReset(): Unit = runBlocking {
        repository.setCredential(LockType.PIN, "112233")

        val first = repository.verify("000000") as VerifyResult.Wrong
        val second = repository.verify("000000") as VerifyResult.Wrong
        assertEquals(first.remainingAttempts - 1, second.remainingAttempts)

        assertEquals(VerifyResult.Success, repository.verify("112233"))
        assertEquals("Attempts reset after a success", 0, prefs.readRecord()!!.failedAttempts)
    }

    /**
     * The hash is reproducible from the same inputs, and changes when either input changes.
     *
     * Determinism is what makes verification possible at all; salt sensitivity is what stops two
     * users with the same passkey producing the same stored hash.
     */
    @Test
    fun hashingIsSaltedAndDeterministic() {
        val salt = credentials.newSalt()

        assertTrue(
            "The same input must produce the same hash",
            credentials.matches(
                credentials.hash(PASSKEY, salt),
                credentials.hash(PASSKEY, salt),
            )
        )
        assertFalse(
            "A different salt must produce a different hash",
            credentials.matches(
                credentials.hash(PASSKEY, salt),
                credentials.hash(PASSKEY, credentials.newSalt()),
            )
        )
        assertFalse(
            "A different passkey must produce a different hash",
            credentials.matches(
                credentials.hash(PASSKEY, salt),
                credentials.hash("1-2-3-4", salt),
            )
        )
        assertEquals("Expected a SHA-256 digest", 32, credentials.hash(PASSKEY, salt).size)
    }

    private companion object {
        /** A pattern in its normalised form: visited dot indices joined with `-`. */
        const val PASSKEY = "0-3-6-7"
    }
}
