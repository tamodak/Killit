package org.tamodak.killit.pairing

import java.io.IOException
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.EventType
import org.tamodak.killit.pairing.store.LinkDeletionEntity
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.protocol.FINGERPRINT_BYTES
import org.tamodak.killit.protocol.MessageBody
import org.tamodak.killit.protocol.NoticeBody
import org.tamodak.killit.protocol.RemovedHow
import org.tamodak.killit.protocol.UnpairedHow
import org.tamodak.killit.protocol.decodeReveal
import org.tamodak.killit.protocol.fingerprint
import org.tamodak.killit.protocol.fromBase64Url

/**
 * The pairings themselves: their beginning and end, the notices a protected phone owes its
 * guardians about them, and the links they leave behind (SPEC.md §7, §11.8, §11.9).
 *
 * Ending a pairing never needs the other side's consent in the direction that weakens nobody: a
 * guardian may stop guarding at once (LEAVE), and a protected phone is told it lost a guardian
 * only after the rules for that were met (UNPAIRED). Each change is announced to the protected
 * phone's other guardians (A4, B5).
 *
 * A link is deleted only once no pairing is left between the two phones, in either direction, and
 * every message queued for the peer has gone out (SPEC.md §7), so a goodbye still arrives.
 *
 * Callers hold the engine's lock.
 */
class PeerList(
    private val store: PairingStore,
    private val mailbox: Mailbox,
    private val server: PairingServer,
    private val notifier: PairingNotifier,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = store.dao

    /** Told when guardians come and go, so open requests are counted again (G6). */
    lateinit var requests: RequestEngine

    /** Told when a guardian goes, so a live unlock is counted again (B4). */
    lateinit var unlock: LiveUnlock

    /**
     * Records a pairing the ceremony just completed. On a protected phone, its other guardians are
     * told (A4), and the new guardian receives the requests still waiting (G6, E-C2).
     *
     * @return false when the pairing already existed, as for a repeated message.
     */
    suspend fun added(pairing: PairingEntity): Boolean {
        if (dao.pairing(pairing.pairId) != null) return false
        store.addPairing(pairing)
        dao.deleteLinkDeletion(pairing.peerFp)
        history(EventType.PAIRED, pairing)
        if (pairing.myRole == PairRole.PROTECTED) {
            notifyGuardians(NoticeBody.GuardianAdded(pairing.peerFp, pairing.peerName), except = pairing.pairId)
            requests.onGuardianAdded(pairing)
        }
        return true
    }

    /**
     * Guardian side: stops guarding a phone (LEAVE). Immediate and needs nobody's approval; the
     * protected phone tells its other guardians.
     */
    suspend fun leave(pairId: ByteArray) {
        val pairing = dao.pairing(pairId) ?: return
        check(pairing.myRole == PairRole.GUARDIAN) { "only a guardian leaves" }
        mailbox.post(pairing, MessageBody.Leave)
        forget(pairing)
        history(EventType.GUARDIAN_LEFT, pairing)
        KillitLog.i(KillitLog.PAIR, "Stopped guarding ${pairing.peerFp.short()}")
    }

    /**
     * Protected side: removes a guardian once the rules allowed it (an approved UNPAIR, its timer,
     * or releasing device owner). The guardian is told why, and so are the others.
     */
    suspend fun removeGuardian(pairId: ByteArray, how: UnpairedHow) {
        val pairing = dao.pairing(pairId) ?: return
        check(pairing.myRole == PairRole.PROTECTED) { "only a guardian is removed" }
        mailbox.post(pairing, MessageBody.Unpaired(how))
        forget(pairing)
        history(EventType.GUARDIAN_REMOVED, pairing, value = how.code)
        if (how != UnpairedHow.RELEASED) {
            notifyGuardians(NoticeBody.GuardianRemoved(pairing.peerFp, pairing.peerName, RemovedHow.UNPAIRED))
        }
        guardianGone(pairing)
    }

    /** Protected side: a guardian left (LEAVE). */
    suspend fun onLeave(received: Mailbox.Received) {
        val pairing = received.pairing ?: return
        forget(pairing)
        val event = history(EventType.GUARDIAN_LEFT, pairing)
        notifyGuardians(NoticeBody.GuardianRemoved(pairing.peerFp, pairing.peerName, RemovedHow.LEFT))
        guardianGone(pairing)
        notifier.pairingEnded(pairing, event)
    }

    /** Guardian side: the protected phone removed this phone as its guardian (UNPAIRED). */
    suspend fun onUnpaired(received: Mailbox.Received) {
        val pairing = received.pairing ?: return
        val how = (received.body as MessageBody.Unpaired).how
        forget(pairing)
        val event = history(EventType.REMOVED_AS_GUARDIAN, pairing, value = how.code)
        notifier.pairingEnded(pairing, event)
    }

    /** Guardian side: a NOTICE from a phone this phone guards. It informs and changes nothing. */
    suspend fun onNotice(received: Mailbox.Received) {
        val pairing = received.pairing ?: return
        val event = when (val notice = (received.body as MessageBody.Notice).notice) {
            is NoticeBody.AppInstalled -> event(
                EventType.APP_INSTALLED,
                pairing,
                appLabel = notice.app.label,
                packageName = notice.app.packageName,
                flag = notice.blocked,
            )
            is NoticeBody.GuardianAdded -> event(EventType.GUARDIAN_ADDED, pairing, otherName = notice.guardianName)
            is NoticeBody.GuardianRemoved ->
                event(EventType.GUARDIAN_REMOVED, pairing, otherName = notice.guardianName, value = notice.how.code)
            is NoticeBody.ReleaseStarted -> event(EventType.RELEASE_STARTED, pairing, time = notice.completesAt)
            NoticeBody.ReleaseCancelled -> event(EventType.RELEASE_CANCELLED, pairing)
            is NoticeBody.ProtectionChanged ->
                event(EventType.PROTECTION_CHANGED, pairing, value = notice.option.code, flag = notice.enabled)
            is NoticeBody.ManagementChanged -> event(EventType.MANAGEMENT_CHANGED, pairing, flag = notice.enabled)
            is NoticeBody.Unknown -> event(EventType.UNKNOWN_NOTICE, pairing, value = notice.code)
            is NoticeBody.AppManaged, is NoticeBody.GuardianMoved -> return
        }
        dao.insertEvent(event)
        if (event.type == EventType.UNKNOWN_NOTICE) notifier.updateNeeded() else notifier.noticeArrived(pairing, event)
    }

    /**
     * Protected side: tells every guardian something (SPEC.md §11.9).
     *
     * @param except a guardian not to tell, such as the one the notice is about.
     */
    suspend fun notifyGuardians(notice: NoticeBody, except: ByteArray? = null) {
        for (guardian in store.guardians()) {
            if (except != null && guardian.pairId.contentEquals(except)) continue
            mailbox.post(guardian, MessageBody.Notice(notice))
        }
    }

    /**
     * Protected side, H7: device owner is being released, so every guardian is removed and told.
     * Called while the release is still cancellable no more: right before it happens.
     */
    suspend fun releaseAll() {
        for (guardian in store.guardians()) removeGuardian(guardian.pairId, UnpairedHow.RELEASED)
    }

    /** Sets what this phone calls a peer (A5). */
    suspend fun rename(pairId: ByteArray, nickname: String) {
        store.setNickname(pairId, nickname)
    }

    /**
     * Deletes the links no pairing needs any more, once their last message has gone out.
     *
     * @throws IOException when the server can't be reached; the deletions wait for the next sync.
     */
    suspend fun deleteFinishedLinks() {
        val ceremonyPeer = dao.ceremony()
            ?.takeIf { it.stage != CeremonyStage.FAILED }
            ?.peerReveal
            ?.let { decodeReveal(it).valueOrNull() }
            ?.let { fingerprint(it.bundle.signPk) }
        for (deletion in dao.linkDeletions()) {
            when {
                dao.pairingsWith(deletion.peerFp).isNotEmpty() -> dao.deleteLinkDeletion(deletion.peerFp)
                ceremonyPeer?.contentEquals(deletion.peerFp) == true -> Unit // pairing with it again
                dao.outboxCountFor(deletion.peerFp) > 0 -> Unit
                else -> {
                    server.deleteLink(deletion.peerFp)
                    dao.deleteLinkDeletion(deletion.peerFp)
                    KillitLog.i(KillitLog.PAIR, "Link to ${deletion.peerFp.short()} deleted")
                }
            }
        }
    }

    /**
     * Refreshes "last seen" for every peer (SPEC.md §7, PLAN.md §3.11), and tells a guardian when a
     * phone it guards has been silent too long (H8).
     *
     * @param silenceAlertMillis how long a guarded phone may be silent before its guardians hear.
     */
    suspend fun refreshLastSeen(silenceAlertMillis: Long) {
        val links = server.links()
        for (link in links) {
            val fp = runCatching { link.peer.fromBase64Url(FINGERPRINT_BYTES) }.getOrNull() ?: continue
            dao.setLastSeen(fp, link.lastSeenDaysAgo.coerceAtLeast(0))
        }
        for (pairing in dao.pairings()) {
            if (pairing.myRole != PairRole.GUARDIAN) continue
            val days = pairing.lastSeenDaysAgo ?: continue
            val silent = days * DAY_MILLIS >= silenceAlertMillis && days > 0
            if (silent && !pairing.silenceAlerted) {
                dao.setSilenceAlerted(pairing.pairId, true)
                notifier.silence(pairing)
            } else if (!silent && pairing.silenceAlerted) {
                dao.setSilenceAlerted(pairing.pairId, false)
            }
        }
    }

    /** Removes a pairing, and arranges for the link to go once nothing else needs it. */
    private suspend fun forget(pairing: PairingEntity) {
        store.removePairing(pairing.pairId)
        if (dao.pairingsWith(pairing.peerFp).isEmpty()) {
            dao.requestLinkDeletion(LinkDeletionEntity(pairing.peerFp, now()))
        }
    }

    /** A guardian is gone: open requests and a live unlock are counted again without it (G6, B4). */
    private suspend fun guardianGone(pairing: PairingEntity) {
        requests.onGuardianRemoved(pairing)
        unlock.onGuardianRemoved(pairing.pairId)
    }

    private suspend fun history(type: EventType, pairing: PairingEntity, value: Int? = null): EventEntity =
        event(type, pairing, value = value).also { dao.insertEvent(it) }

    private fun event(
        type: EventType,
        pairing: PairingEntity,
        appLabel: String? = null,
        packageName: String? = null,
        otherName: String? = null,
        value: Int? = null,
        flag: Boolean? = null,
        time: Long? = null,
    ) = EventEntity(
        pairId = pairing.pairId,
        at = now(),
        type = type,
        peerName = pairing.nickname,
        appLabel = appLabel,
        packageName = packageName,
        otherName = otherName,
        value = value,
        flag = flag,
        time = time,
    )

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
