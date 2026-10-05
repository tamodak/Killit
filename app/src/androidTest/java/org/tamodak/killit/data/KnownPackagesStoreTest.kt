package org.tamodak.killit.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.tamodak.killit.admin.DevicePolicyController
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.db.KillitDatabase
import org.tamodak.killit.data.db.KnownPackageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The known-package list stays whole when one of its two copies is lost or falls behind.
 *
 * The local table is an in-memory database, so "Clear data" can be simulated by emptying it; the
 * durable copy is the real one, which needs Killit as device owner. Everything the device's own
 * restrictions held is put back afterwards.
 *
 * On a device where Killit is protecting the phone, the protection service may write the same
 * durable entry while a test runs; run these on a device where nothing is being installed.
 */
@RunWith(AndroidJUnit4::class)
class KnownPackagesStoreTest {

    /** The instrumentation context, which runs as Killit and so as the device owner. */
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The real policy controller, for the durable copy. */
    private val dpc = DevicePolicyController(context)

    /** The durable copy under test. */
    private val durable = DurableStore(dpc)

    /** A local table that lives only as long as the test. */
    private val database = Room.inMemoryDatabaseBuilder(context, KillitDatabase::class.java).build()

    /** The store under test. */
    private val store = KnownPackagesStore(database.knownPackages(), durable)

    /** What the device's stores held before the test. */
    private var snapshot: PersistedStateSnapshot? = null

    /** Skips unless Killit is device owner, then saves the device's own state. */
    @Before
    fun setUp(): Unit = runBlocking {
        KillitLog.verbose = true
        assumeTrue("Needs Killit as device owner", durable.isAvailable())
        snapshot = PersistedStateSnapshot.take(LockPreferences(context), dpc)
        durable.clearKnownPackages()
    }

    /** Puts the device's own state back and closes the scratch database. */
    @After
    fun tearDown(): Unit = runBlocking {
        snapshot?.restore()
        database.close()
    }

    /** A change reaches both copies. */
    @Test
    fun putWritesBothCopies(): Unit = runBlocking {
        store.put(listOf(APPROVED))

        assertEquals(mapOf(APPROVED.packageName to APPROVED), database.knownPackages().getAll().toMap())
        assertEquals(mapOf(APPROVED.packageName to APPROVED), durable.readKnownPackages())
    }

    /** After "Clear data" empties the table, the durable copy is restored into it. */
    @Test
    fun anEmptiedTableIsRestoredFromDurable(): Unit = runBlocking {
        store.put(listOf(APPROVED, PENDING))
        database.knownPackages().deleteAll()

        val restored = store.readAll()

        val expected = listOf(APPROVED, PENDING).associateBy { it.packageName }
        assertEquals(expected, restored)
        assertEquals("The table must hold the restored entries", expected, database.knownPackages().getAll().toMap())
    }

    /** A durable copy that missed a write is brought back in line with the table. */
    @Test
    fun aStaleDurableCopyIsRewritten(): Unit = runBlocking {
        store.put(listOf(APPROVED))
        // A change that reached the table but not the durable copy.
        database.knownPackages().upsert(listOf(KnownPackageEntity.from(PENDING)))

        store.readAll()

        assertEquals(listOf(APPROVED, PENDING).associateBy { it.packageName }, durable.readKnownPackages())
    }

    /** @return the rows as domain values, keyed by package name. */
    private fun List<KnownPackageEntity>.toMap() = associate { it.packageName to it.toKnownPackage() }

    private companion object {
        val APPROVED = KnownPackage("com.example.allowed", ByteArray(32) { 5 }, PackageStatus.APPROVED, 1L)
        val PENDING = KnownPackage("com.example.waiting", ByteArray(32) { 6 }, PackageStatus.PENDING, 2L)
    }
}
