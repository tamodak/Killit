package org.tamodak.killit.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Whether a posted notification would be shown at all.
 *
 * From Android 13 that needs the notification permission, which My phone and Phones I guard ask
 * for (PLAN.md H9).
 *
 * @return true when the permission is held (or not needed) and notifications are on for Killit.
 */
fun Context.canPostNotifications(): Boolean {
    val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return permitted && NotificationManagerCompat.from(this).areNotificationsEnabled()
}
