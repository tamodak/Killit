package org.tamodak.killit.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Access to the `known_packages` table. Every function is main-safe; Room runs the queries. */
@Dao
interface KnownPackageDao {

    /** Every row, re-emitted whenever the table changes. */
    @Query("SELECT * FROM known_packages")
    fun observeAll(): Flow<List<KnownPackageEntity>>

    /** Every row, read once. */
    @Query("SELECT * FROM known_packages")
    suspend fun getAll(): List<KnownPackageEntity>

    /**
     * Inserts rows, replacing any with the same package name.
     *
     * @param rows the rows to write.
     */
    @Upsert
    suspend fun upsert(rows: List<KnownPackageEntity>)

    /** Removes every row. */
    @Query("DELETE FROM known_packages")
    suspend fun deleteAll()

    /**
     * Replaces the whole table in one transaction, so no reader sees it half-written.
     *
     * @param rows the complete new contents.
     */
    @Transaction
    suspend fun replaceAll(rows: List<KnownPackageEntity>) {
        deleteAll()
        upsert(rows)
    }
}
