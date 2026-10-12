package org.tamodak.killit.protection

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.tamodak.killit.MainActivity
import org.tamodak.killit.R
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.core.NotificationChannels
import org.tamodak.killit.core.canPostNotifications
import org.tamodak.killit.data.AppLanguage

/**
 * The protection service's notifications: the ongoing one it runs under, and the one that says
 * new apps were blocked.
 *
 * Text is resolved in the language chosen inside Killit rather than the device's, through
 * [AppLanguage.applyTo], so a notification reads the same as the screen it opens.
 *
 * @param context any context; only its application context is retained.
 * @param language reads the language chosen inside Killit.
 * @param guarded whether this phone has guardians, who then decide about blocked apps.
 */
class ProtectionNotifications(
    context: Context,
    private val language: suspend () -> AppLanguage,
    private val guarded: suspend () -> Boolean,
) {

    /** Held rather than the passed context, so this class can outlive any activity. */
    private val appContext = context.applicationContext

    /** Posts and cancels the notifications. */
    private val manager = NotificationManagerCompat.from(appContext)

    /**
     * Creates every channel, or renames them.
     *
     * @param text where the names come from: the device's language by default, because a channel
     *   must exist before the service's first notification and reading Killit's language takes a
     *   disk read; [showInAppLanguage] renames them afterwards.
     */
    fun createChannels(text: Context = appContext) = NotificationChannels.create(appContext, text)

    /**
     * Builds the ongoing notification the protection service runs under.
     *
     * Its channel is the lowest importance there is: the notification exists because a foreground
     * service must have one, not to interrupt anyone.
     *
     * @param text where the text comes from; see [createChannels] for why the default is the
     *   device's language.
     * @return the notification to pass to `startForeground`.
     */
    fun protectionNotification(text: Context = appContext): Notification =
        NotificationCompat.Builder(appContext, NotificationChannels.PROTECTION)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(text.getString(R.string.notif_protection_title))
            .setContentText(text.getString(R.string.notif_protection_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(MainActivity.open(appContext, MainActivity.Destination.Start))
            .build()

    /**
     * Renames the channels and rewrites the service's notification in the language chosen inside
     * Killit, once that has been read.
     */
    suspend fun showInAppLanguage() {
        val text = localised()
        createChannels(text)
        if (appContext.canPostNotifications()) {
            // Checked just above; replacing a foreground service's notification is a plain notify.
            @Suppress("MissingPermission")
            runCatching { manager.notify(ID_PROTECTION, protectionNotification(text)) }
        }
    }

    /**
     * Shows which apps are blocked and waiting for a decision, replacing what the notification said
     * before, or removes it when nothing is waiting.
     *
     * One notification covers them all, however many arrive at once — restoring a backup can
     * install dozens (D10). On a phone with guardians it points at them, since only they can
     * unblock an app now.
     *
     * @param labels the waiting apps' names, newest first.
     * @param alert true when an app was just blocked, so the update makes a sound; false when the
     *   list merely got shorter.
     */
    suspend fun showWaitingApps(labels: List<String>, alert: Boolean) {
        if (labels.isEmpty()) {
            manager.cancel(ID_NEW_APPS)
            return
        }
        if (!appContext.canPostNotifications()) {
            KillitLog.d(KillitLog.GUARD) { "Notifications are off; not announcing ${labels.size} blocked apps" }
            return
        }
        val text = localised()
        val guarded = guarded()
        val builder = NotificationCompat.Builder(appContext, NotificationChannels.MY_PHONE)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(MainActivity.open(appContext, MainActivity.Destination.Apps))
        if (labels.size == 1) {
            builder
                .setContentTitle(text.getString(R.string.notif_blocked_one_title, labels.single()))
                .setContentText(text.getString(if (guarded) R.string.notif_blocked_one_text_guarded else R.string.notif_blocked_one_text))
        } else {
            val title = text.resources.getQuantityString(R.plurals.notif_blocked_many_title, labels.size, labels.size)
            builder
                .setContentTitle(title)
                .setContentText(text.getString(if (guarded) R.string.notif_blocked_many_text_guarded else R.string.notif_blocked_many_text))
                .setStyle(
                    NotificationCompat.InboxStyle()
                        .setBigContentTitle(title)
                        .also { style -> labels.take(MAX_LISTED).forEach(style::addLine) }
                )
        }
        // Checked just above; the platform can still refuse, and that is not an error.
        @Suppress("MissingPermission")
        runCatching { manager.notify(ID_NEW_APPS, builder.build()) }
            .onFailure { KillitLog.w(KillitLog.GUARD, "Posting the blocked-apps notification failed", it) }
    }

    /** @return a context whose strings are in the language chosen inside Killit. */
    private suspend fun localised(): Context = language().applyTo(appContext)

    companion object {
        /** The protection service's notification id; any value but zero. */
        const val ID_PROTECTION = 1

        /** The one blocked-apps notification, updated in place. */
        private const val ID_NEW_APPS = 2

        /** Lines in the expanded list; the system shows only so many anyway. */
        private const val MAX_LISTED = 6
    }
}
