package org.tamodak.killit.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.tamodak.killit.R
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.CameraFacing
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitTabs
import org.tamodak.killit.ui.theme.KillitBorderDim
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.client.android.BeepManager
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

internal const val SCANNER_ASPECT_RATIO = 3f / 4f

private const val REPEAT_DELAY_MILLIS = 1_500L

private const val ANY_CAMERA = -1

/**
 * An embedded QR scanner.
 *
 * ### Why this is not ZXing's own scanning activity
 *
 * The obvious integration launches `CaptureActivity` and waits for a result. That cannot be used
 * here: starting another activity backgrounds Killit, which fires `ON_STOP`, which runs
 * `KillitViewModel.lockOnBackground` and re-locks the session — destroying the very unlock flow the
 * scan was part of. Hosting the preview inside the existing composition keeps Killit in the
 * foreground throughout.
 *
 * ### Duplicate scans
 *
 * The decoder fires continuously while a code is in frame, so the same QR arrives many times a
 * second. [onScanned] is called at most once per [REPEAT_DELAY_MILLIS] for a given payload.
 *
 * @param onScanned invoked with the raw decoded text. Callers parse it with `QrPayload.decode`,
 *   which returns null for the many non-Killit codes a camera will happily read.
 */
@Composable
fun QrScanner(
    onScanned: (String) -> Unit,
    facing: CameraFacing,
    onFacingChange: (CameraFacing) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // Only reached when the device-owner self-grant did not apply — an unprovisioned device, or a
    // platform that refused. The prompt is a system window, so the caller must be holding the
    // session open (busy) while it is up.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        KillitLog.i(KillitLog.PAIRING, "Camera permission ${if (granted) "granted" else "denied"}")
        hasPermission = granted
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(SCANNER_ASPECT_RATIO)
                .border(3.dp, KillitBorderDim, RectangleShape)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            KillitButton(
                text = stringResource(R.string.pair_camera_permission),
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        }
        return
    }

    val facings = remember { availableFacings() }

    // rememberUpdatedState so the long-lived BarcodeCallback below always calls the current
    // lambda; capturing it directly would pin the first composition's callback forever.
    val currentOnScanned by rememberUpdatedState(onScanned)
    var lastPayload by remember { mutableStateOf<String?>(null) }
    var lastScanAt by remember { mutableLongStateOf(0L) }

    val barcodeView = remember {
        DecoratedBarcodeView(context).apply {
            // QR only. Leaving every format enabled makes the decoder noticeably slower and
            // invites it to lock on to a barcode that happens to be in shot.
            barcodeView.decoderFactory = DefaultDecoderFactory(
                listOf(BarcodeFormat.QR_CODE),
                mapOf(DecodeHintType.TRY_HARDER to true),
                null,
                0,
            )
            barcodeView.cameraSettings.apply {
                isAutoFocusEnabled = true
                isContinuousFocusEnabled = true
                isMeteringEnabled = true
                isExposureEnabled = true
            }
            setStatusText("")

            // BeepManager needs a real Activity. LocalContext is usually one, but it can be a
            // ContextWrapper depending on how the composition is hosted, and a blind cast would
            // crash the scanner rather than merely lose the confirmation sound.
            val beeps = context.findActivity()?.let { BeepManager(it) }

            decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    val text = result.text ?: return
                    val now = SystemClock.elapsedRealtime()
                    if (text == lastPayload && now - lastScanAt < REPEAT_DELAY_MILLIS) return
                    lastPayload = text
                    lastScanAt = now
                    KillitLog.d(KillitLog.PAIRING) { "Scanned ${text.length} chars" }
                    beeps?.playBeepSoundAndVibrate()
                    currentOnScanned(text)
                }
            })
        }
    }

    // The camera must be released whenever this leaves the screen, or it stays held and every
    // later scan opens on a black preview.
    DisposableEffect(barcodeView, facing) {
        barcodeView.barcodeView.cameraSettings.requestedCameraId = cameraIdFor(facing)
        lastPayload = null
        barcodeView.resume()
        onDispose {
            KillitLog.d(KillitLog.PAIRING) { "Releasing the camera" }
            barcodeView.pause()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (facings.size > 1) {
            CameraPicker(
                facings = facings,
                selected = facing,
                onSelect = onFacingChange,
            )
        }
        AndroidView(
            factory = { barcodeView },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(SCANNER_ASPECT_RATIO)
                .border(3.dp, KillitBorderDim, RectangleShape),
        )
    }
}

/**
 * Tabs for switching between the cameras this device actually has.
 *
 * @param facings the cameras present, in [CameraFacing] order.
 * @param selected the camera currently open.
 * @param onSelect called with the camera that was chosen.
 */
@Composable
private fun CameraPicker(
    facings: List<CameraFacing>,
    selected: CameraFacing,
    onSelect: (CameraFacing) -> Unit,
) {
    KillitTabs(
        titles = facings.map { option ->
            stringResource(
                when (option) {
                    CameraFacing.BACK -> R.string.scan_camera_back
                    CameraFacing.FRONT -> R.string.scan_camera_front
                }
            )
        },
        selected = facings.indexOf(selected).coerceAtLeast(0),
        onSelect = { onSelect(facings[it]) },
    )
}

/**
 * Enumerates which cameras this device has.
 *
 * Asked rather than assumed: a tablet may have no front camera, and offering a tab that opens
 * nothing is worse than offering one tab. Every platform call is guarded, so a device that refuses
 * to answer degrades to "no cameras" and the picker disappears rather than the screen failing.
 *
 * @return the cameras present, in [CameraFacing] declaration order so the tabs never reshuffle.
 */
@Suppress("DEPRECATION")
private fun availableFacings(): List<CameraFacing> {
    val info = android.hardware.Camera.CameraInfo()
    val found = buildSet {
        repeat(runCatching { android.hardware.Camera.getNumberOfCameras() }.getOrDefault(0)) { id ->
            runCatching { android.hardware.Camera.getCameraInfo(id, info) }.onSuccess {
                when (info.facing) {
                    android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK -> add(CameraFacing.BACK)
                    android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT -> add(CameraFacing.FRONT)
                }
            }
        }
    }
    KillitLog.d(KillitLog.PAIRING) { "Cameras available: $found" }
    return CameraFacing.entries.filter { it in found }
}

/**
 * Resolves a facing to the camera id the platform wants.
 *
 * @param facing the camera to open.
 * @return its id, or [ANY_CAMERA] when no camera reports that facing — which lets the scanner open
 *   *something* rather than nothing on a device whose camera info is unreadable.
 */
@Suppress("DEPRECATION")
private fun cameraIdFor(facing: CameraFacing): Int {
    val target = when (facing) {
        CameraFacing.BACK -> android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK
        CameraFacing.FRONT -> android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
    }
    val info = android.hardware.Camera.CameraInfo()
    val count = runCatching { android.hardware.Camera.getNumberOfCameras() }.getOrDefault(0)
    for (id in 0 until count) {
        runCatching { android.hardware.Camera.getCameraInfo(id, info) }.onSuccess {
            if (info.facing == target) return id
        }
    }
    return ANY_CAMERA
}
