package org.tamodak.killit.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tamodak.killit.protocol.Hpke
import org.tamodak.killit.protocol.P256
import org.tamodak.killit.protocol.fingerprint
import org.tamodak.killit.protocol.randomBytes
import org.tamodak.killit.protocol.verifyBundle

/**
 * The identity key in Android Keystore and the encryption keyset Tink keeps under a Keystore key.
 *
 * Runs under its own key namespace, so the identity of the Killit installed on the device is never
 * touched.
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeysTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keys = DeviceKeys(context, namespace = NAMESPACE)

    @Before
    fun clean(): Unit = runBlocking { keys.destroy() }

    @After
    fun tearDown(): Unit = runBlocking { keys.destroy() }

    /** A new identity is attested: a chain whose leaf holds the identity key. */
    @Test
    fun createdIdentityIsAttested(): Unit = runBlocking {
        assertNull(keys.current())
        val identity = keys.create(randomBytes(32))
        val chain = keys.attestationChain()
        assertTrue("an attestation chain has a leaf and an issuer at least", chain.size >= 2)
        val leaf = CertificateFactory.getInstance("X.509").generateCertificate(chain.first().inputStream())
        assertArrayEquals(identity.signPk, P256.point(leaf.publicKey as ECPublicKey))
        assertArrayEquals(fingerprint(identity.signPk), identity.fp)
    }

    /** The identity key's signatures verify under its public key, in the protocol's `r ‖ s` form. */
    @Test
    fun signaturesVerify(): Unit = runBlocking {
        val identity = keys.create(randomBytes(32))
        val data = "Killit".toByteArray()
        assertTrue(P256.verify(identity.signPk, data, identity.signer.sign(data)))
        assertTrue(verifyBundle(identity.bundle()))
    }

    /** What is sealed to the phone's encryption key, its keyset opens. */
    @Test
    fun envelopesSealedToThePhoneOpen(): Unit = runBlocking {
        val identity = keys.create(randomBytes(32))
        val info = "info".toByteArray()
        val sealed = Hpke.encrypter(identity.encPk).encrypt("hello".toByteArray(), info)
        assertArrayEquals("hello".toByteArray(), identity.decrypter.decrypt(sealed, info))
    }

    /** Both keys survive a new process: a fresh instance loads the same identity. */
    @Test
    fun identitySurvivesANewInstance(): Unit = runBlocking {
        val identity = keys.create(randomBytes(32))
        val reloaded = DeviceKeys(context, namespace = NAMESPACE).current()
        assertNotNull(reloaded)
        assertArrayEquals(identity.fp, reloaded!!.fp)
        assertArrayEquals(identity.encPk, reloaded.encPk)
        val sealed = Hpke.encrypter(identity.encPk).encrypt(byteArrayOf(1), byteArrayOf())
        assertArrayEquals(byteArrayOf(1), reloaded.decrypter.decrypt(sealed, byteArrayOf()))
    }

    /** Creating again replaces the identity key; the encryption key stays. */
    @Test
    fun createReplacesTheIdentityKey(): Unit = runBlocking {
        val first = keys.create(randomBytes(32))
        val second = keys.create(randomBytes(32))
        assertFalse(first.fp.contentEquals(second.fp))
        assertArrayEquals(first.encPk, second.encPk)
    }

    /** Destroying removes both keys. */
    @Test
    fun destroyRemovesBothKeys(): Unit = runBlocking {
        val first = keys.create(randomBytes(32))
        keys.destroy()
        assertNull(keys.current())
        val next = keys.create(randomBytes(32))
        assertFalse(first.encPk.contentEquals(next.encPk))
        assertEquals(32, next.encPk.size)
    }

    private companion object {
        const val NAMESPACE = "killit_test"
    }
}
