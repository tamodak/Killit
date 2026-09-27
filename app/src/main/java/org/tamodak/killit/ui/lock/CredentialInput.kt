package org.tamodak.killit.ui.lock

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.tamodak.killit.data.LockType

/**
 * Dispatches to the right input for [lockType].
 *
 * All three emit the same normalised string, so hashing and verification have a single code path
 * and neither the repository nor [org.tamodak.killit.data.CredentialStore] has any idea which
 * method produced the value it is checking.
 *
 * @param enabled false while a verification is in flight or during a lockout.
 * @param onSubmit called with the normalised credential; the input clears itself first.
 */
@Composable
fun CredentialInput(
    lockType: LockType,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (lockType) {
        LockType.PIN -> PinPad(enabled = enabled, onSubmit = onSubmit, modifier = modifier)
        LockType.PASSWORD -> PasswordEntry(enabled = enabled, onSubmit = onSubmit, modifier = modifier)
        LockType.PATTERN -> PatternLock(enabled = enabled, onSubmit = onSubmit, modifier = modifier)
    }
}

/**
 * Whether a submitted value is long enough to be accepted.
 *
 * Used in two places with different intent: setup rejects a short value with an error message,
 * while the gate silently ignores one — a stray two-dot swipe there is a slip, not a guess, and
 * should not cost a lockout attempt.
 *
 * For a pattern the length is the *dot count*, not the string length: `"0-3-6-7"` is four dots but
 * seven characters.
 */
fun LockType.isLongEnough(value: String): Boolean = when (this) {
    LockType.PIN -> value.length >= MIN_PIN_LENGTH
    LockType.PASSWORD -> value.length >= MIN_PASSWORD_LENGTH
    LockType.PATTERN -> value.split("-").filter { it.isNotBlank() }.size >= MIN_PATTERN_DOTS
}
