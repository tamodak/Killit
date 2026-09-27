package org.tamodak.killit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Fixed, dark, and not derived from the wallpaper.
 *
 * Dynamic colour is deliberately absent. Killit says "protected" in green and "exposed" in red,
 * and a wallpaper-derived scheme is free to map both onto neighbouring pastels — on a device whose
 * whole job is to tell the user whether their apps are actually locked, that is not a cosmetic
 * risk. There is no light scheme for the same reason: one palette means one set of contrast
 * figures to have checked, rather than two and a habit of only ever looking at one of them.
 *
 * Most of the UI is built from the components in `ui/components`, which take their colours from
 * `Color.kt` directly. This scheme exists for the Material widgets that survive —
 * `CircularProgressIndicator`, `BasicTextField`'s selection handles, ripples — so that they do not
 * arrive in Material's default purple.
 */
private val KillitColorScheme = darkColorScheme(
    primary = KillitGreen,
    onPrimary = KillitBackground,
    primaryContainer = KillitGreenTint,
    onPrimaryContainer = KillitGreen,
    secondary = KillitBlue,
    onSecondary = KillitBackground,
    tertiary = KillitBlue,
    onTertiary = KillitBackground,
    background = KillitBackground,
    onBackground = KillitForeground,
    surface = KillitBackground,
    onSurface = KillitForeground,
    surfaceVariant = KillitSurface,
    onSurfaceVariant = KillitTextMuted,
    surfaceContainer = KillitSurface,
    surfaceContainerHigh = KillitSurface,
    surfaceContainerHighest = KillitSurface,
    error = KillitRed,
    onError = KillitBackground,
    errorContainer = KillitRedTint,
    onErrorContainer = KillitRed,
    outline = KillitBorder,
    outlineVariant = KillitBorderMuted,
    scrim = KillitBackground,
)

/**
 * Every corner is square.
 *
 * [RoundedCornerShape] at zero rather than `RectangleShape`, which is not a `CornerBasedShape` and
 * so cannot be handed to [Shapes] at all. The effect is identical.
 */
private val Square = RoundedCornerShape(0.dp)

/** Every Material size slot mapped to [Square], so nothing rounds a corner behind our back. */
private val KillitShapes = Shapes(
    extraSmall = Square,
    small = Square,
    medium = Square,
    large = Square,
    extraLarge = Square,
)

/**
 * Wraps content in Killit's palette, type scale and shapes.
 *
 * Fixed dark. There is no light variant and no dynamic colour: the design depends on a near-black
 * background for its bordered, unfilled surfaces to read at all.
 *
 * @param content the UI to theme.
 */
@Composable
fun KillitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KillitColorScheme,
        typography = Typography,
        shapes = KillitShapes,
        content = content,
    )
}
