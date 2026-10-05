package org.tamodak.killit.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.tamodak.killit.BuildConfig
import org.tamodak.killit.ServiceLocator
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.admin.HardeningConfig
import org.tamodak.killit.admin.provisioning.ShizukuProvisioner
import org.tamodak.killit.admin.provisioning.ShizukuStatus
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.AppEntry
import org.tamodak.killit.data.AppLanguage
import org.tamodak.killit.data.AppInventory
import org.tamodak.killit.data.LockRepository
import org.tamodak.killit.data.LockType
import org.tamodak.killit.data.ReleaseRequest
import org.tamodak.killit.data.VerifyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which screen the single-activity UI is showing.
 *
 * @property requiresAuthentication whether the screen may only be shown to a session that has
 *   proved the passkey. [KillitViewModel.navigateTo] refuses such a screen otherwise.
 */
sealed interface Screen {
    val requiresAuthentication: Boolean get() = false

    /** Bootstrapping: reading the stores and the current suspension state. */
    data object Loading : Screen

    /** No passkey yet — choose a method and set one. */
    data object Setup : Screen

    /** A passkey exists; prove it. */
    data object Gate : Screen

    /** Unlocked. Status, hardening toggles, and the way into everything else. */
    data object Home : Screen {
        override val requiresAuthentication get() = true
    }

    /** Choosing which packages to block. */
    data object Apps : Screen {
        override val requiresAuthentication get() = true
    }

    /** Replacing an existing passkey. The same screen as [Setup], but cancellable. */
    data object ChangePasskey : Screen {
        override val requiresAuthentication get() = true
    }

    /** Killit's data was cleared: apps are blocked but the passkey can no longer be verified. */
    data object Tampered : Screen
}

/** What the gate shows after a rejected attempt. */
sealed interface GateFeedback {
    /**
     * The passkey was wrong, and guessing may continue.
     *
     * @param remainingAttempts tries left before a lockout begins.
     */
    data class Wrong(val remainingAttempts: Int) : GateFeedback

    /**
     * Too many wrong attempts; the gate is closed for now.
     *
     * @param remainingMillis how long is left before guessing may resume.
     */
    data class LockedOut(val remainingMillis: Long) : GateFeedback
}

/** Transient confirmations. Modelled rather than pre-rendered so the text stays in strings.xml. */
sealed interface UiMessage {
    /** The blocklist was applied. */
    data object Saved : UiMessage

    /** A new passkey took effect. */
    data object PasskeySet : UiMessage

    /**
     * Something failed.
     *
     * @param text produced by the platform, shown as-is rather than replaced with a generic
     *   message — the platform's own wording usually names the fix.
     */
    data class Error(val text: String) : UiMessage
}

/** Result of a Shizuku provisioning attempt. */
sealed interface ShizukuOutcome {
    /** Shizuku is not installed, or its server has not been started. */
    data object NotRunning : ShizukuOutcome

    /** Pre-v11 Shizuku, whose user-service API Killit does not support. */
    data object TooOld : ShizukuOutcome

    /** The user declined Shizuku's consent dialog. */
    data object PermissionDenied : ShizukuOutcome

    /** Raw stdout/stderr from `dpm set-device-owner`, shown verbatim. */
    data class CommandOutput(val text: String) : ShizukuOutcome
}

/**
 * The entire UI state, in one immutable snapshot.
 *
 * @param screen which screen is showing.
 * @param isDeviceOwner whether Killit holds device owner, i.e. whether it can block anything.
 * @param lockType which input the gate should collect, or null when no passkey is set.
 * @param hardening the anti-tamper toggles as stored.
 * @param apps the installed-package inventory backing the picker.
 * @param blocked what the OS reports as suspended — the source of truth for checkbox state.
 * @param selection pending checkbox state, applied on Save. Diverges from [blocked] only while
 *   the user has unsaved changes.
 * @param appsLoading whether the inventory is still being read; the picker shows a spinner.
 * @param busy whether an action is in flight. Disables the controls that started it.
 * @param gateFeedback why the last unlock attempt was rejected, or null after a fresh start.
 * @param saveFailures packages Android refused to suspend or unsuspend on the last save.
 * @param shizukuOutcome result of the last Shizuku provisioning attempt, shown as a dialog.
 * @param message a transient confirmation waiting to be shown as a toast.
 * @param releaseRequest outstanding request to give up device owner, or null if none has been made.
 * @param language which language the UI is shown in.
 */
data class KillitUiState(
    val screen: Screen = Screen.Loading,
    val isDeviceOwner: Boolean = false,
    val lockType: LockType? = null,
    val hardening: HardeningConfig = HardeningConfig.DEFAULT,
    val apps: List<AppEntry> = emptyList(),
    val blocked: Set<String> = emptySet(),
    val selection: Set<String> = emptySet(),
    val appsLoading: Boolean = false,
    val busy: Boolean = false,
    val gateFeedback: GateFeedback? = null,
    val saveFailures: List<String> = emptyList(),
    val shizukuOutcome: ShizukuOutcome? = null,
    val message: UiMessage? = null,
    val releaseRequest: ReleaseRequest? = null,
    val language: AppLanguage = AppLanguage.DEFAULT,
) {
    /** How many checkboxes differ from what is applied: newly ticked plus newly cleared. */
    val pendingChanges: Int
        get() = ((selection - blocked) + (blocked - selection)).size

    /** True while the picker holds unsaved changes, which gates the Save button and back handling. */
    val hasPendingChanges: Boolean get() = pendingChanges > 0

    /**
     * Renders this state for logging.
     *
     * @return a compact summary. Deliberately excludes the app list, which is huge.
     */
    fun describe(): String =
        "screen=$screen owner=$isDeviceOwner lock=$lockType apps=${apps.size} " +
            "blocked=${blocked.size} selection=${selection.size} busy=$busy loading=$appsLoading"
}

/**
 * Owns the whole UI state and every action the screens can take.
 *
 * ### Threading
 *
 * Everything runs on `viewModelScope`, i.e. the main dispatcher, and there is deliberately no
 * `withContext` here: the repository and the policy controller are main-safe and own the
 * dispatchers for their own binder and disk work. If a slow operation ever does surface
 * on the main thread, the fix belongs in that class, not in a wrapper here.
 *
 * ### Authentication
 *
 * [authenticated] is a plain field rather than part of [KillitUiState], so it cannot leak into a
 * saved state bundle. It dies with the ViewModel, which means process death re-locks — the
 * conservative direction.
 *
 * A session is only ever unlocked while the activity is started (see [unlockIfInForeground]), and
 * leaving the activity always locks it (see [lockOnBackground]). Work that is in flight when the
 * user leaves — verifying a passkey, saving the blocklist, releasing device owner — keeps running
 * in `viewModelScope` and finishes behind the gate.
 *
 * @param repository the passkey record, the hardening toggles and the release request.
 * @param dpc every call into `DevicePolicyManager`.
 * @param inventory the installed-package list and icon decoding.
 * @param shizuku the no-computer provisioning path.
 */
class KillitViewModel(
    private val repository: LockRepository,
    private val dpc: DevicePolicyController,
    private val inventory: AppInventory,
    private val shizuku: ShizukuProvisioner,
) : ViewModel() {

    /** The single source of UI state. Mutated only through [update] calls in this class. */
    private val _state = MutableStateFlow(KillitUiState())

    /** The state the screens observe. */
    val state: StateFlow<KillitUiState> = _state.asStateFlow()

    /**
     * Whether the passkey has been proved this session.
     *
     * Deliberately not part of [KillitUiState] — see the class documentation.
     */
    private var authenticated = false

    /**
     * Whether the activity is between ON_START and ON_STOP, as reported by [onForeground] and
     * [lockOnBackground].
     *
     * Starts false: until the UI reports its first ON_START, nothing may unlock the session.
     */
    private var inForeground = false

    /**
     * True only while another app's screen is expected to cover Killit on purpose: Shizuku's
     * permission prompt, during provisioning.
     *
     * That prompt is a different app's activity, so Killit receives ON_STOP while the user is
     * still in the middle of setting it up, and locking there would send them back to the gate
     * mid-flow. The exemption is this narrow on purpose: it covers one prompt, and only before
     * Killit is device owner, when there is nothing yet for an unlocked session to unblock.
     */
    private var awaitingExternalScreen = false

    init {
        KillitLog.d(KillitLog.VM) { "ViewModel created; bootstrapping" }
        viewModelScope.launch { bootstrap() }
    }

    // ---------------------------------------------------------------- startup

    /**
     * Decides which screen the app opens on, and gets there as fast as it can.
     *
     * ### Why this is split
     *
     * Choosing the screen needs exactly one thing: whether a passkey record exists. Everything
     * else the app eventually wants — the hardening toggles, the installed package list, which
     * packages the OS currently has suspended — belongs to screens the user has not reached yet.
     * Loading them here first meant the gate waited on ~400ms of package scanning it never used.
     *
     * So the record is read, the screen is shown, and the rest is filled in behind it. The app
     * list arrives while the user is still typing.
     *
     * ### The one case that cannot be rushed
     *
     * `record == null && isDeviceOwner` is ambiguous: it is either a fresh install, or a Killit
     * whose passkey was wiped while the OS kept its apps suspended. Telling those apart requires
     * knowing whether anything is actually blocked, so that path — and only that path — waits.
     *
     * It has to wait. Showing setup first and correcting to [Screen.Tampered] a few hundred
     * milliseconds later would open a window in which whoever cleared the data could set a new
     * passkey, which is exactly the attack the tampered screen exists to stop.
     */
    private suspend fun bootstrap() = KillitLog.timed(KillitLog.VM, "bootstrap (to first screen)") {
        // Fast path. A cached record is proof a passkey exists, and it costs one DataStore read —
        // no device policy service, whose first call in a process is the most expensive thing at
        // startup. Everything else, reconciliation with durable storage included, runs behind the
        // gate the user is already looking at.
        val cached = repository.readCachedRecord()
        // Read here rather than with the rest of the preferences, because every screen below this
        // point renders text: loading it later would show one frame in the wrong language.
        val language = repository.language()
        _state.update { it.copy(language = language) }

        if (cached != null) {
            KillitLog.i(KillitLog.VM, "Bootstrap: cached ${cached.lockType} record -> Gate")
            _state.update {
                it.copy(lockType = cached.lockType, screen = Screen.Gate, appsLoading = true)
            }
            startBackgroundLoad(reconcile = true)
            return@timed
        }

        // Nothing cached: either a fresh install, or one whose data was cleared. Only the durable
        // copy and the blocked set can tell those apart, so this path pays for both.
        KillitLog.d(KillitLog.VM) { "No cached record; consulting durable storage" }
        val record = repository.reconcile()
        val isOwner = dpc.isDeviceOwner()
        _state.update {
            it.copy(isDeviceOwner = isOwner, lockType = record?.lockType, appsLoading = true)
        }

        if (record != null || !isOwner) {
            // A record here came back from durable storage — the restore after a "Clear data".
            val screen = if (record != null) Screen.Gate else Screen.Setup
            KillitLog.i(KillitLog.VM, "Bootstrap: owner=$isOwner lock=${record?.lockType} -> $screen")
            _state.update { it.copy(screen = screen) }
            startBackgroundLoad(reconcile = false)
            return@timed
        }

        // Device owner with no passkey anywhere. Ambiguous, and the only path that has to wait.
        KillitLog.i(KillitLog.VM, "No record while device owner; checking the blocklist for tampering")
        viewModelScope.launch { loadHardening() }
        loadApps()
        val blocked = _state.value.blocked
        val screen = if (blocked.isNotEmpty()) Screen.Tampered else Screen.Setup
        KillitLog.i(KillitLog.VM, "Bootstrap: blocked=${blocked.size} -> $screen")
        _state.update { it.copy(screen = screen) }
    }

    /**
     * Fills in everything the first screen did not need.
     *
     * @param reconcile whether the two credential stores still have to be brought into step. True
     *   on the fast path, which deliberately skipped that to avoid a device policy service call.
     */
    private fun startBackgroundLoad(reconcile: Boolean) {
        viewModelScope.launch { loadHardening() }
        viewModelScope.launch { loadApps() }
        viewModelScope.launch {
            val isOwner = dpc.isDeviceOwner()
            _state.update { it.copy(isDeviceOwner = isOwner) }
            // Promotes a pre-provisioning passkey to durable storage, and refreshes the local
            // cache from durable if the two ever drifted.
            if (reconcile) repository.reconcile()
        }
    }

    /**
     * Loads the hardening toggles and any pending release request.
     *
     * Both belong to the Home screen, which the user cannot reach without authenticating first, so
     * neither gates the gate.
     */
    private suspend fun loadHardening() {
        val hardening = KillitLog.timed(KillitLog.VM, "read hardening") { repository.hardening.first() }
        _state.update { it.copy(hardening = hardening) }

        val request = repository.releaseRequest()
        if (request != null) {
            KillitLog.i(KillitLog.VM, "Release pending, available at ${request.availableAtMillis}")
        }
        _state.update { it.copy(releaseRequest = request) }
    }

    /**
     * Loads the package inventory and probes which of them are currently suspended.
     *
     * The slowest step in startup, which is why it runs behind whichever screen is already showing.
     */
    private suspend fun loadApps() {
        val apps = inventory.load()
        val blocked = dpc.blockedPackages(apps.map { it.packageName })
        _state.update { it.copy(
            apps = apps,
            blocked = blocked,
            // Start with no pending changes: the checkboxes mirror reality.
            selection = blocked,
            appsLoading = false,
        ) }
        KillitLog.d(KillitLog.VM) { "loadApps: ${apps.size} apps, ${blocked.size} blocked" }
    }

    /** Re-checks device owner status after the user has provisioned outside the app (adb, QR). */
    fun refreshOwnerStatus() {
        viewModelScope.launch {
            KillitLog.i(KillitLog.VM, "Refreshing device owner status")
            val isOwner = dpc.isDeviceOwner()
            _state.update { it.copy(isDeviceOwner = isOwner, appsLoading = true) }
            if (isOwner) {
                // A passkey set before provisioning now gains durable, clear-data-proof backing.
                repository.promoteToDurable()
                dpc.applyHardening(_state.value.hardening)
            }
            loadApps()
        }
    }

    // ---------------------------------------------------------------- auth

    /**
     * Checks a submitted credential.
     *
     * Guarded by [KillitUiState.busy] so a double tap cannot spend two lockout attempts on one
     * entry — which matters because key derivation takes long enough for a second tap to land.
     *
     * A correct passkey opens Home only if the user is still here when the check finishes; see
     * [unlockIfInForeground].
     *
     * @param credential the normalised passkey string as entered.
     */
    fun submitGate(credential: String) {
        if (_state.value.busy) {
            KillitLog.d(KillitLog.VM) { "submitGate ignored: already verifying" }
            return
        }
        viewModelScope.launch {
            KillitLog.i(KillitLog.VM, "Gate submission received (${KillitLog.describeSecret(credential)})")
            _state.update { it.copy(busy = true, gateFeedback = null) }

            val feedback = when (val result = repository.verify(credential)) {
                VerifyResult.Success -> {
                    unlockIfInForeground()
                    null
                }

                is VerifyResult.Wrong -> GateFeedback.Wrong(result.remainingAttempts)
                is VerifyResult.LockedOut -> GateFeedback.LockedOut(result.remainingMillis)

                VerifyResult.NoCredential -> {
                    // Nothing to check against; send the user to set one rather than loop.
                    KillitLog.w(KillitLog.VM, "Gate had no record to verify against; going to Setup")
                    _state.update { it.copy(screen = Screen.Setup) }
                    null
                }
            }

            _state.update { it.copy(busy = false, gateFeedback = feedback) }
            KillitLog.d(KillitLog.VM) { "Gate finished: ${_state.value.describe()}" }
        }
    }

    /**
     * Sets or replaces the passkey, then opens Home.
     *
     * Setting one authenticates the session, since the setup screen has the user enter it twice —
     * but, as for [submitGate], only if the user is still here when the record has been written.
     * Otherwise the new passkey is in place and the gate asks for it on return.
     *
     * @param lockType which input the gate should collect in future.
     * @param credential the normalised passkey string.
     */
    fun setCredential(lockType: LockType, credential: String) {
        viewModelScope.launch {
            KillitLog.i(KillitLog.VM, "Setting passkey ($lockType)")
            _state.update { it.copy(busy = true) }
            repository.setCredential(lockType, credential)
            _state.update { it.copy(busy = false, lockType = lockType, message = UiMessage.PasskeySet) }
            if (!unlockIfInForeground()) _state.update { it.copy(screen = Screen.Gate) }
        }
    }

    /**
     * Opens the session, but only while the activity is in the foreground.
     *
     * Every path that proves the passkey ends here. Proving it takes time, and a result that lands
     * after the user has left would otherwise leave an unlocked Killit waiting in the background
     * for whoever opens it next. Such a result is dropped: the user simply proves the passkey again
     * on return.
     *
     * @return true when the session was unlocked and Home is showing.
     */
    private fun unlockIfInForeground(): Boolean {
        if (!inForeground) {
            KillitLog.i(KillitLog.VM, "Passkey proved after Killit left the foreground; staying locked")
            return false
        }
        authenticated = true
        _state.update { it.copy(screen = Screen.Home) }
        return true
    }

    // ---------------------------------------------------------------- app blocking

    /**
     * Ticks or clears one checkbox.
     *
     * Local change only; nothing reaches the OS until [save].
     *
     * @param packageName the package the row represents.
     * @param checked the box's new state.
     */
    fun toggleSelection(packageName: String, checked: Boolean) {
        val current = _state.value.selection
        _state.update { it.copy(
            selection = if (checked) current + packageName else current - packageName
        ) }
        KillitLog.v(KillitLog.VM) {
            "toggle $packageName -> $checked (${_state.value.pendingChanges} pending)"
        }
    }

    /** Throws away unsaved checkbox changes, putting the picker back in step with the OS. */
    fun discardSelection() {
        KillitLog.d(KillitLog.VM) { "Discarding ${_state.value.pendingChanges} pending changes" }
        _state.update { it.copy(selection = _state.value.blocked) }
    }

    /**
     * Pushes the pending selection to the OS, then re-reads what actually landed.
     *
     * The re-read is the important part: Android silently refuses to suspend some packages, so
     * assuming the write succeeded would leave checkboxes ticked for apps that are still usable.
     *
     * The selection is read from a snapshot taken when Save was pressed, but state is only ever
     * written through `update` on the latest value. Writing the snapshot back would undo anything
     * that happened in between — a lock on leaving included, which would put the app list back on
     * screen without a session.
     */
    fun save() {
        val snapshot = _state.value
        if (snapshot.busy) {
            KillitLog.d(KillitLog.VM) { "save ignored: already busy" }
            return
        }
        viewModelScope.launch {
            KillitLog.i(KillitLog.VM, "Saving ${snapshot.pendingChanges} blocklist changes")
            _state.update { it.copy(busy = true, saveFailures = emptyList()) }

            val result = dpc.applyBlocklist(desired = snapshot.selection, current = snapshot.blocked)

            // Re-read from the OS rather than assuming the write landed: any package Android
            // refused is still unblocked, and its checkbox has to go back.
            val blocked = dpc.blockedPackages(snapshot.apps.map { it.packageName })

            val failures = result.failedToBlock + result.failedToUnblock
            if (failures.isNotEmpty()) {
                KillitLog.w(KillitLog.VM, "Save completed with ${failures.size} rejected packages: $failures")
            } else {
                KillitLog.i(KillitLog.VM, "Save complete; ${blocked.size} packages now blocked")
            }

            _state.update { it.copy(
                busy = false,
                blocked = blocked,
                selection = blocked,
                saveFailures = failures,
                message = result.error?.let(UiMessage::Error) ?: UiMessage.Saved,
            ) }
        }
    }

    /** Closes the dialog listing packages Android refused to suspend. */
    fun dismissSaveFailures() {
        _state.update { it.copy(saveFailures = emptyList()) }
    }

    // ---------------------------------------------------------------- hardening

    /**
     * Applies and persists the anti-tamper toggles.
     *
     * @param config the full set of toggles, as the user left them.
     */
    fun setHardening(config: HardeningConfig) {
        viewModelScope.launch {
            KillitLog.i(KillitLog.VM, "Hardening changed: $config")
            repository.setHardening(config)
            dpc.applyHardening(config)
            _state.update { it.copy(hardening = config) }
        }
    }

    // ---------------------------------------------------------------- provisioning

    /**
     * Reports whether Shizuku is usable, without attempting anything.
     *
     * Not currently used by any screen; kept as the read-only counterpart to [provisionViaShizuku].
     *
     * @return the current Shizuku status.
     */
    suspend fun shizukuStatus(): ShizukuStatus = shizuku.status()

    /**
     * Walks the Shizuku provisioning flow and reports whatever came back.
     *
     * Shizuku's permission prompt is another app's activity, so Killit receives ON_STOP while it is
     * showing. [awaitingExternalScreen] is raised for exactly the duration of that prompt so the
     * session is not locked mid-provisioning; see its documentation for why that is safe.
     */
    fun provisionViaShizuku() {
        if (_state.value.busy) {
            KillitLog.d(KillitLog.VM) { "provisionViaShizuku ignored: already busy" }
            return
        }
        viewModelScope.launch {
            KillitLog.i(KillitLog.VM, "Starting Shizuku provisioning")
            _state.update { it.copy(busy = true, shizukuOutcome = null) }

            val outcome = when (shizuku.status()) {
                ShizukuStatus.NOT_RUNNING -> ShizukuOutcome.NotRunning
                ShizukuStatus.TOO_OLD -> ShizukuOutcome.TooOld
                ShizukuStatus.READY -> {
                    awaitingExternalScreen = true
                    val granted = try {
                        shizuku.requestPermission()
                    } finally {
                        awaitingExternalScreen = false
                    }
                    if (!granted) {
                        ShizukuOutcome.PermissionDenied
                    } else {
                        ShizukuOutcome.CommandOutput(shizuku.setDeviceOwner())
                    }
                }
            }

            KillitLog.i(KillitLog.VM, "Shizuku provisioning outcome: ${outcome::class.java.simpleName}")
            _state.update { it.copy(busy = false, shizukuOutcome = outcome) }
            // The command may have succeeded regardless of what its text says, so re-check rather
            // than parsing the output.
            refreshOwnerStatus()
        }
    }

    /** Closes the dialog showing what the provisioning command reported. */
    fun dismissShizukuOutcome() {
        _state.update { it.copy(shizukuOutcome = null) }
    }

    // ---------------------------------------------------------------- language

    /**
     * Switches the language the UI is shown in, and remembers the choice.
     *
     * The state is updated before the write lands, so the screen re-reads its strings immediately
     * rather than after a round trip to disk. Re-selecting the current language is a no-op.
     *
     * @param language the language to switch to.
     */
    fun setLanguage(language: AppLanguage) {
        if (_state.value.language == language) return
        KillitLog.i(KillitLog.VM, "Language changed to ${language.name}")
        _state.update { it.copy(language = language) }
        viewModelScope.launch { repository.setLanguage(language) }
    }

    // ---------------------------------------------------------------- delayed release

    /**
     * Starts the waiting period before device owner can be given up.
     *
     * Nothing is released here — this only writes the deadline. The release itself needs a second
     * confirmation once the wait has elapsed.
     */
    fun requestRelease() {
        viewModelScope.launch {
            val request = repository.requestRelease(BuildConfig.RELEASE_DELAY_MILLIS)
            _state.update { it.copy(releaseRequest = request) }
        }
    }

    /** Abandons the waiting period, so giving up device owner starts from zero again. */
    fun cancelRelease() {
        viewModelScope.launch {
            repository.cancelRelease()
            _state.update { it.copy(releaseRequest = null) }
        }
    }

    /**
     * Carries out the release, but only if the wait has actually elapsed.
     *
     * The check is repeated here against freshly read storage rather than trusting
     * [KillitUiState.releaseRequest], which the UI may have been holding for a while. This is the
     * last gate before something that cannot be undone without a factory reset.
     */
    fun releaseDeviceOwner() {
        if (_state.value.busy) return
        viewModelScope.launch {
            if (!repository.isReleaseAllowed()) {
                KillitLog.w(KillitLog.VM, "Release refused: the waiting period has not elapsed")
                // Re-sync so the UI shows the real remaining time rather than an enabled button.
                _state.update { it.copy(releaseRequest = repository.releaseRequest()) }
                return@launch
            }

            KillitLog.i(KillitLog.VM, "Waiting period elapsed; releasing device owner")
            _state.update { it.copy(busy = true) }

            // Cleared *before* the release, not after. Clearing the durable copy needs device
            // owner, so doing it afterwards would silently leave the request behind in system
            // storage — and a device provisioned again later would find an already-elapsed
            // deadline waiting for it, skipping the wait entirely.
            //
            // The cost of this ordering is that a failed release makes the user start the wait
            // over. That is the right direction to fail in.
            repository.cancelRelease()

            val packages = _state.value.apps.map { it.packageName }
            dpc.releaseDeviceOwner(packages)
            _state.update {
                it.copy(busy = false, isDeviceOwner = dpc.isDeviceOwner(), releaseRequest = null)
            }
            loadApps()
        }
    }

    // ---------------------------------------------------------------- navigation

    /**
     * Moves to another screen, clearing any stale gate feedback on the way.
     *
     * A screen that needs a session is refused while locked. The UI only offers those screens from
     * an unlocked one, but a tap that lands just as Killit locks would otherwise open one without
     * the passkey.
     *
     * @param screen where to go.
     */
    fun navigateTo(screen: Screen) {
        if (screen.requiresAuthentication && !authenticated) {
            KillitLog.w(KillitLog.VM, "navigateTo($screen) refused: session is not authenticated")
            return
        }
        KillitLog.d(KillitLog.VM) { "navigate ${_state.value.screen} -> $screen" }
        _state.update { it.copy(screen = screen, gateFeedback = null) }
    }

    /**
     * Returns to Home, if this session has earned it.
     *
     * Guarded: without the check, a back press from an unauthenticated screen would reach Home.
     */
    fun goHome() {
        if (!authenticated) {
            KillitLog.w(KillitLog.VM, "goHome refused: session is not authenticated")
            return
        }
        _state.update { it.copy(screen = Screen.Home) }
    }

    /**
     * Records that the activity has started, which is what allows a proved passkey to unlock the
     * session. Called on every ON_START, including the first.
     */
    fun onForeground() {
        KillitLog.v(KillitLog.VM) { "onForeground" }
        inForeground = true
    }

    /**
     * Re-locks when Killit leaves the foreground. Called on every ON_STOP.
     *
     * Without this, an authenticated session would sit on the home screen indefinitely — hand the
     * phone over and everything can be unblocked.
     *
     * The lock is unconditional: an operation that is still running when the user leaves carries
     * on in `viewModelScope` and finishes behind the gate. The one exception is
     * [awaitingExternalScreen], and only while Killit is not yet device owner.
     */
    fun lockOnBackground() {
        inForeground = false
        if (!authenticated) {
            KillitLog.v(KillitLog.VM) { "lockOnBackground: already locked" }
            return
        }
        if (awaitingExternalScreen && !_state.value.isDeviceOwner) {
            KillitLog.d(KillitLog.VM) { "lockOnBackground skipped: Shizuku's permission prompt is showing" }
            return
        }

        KillitLog.i(KillitLog.VM, "App backgrounded; re-locking")
        authenticated = false
        _state.update {
            it.copy(
                screen = Screen.Gate,
                gateFeedback = null,
                // Drop unsaved checkbox changes rather than carrying them past a lock.
                selection = it.blocked,
            )
        }
    }

    /** Marks the pending toast as shown, so it is not repeated on the next recomposition. */
    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * Decodes one launcher icon for the app list.
     *
     * Passed to the picker as a loader rather than the icons being put in [KillitUiState], so only
     * the rows actually on screen are ever decoded.
     *
     * @param packageName the package whose icon is wanted.
     * @return the decoded icon, or null if the package has none.
     */
    suspend fun iconFor(packageName: String) = inventory.icon(packageName)

    /** Traces teardown. Nothing needs releasing: `viewModelScope` cancels its own coroutines. */
    override fun onCleared() {
        super.onCleared()
        KillitLog.d(KillitLog.VM) { "ViewModel cleared" }
    }

    companion object {
        /**
         * Reads the graph from [ServiceLocator]. [ShizukuProvisioner] is built per-ViewModel
         * rather than being a singleton because it holds no state between provisioning attempts.
         */
        val Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                KillitViewModel(
                    repository = ServiceLocator.lockRepository,
                    dpc = ServiceLocator.devicePolicyController,
                    inventory = ServiceLocator.appInventory,
                    shizuku = ShizukuProvisioner(application),
                )
            }
        }
    }
}
