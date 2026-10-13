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
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.RequestState
import org.tamodak.killit.protocol.deviceId
import org.tamodak.killit.protocol.fromBase64Url

/**
 * Two identities on this device pair through a real server and settle a request, end to end:
 * Keystore attestation, signed requests, the invite ceremony, links and the mailbox.
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
        val engine = PairingEngine(
            store = PairingStore(database.pairing(), FakeDurable()),
            server = api,
            identity = { identity },
            apps = apps,
            durable = FakeDurable(),
            notifier = RecordingNotifier(),
            config = PairingEngine.Config("Test $name", 30L * 24 * 60 * 60 * 1000, 300_000, 6, 7L * 24 * 60 * 60 * 1000, listAnswersPerHour = 4),
            canBeProtected = { true },
        )

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

    @Test
    fun pairAndApproveThroughTheServer(): Unit = runBlocking {
        val mom = RealPhone("killit_e2e_mom", "Mom").also { phones += it }
        val ali = RealPhone("killit_e2e_ali", "Ali").also { phones += it }
        mom.register()
        ali.register()
        assertEquals(deviceId(mom.identity.fp), mom.api.me().device)

        mom.engine.ceremony.create(PairRole.GUARDIAN, "Mom")
        val code = mom.database.pairing().ceremony()!!.code!!
        assertEquals(CeremonyEngine.JoinResult.JOINED, ali.engine.ceremony.join(code, PairRole.PROTECTED, "Ali"))
        mom.engine.followCeremony(0)
        ali.engine.followCeremony(0)
        mom.engine.followCeremony(0)
        val onMom = mom.database.pairing().ceremony()!!
        val onAli = ali.database.pairing().ceremony()!!
        assertEquals(CeremonyStage.COMPARING, onMom.stage)
        assertEquals(onMom.safetyCode, onAli.safetyCode)
        mom.engine.ceremony.confirm()
        ali.engine.ceremony.confirm()
        mom.engine.followCeremony(0)
        ali.engine.sync()
        mom.engine.sync()
        val pairing = mom.database.pairing().pairings().single()
        assertArrayEquals(pairing.pairId, ali.database.pairing().pairings().single().pairId)
        assertEquals(PairRole.GUARDIAN, pairing.myRole)

        ali.apps.install("com.example.game")
        assertTrue(ali.engine.askToUnblock("com.example.game", "My club posts its news there.") is RequestEngine.AskResult.Sent)
        mom.engine.sync()
        val request = mom.database.pairing().observeIncoming().first().single()
        assertTrue(mom.engine.decide(request.pairId, request.requestId, approve = true, note = "Fine."))
        ali.engine.sync()
        assertEquals(RequestState.APPROVED, ali.database.pairing().observeOutgoing().first().single().request.state)
        assertEquals(listOf("com.example.game"), ali.apps.unblocked)

        mom.engine.refreshLastSeen()
        assertEquals(0, mom.database.pairing().pairings().single().lastSeenDaysAgo)
    }
}
