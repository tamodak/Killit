package org.tamodak.killit.ui.myphone

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.tamodak.killit.R
import org.tamodak.killit.admin.HardeningConfig
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.AppLanguage
import org.tamodak.killit.ui.KillitUiState
import org.tamodak.killit.ui.LanguagePicker
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitDangerButton
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitRow
import org.tamodak.killit.ui.components.KillitRule
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitToggleRow
import org.tamodak.killit.ui.formatDuration
import org.tamodak.killit.ui.label
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitRowDivider

/**
 * My phone's settings (PLAN.md §15.5): the protection options, the language, the passkey, and,
 * last and in red, giving up device owner. Only a session with full control gets here.
 *
 * @param onHardeningChange called with the full config whenever one toggle moves.
 * @param onRequestRelease starts the wait before device owner can be given up.
 * @param onCancelRelease abandons that wait.
 * @param onReleaseDeviceOwner carries out the release, once the wait has elapsed.
 */
@Composable
fun SettingsScreen(
    state: KillitUiState,
    onBack: () -> Unit,
    onHardeningChange: (HardeningConfig) -> Unit,
    onChangePasskey: () -> Unit,
    onRequestRelease: () -> Unit,
    onCancelRelease: () -> Unit,
    onReleaseDeviceOwner: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    // Two separate confirmations: one to start the wait, another to actually release once it is
    // over. The second is the irreversible one.
    var confirmRelease by remember { mutableStateOf(false) }
    var confirmReleaseNow by remember { mutableStateOf(false) }
    var pickingLanguage by remember { mutableStateOf(false) }
    val guarded = (state.guardianCount ?: 0) > 0

    BackHandler(onBack = onBack)
    KillitScreen(title = stringResource(R.string.my_phone_settings), onBack = onBack) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            HardeningPanel(config = state.hardening, guarded = guarded, onChange = onHardeningChange)

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                KillitRow(
                    title = stringResource(R.string.home_language),
                    subtitle = state.language.label(),
                    icon = KillitIcons.Globe,
                    onClick = { pickingLanguage = true },
                )
                KillitRow(
                    title = stringResource(R.string.home_change_passkey),
                    icon = KillitIcons.Key,
                    onClick = onChangePasskey,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                KillitRule()
                ReleaseSection(
                    state = state,
                    guarded = guarded,
                    onRequestRelease = { confirmRelease = true },
                    onCancelRelease = onCancelRelease,
                    onReleaseNow = { confirmReleaseNow = true },
                )
            }
        }
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

    if (confirmRelease) {
        KillitDialog(
            title = stringResource(R.string.home_release_confirm_title),
            body = stringResource(R.string.home_release_confirm_body) +
                if (guarded) "\n\n" + stringResource(R.string.release_guardians_note) else "",
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
 * The three states of giving up device owner: not requested, waiting, ready.
 *
 * The waiting state is the point of the whole feature, so it is shown as a panel with a live
 * countdown rather than a disabled button — the user should be able to see exactly how long is
 * left, and that cancelling is available at any time.
 *
 * @param state supplies the pending request, and whether an action is already in flight.
 * @param guarded whether guardians will be removed and told when the release completes (H7).
 * @param onRequestRelease starts the wait.
 * @param onCancelRelease abandons it.
 * @param onReleaseNow carries out the release, once the wait has elapsed.
 */
@Composable
private fun ReleaseSection(
    state: KillitUiState,
    guarded: Boolean,
    onRequestRelease: () -> Unit,
    onCancelRelease: () -> Unit,
    onReleaseNow: () -> Unit,
) {
    val request = state.releaseRequest

    if (request == null) {
        KillitRow(
            title = stringResource(R.string.home_release),
            subtitle = if (guarded) stringResource(R.string.release_guardians_note) else null,
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
 * While anyone guards this phone the date and time stay locked (I1): the UNPAIR timer and the
 * release delay are measured against the clock.
 *
 * @param config the toggles as stored.
 * @param guarded whether this phone has guardians.
 * @param onChange called with the full config whenever one toggle moves.
 */
@Composable
private fun HardeningPanel(
    config: HardeningConfig,
    guarded: Boolean,
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
                description = stringResource(if (guarded) R.string.harden_date_time_guarded else R.string.harden_date_time_desc),
                checked = config.blockDateTime || guarded,
                enabled = !guarded,
                onCheckedChange = { onChange(config.copy(blockDateTime = it)) },
            )
        }
    }
}

