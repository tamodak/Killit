package org.tamodak.killit.pairing

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.core.content.edit
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.protocol.Bundle
import org.tamodak.killit.protocol.Hpke
import org.tamodak.killit.protocol.P256
import org.tamodak.killit.protocol.Signer
import org.tamodak.killit.protocol.fingerprint
import org.tamodak.killit.protocol.signEncKey

/**
 * This phone's two keys (SPEC.md §3, PLAN.md §5.1).
 *
 * - **The identity key** signs every API request and every message. It is a P-256 key generated
 *   inside Android Keystore — StrongBox when the phone has one, the TEE otherwise — and it never
 *   leaves the secure hardware. Its fingerprint is the phone's address. It is generated with the
 *   server's attestation challenge, so the server can check that it really is hardware-held by
 *   Killit (SPEC.md §5.2).
 * - **The encryption key** opens envelopes sent to this phone. It is X25519, which Keystore can't
 *   hold on every supported Android version (key agreement needs API 31), so it lives in a Tink
 *   keyset that a Keystore AES-GCM key encrypts at rest. Leaking it would expose message contents,
 *   never authority: everything that matters is signed.
 *
 * Both live in app storage and Keystore, so **Clear data deletes both**: the phone becomes a new
 * identity that its peers don't know. A guarded phone's pairings survive in the durable store,
 * which keeps it locked; it then has to win back its freedom through the local UNPAIR timer, the
 * escape the protocol guarantees whatever happens to the network.
 *
 * Every Keystore and keyset operation is a blocking call into a system service, so everything here
 * runs on [io]. The [Signer] it hands out blocks too: callers sign on a background thread.
 *
 * @param context any context; only its application context is retained.
 * @param namespace prefixes every Keystore alias and file name. Tests use their own, so they never
 *   touch the identity of the Killit installed on the same device.
 */
class DeviceKeys(
    context: Context,
    namespace: String = "killit",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext

    /** The identity key. */
    private val signAlias = "${namespace}_identity"

    /** The Keystore key that encrypts the encryption keyset. */
    private val masterKeyAlias = "${namespace}_keyset_master"

    /** Where Tink keeps the encrypted keyset. */
    private val keysetPrefs = "${namespace}_keyset"

    /** Serialises loading and generation, so two callers never create two keys. */
    private val lock = Mutex()

    /** The loaded identity; cached because loading the keyset costs a Keystore decryption. */
    @Volatile
    private var cached: Identity? = null

    /**
     * The phone's identity, with everything derived from its keys.
     *
     * @param signPk the identity key's public point, 65 bytes.
     * @param fp `fingerprint(signPk)`: the phone's address.
     * @param encPk the encryption key's public half, 32 bytes.
     * @param signer signs with the identity key. Blocking: call it off the main thread.
     * @param decrypter opens envelopes sealed to [encPk].
     * @param strongBox true when the identity key is in StrongBox rather than the TEE.
     */
    class Identity(
        val signPk: ByteArray,
        val fp: ByteArray,
        val encPk: ByteArray,
        val signer: Signer,
        val decrypter: HybridDecrypt,
        val strongBox: Boolean,
    ) {
        /** A fresh bundle for a pairing ceremony: the keys, with `encSig` binding them (SPEC.md §3.2). */
        fun bundle(): Bundle = Bundle(signPk, encPk, signEncKey(signer, encPk))
    }

    /**
     * Loads the identity, if this phone has one.
     *
     * @return the identity, or null when the identity key doesn't exist yet. A missing keyset next
     *   to an existing identity key is created on the spot: it only means this phone has never
     *   finished registering.
     */
    suspend fun current(): Identity? = lock.withLock { cached ?: withContext(io) { load() }?.also { cached = it } }

    /**
     * Creates a new identity key attested with [challenge], replacing any existing one.
     *
     * Only for a phone the server doesn't know yet: an attestation can't be renewed, so a key whose
     * registration failed for good is thrown away and generated again with a fresh challenge.
     * Replacing a registered key would orphan every pairing, so [Registration] never calls this
     * once registration has succeeded.
     *
     * @param challenge the server's 32-byte attestation challenge (SPEC.md §5.1).
     * @return the new identity.
     */
    suspend fun create(challenge: ByteArray): Identity = lock.withLock {
        withContext(io) {
            cached = null
            generateSigningKey(challenge)
            checkNotNull(load()) { "the identity key vanished right after it was generated" }
        }.also { cached = it }
    }

    /**
     * The identity key's attestation chain, leaf first, as DER (SPEC.md §5.2).
     *
     * @throws IllegalStateException when there is no identity key.
     */
    suspend fun attestationChain(): List<ByteArray> = withContext(io) {
        val chain = keyStore().getCertificateChain(signAlias) ?: error("no identity key")
        chain.map { it.encoded }
    }

    /**
     * Deletes both keys. Used when a phone with no pairings abandons an identity the server
     * refused; with pairings, the identity is never thrown away.
     */
    suspend fun destroy() = lock.withLock {
        withContext(io) {
            cached = null
            val store = keyStore()
            if (store.containsAlias(signAlias)) store.deleteEntry(signAlias)
            // On disk before the master key goes, so no keyset outlives the key that decrypts it.
            appContext.getSharedPreferences(keysetPrefs, Context.MODE_PRIVATE).edit(commit = true) { clear() }
            if (store.containsAlias(masterKeyAlias)) store.deleteEntry(masterKeyAlias)
            KillitLog.i(KillitLog.KEYS, "Identity deleted")
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun load(): Identity? {
        val store = keyStore()
        val privateKey = store.getKey(signAlias, null) as? PrivateKey ?: return null
        val publicKey = store.getCertificate(signAlias)?.publicKey as? ECPublicKey ?: return null
        val signPk = P256.point(publicKey)
        val keyset = encryptionKeyset()
        val identity = Identity(
            signPk = signPk,
            fp = fingerprint(signPk),
            encPk = Hpke.publicKeyBytes(keyset.keysetHandle),
            signer = KeystoreSigner(privateKey),
            decrypter = Hpke.decrypter(keyset.keysetHandle),
            strongBox = isStrongBox(privateKey),
        )
        KillitLog.d(KillitLog.KEYS) { "Identity loaded (strongBox=${identity.strongBox})" }
        return identity
    }

    /**
     * Generates the identity key, in StrongBox when the phone has one.
     *
     * No user authentication is required to use it: the phone signs while syncing in the
     * background, with the screen locked. What the key guards is this phone's voice in its
     * pairings, and the passkey still guards everything a person can do with it.
     */
    private fun generateSigningKey(challenge: ByteArray) {
        val store = keyStore()
        if (store.containsAlias(signAlias)) store.deleteEntry(signAlias)
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(signAlias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
        if (strongBox) {
            try {
                generator.initialize(spec(strongBox = true))
                generator.generateKeyPair()
                KillitLog.i(KillitLog.KEYS, "Identity key generated in StrongBox")
                return
            } catch (e: StrongBoxUnavailableException) {
                KillitLog.w(KillitLog.KEYS, "StrongBox refused the key; using the TEE")
            }
        }
        generator.initialize(spec(strongBox = false))
        generator.generateKeyPair()
        KillitLog.i(KillitLog.KEYS, "Identity key generated")
    }

    /**
     * Opens the encryption keyset, creating it the first time.
     *
     * Tink keeps it in its own preferences file, encrypted by a Keystore AES-GCM master key. If
     * Keystore can't provide that key, Tink would store the keyset in the clear; that is refused,
     * because PLAN.md §5.1 promises encryption at rest.
     *
     * The keyset manager creates and parses keys through Tink's global registry, so HPKE is
     * registered there first. The primitives themselves still come from a fixed configuration
     * ([Hpke]), whatever else the registry holds.
     */
    private fun encryptionKeyset(): AndroidKeysetManager {
        HybridConfig.register()
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, keysetPrefs)
            .withKeyTemplate(KeyTemplate.createFrom(Hpke.PARAMETERS))
            .withMasterKeyUri("android-keystore://$masterKeyAlias")
            .build()
        check(manager.isUsingKeystore) { "Android Keystore can't protect the encryption keyset" }
        return manager
    }

    /** True when [key] lives in StrongBox. Only Android 12 reports it; older versions say false. */
    private fun isStrongBox(key: PrivateKey): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val factory = java.security.KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java)
            info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        }.getOrDefault(false)
    }

    /** Signs with a Keystore key and converts the platform's DER signature to `r ‖ s`. */
    private class KeystoreSigner(private val key: PrivateKey) : Signer {
        override fun sign(data: ByteArray): ByteArray =
            P256.derToRaw(
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(key)
                    update(data)
                    sign()
                },
            )
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** The keyset's key inside its preferences file. */
        const val KEYSET_NAME = "encryption"
    }
}
