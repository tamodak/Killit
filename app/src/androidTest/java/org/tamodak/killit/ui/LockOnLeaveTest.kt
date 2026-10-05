package org.tamodak.killit.ui

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.admin.provisioning.ShizukuProvisioner
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.AppInventory
import org.tamodak.killit.data.CredentialStore
import org.tamodak.killit.data.DurableStore
import org.tamodak.killit.data.LockPreferences
import org.tamodak.killit.data.LockRepository
import org.tamodak.killit.data.LockType
import org.tamodak.killit.data.PersistedStateSnapshot
import org.tamodak.killit.protection.ProtectionControl
import org.tamodak.killit.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leaving Killit always locks it, whatever was in flight at the time.
 *
 * Drives the ViewModel the way the activity does — [KillitViewModel.onForeground] on ON_START,
 * [KillitViewModel.lockOnBackground] on ON_STOP — against the real stores. Each case starts an
 * operation, leaves while it is still running, and checks what is on screen once it has finished.
 *
 * The calls are made on the main thread, where `viewModelScope` runs a launched coroutine
 * synchronously up to its first suspension. That is what makes "leave while it is still running"
 * deterministic here, and each test asserts the operation really was in flight before leaving.
 */
@RunWith(AndroidJUnit4::class)
class LockOnLeaveTest {

    /** The instrumentation context, which owns the stores these tests write to. */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The local store, snapshotted around each test. */
    private val prefs = LockPreferences(context)

    /** The real policy controller, so a save does the real (empty) blocklist round trip. */
    private val dpc = DevicePolicyController(context)

    /** The repository the ViewModel verifies against. */
    private val repository = LockRepository(
        prefs = prefs,
        durable = DurableStore(dpc),
        credentials = CredentialStore(),
    )

    /** Owns the ViewModel, so [tearDown] can cancel its coroutines before the stores are restored. */
    private val viewModelStore = ViewModelStore()

    /** The ViewModel under test, built on the main thread like the real one. */
    private lateinit var viewModel: KillitViewModel

    /** What both stores held before the test. */
    private lateinit var snapshot: PersistedStateSnapshot

    /** Sets a known passkey and waits for the ViewModel to settle on the gate. */
    @Before
    fun setUp() = runBlocking {
        KillitLog.verbose = true
        snapshot = PersistedStateSnapshot.take(prefs, dpc)
        repository.setCredential(LockType.PIN, PASSKEY)

        viewModel = withContext(Dispatchers.Main) {
            val factory = viewModelFactory {
                initializer {
                    KillitViewModel(
                        repository = repository,
                        dpc = dpc,
                        inventory = AppInventory(context),
                        shizuku = ShizukuProvisioner(context),
                        guard = ServiceLocator.packageGuard,
                        // Protection is not what these tests are about; starting it would only
                        // add a service and a snapshot to every run.
                        protection = object : ProtectionControl {
                            override suspend fun ensureRunning() = Unit
                            override fun stop() = Unit
                        },
                    )
                }
            }
            ViewModelProvider(viewModelStore, factory)[KillitViewModel::class.java]
        }
        awaitState { it.screen == Screen.Gate && !it.appsLoading }
    }

    /** Cancels the ViewModel's work, then puts the device's own passkey back. */
    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { viewModelStore.clear() }
        snapshot.restore()
    }

    /** Leaving while a save is running locks at once, and the save finishing does not reopen Home. */
    @Test
    fun leavingDuringASaveLocks(): Unit = runBlocking {
        unlock()

        withContext(Dispatchers.Main) {
            viewModel.navigateTo(Screen.Apps)
            viewModel.save()
            assertTrue("The save must still be running when the user leaves", viewModel.state.value.busy)
            viewModel.lockOnBackground()
            assertEquals(Screen.Gate, viewModel.state.value.screen)
        }

        awaitState { !it.busy }
        assertEquals("A finished save must not bring the app list back", Screen.Gate, viewModel.state.value.screen)

        withContext(Dispatchers.Main) { viewModel.onForeground() }
        assertEquals("Coming back must find the gate", Screen.Gate, viewModel.state.value.screen)
    }

    /** A passkey check that finishes after the user left does not unlock the session. */
    @Test
    fun aPasskeyProvedAfterLeavingDoesNotUnlock(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            viewModel.onForeground()
            viewModel.submitGate(PASSKEY)
            assertTrue("The check must still be running when the user leaves", viewModel.state.value.busy)
            viewModel.lockOnBackground()
        }

        awaitState { !it.busy }
        assertEquals(Screen.Gate, viewModel.state.value.screen)

        // Proving the passkey again once back in the foreground opens Home as usual.
        unlock()
    }

    /** Screens that need a session cannot be reached from a locked one. */
    @Test
    fun protectedScreensNeedASession(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            viewModel.onForeground()
            listOf(Screen.Home, Screen.Apps, Screen.ChangePasskey).forEach { screen ->
                viewModel.navigateTo(screen)
                assertEquals("$screen must be refused while locked", Screen.Gate, viewModel.state.value.screen)
            }
        }
    }

    /** Enters the passkey with the activity in the foreground and waits for Home. */
    private suspend fun unlock() {
        withContext(Dispatchers.Main) {
            viewModel.onForeground()
            viewModel.submitGate(PASSKEY)
        }
        awaitState { it.screen == Screen.Home && !it.busy }
    }

    /**
     * Waits for the ViewModel to reach a state.
     *
     * @param predicate the state to wait for.
     */
    private suspend fun awaitState(predicate: (KillitUiState) -> Boolean) {
        withTimeout(TIMEOUT_MILLIS) { viewModel.state.first(predicate) }
    }

    private companion object {
        /** A PIN in its normalised form. */
        const val PASSKEY = "246810"

        /** Generous: the app list load and a save each probe every installed package. */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
