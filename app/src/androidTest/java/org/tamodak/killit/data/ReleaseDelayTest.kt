package org.tamodak.killit.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.core.KillitLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The waiting period before Killit can be removed.
 *
 * The delay is the entire feature — a user in a weak moment must not be able to undo everything
 * with one tap — so the cases that matter are the ones where it could be skipped: pressing the
 * button twice, restarting the app, or winding the clock.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseDelayTest {

    /** The instrumentation context, which owns the DataStore file these tests write to. */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The local store. Kept so [requestSurvivesANewRepositoryInstance] can reopen it separately. */
    private val prefs = LockPreferences(context)

    /**
     * The repository under test.
     *
     * The durable store is real but inert: the test device is not device owner, so every write
     * goes to [prefs] alone.
     */
    private val repository = LockRepository(
        prefs = prefs,
        durable = DurableStore(DevicePolicyController(context)),
        credentials = CredentialStore(),
    )

    /** Turns tracing on and clears any request left by a previous run. */
    @Before
    fun setUp() = runBlocking {
        KillitLog.verbose = true
        repository.cancelRelease()
    }

    /** Leaves no countdown behind — it would outlive the process and confuse the next run. */
    @After
    fun tearDown() = runBlocking { repository.cancelRelease() }

    /** The release is refused before a request, refused during the wait, and allowed after it. */
    @Test
    fun releaseIsRefusedUntilTheWaitElapses(): Unit = runBlocking {
        assertFalse("Nothing requested yet, so release must be refused", repository.isReleaseAllowed())

        repository.requestRelease(SHORT_DELAY)
        assertFalse("Release must be refused while the wait is running", repository.isReleaseAllowed())

        delay(SHORT_DELAY + SETTLE)
        assertTrue("Release must be allowed once the wait has elapsed", repository.isReleaseAllowed())
    }

    /**
     * A second request returns the first one unchanged.
     *
     * Pressing the button again must not push the deadline out — otherwise a second person could
     * keep a user waiting indefinitely, and an accidental double tap would double the wait.
     */
    @Test
    fun repeatedRequestsDoNotExtendTheDeadline(): Unit = runBlocking {
        val first = repository.requestRelease(LONG_DELAY)
        val second = repository.requestRelease(LONG_DELAY)

        assertEquals("The deadline must not move", first.availableAtMillis, second.availableAtMillis)
        assertEquals(first, repository.releaseRequest())
    }

    /** Cancelling removes the request outright, so the wait restarts from zero rather than resuming. */
    @Test
    fun cancellingClearsTheRequest(): Unit = runBlocking {
        repository.requestRelease(LONG_DELAY)
        assertNotNull(repository.releaseRequest())

        repository.cancelRelease()
        assertNull("Cancelling must remove the request entirely", repository.releaseRequest())
        assertFalse(repository.isReleaseAllowed())
    }

    /**
     * A repository built afresh sees the same deadline.
     *
     * The request has to outlive the process, or force-stopping the app would reset the wait.
     */
    @Test
    fun requestSurvivesANewRepositoryInstance(): Unit = runBlocking {
        val original = repository.requestRelease(LONG_DELAY)

        val reopened = LockRepository(
            prefs = LockPreferences(context),
            durable = DurableStore(DevicePolicyController(context)),
            credentials = CredentialStore(),
        )

        assertEquals(
            "A fresh repository must see the same deadline",
            original.availableAtMillis,
            reopened.releaseRequest()?.availableAtMillis,
        )
    }

    /**
     * A rewound clock is detected, and does not bring the deadline closer.
     *
     * Winding the clock backwards must not be treated as time passing, and must not shorten the
     * remaining wait. (Winding it *forwards* is blocked by DISALLOW_CONFIG_DATE_TIME, which needs
     * device owner and so cannot be exercised here.)
     */
    @Test
    fun clockMovingBackwardsDoesNotShortenTheWait() {
        val requestedAt = 1_000_000L
        val request = ReleaseRequest(
            requestedAtMillis = requestedAt,
            availableAtMillis = requestedAt + LONG_DELAY,
        )

        val rewound = requestedAt - 60_000L
        assertTrue("Should be detected as a backwards clock", request.clockWentBackwards(rewound))
        assertFalse("A rewound clock must not make the release ready", request.isReady(rewound))
        assertTrue(
            "Remaining time must not shrink when the clock goes backwards",
            request.remainingMillis(rewound) >= LONG_DELAY,
        )
    }

    /**
     * Past the deadline the countdown reads zero, not a negative duration.
     *
     * The UI formats this value directly, so a negative would surface as nonsense on screen.
     */
    @Test
    fun remainingTimeFloorsAtZero() {
        val request = ReleaseRequest(requestedAtMillis = 0L, availableAtMillis = 1_000L)
        assertEquals(0L, request.remainingMillis(5_000L))
        assertTrue(request.isReady(5_000L))
    }

    private companion object {
        /** Short enough to wait out inside a test, long enough that the refusal is real. */
        const val SHORT_DELAY = 1_500L

        /** Long enough that it cannot elapse during a test, for the cases that must not wait. */
        const val LONG_DELAY = 600_000L

        /** Slack so the test does not race the deadline it just set. */
        const val SETTLE = 300L
    }
}
