package org.tamodak.killit.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tamodak.killit.pairing.store.CancelCause
import org.tamodak.killit.pairing.store.EventType
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.RequestState
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.pairing.store.Vote
import org.tamodak.killit.pairing.store.WardAppState
import org.tamodak.killit.protocol.AppRef

/**
 * Guardians managing a protected phone's apps (PLAN.md §3.8, SPEC.md §11.10–§11.12), end to end
 * with an in-memory server: Mom and Dad guard Ali's phone.
 */
@RunWith(AndroidJUnit4::class)
class AppManagementTest {
    private val clock = TestClock()
    private val server = FakeServer(clock.now)
    private val mom = EnginePhone("A", "Mom", server, clock)
    private val ali = EnginePhone("B", "Ali", server, clock)
    private val dad = EnginePhone("C", "Dad", server, clock)

    private val chat = "com.example.chat"
    private val game = "com.example.game"

    /** Ali's phone with one allowed and one blocked app, guarded by [guardians], management on. */
    private suspend fun managedBy(vararg guardians: EnginePhone) {
        ali.apps.installAllowed(chat)
        ali.apps.install(game)
        assertTrue(ali.engine.setAppManagement(true))
        for (guardian in guardians) pairPhones(guardian, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        syncAll(ali, *guardians)
    }

    private suspend fun syncAll(vararg phones: EnginePhone) = phones.forEach { it.engine.sync() }

    private suspend fun EnginePhone.wardOf(protected: EnginePhone) = pairingWith(protected)!!

    private suspend fun EnginePhone.appsOf(protected: EnginePhone) =
        dao.observeWardApps().first().filter { it.pairId.contentEquals(wardOf(protected).pairId) }

    private suspend fun EnginePhone.listApps(protected: EnginePhone) {
        assertTrue(engine.requestAppList(wardOf(protected).pairId))
        protected.engine.sync()
        engine.sync()
    }

    private fun ref(phone: EnginePhone, packageName: String) = AppRef(packageName, phone.apps.digests.getValue(packageName))

    @Test
    fun theSettingTravelsWithThePairingAndItsChanges(): Unit = runBlocking {
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        assertFalse(mom.wardOf(ali).appManagement)
        assertFalse(mom.engine.requestAppList(mom.wardOf(ali).pairId))

        assertTrue(ali.engine.setAppManagement(true))
        mom.engine.sync()
        assertTrue(mom.wardOf(ali).appManagement)

        // A guardian added later learns it from the pairing itself (SPEC.md §11.1).
        pairPhones(dad, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        assertTrue(dad.wardOf(ali).appManagement)
    }

    @Test
    fun aGuardianSeesTheListAndBlocksAtOnce(): Unit = runBlocking {
        managedBy(mom, dad)
        mom.listApps(ali)
        val listed = mom.appsOf(ali).associateBy { it.packageName }
        assertEquals(WardAppState.ALLOWED, listed.getValue(chat).state)
        assertEquals(WardAppState.BLOCKED, listed.getValue(game).state)

        assertTrue(mom.engine.manageApps(mom.wardOf(ali).pairId, listOf(ref(ali, chat) to true)))
        assertEquals(true, mom.appsOf(ali).single { it.packageName == chat }.pendingBlock)
        syncAll(ali, mom, dad)
        assertTrue(chat in ali.apps.blocked)
        assertTrue("managed BLOCKED" in ali.notifier.events)
        // The change is confirmed to Mom, and Dad hears of it (SPEC.md §11.9 APP_MANAGED).
        val onMom = mom.appsOf(ali).single { it.packageName == chat }
        assertEquals(WardAppState.BLOCKED, onMom.state)
        assertNull(onMom.pendingBlock)
        assertTrue("notice ${EventType.APP_MANAGED}" in dad.notifier.events)
        assertFalse("Mom's own change needs no telling", "notice ${EventType.APP_MANAGED}" in mom.notifier.events)
    }

    @Test
    fun unblockingNeedsEveryGuardian(): Unit = runBlocking {
        managedBy(mom, dad)
        mom.listApps(ali)
        mom.engine.manageApps(mom.wardOf(ali).pairId, listOf(ref(ali, game) to false))
        syncAll(ali, mom, dad)
        assertTrue("nothing is unblocked before Dad agrees", ali.apps.unblocked.isEmpty())

        val onMom = mom.dao.observeIncoming().first().single()
        assertEquals(RequestType.GUARDIAN_UNBLOCK, onMom.type)
        assertTrue(onMom.proposedByMe)
        assertEquals(Vote.APPROVED, onMom.myVote)
        val onDad = dad.dao.observeIncoming().first().single()
        assertEquals("Mom", onDad.proposerName)
        assertEquals(Vote.WAITING, onDad.myVote)

        assertTrue(dad.engine.decide(onDad.pairId, onDad.requestId, approve = true, note = ""))
        syncAll(ali, mom)
        assertEquals(listOf(game), ali.apps.unblocked)
        assertEquals(RequestState.APPROVED, mom.dao.observeIncoming().first().single().state)
        // The outcome settles what Mom's list shows of the app.
        val onMomList = mom.appsOf(ali).single { it.packageName == game }
        assertEquals(WardAppState.ALLOWED, onMomList.state)
        assertNull(onMomList.pendingBlock)
    }

    @Test
    fun aDenialEndsAGuardianUnblockAndCostsNoChance(): Unit = runBlocking {
        managedBy(mom, dad)
        mom.engine.manageApps(mom.wardOf(ali).pairId, listOf(ref(ali, game) to false))
        syncAll(ali, dad)
        val onDad = dad.dao.observeIncoming().first().single()
        dad.engine.decide(onDad.pairId, onDad.requestId, approve = false, note = "Not yet.")
        ali.engine.sync()
        assertTrue(game in ali.apps.blocked)
        assertEquals(RequestState.DENIED, ali.dao.observeOutgoing().first().single().request.state)
        assertEquals(3, ali.store.appChances(game, ali.apps.digests.getValue(game)))
    }

    @Test
    fun aSingleGuardianUnblocksAtOnce(): Unit = runBlocking {
        managedBy(mom)
        mom.listApps(ali)
        mom.engine.manageApps(mom.wardOf(ali).pairId, listOf(ref(ali, game) to false))
        syncAll(ali, mom)
        assertEquals(listOf(game), ali.apps.unblocked)
        assertTrue("managed UNBLOCKED" in ali.notifier.events)
        assertEquals(WardAppState.ALLOWED, mom.appsOf(ali).single { it.packageName == game }.state)
    }

    @Test
    fun anUnblockOnlyCountsForTheAppTheGuardianSaw(): Unit = runBlocking {
        managedBy(mom)
        mom.engine.manageApps(mom.wardOf(ali).pairId, listOf(AppRef(game, ByteArray(32) { 7 }) to false))
        ali.engine.sync()
        assertTrue(ali.apps.unblocked.isEmpty())
    }

    @Test
    fun aGuardiansCommandAnswersAnOpenRequest(): Unit = runBlocking {
        managedBy(mom, dad)
        ali.engine.askToUnblock(game, "My club posts its news there.")
        // Mom hasn't read the request yet: her MANAGE counts as her approval (SPEC.md §11.11).
        mom.engine.mailbox.post(mom.wardOf(ali), org.tamodak.killit.protocol.MessageBody.Manage(1, listOf(
            org.tamodak.killit.protocol.ManageEntry(ref(ali, game), org.tamodak.killit.protocol.Desired.ALLOWED),
        )))
        mom.engine.mailbox.flush()
        ali.engine.sync()
        val request = ali.dao.openOutgoing().single()
        assertEquals(RequestType.UNBLOCK_APP, request.request.type)
        assertEquals(Vote.APPROVED, request.copies.single { it.pairId.contentEquals(ali.pairingWith(mom)!!.pairId) }.vote)
        // Mom's phone learns from the STATUS that the answer was its own.
        mom.engine.sync()
        assertEquals(Vote.APPROVED, mom.dao.observeIncoming().first().single().myVote)

        // Dad knows of the request, so his block answers it as a denial (a DECISION, not a MANAGE).
        dad.engine.sync()
        dad.engine.manageApps(dad.wardOf(ali).pairId, listOf(ref(ali, game) to true))
        assertEquals(Vote.DENIED, dad.dao.observeIncoming().first().single().myVote)
        ali.engine.sync()
        assertEquals(RequestState.DENIED, ali.dao.observeOutgoing().first().first().request.state)
        assertEquals(2, ali.store.appChances(game, ali.apps.digests.getValue(game)))
    }

    @Test
    fun aReorderedCommandCanNotUndoANewerOne(): Unit = runBlocking {
        managedBy(mom)
        val ward = mom.wardOf(ali).pairId
        mom.engine.manageApps(ward, listOf(ref(ali, chat) to true))
        mom.engine.manageApps(ward, listOf(ref(ali, chat) to false))
        // The server delivers the unblock first and the block after it.
        server.reverseMailbox(ali.fp)
        ali.engine.sync()
        assertFalse("the older block is stale once the newer unblock was seen", chat in ali.apps.blocked)
    }

    @Test
    fun theProposerBlockingAgainWithdrawsItsUnblock(): Unit = runBlocking {
        managedBy(mom, dad)
        val ward = mom.wardOf(ali).pairId
        mom.engine.manageApps(ward, listOf(ref(ali, game) to false))
        ali.engine.sync()
        mom.engine.manageApps(ward, listOf(ref(ali, game) to true))
        ali.engine.sync()
        val relay = ali.dao.observeOutgoing().first().single().request
        assertEquals(RequestState.CANCELLED, relay.state)
        assertEquals(CancelCause.WITHDRAWN, relay.cancelCause)
        assertTrue(game in ali.apps.blocked)
    }

    @Test
    fun turningManagementOffStopsEverything(): Unit = runBlocking {
        managedBy(mom, dad)
        mom.listApps(ali)
        mom.engine.manageApps(mom.wardOf(ali).pairId, listOf(ref(ali, game) to false))
        ali.engine.sync()
        assertTrue(ali.engine.setAppManagement(false))
        assertEquals(RequestState.CANCELLED, ali.dao.observeOutgoing().first().single().request.state)

        syncAll(mom, dad)
        assertFalse(mom.wardOf(ali).appManagement)
        assertTrue("the list was only shared for managing", mom.appsOf(ali).isEmpty())
        // A command that crosses the change is ignored.
        mom.engine.mailbox.post(mom.wardOf(ali), org.tamodak.killit.protocol.MessageBody.Manage(9, listOf(
            org.tamodak.killit.protocol.ManageEntry(ref(ali, chat), org.tamodak.killit.protocol.Desired.BLOCKED),
        )))
        mom.engine.mailbox.flush()
        ali.engine.sync()
        assertFalse(chat in ali.apps.blocked)
    }

    @Test
    fun aRefusedBlockIsReportedToItsGuardianOnly(): Unit = runBlocking {
        managedBy(mom, dad)
        ali.apps.unblockable += chat
        mom.listApps(ali)
        mom.engine.manageApps(mom.wardOf(ali).pairId, listOf(ref(ali, chat) to true))
        syncAll(ali, mom, dad)
        val onMom = mom.appsOf(ali).single { it.packageName == chat }
        assertEquals(WardAppState.ALLOWED, onMom.state)
        assertNull(onMom.pendingBlock)
        assertFalse("notice ${EventType.APP_MANAGED}" in dad.notifier.events)
    }

    @Test
    fun listAnswersAreLimitedPerHour(): Unit = runBlocking {
        managedBy(mom)
        val ward = mom.wardOf(ali).pairId
        repeat(5) { mom.engine.requestAppList(ward) }
        ali.engine.sync()
        // Each answer here is one page: four were answered, the fifth ignored.
        assertEquals(4, server.waitingFor(mom.fp))
        clock.millis += 61 * 60 * 1000
        mom.engine.requestAppList(ward)
        ali.engine.sync()
        assertEquals(5, server.waitingFor(mom.fp))
    }
}
