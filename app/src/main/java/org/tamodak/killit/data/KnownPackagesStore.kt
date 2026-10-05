package org.tamodak.killit.data

import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.data.db.KnownPackageDao
import org.tamodak.killit.data.db.KnownPackageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The known-package list, kept in two places like the passkey: the local table, and the durable
 * copy in the device owner's application restrictions that survives "Clear data".
 *
 * ### Which copy wins
 *
 * Both copies are written on every change, so they can only disagree in two ways:
 *
 * - The table is empty while the durable copy is not: "Clear data" wiped the app's files. The
 *   durable copy is restored into the table — that restore is the reason it exists. Without it, the
 *   next check would take a fresh snapshot and approve every app that was waiting for a decision.
 * - Both hold entries, but different ones: a durable write failed at some point. The table is then
 *   the more recent of the two, so it is written to the durable copy again.
 *
 * Callers that change the list hold `PackageGuard`'s lock, so this class does not serialise them
 * itself. Every function is main-safe: Room and the device policy service run on their own threads.
 *
 * @param dao the local table.
 * @param durable the clear-data-proof copy.
 */
class KnownPackagesStore(
    private val dao: KnownPackageDao,
    private val durable: DurableStore,
) {

    /** The list as the local table holds it, re-emitted on every change. Backs the app list. */
    val packages: Flow<Map<String, KnownPackage>> =
        dao.observeAll().map { rows -> rows.toKnownPackages() }

    /**
     * Reads the effective list, bringing the two copies back in step first.
     *
     * @return every known package, keyed by name; empty when protection has never started.
     */
    suspend fun readAll(): Map<String, KnownPackage> {
        val local = dao.getAll().toKnownPackages()
        val stored = if (durable.isAvailable()) durable.readKnownPackages() else null
        return when {
            stored == null -> local

            local.isEmpty() && stored.isNotEmpty() -> {
                KillitLog.i(KillitLog.REPO, "Known packages missing locally; restoring ${stored.size} from durable storage")
                dao.replaceAll(stored.values.map(KnownPackageEntity::from))
                stored
            }

            local != stored -> {
                KillitLog.i(KillitLog.REPO, "Durable known packages are stale; rewriting ${local.size} entries")
                durable.writeKnownPackages(local.values)
                local
            }

            else -> local
        }
    }

    /**
     * Adds entries, or replaces those with the same package name, in both copies.
     *
     * @param packages the entries to write.
     */
    suspend fun put(packages: Collection<KnownPackage>) {
        if (packages.isEmpty()) return
        dao.upsert(packages.map(KnownPackageEntity::from))
        if (durable.isAvailable()) {
            durable.writeKnownPackages(dao.getAll().map { it.toKnownPackage() })
        }
        KillitLog.d(KillitLog.REPO) { "Known packages updated: ${packages.joinToString { "${it.packageName}=${it.status}" }}" }
    }

    /** Empties both copies, so protection starting again takes a fresh snapshot. */
    suspend fun clear() {
        KillitLog.i(KillitLog.REPO, "Clearing the known-package list")
        dao.deleteAll()
        if (durable.isAvailable()) durable.clearKnownPackages()
    }

    /** @return the rows as domain values, keyed by package name. */
    private fun List<KnownPackageEntity>.toKnownPackages(): Map<String, KnownPackage> =
        associate { it.packageName to it.toKnownPackage() }
}
