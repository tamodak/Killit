package org.tamodak.killit.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tamodak.killit.pairing.store.CeremonyFailure
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.EventType
import org.tamodak.killit.pairing.store.PairingDao
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.RequestState
import org.tamodak.killit.pairing.store.RequestType
import org.tamodak.killit.pairing.store.StatusEntries
import org.tamodak.killit.pairing.store.Vote
import org.tamodak.killit.protocol.AppRef
import org.tamodak.killit.protocol.DecisionTarget
import org.tamodak.killit.protocol.MessageBody
import org.tamodak.killit.protocol.Verdict
import org.tamodak.killit.protocol.deviceId

/**
 * The pairing engine end to end, with three phones and an in-memory server in one process:
 * Mom and Dad guard Ali's phone.
 */
@RunWith(AndroidJUnit4::class)
class PairingEngineTest {
    private val clock = TestClock()
    private val server = FakeServer(clock.now)
    private val mom = EnginePhone("A", "Mom", server, clock)
    private val ali = EnginePhone("B", "Ali", server, clock)
    private val dad = EnginePhone("C", "Dad", server, clock)

    private suspend fun pair(creator: EnginePhone, creatorRole: PairRole, joiner: EnginePhone, joinerRole: PairRole) =
        pairPhones(creator, creatorRole, joiner, joinerRole)

    /** Mom and Dad both guard Ali. */
    private suspend fun guardAli() {
        pair(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        pair(ali, PairRole.PROTECTED, dad, PairRole.GUARDIAN)
        listOf(mom, ali, dad, mom).forEach { it.engine.sync() }
    }

    private suspend fun syncAll(vararg phones: EnginePhone) = phones.forEach { it.engine.sync() }

    @Test
    fun pairingGivesBothPhonesTheSamePairing(): Unit = runBlocking {
        pair(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        val onMom = mom.pairingWith(ali)!!
        val onAli = ali.pairingWith(mom)!!
        assertEquals(PairRole.GUARDIAN, onMom.myRole)
        assertEquals(PairRole.PROTECTED, onAli.myRole)
        assertArrayEquals(onMom.pairId, onAli.pairId)
        assertEquals("Ali", onMom.nickname)
        assertEquals("Mom", onAli.peerName)
        assertEquals("Test Ali", onMom.peerModel)
        assertEquals(CeremonyStage.PAIRED, mom.ceremony()!!.stage)
        assertEquals(CeremonyStage.PAIRED, ali.ceremony()!!.stage)
        assertTrue(server.linked(mom.fp, ali.fp))
        // The protected side's guardians are copied durably; the guardian side has nothing to copy.
        assertEquals(1, ali.durable.guardians.size)
        assertTrue(mom.durable.guardians.isEmpty())
    }

    @Test
    fun conflictingRolesAreDeclined(): Unit = runBlocking {
        mom.engine.ceremony.create(PairRole.GUARDIAN, "Mom")
        ali.engine.ceremony.join(mom.ceremony()!!.code!!, PairRole.GUARDIAN, "Ali")
        mom.engine.followCeremony(0)
        ali.engine.followCeremony(0)
        mom.engine.followCeremony(0)
        mom.engine.ceremony.confirm()
        ali.engine.ceremony.confirm()
        mom.engine.followCeremony(0)
        syncAll(ali, mom)
        assertEquals(CeremonyFailure.ROLE_CONFLICT, ali.ceremony()!!.failure)
        assertEquals(CeremonyFailure.ROLE_CONFLICT, mom.ceremony()!!.failure)
        assertTrue(mom.pairings().isEmpty())
        assertTrue(ali.pairings().isEmpty())
        syncAll(ali, mom)
        assertFalse("the link goes once nothing needs it", server.linked(mom.fp, ali.fp))
    }

    @Test
    fun mismatchCancelsEverything(): Unit = runBlocking {
        mom.engine.ceremony.create(PairRole.GUARDIAN, "Mom")
        ali.engine.ceremony.join(mom.ceremony()!!.code!!, PairRole.PROTECTED, "Ali")
        mom.engine.followCeremony(0)
        ali.engine.followCeremony(0)
        mom.engine.followCeremony(0)
        ali.engine.ceremony.reject()
        mom.engine.followCeremony(0)
        assertEquals(CeremonyFailure.MISMATCH, ali.ceremony()!!.failure)
        assertEquals(CeremonyFailure.CANCELLED, mom.ceremony()!!.failure)
        assertFalse(server.linked(mom.fp, ali.fp))
    }

    @Test
    fun aWrongCodeIsRefused(): Unit = runBlocking {
        mom.engine.ceremony.create(PairRole.GUARDIAN, "Mom")
        assertEquals(CeremonyEngine.JoinResult.CODE_NOT_FOUND, ali.engine.ceremony.join("0000-0000", PairRole.PROTECTED, "Ali"))
        assertEquals(CeremonyEngine.JoinResult.CODE_NOT_FOUND, ali.engine.ceremony.join("not a code", PairRole.PROTECTED, "Ali"))
    }

    @Test
    fun unblockingNeedsEveryGuardian(): Unit = runBlocking {
        guardAli()
        ali.apps.install("com.example.game")
        val sent = ali.engine.askToUnblock("com.example.game", "  My club posts its news there.  ")
        assertTrue(sent is RequestEngine.AskResult.Sent)
        syncAll(mom, dad)
        val onMom = mom.dao.observeIncomingOnce().single()
        assertEquals("My club posts its news there.", onMom.explanation)
        assertEquals(3, onMom.chancesLeft)
        assertEquals(2, onMom.guardianCount)

        assertTrue(mom.engine.decide(onMom.pairId, onMom.requestId, approve = true, note = ""))
        syncAll(ali, dad)
        assertEquals(RequestState.OPEN, ali.outgoing().state)
        // Dad sees Mom's answer (G10).
        val seenByDad = StatusEntries.decode(dad.dao.observeIncomingOnce().single().statusEntries)
        assertEquals(Vote.APPROVED, seenByDad.single { it.name == "Mom" }.vote)

        val onDad = dad.dao.observeIncomingOnce().single()
        assertTrue(dad.engine.decide(onDad.pairId, onDad.requestId, approve = true, note = "OK"))
        syncAll(ali, mom)
        assertEquals(RequestState.APPROVED, ali.outgoing().state)
        assertEquals(listOf("com.example.game"), ali.apps.unblocked)
        assertEquals(RequestState.APPROVED, mom.dao.observeIncomingOnce().single().state)
    }

    @Test
    fun aReminderNamesWhatStillWaits(): Unit = runBlocking {
        pair(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        ali.apps.install("com.example.game")
        ali.engine.askToUnblock("com.example.game", "My club posts its news there.")
        ali.engine.startUnlock("")
        mom.engine.sync()

        // A live unlock is never part of a reminder.
        assertEquals(listOf(RequestType.UNBLOCK_APP), mom.engine.remind(onlyOnce = true).map { it.first.type })
        // Debug builds remind once; release builds every day while the request waits.
        assertTrue(mom.engine.remind(onlyOnce = true).isEmpty())
        assertEquals(1, mom.engine.remind(onlyOnce = false).size)

        val request = mom.dao.observeIncomingOnce().single { it.type == RequestType.UNBLOCK_APP }
        mom.engine.decide(request.pairId, request.requestId, approve = false, note = "")
        assertTrue(mom.engine.remind(onlyOnce = false).isEmpty())
    }

    @Test
    fun guardedPoliciesFollowTheGuardians(): Unit = runBlocking {
        pair(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        assertEquals(true, ali.guarded.last())
        // A guardian guards nobody else by guarding: its own phone stays unguarded.
        assertTrue(mom.guarded.none { it })

        mom.engine.leave(mom.pairingWith(ali)!!.pairId)
        ali.engine.sync()
        assertEquals(false, ali.guarded.last())
    }

    @Test
    fun eachDenialCostsAChance(): Unit = runBlocking {
        pair(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        ali.apps.install("com.example.game")
        repeat(3) { round ->
            assertTrue(ali.engine.askToUnblock("com.example.game", "Please, just this once.") is RequestEngine.AskResult.Sent)
            mom.engine.sync()
            val open = mom.dao.observeIncomingOnce().first { it.state == RequestState.OPEN }
            assertEquals(3 - round, open.chancesLeft)
            mom.engine.decide(open.pairId, open.requestId, approve = false, note = "No.")
            ali.engine.sync()
        }
        assertEquals(RequestEngine.AskResult.NoChancesLeft, ali.engine.askToUnblock("com.example.game", "Please, just this once."))
        assertEquals(0, ali.durable.chances.single().chancesLeft)
        assertTrue(ali.apps.unblocked.isEmpty())
    }

    @Test
    fun aDecisionForAnotherAppIsIgnored(): Unit = runBlocking {
        pair(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        ali.apps.install("com.example.game")
        val sent = ali.engine.askToUnblock("com.example.game", "Please, just this once.") as RequestEngine.AskResult.Sent
        mom.engine.sync()
        // Mom's phone signs an approval naming another app under the same request id.
        val forged = MessageBody.Decision(sent.requestId, DecisionTarget.App(AppRef("com.example.other", ByteArray(32))), Verdict.APPROVE, "")
        mom.engine.mailbox.post(mom.pairingWith(ali)!!, forged)
        mom.engine.mailbox.flush()
        ali.engine.sync()
        assertEquals(RequestState.OPEN, ali.outgoing().state)
        assertTrue(ali.apps.unblocked.isEmpty())
    }

    @Test
    fun repeatedMessagesAreDropped(): Unit = runBlocking {
        pair(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        ali.apps.install("com.example.game")
        ali.engine.askToUnblock("com.example.game", "Please, just this once.")
        mom.engine.sync()
        val request = server.delivered.last { it.first == deviceId(mom.fp) }.second
        server.inject(mom.fp, ali.fp, request)
        mom.engine.sync()
        assertEquals(1, mom.notifier.events.count { it == "arrived UNBLOCK_APP" })
        assertEquals(1, mom.dao.observeIncomingOnce().size)
    }

    @Test
    fun liveUnlockOpensOnlyWhenEveryGuardianApproves(): Unit = runBlocking {
        guardAli()
        assertEquals(LiveUnlock.Availability.Ready, ali.engine.startUnlock(""))
        syncAll(mom, dad)
        val onMom = mom.dao.observeIncomingOnce().single { it.type == RequestType.UNLOCK }
        val onDad = dad.dao.observeIncomingOnce().single { it.type == RequestType.UNLOCK }
        mom.engine.decide(onMom.pairId, onMom.requestId, approve = true, note = "")
        ali.engine.sync()
        assertTrue(ali.engine.unlock.state.value is LiveUnlock.State.Waiting)
        dad.engine.decide(onDad.pairId, onDad.requestId, approve = true, note = "")
        ali.engine.sync()
        assertTrue(ali.engine.unlock.state.value is LiveUnlock.State.Approved)
        syncAll(mom)
        assertEquals(RequestState.APPROVED, mom.dao.observeIncomingOnce().single { it.type == RequestType.UNLOCK }.state)
    }

    @Test
    fun leavingTheUnlockScreenWithdrawsTheRequest(): Unit = runBlocking {
        pair(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        ali.engine.startUnlock("I agreed it with Mom")
        mom.engine.sync()
        val onMom = mom.dao.observeIncomingOnce().single()
        ali.engine.cancelUnlock()
        mom.engine.sync()
        assertEquals(RequestState.CANCELLED, mom.dao.observeIncomingOnce().single().state)
        // An approval after the cancel matches no session and opens nothing (E-B3).
        assertFalse(mom.engine.decide(onMom.pairId, onMom.requestId, approve = true, note = ""))
        ali.engine.sync()
        assertTrue(ali.engine.unlock.state.value is LiveUnlock.State.Idle)
        assertTrue(ali.engine.unlockAvailability() is LiveUnlock.Availability.CoolingDown)
        clock.millis += 5 * 60 * 1000
        assertEquals(LiveUnlock.Availability.Ready, ali.engine.unlockAvailability())
    }

    @Test
    fun oneDenialClosesTheUnlock(): Unit = runBlocking {
        guardAli()
        ali.engine.startUnlock("")
        syncAll(mom)
        val onMom = mom.dao.observeIncomingOnce().single { it.type == RequestType.UNLOCK }
        mom.engine.decide(onMom.pairId, onMom.requestId, approve = false, note = "Not tonight.")
        ali.engine.sync()
        val state = ali.engine.unlock.state.value as LiveUnlock.State.Denied
        assertEquals("Not tonight.", state.guardians.single { it.vote == Vote.DENIED }.note)
    }

    @Test
    fun removingAGuardianNeedsEveryone(): Unit = runBlocking {
        guardAli()
        val dadPairing = ali.pairingWith(dad)!!
        assertTrue(ali.engine.askToRemove(dadPairing.pairId, "Dad gave me this phone back.") is RequestEngine.AskResult.Sent)
        syncAll(mom, dad)
        for (phone in listOf(mom, dad)) {
            val request = phone.dao.observeIncomingOnce().single { it.type == RequestType.UNPAIR }
            phone.engine.decide(request.pairId, request.requestId, approve = true, note = "")
        }
        syncAll(ali, dad, mom)
        assertNull(ali.pairingWith(dad))
        assertNull(dad.pairingWith(ali))
        assertTrue(mom.notifier.events.contains("notice ${EventType.GUARDIAN_REMOVED}"))
        assertEquals(1, ali.durable.guardians.size)
    }

    @Test
    fun aDenialStopsTheUnpairTimer(): Unit = runBlocking {
        guardAli()
        val dadPairing = ali.pairingWith(dad)!!
        ali.engine.askToRemove(dadPairing.pairId, "Dad gave me this phone back.")
        dad.engine.sync()
        val request = dad.dao.observeIncomingOnce().single { it.type == RequestType.UNPAIR }
        dad.engine.decide(request.pairId, request.requestId, approve = false, note = "")
        ali.engine.sync()
        clock.millis += 31L * 24 * 60 * 60 * 1000
        ali.engine.sync()
        assertNotNull(ali.pairingWith(dad))
        assertEquals(2, ali.pairingWith(dad)!!.unpairChances)
    }

    @Test
    fun aDenialWaitingInTheMailboxStopsAnExpiredTimer(): Unit = runBlocking {
        guardAli()
        val dadPairing = ali.pairingWith(dad)!!
        ali.engine.askToRemove(dadPairing.pairId, "Dad gave me this phone back.")
        dad.engine.sync()
        val request = dad.dao.observeIncomingOnce().single { it.type == RequestType.UNPAIR }
        dad.engine.decide(request.pairId, request.requestId, approve = false, note = "")
        // Ali comes back online only after the 30 days: the denial was there in time.
        clock.millis += 31L * 24 * 60 * 60 * 1000
        ali.engine.sync()
        assertNotNull(ali.pairingWith(dad))
        assertEquals(RequestState.DENIED, ali.outgoing().state)
    }

    @Test
    fun anUnreachableServerDoesNotHoldTheTimerBack(): Unit = runBlocking {
        guardAli()
        val dadPairing = ali.pairingWith(dad)!!
        ali.engine.askToRemove(dadPairing.pairId, "Dad lost his phone for good.")
        clock.millis += 30L * 24 * 60 * 60 * 1000
        server.reachable = false
        assertTrue(runCatching { ali.engine.sync() }.exceptionOrNull() is IOException)
        assertNull(ali.pairingWith(dad))
    }

    @Test
    fun theUnpairTimerRemovesASilentGuardian(): Unit = runBlocking {
        guardAli()
        val dadPairing = ali.pairingWith(dad)!!
        ali.engine.askToRemove(dadPairing.pairId, "Dad lost his phone for good.")
        clock.millis += 30L * 24 * 60 * 60 * 1000
        ali.engine.sync()
        assertNull(ali.pairingWith(dad))
        dad.engine.sync()
        assertNull(dad.pairingWith(ali))
    }

    @Test
    fun aGuardianCanLeaveAtOnce(): Unit = runBlocking {
        guardAli()
        mom.engine.leave(mom.pairingWith(ali)!!.pairId)
        syncAll(ali, dad, mom, ali)
        assertNull(ali.pairingWith(mom))
        assertTrue(dad.notifier.events.contains("notice ${EventType.GUARDIAN_REMOVED}"))
        assertFalse(server.linked(mom.fp, ali.fp))
        assertTrue(server.linked(dad.fp, ali.fp))
    }

    @Test
    fun aNewGuardianReceivesOpenRequests(): Unit = runBlocking {
        pair(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        ali.apps.install("com.example.game")
        ali.engine.askToUnblock("com.example.game", "Please, just this once.")
        pair(ali, PairRole.PROTECTED, dad, PairRole.GUARDIAN)
        syncAll(ali, dad)
        val onDad = dad.dao.observeIncomingOnce().single()
        assertEquals("com.example.game", onDad.packageName)
        assertEquals(2, onDad.guardianCount)
    }

    @Test
    fun guardiansSurviveClearData(): Unit = runBlocking {
        pair(mom, PairRole.GUARDIAN, ali, PairRole.PROTECTED)
        val cleared = EnginePhone("B", "Ali", server, clock, durable = ali.durable)
        assertTrue(cleared.pairings().isEmpty())
        cleared.store.reconcile()
        val restored = cleared.pairings().single()
        assertEquals(PairRole.PROTECTED, restored.myRole)
        assertArrayEquals(mom.fp, restored.peerFp)
    }

    /** The phone's open request, or its latest one. */
    private suspend fun EnginePhone.outgoing() = dao.openOutgoing().singleOrNull()?.request
        ?: dao.observeOutgoingOnce().first()

    private suspend fun PairingDao.observeIncomingOnce() = observeIncoming().first()

    private suspend fun PairingDao.observeOutgoingOnce() = observeOutgoing().first().map { it.request }
}
