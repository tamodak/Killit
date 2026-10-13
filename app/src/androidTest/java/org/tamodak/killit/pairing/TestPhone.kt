package org.tamodak.killit.pairing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import org.tamodak.killit.data.DurableAppChances
import org.tamodak.killit.data.DurableGuardian
import org.tamodak.killit.data.DurablePairingStore
import org.tamodak.killit.data.MovedAway
import org.tamodak.killit.data.UnlockLimits
import org.tamodak.killit.data.db.KillitDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.tamodak.killit.pairing.store.CeremonyEntity
import org.tamodak.killit.pairing.store.CeremonyStage
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.OutgoingRequestEntity
import org.tamodak.killit.pairing.store.PairRole
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.protection.RemoteBlock
import org.tamodak.killit.protocol.AppChange
import org.tamodak.killit.protocol.AppInfo
import org.tamodak.killit.protocol.AppRef
import org.tamodak.killit.protocol.AppState
import org.tamodak.killit.protocol.ListedApp
import org.tamodak.killit.protocol.Vectors

/** A shared, hand-driven clock. */
class TestClock(var millis: Long = 1_790_000_000_000L) {
    val now: () -> Long = { millis }
}

/** Runs a whole ceremony: [creator] shows the code, [joiner] types it, and both confirm the safety code. */
suspend fun pairPhones(creator: EnginePhone, creatorRole: PairRole, joiner: EnginePhone, joinerRole: PairRole) {
    creator.engine.ceremony.create(creatorRole, creator.name)
    val code = creator.ceremony()!!.code!!
    assertEquals(CeremonyEngine.JoinResult.JOINED, joiner.engine.ceremony.join(code.lowercase(), joinerRole, joiner.name))
    creator.engine.followCeremony(0)
    joiner.engine.followCeremony(0)
    creator.engine.followCeremony(0)
    val a = creator.ceremony()!!
    val b = joiner.ceremony()!!
    assertEquals(CeremonyStage.COMPARING, a.stage)
    assertEquals(CeremonyStage.COMPARING, b.stage)
    assertEquals(a.safetyCode, b.safetyCode)
    assertArrayEquals(a.pairId, b.pairId)
    creator.engine.ceremony.confirm()
    joiner.engine.ceremony.confirm()
    creator.engine.followCeremony(0)
    joiner.engine.sync()
    creator.engine.sync()
}

/**
 * One phone in a pairing test: the keys of a vector test phone ("A", "B", "C"), an in-memory
 * database, fake app blocking, a fake durable copy, and a real [PairingEngine].
 */
class EnginePhone(
    vectorName: String,
    val name: String,
    server: FakeServer,
    clock: TestClock,
    database: KillitDatabase? = null,
    val durable: FakeDurable = FakeDurable(),
    unpairTimerMillis: Long = 30L * 24 * 60 * 60 * 1000,
) {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val vector = Vectors.phone(vectorName)
    val identity = DeviceKeys.Identity(vector.signPk, vector.fp, vector.encPk, vector.signer, vector.decrypter, strongBox = false)
    val fp: ByteArray get() = identity.fp
    val database: KillitDatabase = database ?: Room.inMemoryDatabaseBuilder(context, KillitDatabase::class.java).build()
    val apps = FakeApps()
    val notifier = RecordingNotifier()

    /** Each time the guarded policies were applied: true with guardians, false without. */
    val guarded = mutableListOf<Boolean>()

    /** Whether this phone could take guardians: device owner, with a passkey. */
    var deviceOwner = true

    /** This phone's protection options, as MOVE_STATE's bits. */
    var protection = 0

    /** The protection options moves brought, in order. */
    val adoptedProtection = mutableListOf<Int>()

    /** True once this phone deleted its identity after moving away. */
    var forgotten = false
    val store = PairingStore(this.database.pairing(), durable)
    val engine = PairingEngine(
        store = store,
        server = server.client(vector.fp),
        identity = { identity },
        apps = apps,
        durable = durable,
        notifier = notifier,
        config = PairingEngine.Config(
            deviceModel = "Test $name",
            unpairTimerMillis = unpairTimerMillis,
            unlockCooldownMillis = 5 * 60 * 1000,
            unlocksPerDay = 6,
            silenceAlertMillis = 7L * 24 * 60 * 60 * 1000,
            listAnswersPerHour = 4,
        ),
        canBeProtected = { deviceOwner },
        onGuarded = { guarded += it },
        protectionBits = { protection },
        adoptProtection = { adoptedProtection += it },
        forgetIdentity = {
            forgotten = true
            server.deleteDevice(vector.fp)
        },
        now = clock.now,
    )
    val dao = this.database.pairing()

    suspend fun ceremony(): CeremonyEntity? = dao.ceremony()

    suspend fun pairings(): List<PairingEntity> = dao.pairings()

    suspend fun pairingWith(peer: EnginePhone): PairingEntity? = dao.pairingsWith(peer.fp).singleOrNull()
}

/** App blocking without a device owner: a set of blocked packages and their digests. */
class FakeApps : AppBlocker {
    val blocked = mutableSetOf<String>()
    val digests = mutableMapOf<String, ByteArray>()
    val unblocked = mutableListOf<String>()

    /** Packages Android would refuse to suspend. */
    val unblockable = mutableSetOf<String>()

    /** Installs and blocks an app. */
    fun install(packageName: String, digest: ByteArray = ByteArray(32) { packageName.length.toByte() }) {
        digests[packageName] = digest
        blocked += packageName
    }

    /** Installs an app that stays allowed. */
    fun installAllowed(packageName: String, digest: ByteArray = ByteArray(32) { packageName.length.toByte() }) {
        digests[packageName] = digest
    }

    override suspend fun blockRemotely(packageName: String, certDigest: ByteArray): RemoteBlock = when {
        packageName in unblockable -> RemoteBlock.REFUSED
        packageName !in digests -> RemoteBlock.NOT_INSTALLED
        !blocked.add(packageName) -> RemoteBlock.ALREADY_BLOCKED
        else -> RemoteBlock.BLOCKED
    }

    /** What a move handed this phone. */
    val adopted = mutableListOf<Pair<AppRef, AppState>>()

    /** Apps a move should treat as installed here by the user and never decided about. */
    val installedHereOnly = mutableSetOf<String>()

    override suspend fun decisionsToMove(): List<Pair<AppRef, AppState>> = digests.map { (name, digest) ->
        AppRef(name, digest) to if (name in blocked) AppState.BLOCKED else AppState.ALLOWED
    }

    override suspend fun adoptMoved(decisions: List<Pair<AppRef, AppState>>): List<String> {
        adopted += decisions
        for ((app, state) in decisions) {
            digests[app.packageName] = app.certDigest
            if (state == AppState.ALLOWED) blocked -= app.packageName else blocked += app.packageName
        }
        blocked += installedHereOnly
        return installedHereOnly.toList()
    }

    override suspend fun listForGuardians(): List<ListedApp> = digests.map { (name, digest) ->
        ListedApp(name, digest, "App ${name.substringAfterLast('.')}", "com.android.vending", 1_789_000_000_000L,
            if (name in blocked) AppState.BLOCKED else AppState.ALLOWED, null)
    }

    override suspend fun describe(packageName: String): AppInfo? = digests[packageName]?.let {
        AppInfo(packageName, it, "App ${packageName.substringAfterLast('.')}", "com.android.vending", 1_789_000_000_000L, null)
    }

    override suspend fun isBlocked(packageName: String): Boolean = packageName in blocked

    override suspend fun unblockApproved(packageName: String, certDigest: ByteArray): Boolean {
        if (digests[packageName]?.contentEquals(certDigest) != true) return false
        blocked -= packageName
        unblocked += packageName
        return true
    }
}

/** A durable copy in memory, which a test can keep while "Clear data" wipes the database. */
class FakeDurable(var available: Boolean = true) : DurablePairingStore {
    var guardians: List<DurableGuardian> = emptyList()
    var chances: List<DurableAppChances> = emptyList()
    var limits: UnlockLimits? = null
    var appManagement = false
    var movedAway: MovedAway? = null

    /** How long [clearPairing] takes, as a real write through system_server can. */
    var clearDelayMillis = 0L

    override suspend fun isAvailable() = available

    override suspend fun readMovedAway() = if (available) movedAway else null

    override suspend fun readAppManagement() = if (available) appManagement else null

    override suspend fun writeAppManagement(enabled: Boolean): Boolean {
        appManagement = enabled
        return true
    }

    override suspend fun readGuardians() = guardians

    override suspend fun writeGuardians(guardians: Collection<DurableGuardian>): Boolean {
        this.guardians = guardians.toList()
        return true
    }

    override suspend fun readAppChances() = chances

    override suspend fun writeAppChances(chances: Collection<DurableAppChances>): Boolean {
        this.chances = chances.toList()
        return true
    }

    override suspend fun readUnlockLimits() = limits

    override suspend fun writeUnlockLimits(limits: UnlockLimits): Boolean {
        this.limits = limits
        return true
    }

    override suspend fun clearPairing(movedAway: MovedAway?): Boolean {
        if (clearDelayMillis > 0) delay(clearDelayMillis)
        guardians = emptyList()
        chances = emptyList()
        limits = null
        appManagement = false
        this.movedAway = movedAway
        return true
    }
}

/** Records what the engine would have told the user. */
class RecordingNotifier : PairingNotifier {
    val events = mutableListOf<String>()

    override suspend fun requestArrived(from: PairingEntity, request: IncomingRequestEntity) {
        events += "arrived ${request.type}"
    }

    override suspend fun requestSettled(from: PairingEntity, request: IncomingRequestEntity) {
        events += "settled ${request.type}"
    }

    override suspend fun requestClosed(request: OutgoingRequestEntity) {
        events += "closed ${request.type} ${request.state}"
    }

    override suspend fun guardianAnswered(guardian: PairingEntity, request: OutgoingRequestEntity, approved: Boolean, note: String) {
        events += "answered ${request.type} $approved"
    }

    override suspend fun appManaged(guardian: PairingEntity, label: String, change: AppChange) {
        events += "managed $change"
    }

    override suspend fun pairingEnded(pairing: PairingEntity, event: EventEntity) {
        events += "ended ${event.type}"
    }

    override suspend fun noticeArrived(from: PairingEntity, event: EventEntity) {
        events += "notice ${event.type}"
    }

    override suspend fun peerMoved(pairing: PairingEntity, event: EventEntity) {
        events += "moved ${pairing.myRole}"
    }

    override suspend fun silence(pairing: PairingEntity) {
        events += "silence"
    }

    override suspend fun updateNeeded() {
        events += "update needed"
    }
}
