package org.tamodak.killit.protection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.tamodak.killit.ServiceLocator
import org.tamodak.killit.core.KillitLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts protection after the phone boots and after Killit itself is updated.
 *
 * Both broadcasts are among the few a manifest receiver still gets, and both are moments when the
 * protection service is not running: a boot starts every process from nothing, and an update kills
 * the old one.
 *
 * `LOCKED_BOOT_COMPLETED` is not used. Before the user first unlocks, Killit's stores are still
 * encrypted and unreadable, and no app can be installed or opened, so `BOOT_COMPLETED` — sent right
 * after that unlock — is the first moment there is anything to do.
 */
class ProtectionStartReceiver : BroadcastReceiver() {

    /**
     * Starts protection in the background, keeping the broadcast alive until it has.
     *
     * @param context the receiver's context.
     * @param intent the broadcast; only its action is read.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        KillitLog.i(KillitLog.GUARD, "${intent.action}: starting protection")
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Protection.ensureRunning(context, ServiceLocator.packageGuard)
            } finally {
                pending.finish()
            }
        }
    }
}
