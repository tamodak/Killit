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
 * Runs on a device rather than the JVM because [LockPreferences] is backed by DataStore.
 */
@RunWith(AndroidJUnit4::class)
class PasskeyFlowTest {

    /** The instrumentation context, which owns the DataStore file these tests write to. */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Exercised directly by [hashingIsSaltedAndDeterministic], and through the repository above. */
    private val credentials = CredentialStore()

    /** The local store. Cleared around every test, since it outlives the process. */
    private val prefs = LockPreferences(context)

    /**
     * The repository under test.
     *
     * The durable store is real but inert: the test device is not device owner, so
     * `DurableStore.isAvailable` is false throughout and every write goes to [prefs] alone.
     */
    private val repository = LockRepository(
        prefs = prefs,
        durable = DurableStore(DevicePolicyController(context)),
        credentials = credentials,
    )

    /** Clears any record left by a previous run, so a test never starts against stale state. */
    @Before
    fun setUp() = runBlocking { prefs.clearRecord() }

    /** Leaves nothing behind for the next test, or for the app if it is run on the same device. */
    @After
    fun tearDown() = runBlocking { prefs.clearRecord() }

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
