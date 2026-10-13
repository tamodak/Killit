package org.tamodak.killit.ui.myphone

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.tamodak.killit.R
import org.tamodak.killit.admin.HardeningConfig
import org.tamodak.killit.admin.KillitDeviceAdminReceiver
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.ui.KillitUiState
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.ShizukuOutcome
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitDangerButton
import org.tamodak.killit.data.AppLanguage
import org.tamodak.killit.ui.label
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitRow
import org.tamodak.killit.ui.components.KillitRule
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitStatusPanel
import org.tamodak.killit.ui.components.KillitStep
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.components.KillitToggleRow
import org.tamodak.killit.ui.components.formatDate
import org.tamodak.killit.ui.formatDuration
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitBorderDim
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitRowDivider
import org.tamodak.killit.ui.theme.KillitSurface
import org.tamodak.killit.ui.theme.KillitTextFaint
import org.tamodak.killit.ui.theme.KillitTextMuted
import org.tamodak.killit.ui.theme.KillitTextStrong
import kotlinx.coroutines.delay

/**
 * Which provisioning route the user opened, or null on the hub.
 *
 * Local UI state rather than a [org.tamodak.killit.ui.Screen]: these pages are pure instructions
 * with no state of their own, and routing them through the ViewModel would put three more entries
 * in a screen enum that exists to describe where the *session* is.
 */
private enum class SetupMethod { Adb, Shizuku, Qr }

/**
 * This phone (PLAN.md §15.2), behind the passkey. It has four faces:
 *
 * - **Not device owner yet:** the three ways to become one, each a step-by-step page, and the app
 *   list as a read-only preview, so the user can see what Killit will manage before committing to
 *   a factory reset.
 * - **Full control** (no guardians, or every guardian approved a live unlock): the status, and
 *   Apps · Guardians · Settings (§15.5).
 * - **Locked** (has guardians): what the protected user can still do on their own — ask to unlock,
 *   ask about an app, block more, see their guardians (§15.4).
 * - **Frozen** (its guardians moved to a new phone, §3.10): where they went, and giving up device
 *   owner, the only way out.
 *
 * Nothing is drawn until the number of guardians is known, so a guarded phone never flashes its
 * unlocked face.
 */
@Composable
fun MyPhoneScreen(
    state: KillitUiState,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onManageApps: () -> Unit,
    onGuardians: () -> Unit,
    onSettings: () -> Unit,
    onUnlock: () -> Unit,
    onAskUnblock: () -> Unit,
    onBlockMore: () -> Unit,
    onProvisionViaShizuku: () -> Unit,
    onDismissShizukuOutcome: () -> Unit,
    onRefreshStatus: () -> Unit,
    onRequestRelease: () -> Unit,
    onCancelRelease: () -> Unit,
    onReleaseDeviceOwner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val adminComponent = remember(context) {
        KillitDeviceAdminReceiver.componentName(context).flattenToString()
    }
    var setupMethod by remember { mutableStateOf<SetupMethod?>(null) }

    LaunchedEffect(state.isDeviceOwner) {
        if (state.isDeviceOwner) setupMethod = null
    }

    val method = setupMethod
    if (method != null) {
        BackHandler { setupMethod = null }
        SetupMethodScreen(
            method = method,
            adminComponent = adminComponent,
            busy = state.busy,
            modifier = modifier,
            onCopy = { context.copyToClipboard(it) },
            onProvisionViaShizuku = onProvisionViaShizuku,
            onRefreshStatus = onRefreshStatus,
            onBack = { setupMethod = null },
        )
    } else {
        BackHandler(onBack = onBack)
        KillitScreen(title = stringResource(R.string.hub_my_phone), onBack = onBack, modifier = modifier) {
            Column(
                modifier = Modifier.padding(
                    start = KillitGutter,
                    end = KillitGutter,
                    top = 24.dp,
                    bottom = 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                val guardians = state.guardianCount
                when {
                    guardians == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KillitGreen)
                    }
                    !state.isDeviceOwner -> SetupFace(state, onRefreshStatus, onManageApps) { setupMethod = it }
                    state.movedAway != null -> FrozenFace(state, onRequestRelease, onCancelRelease, onReleaseDeviceOwner)
                    state.fullControl -> UnlockedFace(state, guardians, onRefreshStatus, onManageApps, onGuardians, onSettings)
                    else -> LockedFace(state, pairing, guardians, onUnlock, onAskUnblock, onBlockMore, onGuardians)
                }
            }
        }
    }

    state.shizukuOutcome?.let { outcome ->
        KillitDialog(
            title = stringResource(R.string.prov_result_title),
            onDismiss = onDismissShizukuOutcome,
            dismissText = stringResource(R.string.ok),
        ) {
            when (outcome) {
                ShizukuOutcome.NotRunning ->
                    KillitBody(
                        text = stringResource(R.string.prov_shizuku_not_running),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                ShizukuOutcome.TooOld ->
                    KillitBody(
                        text = stringResource(R.string.prov_shizuku_old),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                ShizukuOutcome.PermissionDenied ->
                    KillitBody(
                        text = stringResource(R.string.prov_shizuku_denied),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                // Shell output verbatim: "there are already some accounts on the device" is the
                // usual failure and paraphrasing it would hide the fix.
                is ShizukuOutcome.CommandOutput -> MonospaceBlock(outcome.text)
            }
        }
    }
}

/** Before device owner: the status, the app list as a preview, and the three ways to set up. */
@Composable
private fun SetupFace(
    state: KillitUiState,
    onRefreshStatus: () -> Unit,
    onManageApps: () -> Unit,
    onOpen: (SetupMethod) -> Unit,
) {
    StatusPanel(state = state, guardians = 0, onRefresh = onRefreshStatus)
    // Browsable before provisioning too: the list shows a banner and leaves the checkboxes
    // disabled, so the user can see what Killit will manage first.
    KillitRow(
        title = stringResource(R.string.home_manage_apps),
        subtitle = stringResource(R.string.home_manage_apps_locked),
        icon = KillitIcons.Apps,
        onClick = onManageApps,
    )
    SetupSection(onOpen = onOpen)
}

/** Full control: the status, new apps, and the way into Apps, Guardians and Settings (§15.5). */
@Composable
private fun UnlockedFace(
    state: KillitUiState,
    guardians: Int,
    onRefreshStatus: () -> Unit,
    onManageApps: () -> Unit,
    onGuardians: () -> Unit,
    onSettings: () -> Unit,
) {
    if (state.unlockedByGuardians) {
        KillitPanel(borderColor = KillitBlue, spacing = 6.dp) {
            KillitSectionTitle(stringResource(R.string.my_phone_unlocked_title))
            KillitBody(stringResource(R.string.my_phone_unlocked_body))
        }
    }

    StatusPanel(state = state, guardians = guardians, onRefresh = onRefreshStatus)

    if (state.waitingApps.isNotEmpty()) {
        KillitRow(
            title = pluralStringResource(R.plurals.home_waiting_apps, state.waitingApps.size, state.waitingApps.size),
            subtitle = stringResource(R.string.home_waiting_apps_desc),
            icon = KillitIcons.Apps,
            iconTint = KillitBlue,
            borderColor = KillitBlue,
            onClick = onManageApps,
        )
    }

    NotificationPermissionPanel()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        KillitRow(
            title = stringResource(R.string.home_manage_apps),
            icon = KillitIcons.Apps,
            iconTint = KillitGreen,
            borderColor = KillitGreen,
            background = KillitGreen.copy(alpha = 0.10f),
            onClick = onManageApps,
        )
        KillitRow(
            title = stringResource(R.string.my_phone_guardians),
            subtitle = if (guardians == 0) {
                stringResource(R.string.my_phone_guardians_none)
            } else {
                pluralStringResource(R.plurals.hub_my_phone_guardians, guardians, guardians)
            },
            icon = KillitIcons.People,
            onClick = onGuardians,
        )
        KillitRow(
            title = stringResource(R.string.my_phone_settings),
            subtitle = stringResource(R.string.my_phone_settings_desc),
            icon = KillitIcons.Settings,
            onClick = onSettings,
        )
    }
}

/**
 * A phone whose guardians moved to a new phone (PLAN.md §3.10). Its blocks stay, nothing can be
 * unblocked, and giving up device owner, after its wait, is the only way out: otherwise moving to
 * a spare phone would be a way to shed the guardians.
 */
@Composable
private fun FrozenFace(
    state: KillitUiState,
    onRequestRelease: () -> Unit,
    onCancelRelease: () -> Unit,
    onReleaseDeviceOwner: () -> Unit,
) {
    val moved = state.movedAway ?: return
    val model = moved.toModel.ifEmpty { null }
    val date = formatDate(moved.at)
    KillitStatusPanel(
        icon = KillitIcons.Lock,
        title = stringResource(R.string.frozen_title),
        subtitle = if (model != null) stringResource(R.string.frozen_body, model, date) else stringResource(R.string.frozen_body_bare, date),
        accent = KillitBlue,
    ) {
        KillitBody(
            text = pluralStringResource(R.plurals.home_blocked_count, state.blocked.size, state.blocked.size),
            color = KillitForeground,
        )
    }
    ReleaseControls(state, guarded = false, onRequestRelease, onCancelRelease, onReleaseDeviceOwner)
}

/**
 * A guarded phone behind its passkey (§15.4): every action here is available without asking
 * again, and none of them weakens anything on its own.
 */
@Composable
private fun LockedFace(
    state: KillitUiState,
    pairing: PairingUiState,
    guardians: Int,
    onUnlock: () -> Unit,
    onAskUnblock: () -> Unit,
    onBlockMore: () -> Unit,
    onGuardians: () -> Unit,
) {
    KillitStatusPanel(
        icon = KillitIcons.Lock,
        title = pluralStringResource(R.plurals.hub_my_phone_guardians, guardians, guardians),
        subtitle = stringResource(R.string.my_phone_locked_body),
        accent = KillitGreen,
    ) {
        KillitBody(
            text = pluralStringResource(R.plurals.home_blocked_count, state.blocked.size, state.blocked.size),
            color = KillitForeground,
        )
    }

    KillitPrimaryButton(text = stringResource(R.string.my_phone_unlock), onClick = onUnlock)

    val waiting = state.waitingApps.size
    val asking = pairing.outgoing.count { it.request.state.isOpen && it.request.type == org.tamodak.killit.pairing.store.RequestType.UNBLOCK_APP }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        KillitRow(
            title = stringResource(R.string.my_phone_ask_unblock),
            subtitle = when {
                asking > 0 -> pluralStringResource(R.plurals.hub_my_requests_open, asking, asking)
                waiting > 0 -> pluralStringResource(R.plurals.home_waiting_apps, waiting, waiting)
                else -> stringResource(R.string.my_phone_ask_unblock_desc)
            },
            icon = KillitIcons.Apps,
            iconTint = if (waiting > 0) KillitBlue else KillitForeground,
            borderColor = if (waiting > 0) KillitBlue else KillitBorderDim,
            onClick = onAskUnblock,
        )
        KillitRow(
            title = stringResource(R.string.my_phone_block_more),
            subtitle = stringResource(R.string.my_phone_block_more_desc),
            icon = KillitIcons.Lock,
            onClick = onBlockMore,
        )
        KillitRow(
            title = stringResource(R.string.my_phone_my_guardians),
            subtitle = pluralStringResource(R.plurals.hub_my_phone_guardians, guardians, guardians),
            icon = KillitIcons.People,
            onClick = onGuardians,
        )
    }

    NotificationPermissionPanel()
}

/**
 * The headline panel: whether Killit is device owner, and how much it is currently blocking.
 *
 * Green or red throughout, because this one fact decides whether anything else on the screen can
 * take effect at all.
 *
 * @param state supplies owner status and the blocked count.
 * @param guardians how many guardians this phone has.
 * @param onRefresh re-checks owner status, for when the user has provisioned outside the app.
 */
@Composable
private fun StatusPanel(state: KillitUiState, guardians: Int, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KillitStatusPanel(
            icon = KillitIcons.Shield,
            title = when {
                !state.isDeviceOwner -> stringResource(R.string.home_status_owner_no)
                guardians == 0 -> stringResource(R.string.hub_my_phone_protected)
                else -> pluralStringResource(R.plurals.hub_my_phone_guardians, guardians, guardians)
            },
            subtitle = stringResource(
                if (state.isDeviceOwner) R.string.home_status_owner_yes_desc
                else R.string.home_status_owner_no_desc
            ),
            accent = if (state.isDeviceOwner) KillitGreen else KillitRed,
        ) {
            if (state.isDeviceOwner) {
                KillitBody(
                    text = pluralStringResource(
                        R.plurals.home_blocked_count,
                        state.blocked.size,
                        state.blocked.size,
                    ),
                    color = KillitForeground,
                )
            }
        }
        KillitTextButton(text = stringResource(R.string.prov_refresh), onClick = onRefresh)
    }
}

/**
 * Asks for permission to post notifications, for as long as Killit cannot post any.
 *
 * The request sits next to its reason, on the screen where protection starts, which is where
 * Android's guidance wants a permission asked for. Android 13 and later show the system's dialog;
 * once that has been declined it will not be shown again, and on any version notifications can be
 * switched off in Settings, so the button then opens Killit's notification settings instead.
 *
 * Leaving for Settings locks Killit like leaving for anywhere else; the permission dialog does not,
 * because it only pauses the activity.
 */
@Composable
internal fun NotificationPermissionPanel() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(context.notificationsAllowed()) }
    var declined by rememberSaveable { mutableStateOf(false) }

    // Read again on every return: the answer can change in Settings while Killit is away.
    LifecycleResumeEffect(Unit) {
        allowed = context.notificationsAllowed()
        onPauseOrDispose { }
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        KillitLog.i(KillitLog.UI, "Notification permission ${if (granted) "granted" else "declined"}")
        declined = !granted
        allowed = context.notificationsAllowed()
    }
    if (allowed) return

    KillitPanel(borderColor = KillitBlue, spacing = 12.dp) {
        KillitSectionTitle(stringResource(R.string.home_notifications_title))
        KillitBody(stringResource(R.string.home_notifications_body))
        KillitButton(
            text = stringResource(R.string.home_notifications_allow),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !declined &&
                    !context.hasNotificationPermission()
                ) {
                    requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            },
        )
    }
}

/**
 * Reports whether Killit's notifications would be shown.
 *
 * @return true when the permission is held (or not needed) and notifications are on for Killit.
 */
private fun Context.notificationsAllowed(): Boolean =
    hasNotificationPermission() && NotificationManagerCompat.from(this).areNotificationsEnabled()

/**
 * Reports whether the notification permission is held.
 *
 * @return true from Android 13 when it has been granted, and always below 13, which has no such
 *   permission.
 */
private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The routes to becoming device owner, shown only while Killit is not one.
 *
 * @param onOpen opens the walkthrough for the chosen method.
 */
@Composable
private fun SetupSection(onOpen: (SetupMethod) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        KillitSectionTitle(stringResource(R.string.prov_section_title))

        KillitRow(
            title = stringResource(R.string.prov_adb_name),
            subtitle = stringResource(R.string.prov_adb_short),
            icon = KillitIcons.Computer,
            iconTint = KillitBlue,
            onClick = { onOpen(SetupMethod.Adb) },
        )
        KillitRow(
            title = stringResource(R.string.prov_shizuku_name),
            subtitle = stringResource(R.string.prov_shizuku_short),
            icon = KillitIcons.Phone,
            iconTint = KillitBlue,
            onClick = { onOpen(SetupMethod.Shizuku) },
        )
        KillitRow(
            title = stringResource(R.string.prov_qr_name),
            subtitle = stringResource(R.string.prov_qr_short),
            icon = KillitIcons.QrCode,
            iconTint = KillitBlue,
            onClick = { onOpen(SetupMethod.Qr) },
        )
    }
}

/**
 * One provisioning route, as numbered steps.
 *
 * All three need a device with no accounts and no existing device owner, which on a phone already
 * in use means a factory reset — stated up front on every route rather than discovered halfway
 * through one of them.
 *
 * Each page ends in Refresh status, because none of the three can report their own success: adb
 * and the QR wizard finish outside the app entirely, and even Shizuku only reports what the shell
 * printed. Re-reading the device policy service is the only thing that actually settles it.
 *
 * @param method which route to walk through.
 * @param adminComponent the flattened admin name, which the adb route has the user copy.
 * @param busy false enables the actions; true while provisioning is in flight.
 * @param onCopy puts a command on the clipboard.
 * @param onProvisionViaShizuku runs the Shizuku path. Used only on that page.
 * @param onRefreshStatus re-checks owner status.
 * @param onBack returns to the hub.
 * @param modifier applied to the screen.
 */
@Composable
private fun SetupMethodScreen(
    method: SetupMethod,
    adminComponent: String,
    busy: Boolean,
    onCopy: (String) -> Unit,
    onProvisionViaShizuku: () -> Unit,
    onRefreshStatus: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleRes = when (method) {
        SetupMethod.Adb -> R.string.prov_adb_name
        SetupMethod.Shizuku -> R.string.prov_shizuku_name
        SetupMethod.Qr -> R.string.prov_qr_name
    }
    val introRes = when (method) {
        SetupMethod.Adb -> R.string.prov_adb_intro
        SetupMethod.Shizuku -> R.string.prov_shizuku_intro
        SetupMethod.Qr -> R.string.prov_qr_intro
    }
    val steps = when (method) {
        SetupMethod.Adb -> listOf(
            R.string.prov_adb_step_1,
            R.string.prov_adb_step_2,
            R.string.prov_adb_step_3,
        )

        SetupMethod.Shizuku -> listOf(
            R.string.prov_shizuku_step_1,
            R.string.prov_shizuku_step_2,
            R.string.prov_shizuku_step_3,
        )

        SetupMethod.Qr -> listOf(
            R.string.prov_qr_step_1,
            R.string.prov_qr_step_2,
            R.string.prov_qr_step_3,
        )
    }

    KillitScreen(title = stringResource(titleRes), onBack = onBack, modifier = modifier) {
        Column(
            modifier = Modifier.padding(
                start = KillitGutter,
                end = KillitGutter,
                top = 24.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            KillitBody(stringResource(introRes))

            KillitPanel(borderColor = KillitBorderDim) {
                KillitBody(stringResource(R.string.prov_precondition), color = KillitTextFaint)
            }

            steps.forEachIndexed { index, textRes ->
                KillitStep(number = index + 1, text = stringResource(textRes))
            }

            when (method) {
                SetupMethod.Adb -> {
                    MonospaceBlock("adb shell dpm set-device-owner $adminComponent")
                    KillitButton(
                        text = stringResource(R.string.copy),
                        onClick = { onCopy("adb shell dpm set-device-owner $adminComponent") },
                    )
                }

                SetupMethod.Shizuku -> KillitPrimaryButton(
                    text = stringResource(
                        if (busy) R.string.prov_shizuku_working else R.string.prov_shizuku_run
                    ),
                    onClick = onProvisionViaShizuku,
                    enabled = !busy,
                )

                SetupMethod.Qr -> QrIllustration()
            }

            KillitButton(text = stringResource(R.string.prov_refresh), onClick = onRefreshStatus)
        }
    }
}

/** A stand-in glyph, not a scannable code — the real provisioning QR lives on the website. */
@Composable
private fun QrIllustration() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally),
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .border(3.dp, KillitTextStrong, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = KillitIcons.QrCodeSolid,
                contentDescription = null,
                tint = KillitForeground,
                modifier = Modifier.size(130.dp),
            )
        }
    }
}

/**
 * Verbatim text on a raised surface: adb commands to copy, and shell output to read.
 *
 * Monospaced because both are things the user has to type accurately or compare character by
 * character.
 *
 * @param text the content, shown exactly as given.
 */
@Composable
private fun MonospaceBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(KillitSurface)
            .padding(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = KillitForeground,
        )
    }
}

/** Copies a provisioning command so it can be pasted into a terminal or messaged to a computer. */
private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    KillitLog.d(KillitLog.UI) { "Copied to clipboard: $text" }
    clipboard.setPrimaryClip(ClipData.newPlainText("Killit", text))
    // Android 13+ shows its own copy confirmation; a second one would be noise.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
