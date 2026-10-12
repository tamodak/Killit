package org.tamodak.killit.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.tamodak.killit.R
import org.tamodak.killit.protocol.ICON_BYTES
import org.tamodak.killit.protocol.ICON_SIDE
import org.tamodak.killit.protocol.TextRule
import org.tamodak.killit.protocol.codePointCount
import org.tamodak.killit.protocol.encodeText
import org.tamodak.killit.protocol.toHex
import org.tamodak.killit.protocol.trimText
import org.tamodak.killit.ui.theme.KillitBorderDim
import org.tamodak.killit.ui.theme.KillitBorderMuted
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitGreen
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitTextFaint

/*
 * Widgets for the pairing screens, in the rules of Killit.kt: square, outlined, colour only where
 * it means something.
 */

/**
 * The colours a badge may take. Neither green nor red, which mean protected and exposed, and
 * distinct enough to tell two badges apart at a glance.
 */
private val BadgePalette = listOf(
    Color(0xFF5B8DEF),
    Color(0xFFA36BE0),
    Color(0xFFE59A3A),
    Color(0xFFE5C840),
    Color(0xFF3FC6D6),
    Color(0xFF7C83E8),
    Color(0xFFE06BB0),
    Color(0xFFB08A6A),
)

/**
 * A peer's badge (PLAN.md §3.2): a mirrored 5 × 5 pattern and a colour, both from its fingerprint.
 * Two phones with similar nicknames still look different, and the badge never changes unless the
 * key does. It is drawn from the fingerprint alone, which the peer can't choose.
 *
 * @param fp the peer's fingerprint.
 * @param size the badge's edge length.
 */
@Composable
fun PeerBadge(fp: ByteArray, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val key = fp.toHex()
    val (color, cells) = remember(key) { badgeOf(fp) }
    Box(modifier = modifier.size(size).border(2.dp, color, RectangleShape).padding(size / 8)) {
        Canvas(modifier = Modifier.size(size - size / 4)) {
            val cell = this.size.width / 5f
            for (row in 0 until 5) {
                for (column in 0 until 5) {
                    if (cells[row * 5 + column]) {
                        drawRect(color, topLeft = Offset(column * cell, row * cell), size = Size(cell, cell))
                    }
                }
            }
        }
    }
}

/** The badge's colour and its 25 cells, mirrored left to right. */
private fun badgeOf(fp: ByteArray): Pair<Color, BooleanArray> {
    val color = BadgePalette[(fp[0].toInt() and 0xff) % BadgePalette.size]
    val cells = BooleanArray(25)
    for (row in 0 until 5) {
        for (column in 0 until 3) {
            val on = (fp[1 + row * 3 + column].toInt() and 1) == 1
            cells[row * 5 + column] = on
            cells[row * 5 + (4 - column)] = on
        }
    }
    return color to cells
}

/**
 * An app's icon as a request carried it: 32 × 32 RGBA pixels, turned into a bitmap without any
 * image decoder (SPEC.md §11.2).
 *
 * @param rgba the pixels, or null for an app without one.
 */
@Composable
fun RemoteAppIcon(rgba: ByteArray?, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    val bitmap = remember(rgba?.toHex()) { rgba?.let(::bitmapOf) }
    if (bitmap == null) {
        KillitIconBox(icon = KillitIcons.Package, modifier = modifier, size = size)
    } else {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size))
    }
}

private fun bitmapOf(rgba: ByteArray): ImageBitmap? {
    if (rgba.size != ICON_BYTES) return null
    val pixels = IntArray(ICON_SIDE * ICON_SIDE) { i ->
        val r = rgba[4 * i].toInt() and 0xff
        val g = rgba[4 * i + 1].toInt() and 0xff
        val b = rgba[4 * i + 2].toInt() and 0xff
        val a = rgba[4 * i + 3].toInt() and 0xff
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, ICON_SIDE, ICON_SIDE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Two buttons where one choice weakens protection (PLAN.md §3.9, E1): the cautious one is wider,
 * 60 to 40, and comes first, which is the start side in right-to-left languages too.
 *
 * @param cautious the safe choice: Deny, Cancel, They don't match.
 * @param bold the choice that weakens or commits: Approve, Block, They match.
 * @param boldAccent the bold choice's colour.
 */
@Composable
fun CautiousChoice(
    cautious: String,
    onCautious: () -> Unit,
    bold: String,
    onBold: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    boldAccent: Color = KillitGreen,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KillitButton(
            text = cautious,
            onClick = onCautious,
            enabled = enabled,
            accent = KillitForeground,
            borderColor = KillitBorderMuted,
            modifier = Modifier.weight(0.6f),
        )
        KillitButton(
            text = bold,
            onClick = onBold,
            enabled = enabled,
            accent = boldAccent,
            borderColor = boldAccent,
            modifier = Modifier.weight(0.4f),
        )
    }
}

/**
 * A multi-line text box with a live count of code points against a text rule (SPEC.md §1.3):
 * explanations, notes, names.
 *
 * @param rule what the text must fit; the counter turns red outside it.
 */
@Composable
fun KillitTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    rule: TextRule,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 120.dp,
) {
    val count = codePointCount(trimText(value))
    val fits = textFits(value, rule)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The whole bordered box is the field, so a tap anywhere in it starts typing.
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = KillitForeground),
            cursorBrush = SolidColor(KillitGreen),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .border(3.dp, KillitBorderDim, RectangleShape),
            decorationBox = { field ->
                Box(modifier = Modifier.padding(14.dp)) {
                    if (value.isEmpty()) {
                        Text(text = placeholder, style = MaterialTheme.typography.bodyLarge, color = KillitTextFaint)
                    }
                    field()
                }
            },
        )
        Text(
            text = stringResource(R.string.text_counter, count, rule.maxCodePoints),
            style = MaterialTheme.typography.bodySmall,
            color = if (fits || value.isEmpty()) KillitTextFaint else KillitRed,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** True when [text], trimmed, can be sent under [rule]. */
fun textFits(text: String, rule: TextRule): Boolean = runCatching { encodeText(trimText(text), rule) }.isSuccess

/**
 * When something happened, for a list: "just now", "5 minutes ago", "3 hours ago", or the date.
 * Built from Killit's own strings in its own language, not the system's.
 */
@Composable
fun formatWhen(millis: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = (now - millis).coerceAtLeast(0L)
    val minutes = (elapsed / 60_000L).toInt()
    val hours = (elapsed / 3_600_000L).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.when_just_now)
        hours < 1 -> pluralStringResource(R.plurals.when_minutes_ago, minutes, minutes)
        hours < 24 -> pluralStringResource(R.plurals.when_hours_ago, hours, hours)
        else -> formatDate(millis)
    }
}

/** A date and time in the app's language. */
@Composable
fun formatDate(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

/** "Seen today", "Seen yesterday", "Seen 3 days ago" (PLAN.md §3.11); nothing when unknown. */
@Composable
fun formatLastSeen(days: Int?): String? = when {
    days == null -> null
    days <= 0 -> stringResource(R.string.last_seen_today)
    days == 1 -> stringResource(R.string.last_seen_yesterday)
    else -> pluralStringResource(R.plurals.last_seen_days, days, days)
}
