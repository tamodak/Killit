package org.tamodak.killit.pairing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tamodak.killit.data.db.KillitDatabase
import org.tamodak.killit.net.ApiClient
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.MoveStage
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.RequestState
import org.tamodak.killit.protocol.deviceId
import org.tamodak.killit.protocol.fromBase64Url

/**
 * Identities on this device pair through a real server and settle a request, end to end:
 * Keystore attestation, signed requests, the invite ceremony, links and the mailbox; and they move
 * to new phones, which the server hands their links to (SPEC.md §7, §11.14).
 *
 * Needs a development server (`KILLIT_ENV=development`, `ATTESTATION_DEV_ACCEPT_ANY_ROOT=true`,
 * the debug certificate's digest, `API_AUDIENCE` matching below), and is skipped without one:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=org.tamodak.killit.pairing.RealServerPairingTest \
 *   -Pandroid.testInstrumentationRunnerArguments.killitServer=http://10.0.2.2:18090 \
 *   -Pandroid.testInstrumentationRunnerArguments.killitAudience=10.0.2.2:18090
 * ```
 *
 * Its identities live under their own key namespaces, so the installed Killit's is never touched.
 */
@RunWith(AndroidJUnit4::class)
class RealServerPairingTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val baseUrl: String? = arguments.getString("killitServer")
    private val audience: String? = arguments.getString("killitAudience")
    private val phones = mutableListOf<RealPhone>()

    /** One phone: a real attested identity, a real API client, and an engine on an in-memory database. */
    private inner class RealPhone(namespace: String, val name: String) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val keys = DeviceKeys(context, namespace)
        lateinit var identity: DeviceKeys.Identity
        val api = ApiClient(baseUrl!!, audience!!, identity = { keys.current() })
        val database: KillitDatabase = Room.inMemoryDatabaseBuilder(context, KillitDatabase::class.java).build()
        val apps = FakeApps()
        val durable = FakeDurable()

        /** True once this phone deleted its registration and identity after moving away. */
        var forgotten = false

        val engine = PairingEngine(
            store = PairingStore(database.pairing(), durable),
            server = api,
            identity = { identity },
            apps = apps,
            durable = durable,
            notifier = RecordingNotifier(),
            config = PairingEngine.Config("Test $name", 30L * 24 * 60 * 60 * 1000, 300_000, 6, 7L * 24 * 60 * 60 * 1000, listAnswersPerHour = 4),
            canBeProtected = { true },
            forgetIdentity = {
                api.deleteDevice()
                keys.destroy()
                forgotten = true
            },
        )

        val dao get() = database.pairing()

        suspend fun register() {
            keys.destroy()
            val challenge = api.challenge().challenge.fromBase64Url(32)
            identity = keys.create(challenge)
            val answer = api.register(challenge, keys.attestationChain())
            assertEquals(deviceId(identity.fp), answer.device)
        }
    }

    @Before
    fun requireServer() {
        assumeTrue("Pass killitServer and killitAudience to run against a development server", baseUrl != null && audience != null)
    }

    @After
    fun forget(): Unit = runBlocking {
        for (phone in phones) {
            runCatching { phone.api.deleteDevice() }
            phone.keys.destroy()
        }
    }

    /** Three phones, each with its registered identity. */
    private suspend fun phones(vararg names: String): List<RealPhone> =
        names.map { name -> RealPhone("killit_e2e_${name.lowercase().replace(' ', '_')}", name).also { phones += it; it.register() } }

    /** [guardian] shows the code, [protected] types it, and both confirm. */
    private suspend fun pair(guardian: RealPhone, protected: RealPhone) {
        guardian.engine.ceremony.create(PairRole.GUARDIAN, guardian.name)
        val code = guardian.dao.ceremony()!!.code!!
        assertEquals(CeremonyEngine.JoinResult.JOINED, protected.engine.ceremony.join(code, PairRole.PROTECTED, protected.name))
        guardian.engine.followCeremony(0)
        protected.engine.followCeremony(0)
        guardian.engine.followCeremony(0)
        val onGuardian = guardian.dao.ceremony()!!
        assertEquals(CeremonyStage.COMPARING, onGuardian.stage)
        assertEquals(onGuardian.safetyCode, protected.dao.ceremony()!!.safetyCode)
        guardian.engine.ceremony.confirm()
        protected.engine.ceremony.confirm()
        guardian.engine.followCeremony(0)
        protected.engine.sync()
        guardian.engine.sync()
    }

    /** Moves [from] to [to]: the ceremony, the state, the user's confirmation and the handover. */
    private suspend fun move(from: RealPhone, to: RealPhone) {
        to.engine.startMoveIn()
        assertEquals(CeremonyEngine.JoinResult.JOINED, from.engine.startMoveOut(to.dao.ceremony()!!.code!!))
        to.engine.followCeremony(0)
        from.engine.followCeremony(0)
        to.engine.followCeremony(0)
        assertEquals(to.dao.ceremony()!!.safetyCode, from.dao.ceremony()!!.safetyCode)
        to.engine.ceremony.confirm()
        from.engine.ceremony.confirm()
        to.engine.followCeremony(0)
        from.engine.sync()
        to.engine.sync()
        from.engine.sync()
        assertEquals(MoveStage.READY, from.dao.ceremony()!!.moveStage)
        assertEquals(PairingEngine.MoveCommitResult.COMMITTED, from.engine.commitMove())
        assertTrue("the old phone lets go once the commit is out", from.forgotten)
        assertTrue(from.dao.pairings().isEmpty())
    }

    /** [protected] asks to unblock an app, [guardian] approves, and the app opens. */
    private suspend fun approveAnUnblock(guardian: RealPhone, protected: RealPhone) {
        protected.apps.install("com.example.game")
        assertTrue(protected.engine.askToUnblock("com.example.game", "My club posts its news there.") is RequestEngine.AskResult.Sent)
        guardian.engine.sync()
        val request = guardian.dao.observeIncoming().first().single { it.state.isOpen }
        assertTrue(guardian.engine.decide(request.pairId, request.requestId, approve = true, note = "Fine."))
        protected.engine.sync()
        assertEquals(RequestState.APPROVED, protected.dao.observeOutgoing().first().first().request.state)
        assertTrue("com.example.game" in protected.apps.unblocked)
    }

    @Test
    fun pairAndApproveThroughTheServer(): Unit = runBlocking {
        val (mom, ali) = phones("Mom", "Ali")
        assertEquals(deviceId(mom.identity.fp), mom.api.me().device)
        pair(mom, ali)
        val pairing = mom.database.pairing().pairings().single()
        assertArrayEquals(pairing.pairId, ali.database.pairing().pairings().single().pairId)
        assertEquals(PairRole.GUARDIAN, pairing.myRole)

        approveAnUnblock(mom, ali)

        mom.engine.refreshLastSeen()
        assertEquals(0, mom.database.pairing().pairings().single().lastSeenDaysAgo)
    }

    /** A guardian moves to a new phone; the server hands it the links, and it guards on. */
    @Test
    fun aGuardianMovesThroughTheServer(): Unit = runBlocking {
        val (mom, ali, momNew) = phones("Mom", "Ali", "Mom 2")
        pair(mom, ali)
        move(from = mom, to = momNew)

        ali.engine.sync()
        momNew.engine.sync()
        assertArrayEquals(momNew.identity.fp, ali.dao.guardians().single().peerFp)
        assertEquals(PairRole.GUARDIAN, momNew.dao.pairings().single().myRole)
        // The old phone deleted itself, its links with it; Ali's link is to the new phone now.
        val peers = ali.api.links().map { it.peer.fromBase64Url(32) }
        assertTrue(peers.any { it.contentEquals(momNew.identity.fp) })
        assertTrue(peers.none { it.contentEquals(mom.identity.fp) })
        approveAnUnblock(momNew, ali)
    }

    /** A protected phone moves to a new phone, which its guardian then guards. */
    @Test
    fun aProtectedPhoneMovesThroughTheServer(): Unit = runBlocking {
        val (mom, ali, aliNew) = phones("Mom", "Ali", "Ali 2")
        pair(mom, ali)
        ali.apps.install("com.example.chat")
        move(from = ali, to = aliNew)
        assertTrue("a guarded phone stays frozen", ali.engine.isFrozen())

        mom.engine.sync()
        aliNew.engine.sync()
        assertArrayEquals(aliNew.identity.fp, mom.dao.wards().single().peerFp)
        assertEquals(PairRole.PROTECTED, aliNew.dao.pairings().single().myRole)
        assertTrue("com.example.chat" in aliNew.apps.blocked)
        approveAnUnblock(mom, aliNew)
    }
}
