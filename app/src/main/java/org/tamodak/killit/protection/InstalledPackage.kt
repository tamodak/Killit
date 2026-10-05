package org.tamodak.killit.protection

import java.security.MessageDigest

/**
 * One installed package, as default-blocking needs to see it.
 *
 * Built by `PackageScanner` from the package manager; kept free of Android types so the decisions
 * made on it ([NewPackagePolicy]) can be tested on the JVM.
 *
 * @param packageName the package's name.
 * @param certDigest the digest of the certificates the package is signed with now; see
 *   [SigningDigest.ofSigners].
 * @param lineage the digest of every certificate in the package's signing-key rotation history,
 *   the current one included. Empty when the platform keeps no history: before Android 9, and for
 *   packages signed by several certificates, which cannot rotate.
 * @param isSystem preinstalled with the system image, or an update to such a package.
 * @param isLaunchable has an activity the launcher shows.
 * @param isOverlay a runtime resource overlay: a theme or OEM customisation with no code of its
 *   own. Only detectable from Android 10; false below that.
 * @param isEnabled not disabled by the user or the system.
 */
class InstalledPackage(
    val packageName: String,
    val certDigest: ByteArray,
    val lineage: List<ByteArray>,
    val isSystem: Boolean,
    val isLaunchable: Boolean,
    val isOverlay: Boolean,
    val isEnabled: Boolean,
) {
    /** Renders the package for logging, without the digests. */
    override fun toString() =
        "$packageName(system=$isSystem launchable=$isLaunchable overlay=$isOverlay enabled=$isEnabled)"
}

/** Turns signing certificates into the digest a decision is pinned to. */
object SigningDigest {

    /**
     * Digests one certificate.
     *
     * @param certificate the certificate's DER encoding.
     * @return its SHA-256 — the same value `apksigner verify --print-certs` shows.
     */
    fun of(certificate: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(certificate)

    /**
     * Digests the certificates an APK is currently signed with.
     *
     * Nearly every app has one, and gets that certificate's own digest. The rare app signed by
     * several gets a SHA-256 over their digests in sorted order, so the result does not depend on
     * the order the platform lists them in.
     *
     * @param certificates the DER encodings; at least one.
     * @return the digest the package's decision is pinned to.
     */
    fun ofSigners(certificates: List<ByteArray>): ByteArray {
        require(certificates.isNotEmpty()) { "A package is always signed by at least one certificate" }
        val digests = certificates.map(::of)
        if (digests.size == 1) return digests.single()
        return MessageDigest.getInstance("SHA-256").run {
            digests.sortedBy { it.toHex() }.forEach(::update)
            digest()
        }
    }

    /** @return the bytes as lowercase hex. */
    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
}
