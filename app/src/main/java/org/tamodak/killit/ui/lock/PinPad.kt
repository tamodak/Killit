package org.tamodak.killit.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.ui.theme.KillitBorderDim
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitTextStrong

const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8

/** Key edge length. Comfortably past the 48dp minimum touch target, since this is thumb-typed. */
private val KEY_SIZE = 74.dp

/** Gap between keys, wide enough that a mis-tap lands on nothing rather than the wrong digit. */
private val KEY_GAP = 14.dp

/**
 * Numeric keypad.
 *
 * Submits on the tick rather than auto-submitting at a fixed length, because a PIN here can be
 * anywhere from [MIN_PIN_LENGTH] to [MAX_PIN_LENGTH] digits and there is no way to tell when the
 * user is finished.
 *
 * The entry lives in local state and is cleared before [onSubmit] fires, so the dots disappear the
 * instant the user commits rather than sitting on screen through key derivation.
 *
 * @param enabled false while a check is in flight.
 * @param onSubmit called with the entered digits.
 * @param modifier applied to the pad.
 */
@Composable
fun PinPad(
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var entry by remember { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PinDots(length = entry.length)

        Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
            ).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    row.forEach { digit ->
                        PinKey(label = digit, enabled = enabled) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (entry.length < MAX_PIN_LENGTH) entry += digit
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                // material-icons-core has no backspace glyph, and this reads unambiguously.
                PinKey(
                    label = "⌫",
                    enabled = enabled && entry.isNotEmpty(),
                    onClick = { entry = entry.dropLast(1) },
                )

                PinKey(label = "0", enabled = enabled) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (entry.length < MAX_PIN_LENGTH) entry += "0"
                }

                ConfirmKey(enabled = enabled && entry.length >= MIN_PIN_LENGTH) {
                    val value = entry
                    entry = ""
                    KillitLog.d(KillitLog.UI) { "PIN submitted (${value.length} digits)" }
                    onSubmit(value)
                }
            }
        }
    }
}

/**
 * One digit key.
 *
 * @param label the digit.
 * @param enabled false fades border and label, and stops the key responding.
 * @param onClick appends the digit.
 */
@Composable
private fun PinKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    val accent = if (enabled) KillitForeground else KillitForeground.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(KEY_SIZE)
            .border(3.dp, if (enabled) KillitBorderDim else KillitBorderDim.copy(alpha = 0.5f), RectangleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
        )
    }
}

/**
 * The submit key, green once the entry is long enough to be accepted.
 *
 * @param enabled false while the entry is below [MIN_PIN_LENGTH], or while a check is in flight.
 * @param onClick submits the entry.
 */
@Composable
private fun ConfirmKey(enabled: Boolean, onClick: () -> Unit) {
    val accent = if (enabled) KillitGreen else KillitBorderDim.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .size(KEY_SIZE)
            .background(if (enabled) KillitGreen.copy(alpha = 0.12f) else Color.Transparent)
            .border(3.dp, accent, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = KillitIcons.Check,
            contentDescription = "Confirm",
            tint = accent,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * The filled squares showing how many digits have been entered.
 *
 * Shows a count, never the digits themselves — the same reason the field is masked.
 */
@Composable
private fun PinDots(length: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(40.dp)
            .padding(vertical = 8.dp),
    ) {
        repeat(MIN_PIN_LENGTH.coerceAtLeast(length)) { index ->
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(if (index < length) KillitForeground else Color.Transparent)
                    .border(3.dp, KillitTextStrong, RectangleShape)
            )
        }
    }
}
