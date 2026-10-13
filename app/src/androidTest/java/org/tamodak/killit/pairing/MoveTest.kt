package org.tamodak.killit.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tamodak.killit.pairing.store.CancelCause
import org.tamodak.killit.pairing.store.CeremonyFailure
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.EventType
import org.tamodak.killit.pairing.store.MoveStage
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.RequestState
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.pairing.store.StatusEntries
import org.tamodak.killit.pairing.store.Vote
import org.tamodak.killit.protocol.AppRef
import org.tamodak.killit.protocol.AppState
import org.tamodak.killit.protocol.randomBytes

/**
 * Moving to a new phone (PLAN.md §3.10, SPEC.md §11.13, §11.14), end to end with an in-memory
 * server. Mom and Dad guard Ali's phone; Mom's new phone and Ali's new phone are fresh.
 */
@RunWith(AndroidJUnit4::class)
class MoveTest {
    private val clock = TestClock()
    private val server = FakeServer(clock.now)
    private val mom = EnginePhone("A", "Mom", server, clock)
    private val ali = EnginePhone("B", "Ali", server, clock)
    private val dad = EnginePhone("C", "Dad", server, clock)
    private val momNew = EnginePhone("D", "Mom 2", server, clock)
    private val aliNew = EnginePhone("E", "Ali 2", server, clock)

    private val chat = "com.example.chat"
    private val game = "com.example.game"

    private suspend fun syncAll(vararg phones: EnginePhone) = phones.forEach { it.engine.sync() }

    private suspend fun EnginePhone.wardOf(protected: EnginePhone) = pairingWith(protected)!!

    private fun ref(phone: EnginePhone, packageName: String) = AppRef(packageName, phone.apps.digests.getValue(packageName))

    /**
     * Runs a move's ceremony: [to], the new phone, shows the code, [from], the old one, types it,
     * both confirm the safety code they show side by side, and the new phone asks for the state.
     */
    private suspend fun linkMove(from: EnginePhone, to: EnginePhone) {
        to.engine.startMoveIn()
        val code = to.ceremony()!!.code!!
        assertEquals(CeremonyEngine.JoinResult.JOINED, from.engine.startMoveOut(code))
        to.engine.followCeremony(0)
        from.engine.followCeremony(0)
        to.engine.followCeremony(0)
        assertEquals(to.ceremony()!!.safetyCode, from.ceremony()!!.safetyCode)
        to.engine.ceremony.confirm()
        from.engine.ceremony.confirm()
        assertEquals(MoveStage.AWAITING_REQUEST, from.ceremony()!!.moveStage)
        to.engine.followCeremony(0)
        assertEquals(MoveStage.AWAITING_STATE, to.ceremony()!!.moveStage)
    }

    /** Runs a move up to the point where the old phone's user may confirm it. */
    private suspend fun prepareMove(from: EnginePhone, to: EnginePhone) {
        linkMove(from, to)
        from.engine.sync()
        to.engine.sync()
        from.engine.sync()
        assertEquals(MoveStage.READY, from.ceremony()!!.moveStage)
        assertEquals(MoveStage.READY, to.ceremony()!!.moveStage)
    }

    @Test
    fun aGuardianMovesAndKeepsGuarding(): Unit = runBlocking {
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        val pairId = mom.wardOf(ali).pairId
        mom.engine.rename(pairId, "My son")

        prepareMove(from = mom, to = momNew)
        assertEquals(1, momNew.ceremony()!!.moveWards)
        assertEquals(0, momNew.ceremony()!!.moveGuardians)
        assertEquals(PairingEngine.MoveCommitResult.COMMITTED, mom.engine.commitMove())

        // The old phone lets go of everything, its identity included, once the commit is out.
        assertTrue(mom.pairings().isEmpty())
        assertTrue(mom.forgotten)
        assertEquals(MoveStage.MOVED, mom.ceremony()!!.moveStage)
        assertNull("only a phone with guardians is frozen", mom.durable.movedAway)
        assertFalse(mom.engine.isFrozen())
        assertFalse(mom.engine.hasWork())

        ali.engine.sync()
        val onAli = ali.pairingWith(momNew)!!
        assertArrayEquals(pairId, onAli.pairId)
        assertEquals("Test Mom 2", onAli.peerModel)
        assertNull(ali.pairingWith(mom))
        assertTrue("moved ${PairRole.PROTECTED}" in ali.notifier.events)
        assertEquals("Test Mom 2", ali.dao.observeEvents().first().first { it.type == EventType.MOVED }.otherName)

        momNew.engine.sync()
        val onNew = momNew.wardOf(ali)
        assertArrayEquals(pairId, onNew.pairId)
        assertEquals(PairRole.GUARDIAN, onNew.myRole)
        assertEquals("My son", onNew.nickname)
        assertEquals(CeremonyStage.PAIRED, momNew.ceremony()!!.stage)
        assertEquals(MoveStage.MOVED, momNew.ceremony()!!.moveStage)
        assertTrue(server.linked(ali.fp, momNew.fp))
        assertFalse(server.linked(ali.fp, mom.fp))

        // Ali's requests reach the new phone now, and its answers count.
        ali.apps.install(game)
        val asked = ali.engine.askToUnblock(game, "I need it for school") as RequestEngine.AskResult.Sent
        momNew.engine.sync()
        assertTrue(momNew.engine.decide(pairId, asked.requestId, approve = true, note = ""))
        ali.engine.sync()
        assertTrue(game in ali.apps.unblocked)
    }

    @Test
    fun aProtectedPhoneMovesAndTheOldOneStaysFrozen(): Unit = runBlocking {
        ali.apps.install(game)
        ali.apps.installAllowed(chat)
        ali.protection = 0b10_0011
        assertTrue(ali.engine.setAppManagement(true))
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        val pairId = mom.wardOf(ali).pairId

        // One denial leaves the game two chances; the request after it is still open at the move.
        val denied = ali.engine.askToUnblock(game, "Just one more level") as RequestEngine.AskResult.Sent
        mom.engine.sync()
        mom.engine.decide(pairId, denied.requestId, approve = false, note = "")
        ali.engine.sync()
        val open = ali.engine.askToUnblock(game, "Please, it is for homework") as RequestEngine.AskResult.Sent
        mom.engine.sync()

        val sideloaded = "com.example.sideloaded"
        aliNew.apps.install(sideloaded)
        aliNew.apps.installedHereOnly += sideloaded

        prepareMove(from = ali, to = aliNew)
        assertEquals(1, aliNew.ceremony()!!.moveGuardians)
        assertEquals(PairingEngine.MoveCommitResult.COMMITTED, ali.engine.commitMove())

        // The old phone forgot its guardians and is frozen, in its durable copy too.
        assertTrue(ali.pairings().isEmpty())
        assertTrue(ali.durable.guardians.isEmpty())
        assertEquals("Test Ali 2", ali.durable.movedAway!!.toModel)
        assertTrue(ali.engine.isFrozen())
        assertEquals(true, ali.guarded.last())
        assertTrue(ali.forgotten)
        assertThrows(IllegalStateException::class.java) { runBlocking { ali.engine.ceremony.create(PairRole.PROTECTED, "Ali") } }

        // Mom hears the open request withdrawn, then takes the handover.
        mom.engine.sync()
        assertEquals(RequestState.CANCELLED, mom.dao.incoming(pairId, open.requestId)!!.state)
        assertEquals(CancelCause.WITHDRAWN, mom.dao.incoming(pairId, open.requestId)!!.cancelCause)
        assertArrayEquals(aliNew.fp, mom.wardOf(aliNew).peerFp)
        assertTrue("moved ${PairRole.GUARDIAN}" in mom.notifier.events)

        aliNew.engine.sync()
        val guardian = aliNew.pairingWith(mom)!!
        assertEquals(PairRole.PROTECTED, guardian.myRole)
        assertEquals(1, aliNew.durable.guardians.size)
        assertTrue(aliNew.durable.appManagement)
        assertEquals(listOf(ali.protection), aliNew.adoptedProtection)
        val adopted = aliNew.apps.adopted.associate { (app, state) -> app.packageName to state }
        assertEquals(AppState.BLOCKED, adopted[game])
        assertEquals(AppState.ALLOWED, adopted[chat])
        assertEquals(2, aliNew.store.appChances(game, ali.apps.digests.getValue(game)))
        assertEquals(true, aliNew.guarded.last())

        // What was installed only here is announced as new (D1).
        mom.engine.sync()
        assertTrue("notice ${EventType.APP_INSTALLED}" in mom.notifier.events)
        assertTrue(mom.dao.observeEvents().first().any { it.type == EventType.APP_INSTALLED && it.packageName == sideloaded })

        // The new phone asks, the guardian answers, the app opens.
        val again = aliNew.engine.askToUnblock(game, "Please, it is for homework") as RequestEngine.AskResult.Sent
        mom.engine.sync()
        assertTrue(mom.engine.decide(pairId, again.requestId, approve = true, note = ""))
        aliNew.engine.sync()
        assertTrue(game in aliNew.apps.unblocked)
    }

    @Test
    fun aGuardedPhoneOnlyMovesToOneThatCanBeProtected(): Unit = runBlocking {
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        aliNew.deviceOwner = false
        linkMove(from = ali, to = aliNew)
        ali.engine.sync()
        assertEquals(CeremonyFailure.NOT_DEVICE_OWNER, ali.ceremony()!!.failure)
        aliNew.engine.sync()
        assertEquals(CeremonyFailure.NOT_DEVICE_OWNER, aliNew.ceremony()!!.failure)
        assertEquals(1, ali.pairings().size)
        assertTrue(aliNew.pairings().isEmpty())
        assertFalse(ali.forgotten)
    }

    @Test
    fun theOldPhoneMayGiveUpUntilItCommits(): Unit = runBlocking {
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        prepareMove(from = ali, to = aliNew)
        assertFalse("the old phone may still commit", aliNew.engine.cancelCeremony())
        assertTrue(ali.engine.cancelCeremony())
        assertEquals(CeremonyFailure.CANCELLED, ali.ceremony()!!.failure)

        aliNew.engine.sync()
        assertEquals(CeremonyFailure.ABANDONED, aliNew.ceremony()!!.failure)
        assertTrue(aliNew.pairings().isEmpty())
        assertTrue(aliNew.dao.movePages().isEmpty())
        mom.engine.sync()
        assertArrayEquals(ali.fp, mom.wardOf(ali).peerFp)
        assertEquals(1, ali.pairings().size)
    }

    @Test
    fun theCommitHasThirtyMinutes(): Unit = runBlocking {
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        prepareMove(from = ali, to = aliNew)
        clock.millis += 31L * 60 * 1000
        assertEquals(PairingEngine.MoveCommitResult.GIVEN_UP, ali.engine.commitMove())
        assertEquals(CeremonyFailure.EXPIRED, ali.ceremony()!!.failure)
        aliNew.engine.sync()
        assertEquals(CeremonyFailure.ABANDONED, aliNew.ceremony()!!.failure)
        assertEquals(1, ali.pairings().size)
    }

    @Test
    fun theNewPhoneGivesUpOnlyOnceTheOldOneCantCommit(): Unit = runBlocking {
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        prepareMove(from = ali, to = aliNew)
        assertFalse(aliNew.engine.cancelCeremony())
        assertThrows(IllegalStateException::class.java) { runBlocking { aliNew.engine.startMoveIn() } }
        clock.millis += 61L * 60 * 1000
        assertTrue(aliNew.engine.cancelCeremony())
        assertEquals(CeremonyFailure.CANCELLED, aliNew.ceremony()!!.failure)
        assertTrue(aliNew.dao.movePages().isEmpty())
    }

    @Test
    fun theOtherGuardiansHearOfAMoveAndOpenRequestsFollowIt(): Unit = runBlocking {
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        pairPhones(dad, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        syncAll(ali, mom, dad)
        ali.apps.install(game)
        val asked = ali.engine.askToUnblock(game, "For the school project") as RequestEngine.AskResult.Sent
        syncAll(mom, dad)
        // Dad approves; Mom hasn't answered when she moves.
        assertTrue(dad.engine.decide(dad.wardOf(ali).pairId, asked.requestId, approve = true, note = ""))
        ali.engine.sync()

        prepareMove(from = mom, to = momNew)
        assertEquals(PairingEngine.MoveCommitResult.COMMITTED, mom.engine.commitMove())
        ali.engine.sync()
        dad.engine.sync()
        assertTrue("notice ${EventType.GUARDIAN_MOVED}" in dad.notifier.events)
        assertEquals("Mom", dad.dao.observeEvents().first().first { it.type == EventType.GUARDIAN_MOVED }.otherName)
        assertFalse("Ali's other guardian keeps its own pairing", dad.notifier.events.any { it.startsWith("moved") })

        momNew.engine.sync()
        val request = momNew.dao.observeIncoming().first().single()
        assertEquals(RequestType.UNBLOCK_APP, request.type)
        assertEquals(Vote.WAITING, request.myVote)
        // Dad's approval came along (G10).
        assertEquals(Vote.APPROVED, StatusEntries.decode(request.statusEntries).single { it.name == "Dad" }.vote)
        assertTrue(momNew.engine.decide(request.pairId, request.requestId, approve = true, note = ""))
        ali.engine.sync()
        assertTrue(game in ali.apps.unblocked)
    }

    @Test
    fun aMovedGuardiansCommandsCountAfresh(): Unit = runBlocking {
        ali.apps.installAllowed(chat)
        assertTrue(ali.engine.setAppManagement(true))
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        val pairId = mom.wardOf(ali).pairId
        // Three commands from the old phone: seq 1, 2 and 3.
        for (block in listOf(true, false, true)) {
            assertTrue(mom.engine.manageApps(pairId, listOf(ref(ali, chat) to block)))
            ali.engine.sync()
        }
        assertTrue(chat in ali.apps.blocked)

        prepareMove(from = mom, to = momNew)
        mom.engine.commitMove()
        ali.engine.sync()
        momNew.engine.sync()
        assertTrue(momNew.wardOf(ali).appManagement)

        // The new phone's first command is seq 1, and it counts (SPEC.md §11.13).
        assertTrue(momNew.engine.manageApps(pairId, listOf(ref(ali, chat) to false)))
        ali.engine.sync()
        assertFalse(chat in ali.apps.blocked)
    }

    @Test
    fun aCodeForTheOtherPurposeEndsBothCeremonies(): Unit = runBlocking {
        // Ali means to pair, and types the code Mom's new phone shows for a move.
        momNew.engine.startMoveIn()
        assertEquals(CeremonyEngine.JoinResult.JOINED, ali.engine.ceremony.join(momNew.ceremony()!!.code!!, PairRole.PROTECTED, "Ali"))
        momNew.engine.followCeremony(0)
        ali.engine.followCeremony(0)
        momNew.engine.followCeremony(0)
        momNew.engine.ceremony.confirm()
        ali.engine.ceremony.confirm()
        momNew.engine.followCeremony(0)
        ali.engine.sync()
        assertEquals(CeremonyFailure.WRONG_PURPOSE, ali.ceremony()!!.failure)
        momNew.engine.sync()
        assertEquals(CeremonyFailure.ABANDONED, momNew.ceremony()!!.failure)
        assertTrue(ali.pairings().isEmpty())
        assertTrue(momNew.pairings().isEmpty())

        // Mom means to move, and types the code Dad shows to pair.
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        dad.engine.ceremony.create(PairRole.GUARDIAN, "Dad")
        assertEquals(CeremonyEngine.JoinResult.JOINED, mom.engine.startMoveOut(dad.ceremony()!!.code!!))
        dad.engine.followCeremony(0)
        mom.engine.followCeremony(0)
        dad.engine.followCeremony(0)
        dad.engine.ceremony.confirm()
        mom.engine.ceremony.confirm()
        dad.engine.followCeremony(0)
        mom.engine.sync()
        assertEquals(CeremonyFailure.WRONG_PURPOSE, mom.ceremony()!!.failure)
        dad.engine.sync()
        assertEquals(CeremonyFailure.DECLINED, dad.ceremony()!!.failure)
        assertEquals(1, mom.pairings().size)
        assertTrue(dad.pairings().isEmpty())
    }

    @Test
    fun aMoveNeedsAPhoneWithoutPairings(): Unit = runBlocking {
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        linkMove(from = ali, to = aliNew)
        // A pairing turns up on the new phone before the state does: its guardians restored from
        // the durable copy, say.
        val bundle = dad.identity.bundle()
        aliNew.store.addPairing(
            PairingEntity(
                pairId = randomBytes(32),
                peerFp = dad.fp,
                peerSignPk = bundle.signPk,
                peerEncPk = bundle.encPk,
                peerEncSig = bundle.encSig,
                myRole = PairRole.GUARDIAN,
                peerName = "Dad",
                nickname = "Dad",
                peerModel = "",
                appManagement = false,
                createdAt = clock.millis,
                unpairChances = 3,
                lastSeenDaysAgo = null,
                silenceAlerted = false,
            ),
        )
        ali.engine.sync()
        aliNew.engine.sync()
        assertEquals(CeremonyFailure.HAS_PAIRINGS, aliNew.ceremony()!!.failure)
        ali.engine.sync()
        assertEquals(CeremonyFailure.HAS_PAIRINGS, ali.ceremony()!!.failure)
        assertEquals(1, ali.pairings().size)
    }

    @Test
    fun aPairingThatChangesMeanwhileStopsTheMove(): Unit = runBlocking {
        pairPhones(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        pairPhones(dad, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        syncAll(ali, mom, dad)
        prepareMove(from = ali, to = aliNew)
        dad.engine.leave(dad.wardOf(ali).pairId)
        ali.engine.sync()
        assertEquals(PairingEngine.MoveCommitResult.GIVEN_UP, ali.engine.commitMove())
        assertEquals(CeremonyFailure.PAIRINGS_CHANGED, ali.ceremony()!!.failure)
        aliNew.engine.sync()
        assertEquals(CeremonyFailure.ABANDONED, aliNew.ceremony()!!.failure)
        assertNotNull(ali.pairingWith(mom))
        assertFalse(ali.forgotten)
    }
}
