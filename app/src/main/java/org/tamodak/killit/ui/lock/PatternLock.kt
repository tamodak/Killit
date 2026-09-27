package org.tamodak.killit.ui.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.ui.theme.KillitBorderMuted
import org.tamodak.killit.ui.theme.KillitGreen

/** Fewer dots than this is rejected when setting a passkey. */
const val MIN_PATTERN_DOTS = 4

private const val GRID = 3
private const val DOT_COUNT = GRID * GRID

/**
 * Serialises a drawn pattern as the visited dot indices in order, e.g. `"0-3-6-7-8"`.
 *
 * This string *is* the credential as far as hashing is concerned, so its format must never change
 * without invalidating every stored pattern.
 */
fun List<Int>.toPatternString(): String = joinToString(separator = "-")

/**
 * A 3x3 pattern grid.
 *
 * Submits when the finger lifts, which is how every pattern lock behaves — there is no separate
 * confirm button. That also means the caller cannot know a submission is coming, which is why
 * `AuthGateScreen` filters out too-short patterns rather than spending a lockout attempt on them.
 *
 * ### Known gap
 *
 * This is a bare [Canvas] with no accessibility semantics, so TalkBack cannot operate it. A user
 * who relies on a screen reader cannot set or enter a pattern and must choose PIN or password
 * instead. Worth fixing; noted here so it is not mistaken for an oversight.
 *
 * @param enabled false while a check is in flight.
 * @param onSubmit called on finger lift with the visited dot indices joined by `-`.
 * @param modifier applied to the grid.
 */
@Composable
fun PatternLock(
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // mutableStateListOf so appending a dot mid-drag triggers a redraw of the trail.
    val selected = remember { mutableStateListOf<Int>() }
    var fingerPosition by remember { mutableStateOf<Offset?>(null) }
    val haptics = LocalHapticFeedback.current

    val dotColor = KillitBorderMuted
    val activeColor = KillitGreen

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // Keyed on `enabled` so the gesture detector is torn down and rebuilt when the grid is
            // disabled mid-drag — during a lockout, for instance.
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val extent = size.width.toFloat()
                detectDragGestures(
                    onDragStart = { offset ->
                        selected.clear()
                        fingerPosition = offset
                        hitTest(offset, extent)?.let {
                            selected.add(it)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDrag = { change, _ ->
                        fingerPosition = change.position
                        val hit = hitTest(change.position, extent)
                        // `hit !in selected` is what stops a dot being counted twice when the
                        // finger passes back over it.
                        if (hit != null && hit !in selected) {
                            selected.add(hit)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDragEnd = {
                        fingerPosition = null
                        val drawn = selected.toList()
                        // Cleared before submitting so the trail vanishes immediately rather than
                        // lingering through the seconds that key derivation can take.
                        selected.clear()
                        if (drawn.isNotEmpty()) {
                            KillitLog.d(KillitLog.UI) { "Pattern drawn: ${drawn.size} dots; submitting" }
                            onSubmit(drawn.toPatternString())
                        } else {
                            KillitLog.v(KillitLog.UI) { "Pattern gesture ended without touching a dot" }
                        }
                    },
                    onDragCancel = {
                        KillitLog.v(KillitLog.UI) { "Pattern gesture cancelled" }
                        fingerPosition = null
                        selected.clear()
                    },
                )
            }
    ) {
        val extent = size.minDimension
        val strokeWidth = extent / 50f
        val dotSide = extent / 13f
        val borderWidth = extent / 90f

        // Trail between the dots already visited.
        for (i in 0 until selected.lastIndex) {
            drawLine(
                color = activeColor,
                start = dotCenter(selected[i], extent),
                end = dotCenter(selected[i + 1], extent),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square,
            )
        }

        // Live segment following the finger.
        val finger = fingerPosition
        if (finger != null && selected.isNotEmpty()) {
            drawLine(
                color = activeColor,
                start = dotCenter(selected.last(), extent),
                end = finger,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square,
            )
        }

        for (index in 0 until DOT_COUNT) {
            val isSelected = index in selected
            val center = dotCenter(index, extent)
            val topLeft = Offset(center.x - dotSide / 2f, center.y - dotSide / 2f)
            val square = Size(dotSide, dotSide)
            if (isSelected) {
                drawRect(color = activeColor, topLeft = topLeft, size = square)
            } else {
                drawRect(
                    color = dotColor,
                    topLeft = topLeft,
                    size = square,
                    style = Stroke(width = borderWidth),
                )
            }
        }
    }
}

/** Dots sit at 1/6, 3/6 and 5/6 of the square, so the grid is evenly inset on all sides. */
private fun dotCenter(index: Int, extent: Float): Offset {
    val step = extent / (GRID * 2f)
    val column = index % GRID
    val row = index / GRID
    return Offset(x = step * (column * 2 + 1), y = step * (row * 2 + 1))
}

/**
 * Generous hit radius (a third of the cell) so the pattern is drawable without tracing dot centres
 * exactly — a tight radius makes fast swipes skip dots and produce a different credential than the
 * user thought they drew.
 */
private fun hitTest(point: Offset, extent: Float): Int? {
    val radius = extent / 9f
    return (0 until DOT_COUNT).firstOrNull { index ->
        (point - dotCenter(index, extent)).getDistance() <= radius
    }
}
