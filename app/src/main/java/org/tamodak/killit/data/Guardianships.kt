package org.tamodak.killit.data

/**
 * Encodes the guardianship set for storage.
 *
 * A guardianship is a device that depends on **this** phone's private key to open. They are
 * recorded as Base64 fingerprints only, because that is all a challenge carries — this phone never
 * learns a label for a device it is a companion of. Losing this key is unrecoverable for every
 * device listed, which is why this list, not the peer list, is what has to block giving up device
 * owner.
 *
 * Newline-separated: a fingerprint is Base64, so it cannot itself contain a newline, and the format
 * stays greppable in a preferences dump.
 *
 * @param fingerprints Base64 fingerprints of the devices this phone can open.
 * @return the encoded form, ready to hand to either store.
 */
internal fun serializeGuardianships(fingerprints: Set<String>): String =
    fingerprints.joinToString(separator = "\n")

/**
 * Decodes what [serializeGuardianships] wrote.
 *
 * Blank lines are dropped rather than rejected, so a trailing newline or a partially written value
 * degrades to a smaller set instead of throwing on a path that runs during unlock.
 *
 * @param raw the stored value, or null when nothing has been written yet.
 * @return the fingerprints, or an empty set for null or blank input.
 */
internal fun deserializeGuardianships(raw: String?): Set<String> =
    raw?.lineSequence()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
