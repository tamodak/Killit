package org.tamodak.killit.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The whole palette, converted once from the OKLCH values the design was authored in.
 *
 * OKLCH is perceptually uniform, which is why the neutral ramp below reads as evenly spaced steps
 * rather than bunching up in the dark end the way an sRGB ramp does. Compose has no OKLCH colour
 * space, so the values are pre-converted rather than computed at runtime — there is nothing here
 * that changes, and a conversion in composition would cost more than it explains.
 *
 * [KillitBlue] is the one approximation: `oklch(75% 0.16 230)` falls outside the sRGB gamut, so it
 * is the same hue and lightness with the chroma reduced to 0.1495, the most saturated blue that
 * actually fits. Nudging lightness instead would have broken its contrast against the background.
 *
 * ### The three accents carry meaning
 *
 * Green is "protected", red is "exposed or destructive", blue is "informational". Nothing else in
 * the UI is coloured, so those three readings stay unambiguous — which is the reason
 * [KillitTheme] refuses dynamic colour.
 */

/** Page background. Near-black rather than grey, so an OLED panel can switch the pixels off. */
val KillitBackground = Color(0xFF090B0E)

/** Raised surfaces: dialogs, and the monospace blocks that hold a shell command. */
val KillitSurface = Color(0xFF121416)

/** Hairline between list rows, where a full divider would be too loud at every row. */
val KillitRowDivider = Color(0xFF1D2022)

/** Section divider and the rule under the header. */
val KillitDivider = Color(0xFF27292C)

/** Default border for panels, rows and buttons. */
val KillitBorderDim = Color(0xFF383B3E)

/** Material's `outline` slot. Not used directly by Killit's own components. */
val KillitBorder = Color(0xFF404345)

/** The header's back button, which sits on its own with nothing else to separate it from. */
val KillitBorderStrong = Color(0xFF45484B)

/** Unchecked checkboxes and secondary buttons — a step brighter, because both are targets. */
val KillitBorderMuted = Color(0xFF535659)

/** Supporting text: row subtitles, package names, toggle descriptions. */
val KillitTextFaint = Color(0xFF8C8F93)

/** Body copy. */
val KillitTextMuted = Color(0xFFABAEB2)

/** Emphasised body copy, and the frame around a QR code. */
val KillitTextStrong = Color(0xFFBBBEC1)

/** Titles, values, and anything the eye should land on first. */
val KillitForeground = Color(0xFFF3F5F8)

/** Protected, active, confirmed. */
val KillitGreen = Color(0xFF43B966)

/** Exposed, destructive, or irreversible. */
val KillitRed = Color(0xFFE0615C)

/** Informational: step numbers and setup routes. Never a state. */
val KillitBlue = Color(0xFF00BEFA)

/**
 * Fills for accented surfaces. 12% is deliberately low: it has to read as a tint at a glance
 * without lifting the surface far enough to compete with the border that defines the shape.
 */
val KillitGreenTint = KillitGreen.copy(alpha = 0.12f)

/** The [KillitRed] counterpart, on the same 12% rule. */
val KillitRedTint = KillitRed.copy(alpha = 0.12f)
