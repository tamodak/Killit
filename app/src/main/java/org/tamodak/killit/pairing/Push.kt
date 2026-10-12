package org.tamodak.killit.pairing

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.tamodak.killit.core.KillitLog

/**
 * Push (PLAN.md §7, SPEC.md §9): the server's FCM data message only ever means "read the mailbox
 * now". Its content is never read, so a forged or replayed push can at most make this phone sync
 * (§6.2 rule 6).
 *
 * FCM reaches a phone by its Firebase Installation ID, which FCM hands over in
 * [PushService.onRegistered]; the next sync passes it on to the server. It works in a build that
 * has a Firebase project (app/google-services.json) on a phone with Google Play services; anywhere
 * else the background sync is all there is. FCM isn't contacted until this phone first pairs: a
 * phone that never pairs never registers.
 *
 * @param context any context; only its application context is kept.
 * @param prefs the id FCM gave and the one the server has.
 * @param upload sends an id to the server (`PUT /v1/devices/me/push`).
 */
class Push(
    context: Context,
    private val prefs: PairingPreferences,
    private val upload: suspend (String) -> Unit,
) {
    private val appContext = context.applicationContext

    /** True when this build has a Firebase project to receive pushes through. */
    val available: Boolean get() = FirebaseApp.getApps(appContext).isNotEmpty()

    /**
     * Makes sure FCM has registered this phone and the server knows the id. Cheap once both are
     * true: nothing is sent unless the id changed.
     *
     * The first call starts the registration, whose id arrives at [received]; from then on FCM
     * keeps the registration current by itself.
     *
     * @throws java.io.IOException when the server can't be reached; the next sync tries again.
     */
    suspend fun register() {
        if (!available) return
        val messaging = FirebaseMessaging.getInstance()
        if (!messaging.isAutoInitEnabled) messaging.isAutoInitEnabled = true
        val fid = prefs.fid()
        if (fid == null) {
            messaging.register()
            return
        }
        if (fid == prefs.sentFid()) return
        upload(fid)
        prefs.setSentFid(fid)
        // The id itself is never logged: with the server's FCM credential it reaches this phone.
        KillitLog.i(KillitLog.SYNC, "Push registration sent to the server")
    }

    /** FCM registered this phone under [fid]: remembered until a sync sends it on. */
    suspend fun received(fid: String) = prefs.setFid(fid)

    /** FCM dropped the registration: the next sync registers again. */
    suspend fun dropped() = prefs.setFid(null)
}
