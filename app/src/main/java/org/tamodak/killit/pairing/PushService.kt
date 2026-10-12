package org.tamodak.killit.pairing

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.tamodak.killit.ServiceLocator
import org.tamodak.killit.core.KillitLog

/**
 * Receives FCM (SPEC.md §9). Every message, whatever it holds, means one thing: sync now. Nothing
 * in it is read, so it can't carry an instruction (PLAN.md §6.2 rule 6).
 *
 * A high-priority message lets Killit use the network for a short while even in Doze, so the sync
 * runs right here; whatever doesn't finish in that window is left to the background sync.
 */
// Lint still asks for onNewToken, which firebase-messaging 26 deprecated for onRegistered below.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        KillitLog.d(KillitLog.SYNC) { "Push received" }
        val synced = runBlocking {
            withTimeoutOrNull(SYNC_WINDOW_MILLIS) {
                try {
                    ServiceLocator.pairing.sync()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    KillitLog.d(KillitLog.SYNC) { "Sync on push failed: ${e.message}" }
                    false
                }
            }
        }
        if (synced != true) PairingSync.soon(applicationContext)
    }

    /** FCM registered this phone, or registered it anew: the next sync tells the server. */
    override fun onRegistered(fid: String) {
        runBlocking { ServiceLocator.push.received(fid) }
        PairingSync.soon(applicationContext)
    }

    override fun onUnregistered(fid: String) {
        runBlocking { ServiceLocator.push.dropped() }
        PairingSync.soon(applicationContext)
    }

    private companion object {
        /** Within the few seconds FCM allows, leaving time to stop cleanly. */
        const val SYNC_WINDOW_MILLIS = 15_000L
    }
}
