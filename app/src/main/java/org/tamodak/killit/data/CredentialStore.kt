package org.tamodak.killit.data

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hashes the passkey for storage, and checks an entered passkey against the stored hash.
 *
 * ### Why a slow, memory-hard function
 *
 * The passkey protects more than the device it is set on: a guardian's passkey is what approves
 * another person's requests. If the stored hash ever leaked it could be attacked offline, one guess
 * after another. Argon2id makes every guess fill [MEMORY_KIB] of memory and pass over it
 * [ITERATIONS] times, so guessing in bulk on GPUs or custom hardware gets expensive, not merely
 * slow. That puts a reasonable password out of reach. It cannot do the same for a short PIN or a
 * pattern — there are too few of them — so for those the real protection remains that the hash
 * never leaves the device: it lives in app-private storage and the device owner's own application
 * restrictions.
 *
 * The parameters are RFC 9106's second recommended option, its uniformly safe choice where memory
 * is limited: t = 3, p = 4, m = 64 MiB, a 128-bit salt and a 256-bit output. That costs a fraction
 * of a second per unlock on a phone.
 *
 * The platform has no Argon2, so this is the Argon2 reference C implementation, called over JNI
 * through argon2kt.
 *
 * ### Threading
 *
 * Hashing is deliberately expensive, so [hash] moves itself onto [dispatcher] and is safe to call
 * from the main thread. The native library is loaded by the first hash, on that dispatcher, rather
 * than when this class is built, because construction happens on the app's startup path.
 *
 * @param dispatcher where hashing runs; `Default`, because the work is CPU-bound.
 */
class CredentialStore(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /** The JNI binding. Creating it loads the native library, so it waits for the first hash. */
    private val argon2 by lazy { Argon2Kt() }

    /**
     * Draws a fresh random salt for a new record.
     *
     * @return [SALT_BYTES] bytes from [SecureRandom].
     */
    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Computes Argon2id of the passkey with the parameters described on the class.
     *
     * The UTF-8 bytes handed to the native code are zeroed afterwards. The `String` they came from
     * cannot be, which is why no caller keeps it either.
     *
     * @param credential the normalised passkey string. Never logged, never stored.
     * @param salt the record's salt, from [newSalt].
     * @return the [HASH_BYTES]-byte value to store or compare.
     */
    suspend fun hash(credential: String, salt: ByteArray): ByteArray = withContext(dispatcher) {
        val password = credential.toByteArray(Charsets.UTF_8)
        try {
            argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = password,
                salt = salt,
                tCostInIterations = ITERATIONS,
                mCostInKibibyte = MEMORY_KIB,
                parallelism = PARALLELISM,
                hashLengthInBytes = HASH_BYTES,
                version = Argon2Version.V13,
            ).rawHashAsByteArray()
        } finally {
            password.fill(0)
        }
    }

    /**
     * Compares two hashes in constant time.
     *
     * [MessageDigest.isEqual] compares every byte rather than returning early on the first
     * mismatch, so how long it takes reveals nothing about how much of the hash a guess got right.
     *
     * @param candidate the hash derived from what the user just entered.
     * @param stored the hash from the credential record.
     * @return true when the two match.
     */
    fun matches(candidate: ByteArray, stored: ByteArray): Boolean =
        MessageDigest.isEqual(candidate, stored)

    companion object {
        /** Passes over memory: Argon2's `t`. */
        const val ITERATIONS = 3

        /** Memory per hash in KiB, 64 MiB: Argon2's `m`. */
        const val MEMORY_KIB = 65_536

        /** Independent lanes, computed on as many threads: Argon2's `p`. */
        const val PARALLELISM = 4

        /** Salt width: 128 bits, as RFC 9106 recommends. */
        private const val SALT_BYTES = 16

        /** Output width: 256 bits, as RFC 9106 recommends. */
        private const val HASH_BYTES = 32
    }
}
