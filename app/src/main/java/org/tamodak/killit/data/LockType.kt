package org.tamodak.killit.data

/**
 * The three ways of entering the passkey.
 *
 * All three normalise to a single string before hashing, so verification has one code path:
 * a PIN is its digits, a password is its characters, and a pattern is its visited dot indices
 * joined with `-` (see `PatternLock.toPatternString`). The type is stored alongside the hash only
 * so the gate knows which input to show.
 *
 * The enum *name* is what gets persisted, so these constants must not be renamed without a
 * migration — a stored `PATTERN` that no longer parses reads as "no record" and sends the user to
 * setup with their apps still blocked.
 */
enum class LockType {
    /** Digits only. Normalises to the digits as typed. */
    PIN,

    /** Free-form text. Normalises to the characters as typed. */
    PASSWORD,

    /** A 3x3 grid gesture. Normalises to the visited dot indices joined with `-`. */
    PATTERN;

    companion object {
        /**
         * Parses a persisted name.
         *
         * @param name the stored enum name, or null when no record exists.
         * @return the matching constant, or null for anything unrecognised or absent.
         */
        fun fromName(name: String?): LockType? =
            entries.firstOrNull { it.name == name }
    }
}

/**
 * What Killit persists about the passkey. The credential itself is never stored — only a salted
 * hash of it.
 *
 * @param lockType which input the gate must show to collect this credential.
 * @param salt random per-record input, so two identical passkeys do not produce identical hashes.
 * @param hash `SHA-256(salt || credential)`.
 * @param failedAttempts consecutive wrong guesses; reset to zero on success.
 * @param lockoutUntilMillis wall-clock time (`System.currentTimeMillis`) before which no attempt
 *   is accepted. Zero means no lockout.
 */
data class CredentialRecord(
    val lockType: LockType,
    val salt: ByteArray,
    val hash: ByteArray,
    val failedAttempts: Int = 0,
    val lockoutUntilMillis: Long = 0L,
) {
    /**
     * Compares by content, which the generated implementation would not do.
     *
     * A data class compares [ByteArray] fields by identity, so the generated `equals` would report
     * two identical records as different. `LockRepository.reconcile()` compares records to decide
     * whether to rewrite a store, so getting this wrong would mean writing on every read.
     *
     * @param other the value to compare against.
     * @return true when every field matches, byte arrays by content.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialRecord) return false
        return lockType == other.lockType &&
            salt.contentEquals(other.salt) &&
            hash.contentEquals(other.hash) &&
            failedAttempts == other.failedAttempts &&
            lockoutUntilMillis == other.lockoutUntilMillis
    }

    /**
     * Hashes by content, to stay consistent with [equals].
     *
     * @return a hash derived from the byte arrays' contents rather than their identities.
     */
    override fun hashCode(): Int {
        var result = lockType.hashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + hash.contentHashCode()
        result = 31 * result + failedAttempts
        result = 31 * result + lockoutUntilMillis.hashCode()
        return result
    }
}
