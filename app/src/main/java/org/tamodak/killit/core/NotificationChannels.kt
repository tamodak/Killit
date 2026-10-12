package org.tamodak.killit.core

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import org.tamodak.killit.R

/**
 * Killit's notification channels (PLAN.md §15.8), one per kind of news, so a user can silence one
 * kind without missing another: a guardian can quiet the news from the phones it guards and still
 * hear their requests.
 */
object NotificationChannels {
    /** The protection service's ongoing notification: the lowest importance there is. */
    const val PROTECTION = "protection"

    /** This phone: blocked apps, the guardians' answers, guardians coming and going. */
    const val MY_PHONE = "my_phone"

    /** Requests from the phones this phone guards, and the daily reminder about them (D9). */
    const val GUARD_REQUESTS = "guard_requests"

    /** Everything else the guarded phones say: installs, protection changes, silence (H8). */
    const val GUARD_NEWS = "guard_news"

    /**
     * Creates every channel, or renames them.
     *
     * Safe to repeat: the system only updates a channel's name and description, and keeps every
     * setting the user changed on it.
     *
     * @param context any context; the channels belong to the app.
     * @param text where the names come from, so they can follow the language chosen inside Killit.
     */
    fun create(context: Context, text: Context = context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(PROTECTION, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName(text.getString(R.string.notif_channel_protection))
                    .setDescription(text.getString(R.string.notif_channel_protection_desc))
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(MY_PHONE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(text.getString(R.string.notif_channel_my_phone))
                    .setDescription(text.getString(R.string.notif_channel_my_phone_desc))
                    .build(),
                // High: a live unlock is someone waiting on screen right now.
                NotificationChannelCompat.Builder(GUARD_REQUESTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(text.getString(R.string.notif_channel_requests))
                    .setDescription(text.getString(R.string.notif_channel_requests_desc))
                    .build(),
                NotificationChannelCompat.Builder(GUARD_NEWS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(text.getString(R.string.notif_channel_news))
                    .setDescription(text.getString(R.string.notif_channel_news_desc))
                    .build(),
            ),
        )
    }
}
