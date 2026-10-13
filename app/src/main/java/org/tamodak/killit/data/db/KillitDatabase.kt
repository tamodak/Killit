package org.tamodak.killit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.tamodak.killit.pairing.store.AppChancesEntity
import org.tamodak.killit.pairing.store.CeremonyEntity
import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.LinkDeletionEntity
import org.tamodak.killit.pairing.store.ManageSeqEntity
import org.tamodak.killit.pairing.store.MovePageEntity
import org.tamodak.killit.pairing.store.OutboxEntity
import org.tamodak.killit.pairing.store.OutgoingRequestEntity
import org.tamodak.killit.pairing.store.PairingDao
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.pairing.store.PendingUnlockEntity
import org.tamodak.killit.pairing.store.RequestCopyEntity
import org.tamodak.killit.pairing.store.SeenMessageEntity
import org.tamodak.killit.pairing.store.UnknownMessageEntity
import org.tamodak.killit.pairing.store.WardAppEntity
import org.tamodak.killit.pairing.store.WardListEntity
import org.tamodak.killit.pairing.store.WardListPageEntity

/**
 * Killit's local database.
 *
 * Like `LockPreferences`, everything here is wiped by "Clear data", which is why each table that
 * matters has a durable copy in the device owner's application restrictions as well. The schema of
 * every version is exported to `app/schemas`.
 *
 * Version 2 added pairing, version 3 guardians managing apps, and version 4 moving to a new phone.
 * Killit has no users yet (PLAN.md K5), so an older database is simply dropped: the known-package
 * list and a protected phone's guardians come back from their durable copies.
 */
@Database(
    entities = [
        KnownPackageEntity::class,
        PairingEntity::class,
        CeremonyEntity::class,
        OutgoingRequestEntity::class,
        RequestCopyEntity::class,
        IncomingRequestEntity::class,
        AppChancesEntity::class,
        SeenMessageEntity::class,
        OutboxEntity::class,
        LinkDeletionEntity::class,
        UnknownMessageEntity::class,
        EventEntity::class,
        PendingUnlockEntity::class,
        ManageSeqEntity::class,
        WardListEntity::class,
        WardListPageEntity::class,
        WardAppEntity::class,
        MovePageEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class KillitDatabase : RoomDatabase() {

    /** The known-package list behind default-blocking. */
    abstract fun knownPackages(): KnownPackageDao

    /** Pairings, requests, the mailbox's local state and the history. */
    abstract fun pairing(): PairingDao

    companion object {
        /** The database file's name in the app's data directory. */
        private const val FILE_NAME = "killit.db"

        /**
         * Builds the database. Cheap: Room opens the file on the first query, not here, so this can
         * run on the startup path.
         *
         * @param context any context; only its application context is retained.
         * @return the database, to be built exactly once per process.
         */
        fun create(context: Context): KillitDatabase =
            Room.databaseBuilder(context.applicationContext, KillitDatabase::class.java, FILE_NAME)
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3)
                .build()
    }
}
