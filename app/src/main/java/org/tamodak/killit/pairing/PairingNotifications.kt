package org.tamodak.killit.pairing

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap
import org.tamodak.killit.MainActivity
import org.tamodak.killit.MainActivity.Destination
import org.tamodak.killit.R
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.core.NotificationChannels
import org.tamodak.killit.core.canPostNotifications
import org.tamodak.killit.data.AppLanguage
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.EventType
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.OutgoingRequestEntity
import org.tamodak.killit.pairing.store.PairingDao
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.RequestState
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.pairing.store.Vote
import org.tamodak.killit.protocol.UnpairedHow
import org.tamodak.killit.protocol.toHex

/**
 * Pairing's notifications (PLAN.md §15.8), written on this phone in the language chosen inside
 * Killit (E-F3). A tap opens the screen the notification is about, through the passkey as always.
 *
 * A request from a phone this phone guards stays until it is answered or settled. News comes one
 * notification per event, which the system bundles when several arrive together (D10), and only
 * the first of a burst on a channel makes a sound.
 *
 * Nothing here decides anything: a notification that fails to post loses no request, which waits
 * in Killit regardless.
 *
 * @param context any context; only its application context is kept.
 * @param language reads the language chosen inside Killit.
 * @param dao names, and the answers a closed request collected.
 * @param onRequestWaiting called when a request arrives that a reminder may have to repeat (D9).
 * @param now the clock, for the sound of a burst.
 */
class PairingNotifications(
    context: Context,
    private val language: suspend () -> AppLanguage,
    private val dao: PairingDao,
    private val onRequestWaiting: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : PairingNotifier {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    /** When each channel last made a sound. */
    private val alertedAt = ConcurrentHashMap<String, Long>()

    // ---------------------------------------------------------------- guardian side

    override suspend fun requestArrived(from: PairingEntity, request: IncomingRequestEntity) {
        val text = localised()
        val live = request.type == RequestType.UNLOCK
        val body = if (live) text.getString(R.string.request_waiting_now) else "“${request.explanation}”"
        post(requestTag(request.pairId, request.requestId), NotificationChannels.GUARD_REQUESTS, text) {
            setContentTitle(PairingTexts.request(text, request, from))
            setContentText(body)
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setCategory(NotificationCompat.CATEGORY_MESSAGE)
            setContentIntent(open(Destination.Request(request.pairId.toHex(), request.requestId.toHex())))
        }
        // A live unlock is answered while its requester waits, or not at all: no reminder.
        if (!live) onRequestWaiting()
    }

    override suspend fun requestSettled(from: PairingEntity, request: IncomingRequestEntity) {
        manager.cancel(requestTag(request.pairId, request.requestId), ID)
        dropReminderIfAnswered()
    }

    /** The reminder only stands while something still waits for this phone's answer. */
    private suspend fun dropReminderIfAnswered() {
        if (dao.waitingForMe().none { it.type != RequestType.UNLOCK }) manager.cancel(TAG_REMINDER, ID)
    }

    override suspend fun noticeArrived(from: PairingEntity, event: EventEntity) {
        val text = localised()
        post(newsTag(from, event), NotificationChannels.GUARD_NEWS, text) {
            setContentTitle(from.nickname)
            setContentText(PairingTexts.event(text, event))
            setCategory(NotificationCompat.CATEGORY_STATUS)
            setContentIntent(open(Destination.Phone(from.pairId.toHex())))
        }
    }

    override suspend fun silence(pairing: PairingEntity) {
        val text = localised()
        val body = text.getString(R.string.notif_silence_text)
        post("silence:${pairing.pairId.toHex()}", NotificationChannels.GUARD_NEWS, text) {
            setContentTitle(text.getString(R.string.notif_silence_title, pairing.nickname))
            setContentText(body)
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setCategory(NotificationCompat.CATEGORY_STATUS)
            setContentIntent(open(Destination.Phone(pairing.pairId.toHex())))
        }
    }

    /**
     * The daily reminder (D9): every request still waiting for this phone's answer, in one
     * notification that opens the inbox.
     *
     * @param waiting each request with the phone it came from.
     */
    suspend fun showReminder(waiting: List<Pair<IncomingRequestEntity, PairingEntity>>) {
        if (waiting.isEmpty()) {
            manager.cancel(TAG_REMINDER, ID)
            return
        }
        val text = localised()
        val title = text.resources.getQuantityString(R.plurals.notif_reminder_title, waiting.size, waiting.size)
        post(TAG_REMINDER, NotificationChannels.GUARD_REQUESTS, text) {
            setContentTitle(title)
            setContentText(text.getString(R.string.notif_reminder_text))
            setStyle(
                NotificationCompat.InboxStyle()
                    .setBigContentTitle(title)
                    .also { style -> waiting.take(MAX_LINES).forEach { (request, from) -> style.addLine(PairingTexts.request(text, request, from)) } },
            )
            setCategory(NotificationCompat.CATEGORY_REMINDER)
            setContentIntent(open(Destination.Guard))
        }
    }

    // ---------------------------------------------------------------- protected side

    override suspend fun guardianAnswered(guardian: PairingEntity, request: OutgoingRequestEntity, approved: Boolean, note: String) {
        // A denial closes the request, and requestClosed says so; an approval may leave others to
        // wait for. Only an app is worth a word before the end.
        if (!approved || request.type != RequestType.UNBLOCK_APP) return
        val text = localised()
        val app = appName(request)
        post(outcomeTag(request), NotificationChannels.MY_PHONE, text) {
            setContentTitle(text.getString(R.string.notif_answer_approved, guardian.nickname, app))
            setContentText(text.getString(R.string.notif_answer_waiting))
            setOnlyAlertOnce(true)
            setContentIntent(open(Destination.AskUnblockApp(request.packageName.orEmpty())))
        }
    }

    override suspend fun requestClosed(request: OutgoingRequestEntity) {
        when (request.type) {
            RequestType.UNBLOCK_APP -> appClosed(request)
            RequestType.UNPAIR -> unpairClosed(request)
            RequestType.UNLOCK -> Unit
        }
    }

    private suspend fun appClosed(request: OutgoingRequestEntity) {
        val tag = outcomeTag(request)
        val text = localised()
        val app = appName(request)
        when (request.state) {
            RequestState.APPROVED -> post(tag, NotificationChannels.MY_PHONE, text) {
                setContentTitle(text.getString(R.string.notif_app_allowed_title, app))
                setContentText(text.getString(R.string.notif_app_allowed_text))
                setContentIntent(open(Destination.MyPhone))
            }
            RequestState.DENIED -> {
                val body = deniedText(text, request)
                post(tag, NotificationChannels.MY_PHONE, text) {
                    setContentTitle(text.getString(R.string.notif_app_denied_title, app))
                    setContentText(body.lineSequence().first())
                    setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    setContentIntent(open(Destination.AskUnblockApp(request.packageName.orEmpty())))
                }
            }
            // Withdrawn, uninstalled, settled while unlocked: the user saw it happen.
            RequestState.CANCELLED, RequestState.OPEN -> manager.cancel(tag, ID)
        }
    }

    private suspend fun unpairClosed(request: OutgoingRequestEntity) {
        val target = dao.pairing(request.targetPairId ?: return)?.nickname ?: return
        val text = localised()
        when (request.state) {
            RequestState.APPROVED -> {
                val timer = request.timerEndsAt != null && (request.closedAt ?: 0L) >= request.timerEndsAt
                post(outcomeTag(request), NotificationChannels.MY_PHONE, text) {
                    setContentTitle(text.getString(R.string.notif_guardian_removed_title, target))
                    setContentText(text.getString(if (timer) R.string.notif_guardian_removed_timer else R.string.notif_guardian_removed_text))
                    setContentIntent(open(Destination.Guardians))
                }
            }
            RequestState.DENIED -> {
                val body = deniedText(text, request)
                post(outcomeTag(request), NotificationChannels.MY_PHONE, text) {
                    setContentTitle(text.getString(R.string.notif_guardian_kept_title, target))
                    setContentText(body.lineSequence().first())
                    setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    setContentIntent(open(Destination.Guardians))
                }
            }
            RequestState.CANCELLED, RequestState.OPEN -> Unit
        }
    }

    /**
     * What a denial cost, then each denying guardian's note (G9): the chances are counted before
     * this denial is subtracted, since it is reported as it happens.
     */
    private suspend fun deniedText(text: Context, request: OutgoingRequestEntity): String {
        val left = (request.chancesLeft - 1).coerceAtLeast(0)
        val lines = mutableListOf(
            if (left == 0) {
                text.getString(R.string.notif_denied_no_chances)
            } else {
                text.resources.getQuantityString(R.plurals.notif_denied_chances, left, left)
            },
        )
        dao.outgoing(request.requestId)?.copies.orEmpty()
            .filter { it.vote == Vote.DENIED && it.note.isNotEmpty() }
            .forEach { copy ->
                val name = dao.pairing(copy.pairId)?.nickname ?: return@forEach
                lines += text.getString(R.string.notif_note, name, copy.note)
            }
        return lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- either side

    override suspend fun pairingEnded(pairing: PairingEntity, event: EventEntity) {
        val text = localised()
        when (event.type) {
            // Protected side: a guardian left.
            EventType.GUARDIAN_LEFT -> {
                val unguarded = dao.guardians().isEmpty()
                post("ended:${pairing.pairId.toHex()}", NotificationChannels.MY_PHONE, text) {
                    setContentTitle(text.getString(R.string.notif_guardian_left_title, pairing.nickname))
                    if (unguarded) setContentText(text.getString(R.string.notif_unguarded_text))
                    setContentIntent(open(Destination.Guardians))
                }
            }
            // Guardian side: the protected phone removed this one.
            EventType.REMOVED_AS_GUARDIAN -> {
                cancelRequestsFrom(pairing)
                dropReminderIfAnswered()
                val released = event.value == UnpairedHow.RELEASED.code
                post("ended:${pairing.pairId.toHex()}", NotificationChannels.GUARD_NEWS, text) {
                    setContentTitle(
                        text.getString(if (released) R.string.notif_released_title else R.string.notif_removed_title, pairing.nickname),
                    )
                    setContentText(text.getString(R.string.notif_no_longer_guard))
                    setContentIntent(open(Destination.Guard))
                }
            }
            else -> Unit
        }
    }

    override suspend fun updateNeeded() {
        val text = localised()
        val body = text.getString(R.string.update_needed_long)
        post(TAG_UPDATE, NotificationChannels.MY_PHONE, text) {
            setContentTitle(text.getString(R.string.notif_update_title))
            setContentText(body)
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setOnlyAlertOnce(true)
            setContentIntent(open(Destination.Start))
        }
    }

    // ---------------------------------------------------------------- posting

    /**
     * Posts one notification, replacing any with the same tag. The channels are created first, in
     * the app's language, since a phone that only guards never runs the protection service that
     * otherwise creates them.
     */
    private fun post(tag: String, channel: String, text: Context, build: NotificationCompat.Builder.() -> Unit) {
        if (!appContext.canPostNotifications()) {
            KillitLog.d(KillitLog.PAIR) { "Notifications are off; not posting $channel" }
            return
        }
        NotificationChannels.create(appContext, text)
        val builder = NotificationCompat.Builder(appContext, channel)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setAutoCancel(true)
            .apply(build)
        val at = now()
        if (at - (alertedAt[channel] ?: 0L) < BURST_MILLIS) builder.setSilent(true) else alertedAt[channel] = at
        // Checked just above; the platform can still refuse, and that is not an error.
        @Suppress("MissingPermission")
        runCatching { manager.notify(tag, ID, builder.build()) }
            .onFailure { KillitLog.w(KillitLog.PAIR, "Posting a notification failed", it) }
    }

    /** Takes away the requests of a phone this one no longer guards. */
    private fun cancelRequestsFrom(pairing: PairingEntity) {
        val prefix = "request:${pairing.pairId.toHex()}:"
        manager.activeNotifications
            .filter { it.tag?.startsWith(prefix) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun open(destination: Destination) = MainActivity.open(appContext, destination)

    private suspend fun localised(): Context = language().applyTo(appContext)

    private fun appName(request: OutgoingRequestEntity) = request.appLabel?.ifEmpty { null } ?: request.packageName.orEmpty()

    private fun requestTag(pairId: ByteArray, requestId: ByteArray) = "request:${pairId.toHex()}:${requestId.toHex()}"

    private fun outcomeTag(request: OutgoingRequestEntity) = "outcome:${request.requestId.toHex()}"

    /** Events have no id until stored, so a notice is named by who, what and when. */
    private fun newsTag(from: PairingEntity, event: EventEntity) = "news:${from.pairId.toHex()}:${event.type}:${event.at}"

    private companion object {
        /** Every notification here is told apart by its tag. */
        const val ID = 1

        const val TAG_REMINDER = "reminder"
        const val TAG_UPDATE = "update"

        /** Notifications on one channel this close together are one burst, with one sound. */
        const val BURST_MILLIS = 10_000L

        /** Lines in the reminder's expanded list. */
        const val MAX_LINES = 6
    }
}
