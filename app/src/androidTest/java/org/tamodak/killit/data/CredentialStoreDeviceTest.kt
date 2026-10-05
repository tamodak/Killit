package org.tamodak.killit.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Passkey hashing, through the native Argon2 library as it is packaged in the app.
 *
 * Instrumented because the implementation is native code built for Android; there is no JVM
 * equivalent to run it against.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreDeviceTest {

    /** The store under test, with its production parameters. */
    private val credentials = CredentialStore()

    /**
     * The production parameters reproduce independently computed Argon2id values, including for a
     * passkey outside ASCII, which pins down that characters reach the native code as UTF-8.
     */
    @Test
    fun matchesKnownAnswers(): Unit = runBlocking {
        KNOWN_ANSWERS.forEach { (credential, salt, expectedHex) ->
            val hex = credentials.hash(credential, salt).joinToString(separator = "") { "%02x".format(it) }
            assertEquals(credential, expectedHex, hex)
        }
    }

    /**
     * The hash is reproducible from the same inputs, and changes when either input changes.
     *
     * Determinism is what makes verification possible at all; salt sensitivity is what stops two
     * users with the same passkey producing the same stored hash.
     */
    @Test
    fun hashingIsSaltedAndDeterministic(): Unit = runBlocking {
        val salt = credentials.newSalt()
        val hash = credentials.hash(PASSKEY, salt)

        assertTrue("The same input must produce the same hash", credentials.matches(hash, credentials.hash(PASSKEY, salt)))
        assertFalse(
            "A different salt must produce a different hash",
            credentials.matches(hash, credentials.hash(PASSKEY, credentials.newSalt())),
        )
        assertFalse(
            "A different passkey must produce a different hash",
            credentials.matches(hash, credentials.hash("1-2-3-4", salt)),
        )
        assertEquals("Expected a 32-byte hash", 32, hash.size)
    }

    private companion object {
        /** A pattern in its normalised form: visited dot indices joined with `-`. */
        const val PASSKEY = "0-3-6-7"

        /**
         * Computed with argon2-cffi's `hash_secret_raw(credential.encode("utf-8"), salt, time_cost=3,
         * memory_cost=65536, parallelism=4, hash_len=32, type=Type.ID, version=19)`, which itself
         * reproduces the Argon2 reference implementation's published test vectors. Recompute them if
         * the parameters in [CredentialStore] change.
         */
        val KNOWN_ANSWERS = listOf(
            Triple(
                "0-3-6-7",
                ByteArray(16) { it.toByte() },
                "95eb6b93b59c74c9075d7fa4d62b09e5e845fcc9b6ba3da65d33387c25696513",
            ),
            Triple(
                "şifre-Ğüİ-كلمة",
                ByteArray(16) { (16 + it).toByte() },
                "395ba0ef3e4536c8f8b8ddbd49b460fdfc99a5898109c6109c7b7f61991468fd",
            ),
        )
    }
}
