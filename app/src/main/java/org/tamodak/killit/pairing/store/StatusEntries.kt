package org.tamodak.killit.pairing.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One guardian's answer as a STATUS reported it (SPEC.md §11.7): what a guardian sees of the
 * others' decisions (G10).
 *
 * @param fp the guardian's fingerprint, in hex.
 * @param name the guardian's own name; not editable by the protected phone.
 */
@Serializable
class StatusEntryRecord(val fp: String, val name: String, val vote: Vote)

/**
 * The list of [StatusEntryRecord]s an incoming request keeps in one text column. It is replaced
 * whole by every STATUS, so a table of its own would buy nothing.
 */
object StatusEntries {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StatusEntryRecord.serializer())

    fun encode(entries: List<StatusEntryRecord>): String = json.encodeToString(serializer, entries)

    /** @return the entries, or none when the column holds something unreadable. */
    fun decode(text: String): List<StatusEntryRecord> = runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())
}
