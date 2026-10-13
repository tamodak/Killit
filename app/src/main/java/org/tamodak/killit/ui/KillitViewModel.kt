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
import org.tamodak.killit.data.MovedAway
import org.tamodak.killit.data.PackageStatus
import org.tamodak.killit.data.ReleaseRequest
import org.tamodak.killit.data.VerifyResult
import org.tamodak.killit.pairing.LiveUnlock
import org.tamodak.killit.pairing.PairingEngine
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.protocol.NoticeBody
import org.tamodak.killit.protocol.ProtectionOption
import org.tamodak.killit.protection.PackageGuard
import org.tamodak.killit.protection.Protection
import org.tamodak.killit.protection.ProtectionControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What a screen asks of the session before it may be shown.
 *
 * The order matters: each level includes the ones before it.
 */
enum class Access {
    /** Anyone holding the phone may see it: the Hub, setting the first passkey, the tampered screen. */
    NONE,

    /** The passkey was proved on this visit (PLAN.md J1): both sections, and My phone locked. */
    PASSKEY,

    /**
     * Full control of this phone (PLAN.md §3.5): the passkey on a phone nobody guards, or every
     * guardian's approval of a live unlock, until the user leaves the app.
     */
    FULL_CONTROL,
}

/**
 * Which screen the single-activity UI is showing.
 *
 * @property access what the session must have proved for the screen to be shown;
 *   [KillitViewModel.navigateTo] refuses it otherwise.
 * @property parent where back goes. There is no back stack: every screen knows its parent.
 */
sealed interface Screen {
    val access: Access get() = Access.NONE
    val parent: Screen? get() = Hub

    /** Bootstrapping: reading the stores and the current suspension state. */
    data object Loading : Screen {
        override val parent: Screen? get() = null
    }

    /** The first screen: My phone and Phones I guard, with counts and never names (I5). */
    data object Hub : Screen {
        override val parent: Screen? get() = null
    }

    /** First launch (PLAN.md §15.9): the language, then three cards about what Killit does. */
    data object Onboarding : Screen {
        override val parent: Screen? get() = null
    }

    /** No passkey yet: choose a method and set one. Reached from a Hub card. */
    data object Setup : Screen

    /** A passkey exists; prove it. Shown on the way into either section. */
    data object Gate : Screen

    /** Killit's data was cleared: apps are blocked but the passkey can no longer be verified. */
    data object Tampered : Screen {
        override val parent: Screen? get() = null
    }

    /**
     * This phone: setup when Killit isn't device owner, the locked screen when it has guardians,
     * and everything else when the session has full control.
     */
    data object MyPhone : Screen {
        override val access get() = Access.PASSKEY
    }

    /** Choosing which packages to block. */
    data object Apps : Screen {
        override val access get() = Access.FULL_CONTROL
        override val parent: Screen get() = MyPhone
    }

    /** Protection options, language, and giving up device owner. */
    data object Settings : Screen {
        override val access get() = Access.FULL_CONTROL
        override val parent: Screen get() = MyPhone
    }

    /** Replacing the passkey; reached from either section's settings. */
    data class ChangePasskey(override val parent: Screen) : Screen {
        override val access get() = Access.PASSKEY
    }

    /** This phone's guardians: adding one, asking to remove one. */
    data object Guardians : Screen {
        override val access get() = Access.PASSKEY
        override val parent: Screen get() = MyPhone
    }

    /** Asking every guardian to remove one of them (UNPAIR). */
    data class AskRemove(val pairId: String) : Screen {
        override val access get() = Access.PASSKEY
        override val parent: Screen get() = Guardians
    }

    /** The live unlock: waiting, on screen, for every guardian. */
    data object UnlockWaiting : Screen {
        override val access get() = Access.PASSKEY
        override val parent: Screen get() = MyPhone
    }

    /** Blocked apps this phone can ask its guardians about. */
    data object AskUnblock : Screen {
        override val access get() = Access.PASSKEY
        override val parent: Screen get() = MyPhone
    }

    /** Writing the explanation for one app (UNBLOCK_APP). */
    data class AskUnblockApp(val packageName: String) : Screen {
        override val access get() = Access.PASSKEY
        override val parent: Screen get() = AskUnblock
    }

    /** Blocking more apps without the guardians (D7). */
    data object BlockMore : Screen {
        override val access get() = Access.PASSKEY
        override val parent: Screen get() = MyPhone
    }

    /** Phones I guard: their requests and the phones themselves. */
    data object Guard : Screen {
        override val access get() = Access.PASSKEY
    }

    /**
     * One request from a guarded phone, with Deny and Approve.
     *
     * @property parent the inbox, or the phone whose list it was opened from.
     */
    data class Request(val pairId: String, val requestId: String, override val parent: Screen = Guard) : Screen {
        override val access get() = Access.PASSKEY
    }

    /** One guarded phone: its name, its history, and Stop guarding. */
    data class Phone(val pairId: String) : Screen {
        override val access get() = Access.PASSKEY
        override val parent: Screen get() = Guard
    }

    /** A guarded phone's apps, which its guardians may block when it lets them (PLAN.md §3.8). */
    data class ManageApps(val pairId: String) : Screen {
        override val access get() = Access.PASSKEY
        override val parent: Screen get() = Phone(pairId)
    }

    /** The guardian side's settings: the passkey explanation, language, passkey. */
    data object GuardSettings : Screen {
        override val access get() = Access.PASSKEY
        override val parent: Screen get() = Guard
    }

    /**
     * The pairing wizard. The role follows from where it was started (PLAN.md §15.1): "Guard a new
     * phone" makes this phone the guardian, "Add a guardian" the protected one.
     */
    data class Wizard(val role: PairRole) : Screen {
        override val access get() = Access.PASSKEY
        override val parent: Screen get() = if (role == PairRole.GUARDIAN) Guard else Guardians
    }

    /**
     * The new phone's side of a move (PLAN.md §3.10), from the Hub. Behind the passkey: what the
     * old phone approved becomes allowed here.
     */
    data object MoveIn : Screen {
        override val access get() = Access.PASSKEY
    }

    /**
     * The old phone's side of a move, from either section's settings. A phone with guardians moves
     * only with full control (§3.10), which the actions check; the screen itself stays open while
     * the move ends that control, so it can say how the move went.
     */
    data class MoveOut(override val parent: Screen) : Screen {
        override val access get() = Access.PASSKEY
    }
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
 * @param waitingApps packages blocked automatically that nobody has decided about yet: the app
 *   list's "New" section.
 * @param guardianCount how many guardians this phone has; null until it is known. My phone shows
 *   nothing until then, so a guarded phone never flashes its unlocked face.
 * @param fullControl whether My phone is unlocked: no guardians, or a live unlock they all
 *   approved. A display hint only; every action checks again against storage.
 * @param unlockedByGuardians whether the full control came from a live unlock, for the banner
 *   that says it ends on leaving.
 * @param gateForGuardian whether the gate leads into Phones I guard, which it explains differently.
 * @param appManagement whether this phone lets its guardians manage its apps; null until read.
 * @param movedAway set when this phone's guardians moved to a new phone, which leaves it frozen
 *   (PLAN.md §3.10): no full control, only releasing device owner.
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
    val waitingApps: Set<String> = emptySet(),
    val guardianCount: Int? = null,
    val fullControl: Boolean = false,
    val unlockedByGuardians: Boolean = false,
    val gateForGuardian: Boolean = false,
    val appManagement: Boolean? = null,
    val movedAway: MovedAway? = null,
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
 * The passkey opens both sections (PLAN.md J1), but on a phone with guardians it no longer opens
 * the phone itself. Full control then needs [unlockedByGuardians]: every guardian's approval of a
 * live unlock that is still on screen (§3.4). Whether a phone has guardians is read from storage
 * at every point that grants something, never from a cached count that may not have arrived yet.
 * A phone whose guardians moved to a new phone is frozen (§3.10): nobody has full control of it,
 * and its passkey only lets it give up device owner.
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
 * @param guard default-blocking: records what the user decides in the app list.
 * @param protection starts protection whenever it may have become possible, and stops it before
 *   device owner is given up.
 * @param pairing the guardians: how many there are decides whether the passkey alone opens this
 *   phone, and only an approved live unlock opens it otherwise.
 */
class KillitViewModel(
    private val repository: LockRepository,
    private val dpc: DevicePolicyController,
    private val inventory: AppInventory,
    private val shizuku: ShizukuProvisioner,
    private val guard: PackageGuard,
    private val protection: ProtectionControl,
    private val pairing: PairingEngine,
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

    /**
     * Where to go once the passkey has been proved: the section whose card was tapped, or what a
     * notification asked for. Null means My phone.
     */
    private var destinationAfterUnlock: Screen? = null

    /**
     * Whether every guardian approved the live unlock on screen during this visit (§3.4). Like
     * [authenticated], it ends when the user leaves.
     */
    private var unlockedByGuardians = false

    init {
        KillitLog.d(KillitLog.VM) { "ViewModel created; bootstrapping" }
        viewModelScope.launch { bootstrap() }
        viewModelScope.launch { followWaitingApps() }
        viewModelScope.launch { followGuardians() }
        viewModelScope.launch { followGateOpenings() }
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
            KillitLog.i(KillitLog.VM, "Bootstrap: cached ${cached.lockType} record -> Hub")
            _state.update {
                it.copy(lockType = cached.lockType, screen = Screen.Hub, appsLoading = true)
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
            // Without one, this may be the first launch; after it, the Hub's cards lead to setting
            // the first passkey.
            val screen = if (record == null && !repository.onboarded()) Screen.Onboarding else Screen.Hub
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
        val screen = when {
            blocked.isNotEmpty() -> Screen.Tampered
            !repository.onboarded() -> Screen.Onboarding
            else -> Screen.Hub
        }
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
            // Brings guardians back after a "Clear data", before anything reads how many there are.
            pairing.store.reconcile()
            refreshAppManagement()
            // Opening Killit is one of the moments protection checks every package.
            protection.ensureRunning()
        }
    }

    /**
     * Keeps [KillitUiState.waitingApps] in step with the guard's decisions, for as long as the
     * ViewModel lives.
     *
     * A package that starts waiting was just installed and blocked, so the app list is read again
     * to show it — keeping whatever the user has ticked but not saved yet.
     */
    private suspend fun followWaitingApps() {
        var first = true
        guard.packages
            .map { known -> known.values.filter { it.status == PackageStatus.PENDING }.mapTo(mutableSetOf()) { it.packageName } }
            .distinctUntilChanged()
            .collect { waiting ->
                val arrived = waiting - _state.value.waitingApps
                _state.update { it.copy(waitingApps = waiting) }
                if (!first && arrived.isNotEmpty() && !_state.value.appsLoading) refreshApps()
                first = false
            }
    }

    /**
     * Keeps [KillitUiState.guardianCount] and [KillitUiState.fullControl] current. Gaining a
     * guardian ends the full control a phone without guardians had (the safe direction); losing
     * the last one frees it, unless they moved to a new phone and left this one frozen.
     */
    private suspend fun followGuardians() {
        pairing.store.pairings
            .map { all -> all.count { it.myRole == PairRole.PROTECTED } }
            .distinctUntilChanged()
            .collect { count ->
                val moved = pairing.movedAway()
                _state.update {
                    it.copy(
                        guardianCount = count,
                        fullControl = authenticated && moved == null && (count == 0 || unlockedByGuardians),
                        movedAway = moved,
                    )
                }
                val screen = _state.value.screen
                if (screen.access == Access.FULL_CONTROL && !_state.value.fullControl) {
                    KillitLog.i(KillitLog.VM, "Guardians changed; leaving $screen")
                    _state.update { it.copy(screen = Screen.MyPhone) }
                }
            }
    }

    /**
     * Opens My phone when every guardian approved the live unlock (PLAN.md §6.2 (b)), but only if
     * the waiting screen for that very session is still in the foreground (H10, E-B3).
     */
    private suspend fun followGateOpenings() {
        pairing.effects.gateOpenings.collect { effect ->
            val approved = pairing.unlock.state.value as? LiveUnlock.State.Approved
            val onScreen = inForeground && authenticated && _state.value.screen == Screen.UnlockWaiting
            if (approved?.sessionId != effect.sessionId || !onScreen) {
                KillitLog.w(KillitLog.VM, "An approved unlock arrived for a session no longer on screen; ignored")
                return@collect
            }
            KillitLog.i(KillitLog.VM, "Every guardian approved the unlock; full control until leaving")
            unlockedByGuardians = true
            _state.update { it.copy(screen = Screen.MyPhone, fullControl = true, unlockedByGuardians = true) }
        }
    }

    /**
     * Whether the session has full control, read from storage rather than the cached count: a
     * guarded phone restored after Clear data must never count as unguarded for a moment.
     */
    private suspend fun hasFullControl(): Boolean {
        if (!authenticated) return false
        // A phone whose guardians moved away is frozen: nobody has full control of it any more.
        if (pairing.isFrozen()) return false
        if (unlockedByGuardians) return true
        pairing.store.reconcile()
        return pairing.store.guardians().isEmpty()
    }

    /**
     * Whether this session may move this phone's pairings to a new phone (PLAN.md §3.10): the
     * passkey, and with guardians, their approval of a live unlock. The move itself loosens
     * nothing, but without the guardians' say a protected user could leave this phone behind for a
     * spare one.
     */
    suspend fun mayMoveOut(): Boolean = hasFullControl()

    /**
     * Whether this session may give up device owner: full control, or the passkey on a frozen
     * phone, for which the release is the only way out (PLAN.md §3.10).
     */
    private suspend fun mayRelease(): Boolean = hasFullControl() || (authenticated && pairing.isFrozen())

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

    /**
     * Reads the app list again after something outside the screen changed it, keeping the user's
     * unsaved ticks.
     *
     * Packages whose state changed underneath move with it — a newly blocked app arrives ticked —
     * and every other checkbox stays as the user left it.
     */
    private suspend fun refreshApps() {
        val apps = inventory.load()
        val installed = apps.mapTo(mutableSetOf()) { it.packageName }
        val blocked = dpc.blockedPackages(installed)
        _state.update { current ->
            val newlyBlocked = blocked - current.blocked
            val newlyUnblocked = current.blocked - blocked
            current.copy(
                apps = apps,
                blocked = blocked,
                selection = ((current.selection + newlyBlocked) - newlyUnblocked).intersect(installed),
            )
        }
        KillitLog.d(KillitLog.VM) { "refreshApps: ${apps.size} apps, ${blocked.size} blocked" }
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
                // Becoming device owner is one of the two moments protection can start.
                protection.ensureRunning()
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
     * Sets or replaces the passkey, then goes where the user was heading.
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
            val changing = authenticated && _state.value.screen is Screen.ChangePasskey
            repository.setCredential(lockType, credential)
            _state.update { it.copy(busy = false, lockType = lockType, message = UiMessage.PasskeySet) }
            val screen = _state.value.screen
            when {
                // A change returns to the settings it was opened from.
                changing && screen is Screen.ChangePasskey -> _state.update { it.copy(screen = screen.parent) }
                !unlockIfInForeground() -> _state.update { it.copy(screen = Screen.Hub) }
            }
            // Setting the first passkey is the other moment protection can start.
            protection.ensureRunning()
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
     * The destination is checked like any navigation: a screen that needs full control, asked for
     * by a notification, becomes My phone when the guardians keep this phone locked.
     *
     * @return true when the session was unlocked.
     */
    private suspend fun unlockIfInForeground(): Boolean {
        if (!inForeground) {
            KillitLog.i(KillitLog.VM, "Passkey proved after Killit left the foreground; staying locked")
            return false
        }
        authenticated = true
        val wanted = destinationAfterUnlock ?: Screen.MyPhone
        destinationAfterUnlock = null
        val full = hasFullControl()
        val moved = pairing.movedAway()
        val destination = when {
            wanted.access != Access.FULL_CONTROL || full -> wanted
            wanted == Screen.Apps -> Screen.AskUnblock
            else -> Screen.MyPhone
        }
        _state.update { it.copy(screen = destination, fullControl = full, movedAway = moved) }
        return true
    }

    /**
     * Opens a screen once the passkey has been proved, or right away when it already has.
     *
     * Used when Killit is opened for a purpose, such as tapping the blocked-apps notification. The
     * passkey is asked for as always; this only decides where the user lands afterwards.
     *
     * @param screen where to go.
     */
    fun openAfterUnlock(screen: Screen) {
        when {
            authenticated -> navigateTo(screen)
            // Straight to the passkey (§15.3): a notification opens the relevant place, through
            // the one prompt on the way in.
            _state.value.lockType != null && _state.value.screen != Screen.Loading -> {
                destinationAfterUnlock = screen
                _state.update { it.copy(screen = Screen.Gate, gateFeedback = null, gateForGuardian = screen.isGuardSide()) }
            }
            else -> {
                destinationAfterUnlock = screen
                _state.update { it.copy(gateForGuardian = screen.isGuardSide()) }
            }
        }
    }

    /**
     * Opens a section from the Hub: straight in when the passkey was proved on this visit, through
     * the gate otherwise, and through setting a passkey when there is none yet (§15.9).
     *
     * @param screen [Screen.MyPhone] or [Screen.Guard].
     */
    fun openSection(screen: Screen) {
        when {
            authenticated -> navigateTo(screen)
            _state.value.lockType == null -> {
                destinationAfterUnlock = screen
                _state.update { it.copy(screen = Screen.Setup) }
            }
            else -> {
                destinationAfterUnlock = screen
                _state.update { it.copy(screen = Screen.Gate, gateFeedback = null, gateForGuardian = screen.isGuardSide()) }
            }
        }
    }

    /** True for the screens of Phones I guard, where the passkey is a guardian's key. */
    private fun Screen.isGuardSide(): Boolean = when (this) {
        Screen.Guard, Screen.GuardSettings, is Screen.Request, is Screen.Phone, is Screen.ManageApps -> true
        is Screen.Wizard -> role == PairRole.GUARDIAN
        else -> false
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
        // Busy from the tap on, so a second tap can't start another save while this one checks.
        _state.update { it.copy(busy = true, saveFailures = emptyList()) }
        viewModelScope.launch {
            if (!hasFullControl()) {
                KillitLog.w(KillitLog.VM, "save refused: the session has no full control")
                _state.update { it.copy(busy = false) }
                return@launch
            }
            KillitLog.i(KillitLog.VM, "Saving ${snapshot.pendingChanges} blocklist changes")

            // Through the guard, so the decisions are remembered: an unblocked app stays allowed
            // after an update, a blocked one stays blocked even if it is reinstalled.
            val result = guard.applySelection(desired = snapshot.selection, current = snapshot.blocked)
            // G11: an app unblocked here no longer needs the request its guardians were asked.
            val unblocked = (snapshot.blocked - snapshot.selection) - result.failedToUnblock.toSet()
            if (unblocked.isNotEmpty()) pairing.onUnblockedLocally(unblocked)

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

    /** Keeps every waiting new app blocked, which empties the app list's "New" section. */
    fun keepWaitingAppsBlocked() {
        viewModelScope.launch { guard.keepWaitingAppsBlocked() }
    }

    // ---------------------------------------------------------------- hardening

    /**
     * Applies and persists the anti-tamper toggles.
     *
     * @param config the full set of toggles, as the user left them.
     */
    fun setHardening(config: HardeningConfig) {
        viewModelScope.launch {
            if (!hasFullControl()) {
                KillitLog.w(KillitLog.VM, "setHardening refused: the session has no full control")
                return@launch
            }
            // I1: while anyone guards this phone the clock stays locked, or the UNPAIR timer and
            // the release delay could be skipped by moving it.
            val guarded = pairing.store.guardians().isNotEmpty()
            val applied = if (guarded) config.copy(blockDateTime = true) else config
            val before = _state.value.hardening
            KillitLog.i(KillitLog.VM, "Hardening changed: $applied")
            repository.setHardening(applied)
            dpc.applyHardening(applied)
            _state.update { it.copy(hardening = applied) }
            // H6: guardians hear about every protection that changes.
            for ((option, enabled) in protectionChanges(before, applied)) {
                pairing.notifyGuardians(NoticeBody.ProtectionChanged(option, enabled))
            }
        }
    }

    /** Reads whether this phone lets its guardians manage its apps, for Settings and the wizard. */
    fun refreshAppManagement() {
        viewModelScope.launch { _state.update { it.copy(appManagement = pairing.appManagementEnabled()) } }
    }

    /**
     * Lets this phone's guardians manage its apps, or stops them (PLAN.md §3.8). Either way it
     * changes what the guardians may do, so only full control may (G3), checked against storage.
     */
    fun setAppManagement(enabled: Boolean) {
        viewModelScope.launch {
            if (!hasFullControl()) {
                KillitLog.w(KillitLog.VM, "setAppManagement refused: the session has no full control")
                return@launch
            }
            pairing.setAppManagement(enabled)
            _state.update { it.copy(appManagement = pairing.appManagementEnabled()) }
        }
    }

    /** The options that differ between two configs, as NOTICE PROTECTION_CHANGED names them. */
    private fun protectionChanges(before: HardeningConfig, after: HardeningConfig): List<Pair<ProtectionOption, Boolean>> =
        listOf(
            Triple(ProtectionOption.UNINSTALL, before.blockUninstall, after.blockUninstall),
            Triple(ProtectionOption.FORCE_STOP, before.blockForceStop, after.blockForceStop),
            Triple(ProtectionOption.SAFE_BOOT, before.blockSafeBoot, after.blockSafeBoot),
            Triple(ProtectionOption.FACTORY_RESET, before.blockFactoryReset, after.blockFactoryReset),
            Triple(ProtectionOption.APPS_CONTROL, before.blockAppsControl, after.blockAppsControl),
            Triple(ProtectionOption.DATE_TIME, before.blockDateTime, after.blockDateTime),
        ).filter { (_, old, new) -> old != new }.map { (option, _, new) -> option to new }

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

    /** Leaves the first-launch cards for the Hub, finished or skipped, and never shows them again. */
    fun finishOnboarding() {
        if (_state.value.screen != Screen.Onboarding) return
        _state.update { it.copy(screen = Screen.Hub) }
        viewModelScope.launch { repository.finishOnboarding() }
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
            if (!mayRelease()) return@launch
            val request = repository.requestRelease(BuildConfig.RELEASE_DELAY_MILLIS)
            _state.update { it.copy(releaseRequest = request) }
            // C8: guardians hear when it would complete.
            pairing.notifyGuardians(NoticeBody.ReleaseStarted(request.availableAtMillis))
        }
    }

    /** Abandons the waiting period, so giving up device owner starts from zero again. */
    fun cancelRelease() {
        viewModelScope.launch {
            repository.cancelRelease()
            _state.update { it.copy(releaseRequest = null) }
            pairing.notifyGuardians(NoticeBody.ReleaseCancelled)
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
            if (!mayRelease()) return@launch
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
            // Also before the release, for the same reason: the decisions have a durable copy, and
            // protecting the phone again later should start from a fresh snapshot.
            guard.forget()
            protection.stop()
            // H7: every guardian is removed and told, while this phone can still say so.
            pairing.releaseAll()

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
     * A screen the session hasn't earned is refused. The UI only offers those screens to a session
     * that has, but a tap that lands just as Killit locks would otherwise open one without the
     * passkey. Full control is checked against the cached guardian count and freeze, read together,
     * which fails closed while they are still unknown; every action behind such a screen checks
     * storage again.
     *
     * Leaving the live unlock's screen withdraws the request (§3.4).
     *
     * @param screen where to go.
     */
    fun navigateTo(screen: Screen) {
        if (!mayOpen(screen)) {
            KillitLog.w(KillitLog.VM, "navigateTo($screen) refused: the session hasn't earned it")
            return
        }
        val from = _state.value.screen
        if (from == Screen.UnlockWaiting && screen != Screen.UnlockWaiting) {
            viewModelScope.launch { pairing.cancelUnlock() }
        }
        KillitLog.d(KillitLog.VM) { "navigate $from -> $screen" }
        _state.update { it.copy(screen = screen, gateFeedback = null) }
    }

    /** Whether the session has earned [screen], as [navigateTo] checks it. */
    private fun mayOpen(screen: Screen): Boolean = when (screen.access) {
        Access.NONE -> true
        Access.PASSKEY -> authenticated
        Access.FULL_CONTROL ->
            authenticated && _state.value.movedAway == null && (unlockedByGuardians || _state.value.guardianCount == 0)
    }

    /**
     * Goes back to the current screen's parent; the Hub has none. A parent the session no longer
     * earns, such as Settings on a phone a move just froze, gives way to its own.
     */
    fun back() {
        var parent = _state.value.screen.parent ?: return
        while (!mayOpen(parent)) parent = parent.parent ?: Screen.Hub
        if (parent == Screen.Hub) {
            if (_state.value.screen == Screen.UnlockWaiting) viewModelScope.launch { pairing.cancelUnlock() }
            _state.update { it.copy(screen = Screen.Hub, gateFeedback = null) }
        } else {
            navigateTo(parent)
        }
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
        unlockedByGuardians = false
        destinationAfterUnlock = null
        // A live unlock never survives leaving: the request is withdrawn and its nonce forgotten.
        viewModelScope.launch { pairing.cancelUnlock() }
        _state.update {
            it.copy(
                screen = Screen.Hub,
                gateFeedback = null,
                // Drop unsaved checkbox changes rather than carrying them past a lock.
                selection = it.blocked,
                fullControl = false,
                unlockedByGuardians = false,
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
         * Protection is started and stopped through [Protection] with the application context, so
         * the ViewModel itself never holds a context.
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
                    guard = ServiceLocator.packageGuard,
                    protection = object : ProtectionControl {
                        override suspend fun ensureRunning() =
                            Protection.ensureRunning(application, ServiceLocator.packageGuard)

                        override fun stop() = Protection.stop(application)
                    },
                    pairing = ServiceLocator.pairing,
                )
            }
        }
    }
}
