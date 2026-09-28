package org.tamodak.killit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Builds one text style, so the scale below reads as a table rather than a wall of named arguments.
 *
 * Line height defaults to 1.4x the size, which is what keeps the multi-line explanatory copy on the
 * provisioning and hardening screens legible. Material's ratios are tuned for shorter strings than
 * the ones this app has to show.
 *
 * No font is bundled: [FontFamily.Default] resolves to the device's own typeface, which is what a
 * utility app should do and one less thing in the APK.
 *
 * @param size font size in sp.
 * @param weight font weight.
 * @param lineHeight line height in sp.
 * @param letterSpacing tracking in sp.
 * @return the built style.
 */
private fun style(
    size: Int,
    weight: FontWeight,
    lineHeight: Int = (size * 1.4f).toInt(),
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

/**
 * One scale, deliberately larger and heavier than Material's.
 *
 * Body text starts at 17sp where Material's `bodyMedium` is 14sp, and every title slot is Bold
 * rather than Medium. These screens get read in situations that are already stressful — someone
 * checking whether their apps are still locked, or two people standing over a phone working out
 * which one of them is about to be locked out — and a scale that reads at arm's length is worth
 * more here than fitting more words on screen.
 *
 * Every slot is filled rather than inheriting, so a Material component that reaches for a style
 * this app never uses directly still comes out at the right weight.
 */
val Typography = Typography(
    displayLarge = style(34, FontWeight.Bold),
    displayMedium = style(30, FontWeight.Bold),
    displaySmall = style(28, FontWeight.Bold),
    headlineLarge = style(28, FontWeight.Bold),
    headlineMedium = style(26, FontWeight.Bold),
    headlineSmall = style(24, FontWeight.Bold, letterSpacing = 0.2f),
    titleLarge = style(22, FontWeight.Bold),
    titleMedium = style(20, FontWeight.Bold),
    titleSmall = style(19, FontWeight.Bold),
    bodyLarge = style(19, FontWeight.Normal),
    bodyMedium = style(17, FontWeight.Normal),
    bodySmall = style(15, FontWeight.Normal),
    labelLarge = style(19, FontWeight.Bold, lineHeight = 24),
    labelMedium = style(17, FontWeight.Bold, lineHeight = 22),
    labelSmall = style(15, FontWeight.Bold, lineHeight = 20),
)
