package org.tamodak.killit.ui.tamper

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.admin.KillitDeviceAdminReceiver
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.ui.components.KillitBody
import org.tamodak.killit.ui.components.KillitButton
import org.tamodak.killit.ui.components.KillitGutter
import org.tamodak.killit.ui.components.KillitScreen
import org.tamodak.killit.ui.theme.KillitForeground
import org.tamodak.killit.ui.theme.KillitIcons
import org.tamodak.killit.ui.theme.KillitRed
import org.tamodak.killit.ui.theme.KillitSurface

/**
 * Shown when apps are still blocked but there is no passkey left to verify against: "Clear data"
 * wiped the local copy and durable storage holds no record either.
 *
 * Killit deliberately refuses to unblock anything here. Falling back to "no passkey means anyone
 * can get in" would hand the apps to whoever cleared the data, which is exactly the attack.
 *
 * The only way out is therefore from a computer, removing the admin over adb — which is why this
 * screen is nothing but that command and a copy button. It is a dead end by design: if there were
 * an in-app escape, it would be the bypass.
 */
@Composable
fun TamperedScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val command = remember(context) {
        "adb shell dpm remove-active-admin " +
            KillitDeviceAdminReceiver.componentName(context).flattenToString()
    }

    KillitScreen(title = stringResource(R.string.tamper_title), modifier = modifier) {
        Column(
            modifier = Modifier.padding(
                start = KillitGutter,
                end = KillitGutter,
                top = 28.dp,
                bottom = 40.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Icon(
                imageVector = KillitIcons.Warning,
                contentDescription = null,
                tint = KillitRed,
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = stringResource(R.string.tamper_title),
                style = MaterialTheme.typography.titleLarge,
                color = KillitRed,
                textAlign = TextAlign.Center,
            )
            KillitBody(
                text = stringResource(R.string.tamper_body),
                color = KillitForeground,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KillitSurface)
                    .padding(16.dp),
            ) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = KillitForeground,
                )
            }
            KillitButton(
                text = stringResource(R.string.copy),
                onClick = {
                    KillitLog.i(KillitLog.UI, "Recovery command copied from the tampered screen")
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Killit", command))
                },
            )
        }
    }
}
