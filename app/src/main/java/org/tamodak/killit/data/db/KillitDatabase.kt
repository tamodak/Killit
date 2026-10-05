package org.tamodak.killit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Killit's local database.
 *
 * Like `LockPreferences`, everything here is wiped by "Clear data", which is why each table that
 * matters has a durable copy in the device owner's application restrictions as well. The schema of
 * every version is exported to `app/schemas`.
 */
@Database(entities = [KnownPackageEntity::class], version = 1, exportSchema = true)
abstract class KillitDatabase : RoomDatabase() {

    /** The known-package list behind default-blocking. */
    abstract fun knownPackages(): KnownPackageDao

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
                .build()
    }
}
