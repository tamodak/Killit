package org.tamodak.killit.ui.pairing

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.tamodak.killit.R
import org.tamodak.killit.pairing.store.CeremonyEntity
import org.tamodak.killit.pairing.store.CeremonyFailure
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.decodeReveal
import org.tamodak.killit.protocol.fingerprint
import org.tamodak.killit.protocol.formatSafetyCode
import org.tamodak.killit.ui.PairingUiState
import org.tamodak.killit.ui.components.CautiousChoice
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitCheckbox
import org.tamodak.killit.ui.components.KillitError
import org.tamodak.killit.ui.components.KillitField
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.components.KillitPrimaryButton
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.components.KillitSectionTitle
import org.tamodak.killit.ui.components.KillitTextButton
import org.tamodak.killit.ui.components.PeerBadge
import org.tamodak.killit.ui.components.textFits
import org.tamodak.killit.ui.formatDuration
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitTextFaint

/**
 * The pairing wizard (PLAN.md §15.7), shared by both sides; the role comes from where it was
 * started. Every step after the first is drawn from the stored ceremony, so the wizard picks up
 * where it was when the user comes back from the call that compares the codes.
 *
 * @param isDeviceOwner whether this phone may be guarded; the protected side needs device owner.
 * @param appManagement whether this phone lets its guardians manage its apps, which the protected
 *   side states before pairing so it knows what the guardian will be able to do (G3).
 * @param follow keeps up with the other phone while the wizard is shown.
 * @param onOpenSetup goes to My phone, to set up device owner first.
 */
@Composable
fun WizardScreen(
    role: PairRole,
    isDeviceOwner: Boolean,
    appManagement: Boolean,
    pairing: PairingUiState,
    onBack: () -> Unit,
    onStart: (String, String?) -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onNickname: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenSetup: () -> Unit,
    follow: suspend () -> Unit,
) {
    LaunchedEffect(Unit) { follow() }
    val ceremony = pairing.ceremony
    val running = ceremony != null && ceremony.stage != CeremonyStage.PAIRED && ceremony.stage != CeremonyStage.FAILED
    val leave = {
        if (running) onCancel()
        if (ceremony != null && !running) onDismiss()
        onBack()
    }
    BackHandler(onBack = leave)

    val title = stringResource(if (role == PairRole.GUARDIAN) R.string.guard_new else R.string.guardians_add)
    KillitScreen(title = title, onBack = leave) {
        Column(
            modifier = Modifier.padding(start = KillitGutter, end = KillitGutter, top = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when {
                ceremony == null -> Intro(role, isDeviceOwner, appManagement, pairing, onStart, onOpenSetup)
                else -> Step(ceremony, pairing, onConfirm, onReject, onCancel, onNickname, onDismiss, onBack)
            }
        }
    }
}

/** Step 1: what the role means, the warning on the protected side (G14), a name, and a code. */
@Composable
private fun Intro(
    role: PairRole,
    isDeviceOwner: Boolean,
    appManagement: Boolean,
    pairing: PairingUiState,
    onStart: (String, String?) -> Unit,
    onOpenSetup: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(pairing.selfName) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var entering by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    val protectedSide = role == PairRole.PROTECTED

    KillitBody(
        stringResource(if (protectedSide) R.string.wizard_intro_protected else R.string.wizard_intro_guardian),
        color = KillitForeground,
    )

    if (protectedSide && !isDeviceOwner) {
        KillitPanel(borderColor = KillitRed, spacing = 10.dp) {
            KillitBody(stringResource(R.string.wizard_needs_owner), color = KillitForeground)
            KillitButton(text = stringResource(R.string.wizard_set_up), onClick = onOpenSetup)
        }
        return
    }

    if (protectedSide) {
        KillitBody(stringResource(if (appManagement) R.string.wizard_management_on else R.string.wizard_management_off))
    }

    if (protectedSide) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(3.dp, if (accepted) KillitGreen else KillitRed, RectangleShape)
                .clickable { accepted = !accepted }
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.wizard_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = KillitForeground,
                modifier = Modifier.weight(1f),
            )
            KillitCheckbox(checked = accepted, size = 28.dp)
        }
    }

    KillitSectionTitle(stringResource(R.string.wizard_your_name))
    KillitField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.wizard_your_name_hint))
    val ready = (!protectedSide || accepted) && textFits(name, TextRule.SELF_NAME) && !pairing.busy

    if (!entering) {
        KillitPrimaryButton(text = stringResource(R.string.wizard_show_code), onClick = { onStart(name, null) }, enabled = ready)
        KillitButton(text = stringResource(R.string.wizard_have_code), onClick = { entering = true }, enabled = ready)
    } else {
        KillitSectionTitle(stringResource(R.string.wizard_enter_code))
        KillitField(
            value = code,
            onValueChange = { code = toAsciiCode(it) },
            placeholder = "XXXX-XXXX",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        )
        KillitPrimaryButton(text = stringResource(R.string.wizard_join), onClick = { onStart(name, code) }, enabled = ready && code.isNotBlank())
        KillitTextButton(text = stringResource(R.string.wizard_show_code_instead), onClick = { entering = false })
    }
}

/**
 * Maps what a keyboard typed into the ASCII the code parser accepts (SPEC.md §6.1): a Turkish
 * dotless `ı` or dotted `İ` becomes `I`; anything else outside ASCII is dropped and so refused.
 */
private fun toAsciiCode(text: String): String = buildString {
    for (char in text) {
        when (char) {
            'ı', 'İ' -> append('I')
            in ' '..'~' -> append(char)
            else -> Unit
        }
    }
}.take(32)

/** Steps 2–5, from the stored ceremony. */
@Composable
private fun Step(
    ceremony: CeremonyEntity,
    pairing: PairingUiState,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onNickname: (String) -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
) {
    when (ceremony.stage) {
        CeremonyStage.WAITING_FOR_JOINER -> ShowCode(ceremony, onCancel)
        CeremonyStage.WAITING_FOR_JOINER_REVEAL, CeremonyStage.WAITING_FOR_CREATOR ->
            Waiting(stringResource(R.string.wizard_connecting), onCancel)
        CeremonyStage.COMPARING -> Compare(ceremony, pairing.busy, onConfirm, onReject)
        CeremonyStage.CONFIRMED -> Waiting(stringResource(R.string.wizard_waiting_confirm), onCancel)
        CeremonyStage.LINKED -> Waiting(stringResource(R.string.wizard_finishing), onCancel)
        CeremonyStage.PAIRED -> Paired(ceremony, pairing, onNickname, onDismiss, onBack)
        CeremonyStage.FAILED -> Failed(ceremony.failure, onDismiss)
    }
}

/** Step 2, the side that shows the code: big, with its lifetime and a way to send it. */
@Composable
private fun ShowCode(ceremony: CeremonyEntity, onCancel: () -> Unit) {
    val context = LocalContext.current
    val code = ceremony.code.orEmpty()
    KillitBody(stringResource(R.string.wizard_give_code), color = KillitForeground)
    Text(
        text = code,
        style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 40.sp),
        color = KillitForeground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Countdown(until = ceremony.expiresAt)
    val shareText = stringResource(R.string.wizard_share_text, code)
    KillitButton(
        text = stringResource(R.string.wizard_share),
        onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
            context.startActivity(Intent.createChooser(send, null))
        },
    )
    Waiting(stringResource(R.string.wizard_waiting_joiner), onCancel)
}

/** Step 3: the safety code, compared on a call. "They don't match" is the wider button. */
@Composable
private fun Compare(ceremony: CeremonyEntity, busy: Boolean, onConfirm: () -> Unit, onReject: () -> Unit) {
    KillitPanel(borderColor = KillitBlue, spacing = 8.dp) {
        KillitSectionTitle(stringResource(R.string.wizard_call_title))
        KillitBody(stringResource(R.string.wizard_call_body))
    }
    Text(
        text = formatSafetyCode(ceremony.safetyCode.orEmpty()),
        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
        color = KillitForeground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    CautiousChoice(
        cautious = stringResource(R.string.wizard_no_match),
        onCautious = onReject,
        bold = stringResource(R.string.wizard_match),
        onBold = onConfirm,
        enabled = !busy,
    )
}

/**
 * Step 4 and 5: paired. The nickname is this phone's alone (A5), starting as the peer's own name.
 * A guardian also learns whether the phone lets it manage its apps (G3).
 */
@Composable
private fun Paired(ceremony: CeremonyEntity, pairing: PairingUiState, onNickname: (String) -> Unit, onDismiss: () -> Unit, onBack: () -> Unit) {
    val peerName = ceremony.peerName.orEmpty()
    var nickname by rememberSaveable(ceremony.inviteId) { mutableStateOf(ceremony.nickname ?: peerName) }
    val peerFp = remember(ceremony.inviteId) { ceremony.peerReveal?.let { decodeReveal(it).valueOrNull() }?.let { fingerprint(it.bundle.signPk) } }
    KillitPanel(borderColor = KillitGreen, spacing = 8.dp) {
        KillitSectionTitle(stringResource(R.string.wizard_paired_title))
        KillitBody(
            stringResource(if (ceremony.myRole == PairRole.GUARDIAN) R.string.wizard_paired_guardian else R.string.wizard_paired_protected, peerName),
            color = KillitForeground,
        )
        val manages = ceremony.myRole == PairRole.GUARDIAN &&
            pairing.pairings.firstOrNull { it.pairId.contentEquals(ceremony.pairId ?: ByteArray(0)) }?.appManagement == true
        if (manages) KillitBody(stringResource(R.string.wizard_paired_management, peerName), color = KillitForeground)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (peerFp != null) PeerBadge(fp = peerFp)
        Column(modifier = Modifier.weight(1f)) {
            KillitField(value = nickname, onValueChange = { nickname = it }, placeholder = stringResource(R.string.rename_hint))
        }
    }
    KillitBody(stringResource(R.string.wizard_nickname_note))
    KillitPrimaryButton(
        text = stringResource(R.string.wizard_done),
        onClick = {
            if (nickname.trim() != peerName) onNickname(nickname)
            onDismiss()
            onBack()
        },
        enabled = textFits(nickname, TextRule.SELF_NAME),
    )
}

/** How a ceremony ended without a pairing, in words a person can act on. */
@Composable
private fun Failed(failure: CeremonyFailure?, onDismiss: () -> Unit) {
    val text = stringResource(
        when (failure) {
            CeremonyFailure.MISMATCH -> R.string.wizard_failed_mismatch
            CeremonyFailure.EXPIRED -> R.string.wizard_failed_expired
            CeremonyFailure.CODE_NOT_FOUND -> R.string.wizard_failed_code
            CeremonyFailure.INVALID -> R.string.wizard_failed_invalid
            CeremonyFailure.ROLE_CONFLICT -> R.string.wizard_failed_roles
            CeremonyFailure.DECLINED -> R.string.wizard_failed_declined
            CeremonyFailure.LINK_LIMIT -> R.string.wizard_failed_limit
            CeremonyFailure.CANCELLED, null -> R.string.wizard_failed_cancelled
        },
    )
    KillitPanel(borderColor = KillitRed) { KillitBody(text, color = KillitForeground) }
    KillitButton(text = stringResource(R.string.wizard_again), onClick = onDismiss)
}

@Composable
private fun Waiting(text: String, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = KillitGreen, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        KillitBody(text, color = KillitForeground)
    }
    KillitTextButton(text = stringResource(R.string.cancel), onClick = onCancel)
}

/** The invite's remaining lifetime, counting down. */
@Composable
private fun Countdown(until: Long) {
    var now by remember(until) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (now < until) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    if (now < until) {
        Text(
            text = stringResource(R.string.wizard_code_expires, formatDuration(until - now)),
            style = MaterialTheme.typography.bodySmall,
            color = KillitTextFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        KillitError(stringResource(R.string.wizard_failed_expired))
    }
}
