package org.tamodak.killit.protection

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.tamodak.killit.MainActivity
import org.tamodak.killit.R
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.AppLanguage

/**
 * Killit's notifications: the ongoing one the protection service runs under, and the one that
 * says new apps were blocked.
 *
 * Text is resolved in the language chosen inside Killit rather than the device's, through
 * [AppLanguage.applyTo], so a notification reads the same as the screen it opens.
 *
 * @param context any context; only its application context is retained.
 * @param language reads the language chosen inside Killit.
 */
class ProtectionNotifications(
    context: Context,
    private val language: suspend () -> AppLanguage,
) {

    /** Held rather than the passed context, so this class can outlive any activity. */
    private val appContext = context.applicationContext

    /** Posts and cancels the notifications. */
    private val manager = NotificationManagerCompat.from(appContext)

    /**
     * Creates both channels, or renames them.
     *
     * Safe to repeat: the system only updates a channel's name and description, and keeps every
     * setting the user changed on it.
     *
     * @param text where the names come from: the device's language by default, because a channel
     *   must exist before the service's first notification and reading Killit's language takes a
     *   disk read; [showInAppLanguage] renames them afterwards.
     */
    fun createChannels(text: Context = appContext) {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_PROTECTION, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName(text.getString(R.string.notif_channel_protection))
                    .setDescription(text.getString(R.string.notif_channel_protection_desc))
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_NEW_APPS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(text.getString(R.string.notif_channel_new_apps))
                    .setDescription(text.getString(R.string.notif_channel_new_apps_desc))
                    .build(),
            )
        )
    }

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
        NotificationCompat.Builder(appContext, CHANNEL_PROTECTION)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(text.getString(R.string.notif_protection_title))
            .setContentText(text.getString(R.string.notif_protection_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openKillit(destination = null))
            .build()

    /**
     * Renames the channels and rewrites the service's notification in the language chosen inside
     * Killit, once that has been read.
     */
    suspend fun showInAppLanguage() {
        val text = localised()
        createChannels(text)
        if (canNotify()) {
            // Checked by canNotify; replacing a foreground service's notification is a plain notify.
            @Suppress("MissingPermission")
            runCatching { manager.notify(ID_PROTECTION, protectionNotification(text)) }
        }
    }

    /**
     * Shows which apps are blocked and waiting for a decision, replacing what the notification said
     * before, or removes it when nothing is waiting.
     *
     * One notification covers them all, however many arrive at once — restoring a backup can
     * install dozens.
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
        if (!canNotify()) {
            KillitLog.d(KillitLog.GUARD) { "Notifications are off; not announcing ${labels.size} blocked apps" }
            return
        }
        val text = localised()
        val builder = NotificationCompat.Builder(appContext, CHANNEL_NEW_APPS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(openKillit(destination = MainActivity.DESTINATION_APPS))
        if (labels.size == 1) {
            builder
                .setContentTitle(text.getString(R.string.notif_blocked_one_title, labels.single()))
                .setContentText(text.getString(R.string.notif_blocked_one_text))
        } else {
            val title = text.resources.getQuantityString(R.plurals.notif_blocked_many_title, labels.size, labels.size)
            builder
                .setContentTitle(title)
                .setContentText(text.getString(R.string.notif_blocked_many_text))
                .setStyle(
                    NotificationCompat.InboxStyle()
                        .setBigContentTitle(title)
                        .also { style -> labels.take(MAX_LISTED).forEach(style::addLine) }
                )
        }
        // Checked by canNotify just above; the platform can still refuse, and that is not an error.
        @Suppress("MissingPermission")
        runCatching { manager.notify(ID_NEW_APPS, builder.build()) }
            .onFailure { KillitLog.w(KillitLog.GUARD, "Posting the blocked-apps notification failed", it) }
    }

    /**
     * Reports whether a notification would be shown at all.
     *
     * From Android 13 that needs the notification permission, which the home screen asks for.
     *
     * @return true when the permission is held (or not needed) and notifications are on for Killit.
     */
    private fun canNotify(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permitted && manager.areNotificationsEnabled()
    }

    /**
     * Opens Killit, which asks for the passkey first as always.
     *
     * Immutable, as every pending intent should be unless something must fill it in later.
     *
     * @param destination where to go once the passkey has been entered, or null for the start screen.
     * @return the intent for the notification's tap.
     */
    private fun openKillit(destination: String?): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (destination != null) putExtra(MainActivity.EXTRA_DESTINATION, destination) }
        return PendingIntent.getActivity(
            appContext,
            destination?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** @return a context whose strings are in the language chosen inside Killit. */
    private suspend fun localised(): Context = language().applyTo(appContext)

    companion object {
        /** The protection service's ongoing notification. */
        const val CHANNEL_PROTECTION = "protection"

        /** Blocked new apps. */
        const val CHANNEL_NEW_APPS = "new_apps"

        /** The protection service's notification id; any value but zero. */
        const val ID_PROTECTION = 1

        /** The one blocked-apps notification, updated in place. */
        private const val ID_NEW_APPS = 2

        /** Lines in the expanded list; the system shows only so many anyway. */
        private const val MAX_LISTED = 6
    }
}
