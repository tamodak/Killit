package org.tamodak.killit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.tamodak.killit.data.KnownPackage
import org.tamodak.killit.data.PackageStatus

/**
 * One row of the `known_packages` table: the local copy of a [KnownPackage].
 *
 * A plain class rather than a data class, because the generated `equals` would compare the digest
 * by identity; code compares [KnownPackage] values instead.
 *
 * @param packageName the package the decision is about; one row per package.
 * @param certSha256 the signing-certificate digest the decision was made for.
 * @param status what was decided, stored by enum name.
 * @param sinceMillis when the package got this status, wall clock.
 */
@Entity(tableName = "known_packages")
class KnownPackageEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "cert_sha256", typeAffinity = ColumnInfo.BLOB)
    val certSha256: ByteArray,
    @ColumnInfo(name = "status")
    val status: PackageStatus,
    @ColumnInfo(name = "since_millis")
    val sinceMillis: Long,
) {
    /** @return the domain value this row stores. */
    fun toKnownPackage() = KnownPackage(packageName, certSha256, status, sinceMillis)

    companion object {
        /**
         * @param known the domain value to store.
         * @return the row for it.
         */
        fun from(known: KnownPackage) =
            KnownPackageEntity(known.packageName, known.certDigest, known.status, known.sinceMillis)
    }
}
