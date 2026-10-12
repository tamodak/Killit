package org.tamodak.killit.pairing

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.tamodak.killit.R
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.EventType
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.pairing.store.StatusEntries
import org.tamodak.killit.protocol.ProtectionOption
import org.tamodak.killit.protocol.RemovedHow
import org.tamodak.killit.protocol.toHex

/**
 * Pairing text, written on this phone in its own language from structured codes (PLAN.md E-F3),
 * for the screens and the notifications alike, so a notification reads the same as the screen it
 * opens.
 *
 * Every function takes a context whose resources are in the language chosen inside Killit: a
 * screen's `LocalContext`, or [org.tamodak.killit.data.AppLanguage.applyTo] for a notification.
 */
object PairingTexts {
    /** One history line (I10). */
    fun event(text: Context, event: EventEntity): String {
        val app = event.appLabel?.ifEmpty { null } ?: event.packageName.orEmpty()
        return when (event.type) {
            EventType.PAIRED -> text.getString(R.string.event_paired)
            EventType.GUARDIAN_LEFT -> text.getString(R.string.event_left)
            EventType.GUARDIAN_REMOVED -> if (event.otherName != null) {
                text.getString(
                    if (event.value == RemovedHow.LEFT.code) R.string.event_other_left else R.string.event_other_removed,
                    event.otherName,
                )
            } else {
                text.getString(R.string.event_guardian_removed)
            }
            EventType.REMOVED_AS_GUARDIAN -> text.getString(R.string.event_removed_you)
            EventType.APP_INSTALLED ->
                text.getString(if (event.flag == true) R.string.event_app_installed_blocked else R.string.event_app_installed, app)
            EventType.GUARDIAN_ADDED -> text.getString(R.string.event_guardian_added, event.otherName.orEmpty())
            EventType.RELEASE_STARTED -> text.getString(R.string.event_release_started, date(text, event.time ?: 0L))
            EventType.RELEASE_CANCELLED -> text.getString(R.string.event_release_cancelled)
            EventType.PROTECTION_CHANGED -> text.getString(
                if (event.flag == true) R.string.event_protection_on else R.string.event_protection_off,
                protectionName(text, event.value),
            )
            EventType.MANAGEMENT_CHANGED ->
                text.getString(if (event.flag == true) R.string.event_management_on else R.string.event_management_off)
            EventType.REQUEST_APPROVED -> text.getString(R.string.event_request_approved, app)
            EventType.REQUEST_DENIED -> text.getString(R.string.event_request_denied, app)
            EventType.REQUEST_CANCELLED -> text.getString(R.string.event_request_cancelled, app)
            EventType.UNKNOWN_NOTICE -> text.getString(R.string.update_needed)
        }
    }

    /** "Ali wants to unblock TikTok", "…wants to unlock Killit", "…wants to remove Dad / you". */
    fun request(text: Context, request: IncomingRequestEntity, from: PairingEntity): String = when (request.type) {
        RequestType.UNBLOCK_APP ->
            text.getString(R.string.request_unblock, from.nickname, request.appLabel?.ifEmpty { null } ?: request.packageName.orEmpty())
        RequestType.UNLOCK -> text.getString(R.string.request_unlock, from.nickname)
        RequestType.UNPAIR -> {
            // The guardian to remove is named by the protected phone's STATUS, in its own words.
            val targetName = StatusEntries.decode(request.statusEntries).firstOrNull { it.fp == request.targetFp?.toHex() }?.name
            when {
                request.targetPairId?.contentEquals(from.pairId) == true -> text.getString(R.string.request_unpair_you, from.nickname)
                targetName != null -> text.getString(R.string.request_unpair_named, from.nickname, targetName)
                else -> text.getString(R.string.request_unpair_other, from.nickname)
            }
        }
    }

    /** A protection option by the name its switch has in Settings. */
    fun protectionName(text: Context, code: Int?): String = text.getString(
        when (ProtectionOption.entries.firstOrNull { it.code == code }) {
            ProtectionOption.UNINSTALL -> R.string.harden_uninstall
            ProtectionOption.FORCE_STOP -> R.string.harden_force_stop
            ProtectionOption.SAFE_BOOT -> R.string.harden_safe_boot
            ProtectionOption.FACTORY_RESET -> R.string.harden_factory_reset
            ProtectionOption.APPS_CONTROL -> R.string.harden_apps_control
            ProtectionOption.DATE_TIME, null -> R.string.harden_date_time
        },
    )

    /** A date and time, formatted for the language of [text]. */
    fun date(text: Context, millis: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(text.resources.configuration.locales[0])
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}
