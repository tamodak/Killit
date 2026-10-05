package org.tamodak.killit.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.data.AppEntry
import org.tamodak.killit.ui.KillitUiState
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitCheckbox
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitField
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitIconBox
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitRule
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTabs
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.components.KillitToggleRow
import org.tamodak.killit.ui.theme.KillitBackground
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitDivider
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitRowDivider
import org.tamodak.killit.ui.theme.KillitTextFaint

/**
 * The core screen: check apps to block, uncheck to unblock, press Save.
 *
 * Every app is checkable. Android refuses to suspend some packages, but which ones is internal
 * and varies by version and OEM, so there is no attempt to predict it — Save reports whatever the
 * system rejected and puts those checkboxes back.
 *
 * The list is split into three sections: new apps Killit blocked by itself and nobody has decided
 * about yet, apps that are blocked, and apps that are allowed. A row's section follows what is
 * applied rather than its checkbox, so a row stays where it is while the user ticks it and only
 * moves once the change is saved. Unticking a new app is how it gets allowed; "Keep blocked" moves
 * the rest of them to the blocked section.
 *
 * @param state supplies the inventory, the applied set, the pending selection and the waiting apps.
 * @param onToggle ticks or clears one row's checkbox.
 * @param onSave pushes the pending selection to the OS.
 * @param onDiscard throws the pending selection away.
 * @param onBack leaves the screen.
 * @param onDismissFailures closes the dialog listing packages Android refused.
 * @param onKeepBlocked keeps every waiting new app blocked.
 * @param iconLoader decodes a row's icon on demand, so only visible rows cost anything.
 * @param modifier applied to the screen.
 */
@Composable
fun AppListScreen(
    state: KillitUiState,
    onToggle: (String, Boolean) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
    onDismissFailures: () -> Unit,
    onKeepBlocked: () -> Unit,
    iconLoader: suspend (String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAllPackages by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    /** Unsaved ticks are easy to lose by reflex, so back asks before discarding them. */
    fun attemptBack() {
        if (state.hasPendingChanges) confirmDiscard = true else onBack()
    }

    BackHandler { attemptBack() }

    // Keyed on everything the filter reads, so a recomposition caused by an unrelated state change
    // (busy, a toast, a checkbox) reuses the previous list instead of re-filtering hundreds of
    // entries. Deliberately excludes `selection`, which changes on every tick.
    val visibleApps = remember(state.apps, query, selectedTab, showAllPackages) {
        state.apps.filter { app ->
            val matchesTab = app.isSystem == (selectedTab == 1)
            val matchesVisibility = showAllPackages || app.isLaunchable
            val matchesQuery = query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            matchesTab && matchesVisibility && matchesQuery
        }
    }

    // Recomputed only when what is applied changes, never on a tick: see the KDoc above.
    val sections = remember(visibleApps, state.blocked, state.waitingApps) {
        val (blocked, allowed) = visibleApps.partition { it.packageName in state.blocked }
        val (waiting, rest) = blocked.partition { it.packageName in state.waitingApps }
        Triple(waiting, rest, allowed)
    }

    KillitScreen(
        title = stringResource(R.string.apps_title),
        modifier = modifier,
        onBack = ::attemptBack,
        scrollable = false,
        bottomBar = {
            if (state.hasPendingChanges) {
                SaveBar(
                    pending = state.pendingChanges,
                    enabled = !state.busy && state.isDeviceOwner,
                    onSave = onSave,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = KillitGutter, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.isDeviceOwner) {
                KillitPanel(borderColor = KillitRed) {
                    KillitBody(stringResource(R.string.apps_needs_owner))
                }
            }

            KillitField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.apps_search),
                leadingIcon = KillitIcons.Search,
            )

            KillitTabs(
                titles = listOf(
                    stringResource(R.string.apps_tab_user),
                    stringResource(R.string.apps_tab_system),
                ),
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )

            KillitToggleRow(
                title = stringResource(R.string.apps_show_all),
                checked = showAllPackages,
                onCheckedChange = { showAllPackages = it },
            )
        }

        KillitRule()

        when {
            state.appsLoading -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = KillitGreen) }

            visibleApps.isEmpty() -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                KillitBody(
                    text = stringResource(R.string.apps_empty),
                    modifier = Modifier.padding(KillitGutter),
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = KillitGutter, vertical = 8.dp),
            ) {
                val (waiting, blocked, allowed) = sections
                val rowsEnabled = state.isDeviceOwner && !state.busy
                listOf(
                    Triple(SECTION_WAITING, R.string.apps_section_new, waiting),
                    Triple(SECTION_BLOCKED, R.string.apps_section_blocked, blocked),
                    Triple(SECTION_ALLOWED, R.string.apps_section_allowed, allowed),
                ).forEach { (key, title, apps) ->
                    if (apps.isEmpty()) return@forEach
                    item(key = key) {
                        SectionHeader(
                            title = stringResource(title),
                            count = apps.size,
                            action = if (key == SECTION_WAITING) {
                                { KillitTextButton(
                                    text = stringResource(R.string.apps_keep_blocked),
                                    onClick = onKeepBlocked,
                                    color = KillitBlue,
                                    enabled = rowsEnabled,
                                ) }
                            } else {
                                null
                            },
                        )
                    }
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            isNew = key == SECTION_WAITING,
                            checked = app.packageName in state.selection,
                            enabled = rowsEnabled,
                            onCheckedChange = { onToggle(app.packageName, it) },
                            iconLoader = iconLoader,
                        )
                    }
                }
            }
        }
    }

    if (state.saveFailures.isNotEmpty()) {
        KillitDialog(
            title = stringResource(R.string.apps_failed_title),
            body = stringResource(R.string.apps_failed_body),
            accent = KillitRed,
            onDismiss = onDismissFailures,
            dismissText = stringResource(R.string.ok),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.saveFailures.forEach { packageName ->
                    KillitBody(
                        text = "• ${state.labelFor(packageName)}",
                        color = KillitForeground,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        KillitDialog(
            title = stringResource(R.string.apps_discard_title),
            body = stringResource(R.string.apps_discard_body),
            accent = KillitRed,
            onDismiss = { confirmDiscard = false },
            confirmText = stringResource(R.string.discard),
            onConfirm = {
                confirmDiscard = false
                onDiscard()
                onBack()
            },
            dismissText = stringResource(R.string.cancel),
        )
    }
}

/**
 * The title above one section of the list, with how many apps it holds.
 *
 * @param title the section's name.
 * @param count how many apps it lists.
 * @param action an optional control at the end of the line, such as "Keep blocked".
 */
@Composable
private fun SectionHeader(title: String, count: Int, action: (@Composable () -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KillitSectionTitle(text = title)
        KillitBody(text = count.toString(), color = KillitTextFaint, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/**
 * The pinned bar that appears once the picker holds unsaved changes.
 *
 * Shows the count rather than just a button, so the user can tell at a glance whether the pending
 * set is what they meant before committing it.
 *
 * @param pending how many checkboxes differ from what is applied.
 * @param enabled false while a save is already in flight.
 * @param onSave pushes the selection to the OS.
 */
@Composable
private fun SaveBar(pending: Int, enabled: Boolean, onSave: () -> Unit) {
    Column {
        KillitRule(color = KillitDivider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KillitBackground)
                .padding(horizontal = KillitGutter, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KillitBody(
                text = pluralStringResource(R.plurals.apps_save_pending, pending, pending),
                color = KillitForeground,
                modifier = Modifier.weight(1f),
            )
            KillitPrimaryButton(
                text = stringResource(R.string.save),
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One package in the list: icon, label, package name, checkbox.
 *
 * The whole row is the touch target and the checkbox is inert, so a mis-tap anywhere on the row
 * still toggles it.
 *
 * @param app the package this row represents.
 * @param isNew marks an app Killit blocked by itself that nobody has decided about yet.
 * @param checked whether it is selected for blocking.
 * @param enabled false while a save is in flight.
 * @param onCheckedChange called with the new state when the row is tapped.
 * @param iconLoader decodes this row's icon. Passed as a loader rather than a decoded bitmap so
 *   only rows that reach the screen cost anything.
 */
@Composable
private fun AppRow(
    app: AppEntry,
    isNew: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconLoader: suspend (String) -> ImageBitmap?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIcon(packageName = app.packageName, iconLoader = iconLoader)

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = KillitForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isNew) NewMark()
                }
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = KillitTextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            KillitCheckbox(checked = checked, enabled = enabled)
        }
        KillitRule(color = KillitRowDivider)
    }
}

/**
 * The small "New" label beside a waiting app's name.
 *
 * Square and outlined like every other control here; blue, the colour Killit uses for anything
 * still waiting on a decision.
 */
@Composable
private fun NewMark() {
    Text(
        text = stringResource(R.string.apps_new_chip),
        style = MaterialTheme.typography.labelSmall,
        color = KillitBlue,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, KillitBlue, RectangleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Loads one icon lazily, per row.
 *
 * [produceState] keyed on the package name starts the load when the row scrolls into view and
 * cancels it when the row leaves — so scrolling fast never queues hundreds of decodes. The slot is
 * reserved up front so rows do not reflow as icons arrive.
 *
 * `contentDescription = null` is correct here: the label and package name sit right next to the
 * icon, so announcing it too would just repeat them.
 */
@Composable
private fun AppIcon(packageName: String, iconLoader: suspend (String) -> ImageBitmap?) {
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = iconLoader(packageName)
    }
    val bitmap = icon
    if (bitmap == null) {
        KillitIconBox(icon = KillitIcons.Package)
    } else {
        Box(
            modifier = Modifier
                .size(42.dp)
                .border(2.dp, KillitRowDivider, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(30.dp))
        }
    }
}

/** Keys of the three section headers, distinct from any package name a row is keyed by. */
private const val SECTION_WAITING = "section:new"
private const val SECTION_BLOCKED = "section:blocked"
private const val SECTION_ALLOWED = "section:allowed"

/** Resolves a package name to "Label (com.pkg)", falling back to the bare name if it is unknown. */
private fun KillitUiState.labelFor(packageName: String): String =
    apps.firstOrNull { it.packageName == packageName }?.let { "${it.label} ($packageName)" }
        ?: packageName
