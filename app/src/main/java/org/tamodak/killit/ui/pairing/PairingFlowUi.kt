package org.tamodak.killit.ui.pairing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.data.CameraFacing
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitPanel
import org.tamodak.killit.ui.theme.KillitBlue
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed

/** Ceiling on the displayed QR. Past this a code gains nothing and starts crowding the screen. */
private val QR_MAX_WIDTH = 420.dp

/** Ceiling on the camera preview, so the viewfinder stays a panel rather than the whole screen. */
private val SCANNER_MAX_WIDTH = 480.dp

/**
 * The heading on each step of a pairing flow: position, title, instruction.
 *
 * @param step the current step, one-based.
 * @param of how many steps the flow has.
 * @param title what this step is.
 * @param instruction what to do on it.
 */
@Composable
internal fun FlowStepHeader(
    step: Int,
    of: Int,
    title: String,
    instruction: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.pair_step_indicator, step, of),
            style = MaterialTheme.typography.labelSmall,
            color = KillitBlue,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = KillitForeground,
            textAlign = TextAlign.Center,
        )
        KillitBody(text = instruction, textAlign = TextAlign.Center)
    }
}

/**
 * States which of the two phones ends up locked.
 *
 * The phone that *scans* is the phone that gets locked, and that is the one thing users reliably
 * get backwards — it reads like an administrative action you would perform on the parent's phone.
 * Getting it wrong silently produces a working pairing on the wrong device, so the consequence is
 * repeated on every screen of the flow rather than stated once on the hub.
 *
 * @param locksThisPhone true on the scanning side, false on the side merely showing its code.
 */
@Composable
internal fun LockDirectionBanner(locksThisPhone: Boolean, modifier: Modifier = Modifier) {
    val accent = if (locksThisPhone) KillitRed else KillitBlue
    KillitPanel(
        modifier = modifier,
        borderColor = accent,
        padding = 16.dp,
        spacing = 8.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (locksThisPhone) KillitIcons.Lock else KillitIcons.Devices,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = stringResource(
                    if (locksThisPhone) R.string.pair_locks_this_phone
                    else R.string.pair_locks_other_phone
                ),
                style = MaterialTheme.typography.titleSmall,
                color = accent,
            )
        }
        KillitBody(
            text = stringResource(
                if (locksThisPhone) R.string.pair_locks_this_phone_desc
                else R.string.pair_locks_other_phone_desc
            ),
        )
    }
}

/**
 * A QR code sized for another phone's camera to read across a table.
 *
 * Turns the screen up and keeps it awake for as long as it is composed — see
 * [KeepScreenBrightAndOn].
 *
 * @param payload the encoded text to render.
 * @param contentDescription what the code is, for screen readers.
 * @param modifier applied to the code.
 */
@Composable
internal fun BigQrPanel(
    payload: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    KeepScreenBrightAndOn()
    QrImage(
        payload = payload,
        contentDescription = contentDescription,
        modifier = modifier.widthIn(max = QR_MAX_WIDTH),
    )
}

/**
 * The camera viewfinder, sized for the pairing flows.
 *
 * @param onScanned called with the decoded text of every code the camera reads.
 * @param cameraFacing which camera to open.
 * @param onCameraFacingChange called when the user switches cameras.
 * @param modifier applied to the scanner.
 */
@Composable
internal fun ScannerPanel(
    onScanned: (String) -> Unit,
    cameraFacing: CameraFacing,
    onCameraFacingChange: (CameraFacing) -> Unit,
    modifier: Modifier = Modifier,
) {
    QrScanner(
        onScanned = onScanned,
        facing = cameraFacing,
        onFacingChange = onCameraFacingChange,
        modifier = modifier.widthIn(max = SCANNER_MAX_WIDTH),
    )
}

/**
 * Holds the screen at full brightness and awake while a QR code is being shown.
 *
 * Both matter for the same reason: the code has to be read by another phone's camera, and a dim or
 * sleeping screen simply will not scan. Both are restored on dispose, so the user's own brightness
 * setting survives the flow.
 *
 * A no-op when there is no hosting activity to reach — a preview, for instance.
 */
@Composable
internal fun KeepScreenBrightAndOn() {
    val window = LocalContext.current.findActivity()?.window ?: return

    DisposableEffect(window) {
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.also { it.screenBrightness = 1f }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window.attributes = window.attributes.also { it.screenBrightness = previous }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** Unwraps [ContextWrapper]s to find the hosting Activity, or null if there is none. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
