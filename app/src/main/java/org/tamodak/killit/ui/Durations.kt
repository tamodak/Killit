package org.tamodak.killit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.tamodak.killit.R
import java.util.concurrent.TimeUnit

/**
 * Formats a remaining duration for display, e.g. `"2d 4h"`, `"3h 12m"`, `"1m 30s"`, `"45s"`.
 *
 * Shared by the two places that count down: the gate's lockout after too many wrong attempts
 * (seconds to minutes) and the wait before device owner can be given up (up to days). Only the two
 * largest units are shown — with days to go, seconds are noise.
 *
 * Floors at one second so a sub-second remainder never reads as `"0s"`, which would look like the
 * wait is over when it is not.
 *
 * `@Composable` because the unit letters come from `strings.xml`: they are the one part of a
 * countdown that differs between languages, and a Russian reader should not be told "2d 4h".
 *
 * @param millis the remaining duration.
 * @return the formatted string, with at most two units.
 */
@Composable
internal fun formatDuration(millis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis).coerceAtLeast(1)

    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60

    return when {
        days > 0 -> stringResource(R.string.duration_d_h, days, hours)
        hours > 0 -> stringResource(R.string.duration_h_m, hours, minutes)
        minutes > 0 -> stringResource(R.string.duration_m_s, minutes, seconds)
        else -> stringResource(R.string.duration_s, seconds)
    }
}
