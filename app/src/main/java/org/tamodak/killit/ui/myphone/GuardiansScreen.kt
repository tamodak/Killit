package org.tamodak.killit.ui.myphone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.toHex
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitDialog
import org.tamodak.killit.ui.components.KillitField
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitRow
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitTextArea
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.components.PeerBadge
import org.tamodak.killit.ui.components.formatDate
import org.tamodak.killit.ui.components.formatLastSeen
import org.tamodak.killit.ui.components.textFits
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitBorderDim
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitTextFaint

/**
 * This phone's guardians (PLAN.md §15.4): who they are, when they were last seen, adding one, and
 * asking to remove one — which always follows the UNPAIR rules, unlocked or not (G1).
 *
 * @param canAddGuardian false before Killit is device owner: only a device owner can be guarded.
 */
@Composable
fun GuardiansScreen(
    pairing: PairingUiState,
    canAddGuardian: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onAskRemove: (String) -> Unit,
    onWithdraw: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    BackHandler(onBack = onBack)
    var renaming by remember { mutableStateOf<PairingEntity?>(null) }
    val guardians = pairing.guardians

    KillitScreen(title = stringResource(R.string.my_phone_guardians), onBack = onBack) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (guardians.isEmpty()) KillitBody(stringResource(R.string.guardians_none))
            guardians.forEach { guardian ->
                val removal = pairing.outgoing.firstOrNull {
                    it.request.state.isOpen && it.request.type == RequestType.UNPAIR &&
                        it.request.targetPairId?.contentEquals(guardian.pairId) == true
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(3.dp, if (removal != null) KillitBlue else KillitBorderDim, RectangleShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PeerHeader(guardian)
                    if (removal != null) {
                        KillitBody(
                            stringResource(R.string.guardians_removal_pending, formatDate(removal.request.timerEndsAt ?: 0L)),
                            color = KillitForeground,
                        )
                        KillitButton(text = stringResource(R.string.ask_withdraw), onClick = { onWithdraw(removal.request.requestId.toHex()) })
                    } else if (guardian.unpairChances > 0) {
                        KillitButton(text = stringResource(R.string.guardians_ask_remove), onClick = { onAskRemove(guardian.pairId.toHex()) })
                    } else {
                        KillitBody(stringResource(R.string.guardians_no_chances), color = KillitRed)
                    }
                    KillitTextButton(text = stringResource(R.string.rename), onClick = { renaming = guardian })
                }
            }

            when {
                guardians.size == 1 -> KillitBody(stringResource(R.string.guardians_hint_second))
                guardians.size >= 4 -> KillitBody(stringResource(R.string.guardians_hint_many))
            }
            KillitRow(
                title = stringResource(R.string.guardians_add),
                subtitle = if (canAddGuardian) null else stringResource(R.string.guardians_add_needs_owner),
                icon = KillitIcons.Plus,
                enabled = canAddGuardian,
                onClick = onAdd,
            )
        }
    }

    renaming?.let { pairingToRename ->
        RenameDialog(
            current = pairingToRename.nickname,
            onDismiss = { renaming = null },
            onSave = {
                onRename(pairingToRename.pairId.toHex(), it)
                renaming = null
            },
        )
    }
}

/** A peer: badge, nickname, its own name when different, model and last seen. */
@Composable
internal fun PeerHeader(peer: PairingEntity) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        PeerBadge(fp = peer.peerFp)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = peer.nickname, style = MaterialTheme.typography.titleMedium, color = KillitForeground)
            val details = listOfNotNull(
                peer.peerName.takeIf { it != peer.nickname },
                peer.peerModel.takeIf { it.isNotEmpty() },
                formatLastSeen(peer.lastSeenDaysAgo),
            )
            if (details.isNotEmpty()) {
                Text(text = details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = KillitTextFaint)
            }
        }
    }
}

/** Renames a peer on this phone only (A5): the other phone never sees the nickname. */
@Composable
internal fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    KillitDialog(title = stringResource(R.string.rename), onDismiss = onDismiss, dismissText = stringResource(R.string.cancel)) {
        KillitField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.rename_hint))
        KillitPrimaryButton(text = stringResource(R.string.save), onClick = { onSave(name) }, enabled = textFits(name, TextRule.SELF_NAME))
    }
}

/**
 * Asking to remove a guardian (§3.3 UNPAIR, B1–B3, G8). Every guardian is asked, the one to be
 * removed included. All approve: removed at once. One denies: cancelled, and a chance is used.
 * Nobody denies within 30 days: removed by this phone's own clock.
 */
@Composable
fun AskRemoveScreen(
    pairIdHex: String,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onSend: (String, String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val target = pairing.pairing(pairIdHex)
    var explanation by rememberSaveable(pairIdHex) { mutableStateOf("") }
    KillitScreen(title = stringResource(R.string.guardians_ask_remove), onBack = onBack) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (target == null) {
                KillitBody(stringResource(R.string.guardians_gone))
                return@Column
            }
            PeerHeader(target)
            KillitPanel(borderColor = KillitBorderDim, spacing = 8.dp) {
                KillitBody(stringResource(R.string.unpair_rules), color = KillitForeground)
            }
            KillitBody(pluralStringResource(R.plurals.ask_chances_left, target.unpairChances, target.unpairChances), color = KillitForeground)
            KillitTextArea(
                value = explanation,
                onValueChange = { explanation = it },
                placeholder = stringResource(R.string.unpair_hint),
                rule = TextRule.REQUIRED_EXPLANATION,
            )
            KillitPrimaryButton(
                text = stringResource(R.string.ask_send),
                onClick = { onSend(pairIdHex, explanation) },
                enabled = !pairing.busy && target.unpairChances > 0 && textFits(explanation, TextRule.REQUIRED_EXPLANATION),
            )
        }
    }
}
