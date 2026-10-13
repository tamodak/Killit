package org.tamodak.killit.pairing

import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.DurablePairingStore
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.EventType
import org.tamodak.killit.pairing.store.ManageSeqEntity
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.WardAppEntity
import org.tamodak.killit.pairing.store.WardAppState
import org.tamodak.killit.pairing.store.WardListEntity
import org.tamodak.killit.pairing.store.WardListPageEntity
import org.tamodak.killit.protocol.AppChange
import org.tamodak.killit.protocol.AppRef
import org.tamodak.killit.protocol.AppState
import org.tamodak.killit.protocol.Assembly
import org.tamodak.killit.protocol.Desired
import org.tamodak.killit.protocol.Kind
import org.tamodak.killit.protocol.LIST_ID_BYTES
import org.tamodak.killit.protocol.ListedApp
import org.tamodak.killit.protocol.MAX_MANAGE_APPS
import org.tamodak.killit.protocol.ManageEntry
import org.tamodak.killit.protocol.MessageBody
import org.tamodak.killit.protocol.NoticeBody
import org.tamodak.killit.protocol.assembleAppList
import org.tamodak.killit.protocol.decodeBody
import org.tamodak.killit.protocol.pageAppList
import org.tamodak.killit.protocol.randomBytes
import org.tamodak.killit.protocol.toHex

/**
 * Guardians managing a protected phone's apps (PLAN.md §3.8, SPEC.md §11.10–§11.12): optional,
 * and off until the protected phone turns it on, which only an unlocked session can do (D5, G3).
 *
 * ### Protected side
 *
 * A guardian pulls the app list (LIST_REQUEST, APP_LIST); nothing is ever streamed. Its MANAGE
 * commands change which apps are blocked, one package at a time, and nothing else. A block takes
 * effect at once, since blocking only makes the phone stricter; an unblock needs every guardian,
 * so with more than one it becomes a GUARDIAN_UNBLOCK this phone asks the others about (G2). A
 * command for an app with an open request answers that request instead. Every change goes through
 * [RemoteEffects], which refuses it while management is off, and the protected user hears of each
 * one (G4).
 *
 * Commands are state-based: the highest `seq` applied from each guardian is kept per package, and
 * an older one is ignored, so a server that replays or reorders MANAGEs can't undo a newer one.
 *
 * ### Guardian side
 *
 * The list a guardian last asked for arrives in pages and is kept until a newer one replaces it,
 * updated by the notices of what guardians change. It is forgotten when the protected phone turns
 * management off: it was only shared for managing. A change for an app the guarded phone is
 * already asking about answers that request with a DECISION, like the request screen would.
 *
 * Callers hold the engine's lock.
 *
 * @param listAnswersPerHour how many list requests the protected phone answers per guardian per
 *   hour (SPEC.md §11.10): each answer is dozens of envelopes, and the server's per-phone budget
 *   is shared with every request this phone sends.
 */
class AppManagement(
    private val store: PairingStore,
    private val mailbox: Mailbox,
    private val effects: RemoteEffects,
    private val apps: AppBlocker,
    private val durable: DurablePairingStore,
    private val notifier: PairingNotifier,
    private val listAnswersPerHour: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = store.dao

    /** Settles open requests that a guardian's command answers. */
    lateinit var requests: RequestEngine

    /** Tells every guardian about a change. */
    lateinit var peers: PeerList

    /**
     * When this phone last answered each guardian's list requests, by pair id. In memory: a
     * process that starts again forgets it, which only loosens the limit for an hour.
     */
    private val answered = mutableMapOf<String, ArrayDeque<Long>>()

    // ---------------------------------------------------------------- protected side

    /** Whether this phone lets its guardians manage its apps. Off when there is no durable copy. */
    suspend fun isEnabled(): Boolean = durable.isAvailable() && durable.readAppManagement() == true

    /**
     * Turns management on or off, and tells every guardian (H6). Turning it off withdraws what
     * the guardians were unblocking for each other.
     *
     * @return false when the setting couldn't be stored, so nothing changed.
     */
    suspend fun setEnabled(enabled: Boolean): Boolean {
        if (isEnabled() == enabled) return true
        if (!durable.isAvailable() || !durable.writeAppManagement(enabled)) return false
        if (!enabled) requests.withdrawGuardianUnblocks()
        peers.notifyGuardians(NoticeBody.ManagementChanged(enabled))
        KillitLog.i(KillitLog.PAIR, "App management turned ${if (enabled) "on" else "off"}")
        return true
    }

    /** A guardian asks for this phone's app list. */
    suspend fun onListRequest(received: Mailbox.Received) {
        val guardian = received.pairing ?: return
        val listRequest = received.body as MessageBody.ListRequest
        if (!isEnabled()) {
            // The guardian's view is stale; this corrects it.
            mailbox.post(guardian, MessageBody.Notice(NoticeBody.ManagementChanged(false)))
            return
        }
        if (!takeAnswer(guardian.pairId)) {
            KillitLog.w(KillitLog.PAIR, "Ignored a list request from ${guardian.peerFp.short()}: too many this hour")
            return
        }
        val pages = pageAppList(listRequest.listId, apps.listForGuardians())
        pages.forEach { mailbox.post(guardian, it) }
        KillitLog.i(KillitLog.PAIR, "Sent the app list to ${guardian.peerFp.short()} in ${pages.size} pages")
    }

    /** A guardian's MANAGE (SPEC.md §11.11). */
    suspend fun onManage(received: Mailbox.Received) {
        val guardian = received.pairing ?: return
        val manage = received.body as MessageBody.Manage
        if (!isEnabled()) {
            KillitLog.w(KillitLog.PAIR, "Ignored a MANAGE from ${guardian.peerFp.short()}: management is off")
            return
        }
        for (entry in manage.entries) {
            val packageName = entry.app.packageName
            if (manage.seq <= (dao.manageSeq(guardian.pairId, packageName) ?: 0L)) continue
            dao.upsertManageSeq(ManageSeqEntity(guardian.pairId, packageName, manage.seq))
            when (entry.desired) {
                Desired.BLOCKED -> block(guardian, entry.app)
                Desired.ALLOWED -> allow(guardian, entry.app)
            }
        }
    }

    /** BLOCKED: at once, whatever the certificate; an open request for the app is answered. */
    private suspend fun block(guardian: PairingEntity, app: AppRef) {
        val outcome = effects.apply(RemoteEffects.Effect.ManagedBlock(app.packageName, app.certDigest))
        if (outcome == RemoteEffects.Outcome.NOT_ALLOWED) return
        requests.answerFromManage(guardian, app.packageName, block = true)
        val label = apps.describe(app.packageName)?.label.orEmpty()
        if (outcome == RemoteEffects.Outcome.REFUSED) {
            mailbox.post(guardian, MessageBody.Notice(managed(app, label, AppChange.REFUSED, guardian)))
            return
        }
        peers.notifyGuardians(managed(app, label, AppChange.BLOCKED, guardian))
        if (outcome == RemoteEffects.Outcome.APPLIED) changed(guardian, app, label, AppChange.BLOCKED)
    }

    /**
     * ALLOWED: only for the app the guardian saw, and only with every guardian. Alone, the
     * guardian unblocks it at once; otherwise the others are asked (G2).
     */
    private suspend fun allow(guardian: PairingEntity, app: AppRef) {
        val installed = apps.describe(app.packageName) ?: return
        if (!installed.certDigest.contentEquals(app.certDigest) || !apps.isBlocked(app.packageName)) return
        if (requests.answerFromManage(guardian, app.packageName, block = false)) return
        if (store.guardians().size > 1) {
            requests.proposeUnblock(guardian, installed)
            return
        }
        if (effects.apply(RemoteEffects.Effect.ManagedUnblock(app.packageName, app.certDigest)) != RemoteEffects.Outcome.APPLIED) return
        // G7: an unblocked app starts again with every chance.
        store.setAppChances(app.packageName, app.certDigest, PairingStore.MAX_CHANCES)
        mailbox.post(guardian, MessageBody.Notice(managed(app, installed.label, AppChange.UNBLOCKED, guardian)))
        changed(guardian, app, installed.label, AppChange.UNBLOCKED)
    }

    /** Records a guardian's change in the history and tells the protected user (G4). */
    private suspend fun changed(guardian: PairingEntity, app: AppRef, label: String, change: AppChange) {
        dao.insertEvent(
            EventEntity(
                pairId = guardian.pairId,
                at = now(),
                type = EventType.APP_MANAGED,
                peerName = guardian.nickname,
                appLabel = label,
                packageName = app.packageName,
                otherName = guardian.nickname,
                value = change.code,
                flag = false,
                time = null,
            ),
        )
        notifier.appManaged(guardian, label.ifEmpty { app.packageName }, change)
    }

    /** NOTICE APP_MANAGED, naming the guardian by its own name (SPEC.md §11.9). */
    private fun managed(app: AppRef, label: String, change: AppChange, by: PairingEntity) =
        NoticeBody.AppManaged(app, label, change, by.peerFp, by.peerName)

    /** Counts one list answer for [pairId], unless the hour's allowance is used up. */
    private fun takeAnswer(pairId: ByteArray): Boolean {
        val times = answered.getOrPut(pairId.toHex()) { ArrayDeque() }
        while (times.isNotEmpty() && times.first() <= now() - HOUR_MILLIS) times.removeFirst()
        if (times.size >= listAnswersPerHour) return false
        times.addLast(now())
        return true
    }

    // ---------------------------------------------------------------- guardian side

    /**
     * Asks a guarded phone for its app list. A list asked for earlier, still arriving, is
     * abandoned; the last complete one stays on screen until the new one is whole.
     *
     * @return false when the phone doesn't let its guardians manage its apps.
     */
    suspend fun requestList(pairId: ByteArray): Boolean {
        val ward = dao.pairing(pairId)?.takeIf { it.myRole == PairRole.GUARDIAN && it.appManagement } ?: return false
        val listId = randomBytes(LIST_ID_BYTES)
        dao.deleteWardListPages(pairId)
        dao.upsertWardList(WardListEntity(pairId, listId, now(), completedAt = null, failed = false))
        mailbox.post(ward, MessageBody.ListRequest(listId))
        return true
    }

    /** One page of the list this phone asked for; the list replaces the old one once it is whole. */
    suspend fun onAppList(received: Mailbox.Received) {
        val ward = received.pairing ?: return
        val page = received.body as MessageBody.AppList
        val asked = dao.wardList(ward.pairId) ?: return
        if (!asked.listId.contentEquals(page.listId) || asked.completedAt != null || asked.failed) return
        val existing = dao.wardListPage(ward.pairId, page.page)
        if (existing != null) {
            // The same page twice, unless it is the same bytes, breaks the list (SPEC.md §11.10).
            if (!existing.body.contentEquals(received.opened.body)) dropList(asked, "a page arrived twice")
            return
        }
        dao.insertWardListPage(WardListPageEntity(ward.pairId, page.page, received.opened.body))
        val pages = dao.wardListPages(ward.pairId).mapNotNull { decodeBody(Kind.APP_LIST, it.body).valueOrNull() as? MessageBody.AppList }
        when (val result = assembleAppList(pages)) {
            is Assembly.Complete -> {
                dao.replaceWardApps(ward.pairId, result.value.map { it.toEntity(ward.pairId) })
                dao.deleteWardListPages(ward.pairId)
                dao.upsertWardList(WardListEntity(ward.pairId, asked.listId, asked.requestedAt, completedAt = now(), failed = false))
                KillitLog.i(KillitLog.PAIR, "App list of ${ward.peerFp.short()} arrived: ${result.value.size} apps")
            }
            is Assembly.Incomplete -> Unit
            is Assembly.Invalid -> dropList(asked, result.reason)
        }
    }

    /**
     * Sends a guarded phone the changes this phone's guardian made to its list, in as few MANAGE
     * messages as fit. An app the phone is already asking about gets a DECISION on that request.
     *
     * @param changes each app with the state wanted: true to block it, false to unblock it.
     * @return false when the phone doesn't let its guardians manage its apps.
     */
    suspend fun send(pairId: ByteArray, changes: List<Pair<AppRef, Boolean>>): Boolean {
        val ward = dao.pairing(pairId)?.takeIf { it.myRole == PairRole.GUARDIAN && it.appManagement } ?: return false
        val open = dao.openAppRequestsFrom(pairId)
        val entries = mutableListOf<ManageEntry>()
        for ((app, block) in changes) {
            val request = open.firstOrNull { it.packageName == app.packageName }
            if (request != null) {
                requests.decide(pairId, request.requestId, approve = !block, note = "")
                continue
            }
            entries += ManageEntry(app, if (block) Desired.BLOCKED else Desired.ALLOWED)
            dao.setWardAppPending(pairId, app.packageName, block)
        }
        var seq = ward.manageSeq
        for (batch in entries.chunked(MAX_MANAGE_APPS)) {
            seq += 1
            mailbox.post(ward, MessageBody.Manage(seq, batch))
        }
        if (seq != ward.manageSeq) dao.setManageSeqSent(pairId, seq)
        KillitLog.i(KillitLog.PAIR, "Sent ${entries.size} app changes to ${ward.peerFp.short()}")
        return true
    }

    /** The guarded phone stated its setting (H6). When it is off, its list is forgotten. */
    suspend fun onManagementChanged(ward: PairingEntity, enabled: Boolean) {
        dao.setAppManagement(ward.pairId, enabled)
        if (!enabled) dao.forgetWardApps(ward.pairId)
    }

    /** A guardian's MANAGE changed one of the guarded phone's apps (SPEC.md §11.9 APP_MANAGED). */
    suspend fun onAppManaged(ward: PairingEntity, notice: NoticeBody.AppManaged) {
        val name = notice.app.packageName
        when (notice.change) {
            AppChange.BLOCKED -> dao.setWardAppState(ward.pairId, name, WardAppState.BLOCKED)
            AppChange.UNBLOCKED -> dao.setWardAppState(ward.pairId, name, WardAppState.ALLOWED)
            AppChange.REFUSED -> dao.setWardAppPending(ward.pairId, name, null)
        }
    }

    private suspend fun dropList(asked: WardListEntity, reason: String) {
        dao.deleteWardListPages(asked.pairId)
        dao.upsertWardList(WardListEntity(asked.pairId, asked.listId, asked.requestedAt, completedAt = null, failed = true))
        KillitLog.w(KillitLog.PAIR, "Dropped an app list: $reason")
    }

    private fun ListedApp.toEntity(pairId: ByteArray) = WardAppEntity(
        pairId = pairId,
        packageName = packageName,
        certDigest = certDigest,
        label = label,
        installer = installer,
        firstInstalled = firstInstalled,
        state = when (state) {
            AppState.ALLOWED -> WardAppState.ALLOWED
            AppState.BLOCKED -> WardAppState.BLOCKED
            AppState.NEW -> WardAppState.NEW
        },
        icon = icon,
        pendingBlock = null,
    )

    private companion object {
        const val HOUR_MILLIS = 60L * 60 * 1000
    }
}
