package org.tamodak.killit.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import org.tamodak.killit.R
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.ui.theme.KillitTextStrong
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a payload as a QR code for another phone to scan.
 *
 * Always black on white regardless of the app theme. A themed QR looks better but scans worse:
 * decoders expect a dark-on-light pattern with a quiet margin, and a dynamic-colour palette in
 * dark mode can drop the contrast below what a camera pointed at a glossy screen can resolve.
 * The code is framed in a white card so it keeps its quiet zone on a dark background.
 *
 * @param payload the text to encode.
 * @param contentDescription what the code is, for screen readers.
 * @param modifier applied to the card.
 */
@Composable
fun QrImage(
    payload: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    // Encoding is pure CPU work over a short string — sub-millisecond — but it runs on every
    // recomposition without this, and the countdown next to it recomposes once a second.
    val bitmap = remember(payload) { encodeQr(payload) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White)
            .border(3.dp, KillitTextStrong, RectangleShape)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(
                text = stringResource(R.string.pair_qr_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black,
            )
        } else {
            val side = with(LocalDensity.current) {
                val scale = (maxWidth.roundToPx() / bitmap.width).coerceAtLeast(1)
                (bitmap.width * scale).toDp()
            }
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.size(side),
                filterQuality = FilterQuality.None,
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

/**
 * Encodes [payload] to a 1-module-per-pixel bitmap, or null if ZXing refuses it.
 *
 * Error correction level M: roughly 15% of the code can be obscured and still decode, which is
 * what makes it forgiving of a fingerprint or a reflection on the other phone's screen. Higher
 * levels would need a denser code for the same payload, which is harder for an old camera to
 * resolve — and one of the target devices is a 2017 budget phone.
 */
private fun encodeQr(payload: String): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        // Quiet zone in modules. The spec asks for 4; ZXing defaults to a wider one that wastes
        // screen space we would rather spend on module size.
        EncodeHintType.MARGIN to 2,
    )

    // Size 0 lets ZXing choose the natural module count, so no scaling happens here.
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)

    val bitmap = createBitmap(matrix.width, matrix.height)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap[x, y] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    KillitLog.d(KillitLog.PAIRING) { "Encoded a ${matrix.width}-module QR from ${payload.length} chars" }
    bitmap.asImageBitmap()
}.getOrElse { error ->
    // Realistically only happens if a payload outgrows QR capacity, which would be a bug.
    KillitLog.e(KillitLog.PAIRING, "Could not encode a QR for a ${payload.length}-char payload", error)
    null
}
