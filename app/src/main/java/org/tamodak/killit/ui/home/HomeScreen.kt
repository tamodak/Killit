package org.tamodak.killit.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.admin.HardeningConfig
import org.tamodak.killit.admin.KillitDeviceAdminReceiver
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.ui.KillitUiState
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
 * The authenticated landing screen.
 *
 * Shows one of two faces depending on [KillitUiState.isDeviceOwner]: before provisioning it offers
 * the three ways to become device owner, each opening its own step-by-step page; afterwards it is
 * the tamper protection toggles. The app list is reachable in both states — browsable as a
 * read-only preview beforehand — so the user can see what Killit will manage before committing to a
 * factory reset.
 *
 * @param state supplies owner status, the hardening toggles and the release request.
 * @param onManageApps opens the app picker.
 * @param onChangePasskey opens the change-passkey flow.
 * @param onHardeningChange called with the full config whenever one toggle moves.
 * @param onProvisionViaShizuku runs the no-computer provisioning path.
 * @param onDismissShizukuOutcome closes the dialog showing what the command reported.
 * @param onRefreshStatus re-checks owner status after provisioning outside the app.
 * @param onRequestRelease starts the wait before device owner can be given up.
 * @param onCancelRelease abandons that wait.
 * @param onReleaseDeviceOwner carries out the release, once the wait has elapsed.
 * @param onLanguageChange switches the language the whole UI is shown in.
 * @param modifier applied to the screen.
 */
@Composable
fun HomeScreen(
    state: KillitUiState,
    onManageApps: () -> Unit,
    onChangePasskey: () -> Unit,
    onHardeningChange: (HardeningConfig) -> Unit,
    onProvisionViaShizuku: () -> Unit,
    onDismissShizukuOutcome: () -> Unit,
    onRefreshStatus: () -> Unit,
    onRequestRelease: () -> Unit,
    onCancelRelease: () -> Unit,
    onReleaseDeviceOwner: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val adminComponent = remember(context) {
        KillitDeviceAdminReceiver.componentName(context).flattenToString()
    }
    var setupMethod by remember { mutableStateOf<SetupMethod?>(null) }
    // Two separate confirmations: one to start the wait, another to actually release once it is
    // over. The second is the irreversible one.
    var confirmRelease by remember { mutableStateOf(false) }
    var confirmReleaseNow by remember { mutableStateOf(false) }
    var pickingLanguage by remember { mutableStateOf(false) }

    LaunchedEffect(state.isDeviceOwner) {
        if (state.isDeviceOwner) setupMethod = null
    }

    if (pickingLanguage) {
        LanguagePicker(
            current = state.language,
            onPick = {
                onLanguageChange(it)
                pickingLanguage = false
            },
            onDismiss = { pickingLanguage = false },
        )
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
        KillitScreen(title = stringResource(R.string.app_name), modifier = modifier) {
            Column(
                modifier = Modifier.padding(
                    start = KillitGutter,
                    end = KillitGutter,
                    top = 24.dp,
                    bottom = 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                KillitRow(
                    title = stringResource(R.string.home_language),
                    subtitle = state.language.label(),
                    icon = KillitIcons.Globe,
                    onClick = { pickingLanguage = true },
                )

                StatusPanel(state = state, onRefresh = onRefreshStatus)

                // Browsable before provisioning too: the list shows a banner and leaves the
                // checkboxes disabled, so the user can see what Killit will manage first.
                KillitRow(
                    title = stringResource(R.string.home_manage_apps),
                    subtitle = if (state.isDeviceOwner) {
                        null
                    } else {
                        stringResource(R.string.home_manage_apps_locked)
                    },
                    icon = KillitIcons.Apps,
                    iconTint = if (state.isDeviceOwner) KillitGreen else KillitForeground,
                    borderColor = if (state.isDeviceOwner) KillitGreen else KillitBorderDim,
                    background = if (state.isDeviceOwner) {
                        KillitGreen.copy(alpha = 0.10f)
                    } else {
                        Color.Transparent
                    },
                    onClick = onManageApps,
                )

                if (state.isDeviceOwner) {
                    HardeningPanel(
                        config = state.hardening,
                        onChange = onHardeningChange,
                    )
                } else {
                    SetupSection(onOpen = { setupMethod = it })
                }

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    KillitRule()

                    if (state.isDeviceOwner) {
                        ReleaseSection(
                            state = state,
                            onRequestRelease = { confirmRelease = true },
                            onCancelRelease = onCancelRelease,
                            onReleaseNow = { confirmReleaseNow = true },
                        )
                    }

                    KillitRow(
                        title = stringResource(R.string.home_change_passkey),
                        icon = KillitIcons.Key,
                        onClick = onChangePasskey,
                    )
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

    if (confirmRelease) {
        KillitDialog(
            title = stringResource(R.string.home_release_confirm_title),
            body = stringResource(R.string.home_release_confirm_body),
            accent = KillitRed,
            onDismiss = { confirmRelease = false },
            confirmText = stringResource(R.string.home_release_start),
            onConfirm = {
                KillitLog.i(KillitLog.UI, "User started the release waiting period")
                confirmRelease = false
                onRequestRelease()
            },
            dismissText = stringResource(R.string.cancel),
        )
    }

    if (confirmReleaseNow) {
        KillitDialog(
            title = stringResource(R.string.home_release_now_confirm_title),
            body = stringResource(R.string.home_release_now_confirm_body),
            accent = KillitRed,
            onDismiss = { confirmReleaseNow = false },
            confirmText = stringResource(R.string.home_release_now),
            onConfirm = {
                KillitLog.i(KillitLog.UI, "User confirmed device owner release after the wait")
                confirmReleaseNow = false
                onReleaseDeviceOwner()
            },
            dismissText = stringResource(R.string.cancel),
        )
    }
}

/**
 * The headline panel: whether Killit is device owner, and how much it is currently blocking.
 *
 * Green or red throughout, because this one fact decides whether anything else on the screen can
 * take effect at all.
 *
 * @param state supplies owner status and the blocked count.
 * @param onRefresh re-checks owner status, for when the user has provisioned outside the app.
 */
@Composable
private fun StatusPanel(state: KillitUiState, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KillitStatusPanel(
            icon = KillitIcons.Shield,
            title = stringResource(
                if (state.isDeviceOwner) R.string.home_status_owner_yes
                else R.string.home_status_owner_no
            ),
            subtitle = stringResource(
                if (state.isDeviceOwner) R.string.home_status_owner_yes_desc
                else R.string.home_status_owner_no_desc
            ),
            accent = if (state.isDeviceOwner) KillitGreen else KillitRed,
        ) {
            if (state.isDeviceOwner) {
                KillitBody(
                    text = stringResource(R.string.home_blocked_count, state.blocked.size),
                    color = KillitForeground,
                )
            }
        }
        KillitTextButton(text = stringResource(R.string.prov_refresh), onClick = onRefresh)
    }
}

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

/**
 * The three states of giving up device owner: not requested, waiting, ready.
 *
 * The waiting state is the point of the whole feature, so it is shown as a panel with a live
 * countdown rather than a disabled button — the user should be able to see exactly how long is
 * left, and that cancelling is available at any time.
 *
 * @param state supplies the pending request, and whether an action is already in flight.
 * @param onRequestRelease starts the wait.
 * @param onCancelRelease abandons it.
 * @param onReleaseNow carries out the release, once the wait has elapsed.
 */
@Composable
private fun ReleaseSection(
    state: KillitUiState,
    onRequestRelease: () -> Unit,
    onCancelRelease: () -> Unit,
    onReleaseNow: () -> Unit,
) {
    val request = state.releaseRequest

    if (request == null) {
        KillitRow(
            title = stringResource(R.string.home_release),
            icon = KillitIcons.Lock,
            iconTint = KillitRed,
            accent = KillitRed,
            borderColor = KillitRed,
            enabled = !state.busy,
            showChevron = false,
            onClick = onRequestRelease,
        )
        return
    }

    // Re-reads the clock once a second so the countdown is live. Keyed on the deadline so a
    // cancel-then-request cycle restarts it rather than reusing a stale coroutine.
    var now by remember(request.availableAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request.availableAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= request.availableAtMillis) break
            delay(1_000)
        }
    }

    val remaining = request.remainingMillis(now)
    val ready = request.isReady(now)

    KillitPanel(borderColor = if (ready) KillitRed else KillitBlue, spacing = 16.dp) {
        KillitSectionTitle(
            stringResource(
                if (ready) R.string.home_release_ready_title else R.string.home_release_pending_title
            )
        )

        if (ready) {
            KillitBody(stringResource(R.string.home_release_ready_body))
            KillitDangerButton(
                text = stringResource(R.string.home_release_now),
                onClick = onReleaseNow,
                enabled = !state.busy,
            )
        } else {
            KillitBody(
                text = stringResource(
                    R.string.home_release_pending_body,
                    formatDuration(remaining),
                ),
                color = KillitForeground,
            )
        }

        KillitButton(
            text = stringResource(R.string.home_release_cancel),
            onClick = onCancelRelease,
            enabled = !state.busy,
        )
    }
}

/**
 * The anti-tamper toggles.
 *
 * @param config the toggles as stored.
 * @param onChange called with the full config whenever one toggle moves.
 */
@Composable
private fun HardeningPanel(
    config: HardeningConfig,
    onChange: (HardeningConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        KillitSectionTitle(stringResource(R.string.home_hardening))

        KillitPanel(padding = 18.dp, spacing = 0.dp) {
            KillitToggleRow(
                title = stringResource(R.string.harden_uninstall),
                description = stringResource(R.string.harden_uninstall_desc),
                checked = config.blockUninstall,
                onCheckedChange = { onChange(config.copy(blockUninstall = it)) },
            )
            KillitRule(color = KillitRowDivider)
            KillitToggleRow(
                title = stringResource(R.string.harden_force_stop),
                description = stringResource(R.string.harden_force_stop_desc),
                checked = config.blockForceStop,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                onCheckedChange = { onChange(config.copy(blockForceStop = it)) },
            )
            KillitRule(color = KillitRowDivider)
            KillitToggleRow(
                title = stringResource(R.string.harden_safe_boot),
                description = stringResource(R.string.harden_safe_boot_desc),
                checked = config.blockSafeBoot,
                onCheckedChange = { onChange(config.copy(blockSafeBoot = it)) },
            )
            KillitRule(color = KillitRowDivider)
            KillitToggleRow(
                title = stringResource(R.string.harden_factory_reset),
                description = stringResource(R.string.harden_factory_reset_desc),
                checked = config.blockFactoryReset,
                onCheckedChange = { onChange(config.copy(blockFactoryReset = it)) },
            )
            KillitRule(color = KillitRowDivider)
            KillitToggleRow(
                title = stringResource(R.string.harden_apps_control),
                description = stringResource(R.string.harden_apps_control_desc),
                checked = config.blockAppsControl,
                onCheckedChange = { onChange(config.copy(blockAppsControl = it)) },
            )
            KillitRule(color = KillitRowDivider)
            KillitToggleRow(
                title = stringResource(R.string.harden_date_time),
                description = stringResource(R.string.harden_date_time_desc),
                checked = config.blockDateTime,
                onCheckedChange = { onChange(config.copy(blockDateTime = it)) },
            )
        }
    }
}

/**
 * The language list.
 *
 * Each entry is written in its own language (see [AppLanguage]), so the list stays usable when the
 * app is currently showing one the reader cannot decipher — which is the whole situation the picker
 * exists for. It is capped and scrollable because ten entries plus a dismiss button will not fit on
 * a short screen, and a dialog that runs off the bottom hides the way out of itself.
 *
 * @param current the language in force, marked in the list.
 * @param onPick called with the chosen language.
 * @param onDismiss called when the dialog is dismissed without choosing.
 */
@Composable
private fun LanguagePicker(
    current: AppLanguage,
    onPick: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    KillitDialog(
        title = stringResource(R.string.home_language),
        onDismiss = onDismiss,
        dismissText = stringResource(R.string.cancel),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppLanguage.entries.forEach { language ->
                val selected = language == current
                KillitRow(
                    title = language.label(),
                    // The tick is always in the layout and only sometimes visible: dropping the
                    // icon on unselected rows would collapse its slot and indent the one selected
                    // label 48dp past every other.
                    icon = KillitIcons.Check,
                    iconTint = if (selected) KillitGreen else Color.Transparent,
                    borderColor = if (selected) KillitGreen else KillitBorderDim,
                    background = if (selected) KillitGreen.copy(alpha = 0.10f) else Color.Transparent,
                    showChevron = false,
                    onClick = { onPick(language) },
                )
            }
        }
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
