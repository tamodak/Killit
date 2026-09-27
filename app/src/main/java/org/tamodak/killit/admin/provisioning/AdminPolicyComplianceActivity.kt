package org.tamodak.killit.admin.provisioning

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.tamodak.killit.R
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.ui.theme.KillitTheme

/**
 * Shown once by the system at the end of QR provisioning, so the DPC can confirm the device is
 * in a compliant state. Killit has nothing to enforce at this point — the user still has to set a
 * passkey and choose apps — so this just acknowledges and hands control back.
 *
 * Guarded by `BIND_DEVICE_ADMIN` in the manifest so only the system can start it. Reaching this
 * screen is the clearest signal that QR provisioning succeeded end to end.
 */
class AdminPolicyComplianceActivity : ComponentActivity() {

    /**
     * Shows the acknowledgement screen and returns `RESULT_OK` when the user continues.
     *
     * @param savedInstanceState standard Android instance state; unused, since the screen holds
     *   nothing worth restoring.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KillitLog.i(KillitLog.ADMIN, "ADMIN_POLICY_COMPLIANCE shown — QR provisioning completed")
        // Matches MainActivity. Nothing secret is shown here, but every Killit window being
        // screenshot-proof is what stops a challenge QR from being relayed to someone who is not
        // in the room — the property companion pairing depends on.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            KillitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.compliance_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.compliance_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = {
                            KillitLog.d(KillitLog.ADMIN) { "Compliance acknowledged; returning RESULT_OK" }
                            setResult(Activity.RESULT_OK)
                            finish()
                        }) {
                            Text(stringResource(R.string.compliance_continue))
                        }
                    }
                }
            }
        }
    }
}
