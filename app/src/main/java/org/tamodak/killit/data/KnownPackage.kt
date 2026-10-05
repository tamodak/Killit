package org.tamodak.killit.data

/**
 * What default-blocking has decided about a package.
 *
 * The enum *name* is what gets persisted, in both the local table and the durable copy, so these
 * constants must not be renamed once released.
 */
enum class PackageStatus {
    /**
     * Allowed. Installed before protection started, approved automatically as a part of the system
     * (see `NewPackagePolicy`), or unblocked by the user.
     */
    APPROVED,

    /** Installed after protection started and blocked automatically; nobody has decided yet. */
    PENDING,

    /** Blocked on purpose: ticked in the app list, or a new app the user chose to keep blocked. */
    USER_BLOCKED;

    /** True when Killit keeps the package suspended. */
    val isBlocked: Boolean get() = this != APPROVED

    companion object {
        /**
         * Parses a persisted name.
         *
         * @param name the stored enum name.
         * @return the matching constant, or null for anything unrecognised or absent.
         */
        fun fromName(name: String?): PackageStatus? = entries.firstOrNull { it.name == name }
    }
}

/**
 * One package Killit has seen while protecting the phone, and what was decided about it.
 *
 * A decision belongs to a package name *and* the certificate it was signed with. Otherwise a
 * different app that reuses an approved package name — a sideloaded look-alike — would inherit the
 * approval. Updates signed with the same key, or with a key the old one vouched for through
 * signing-key rotation, keep the decision (see `NewPackagePolicy`).
 *
 * Entries outlive the app they describe: an uninstalled app keeps its entry, so reinstalling it
 * with the same certificate brings back the same decision instead of counting as new.
 *
 * @param packageName the package the decision is about.
 * @param certDigest the SHA-256 of the signing certificate the decision was made for; for the rare
 *   app signed by several certificates, a digest over all of them (see `SigningDigest`).
 * @param status what was decided.
 * @param sinceMillis when the package got this status, wall clock.
 */
data class KnownPackage(
    val packageName: String,
    val certDigest: ByteArray,
    val status: PackageStatus,
    val sinceMillis: Long,
) {
    /**
     * Compares by content, which the generated implementation would not do for [certDigest].
     *
     * @param other the value to compare against.
     * @return true when every field matches, the digest by content.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnownPackage) return false
        return packageName == other.packageName &&
            certDigest.contentEquals(other.certDigest) &&
            status == other.status &&
            sinceMillis == other.sinceMillis
    }

    /**
     * Hashes by content, to stay consistent with [equals].
     *
     * @return a hash derived from the digest's content rather than its identity.
     */
    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + certDigest.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + sinceMillis.hashCode()
        return result
    }
}
