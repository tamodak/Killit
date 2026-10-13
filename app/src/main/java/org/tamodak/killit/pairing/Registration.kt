package org.tamodak.killit.pairing

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.net.ApiClient
import org.tamodak.killit.net.ApiError
import org.tamodak.killit.protocol.DecodeException
import org.tamodak.killit.protocol.fromBase64Url

/**
 * Gets this phone's identity registered with the server (SPEC.md §5), once.
 *
 * Registration happens the first time pairing needs the network, not at install: a phone that is
 * only ever protected by its own password never talks to the server at all.
 *
 * An attestation is fixed when its key is generated, so a key can't be registered again with a
 * fresh challenge. A registration whose answer was lost is therefore settled by asking the server
 * whether it knows the key (`GET /v1/devices/me`), and a key the server never accepted is thrown
 * away and generated again — but only while no peer trusts it.
 *
 * @param hasPairings whether any peer holds this phone's current key; such a key is never replaced.
 */
class Registration(
    private val keys: DeviceKeys,
    private val api: ApiClient,
    private val prefs: PairingPreferences,
    private val hasPairings: suspend () -> Boolean,
) {
    private val lock = Mutex()

    /**
     * Returns the registered identity, registering it first if needed.
     *
     * @throws AttestationRejectedException when the server refused this phone's attestation: the
     *   phone can't pair (PLAN.md E-E7).
     * @throws IdentityLostException when the server doesn't know a key that peers trust.
     * @throws IOException when the server couldn't be reached; nothing is lost, try again later.
     */
    suspend fun ensureRegistered(): DeviceKeys.Identity = lock.withLock {
        val existing = keys.current()
        if (existing != null) {
            if (prefs.registeredFp()?.contentEquals(existing.fp) == true) return existing
            if (serverKnows()) {
                prefs.setRegistered(existing.fp)
                KillitLog.i(KillitLog.KEYS, "Identity was already registered")
                return existing
            }
            if (hasPairings()) throw IdentityLostException()
            KillitLog.i(KillitLog.KEYS, "Replacing an identity the server never accepted")
        }
        register()
    }

    /**
     * Deletes this phone's registration and identity, once its pairings moved to a new phone
     * (SPEC.md §11.14): no peer trusts the key any more. Pairing again later registers a new one.
     *
     * Deleting the server's record is best effort. A record left behind has no links the pairs
     * still want, since they delete theirs when they take the handover, and its key is gone.
     */
    suspend fun forget() = lock.withLock {
        try {
            api.deleteDevice()
        } catch (e: IOException) {
            KillitLog.w(KillitLog.KEYS, "Deleting this phone's registration failed: ${e.message}")
        }
        keys.destroy()
        prefs.setRegistered(null)
        KillitLog.i(KillitLog.KEYS, "Identity forgotten after moving")
    }

    private suspend fun register(): DeviceKeys.Identity {
        val challenge = try {
            api.challenge().challenge.fromBase64Url(CHALLENGE_BYTES).also { if (it.size != CHALLENGE_BYTES) throw DecodeException("size") }
        } catch (e: DecodeException) {
            throw IOException("malformed challenge")
        }
        val identity = keys.create(challenge)
        try {
            val answer = api.register(challenge, keys.attestationChain())
            KillitLog.i(KillitLog.KEYS, "Identity registered (security level ${answer.securityLevel})")
        } catch (e: ApiError) {
            if (e.status == 403 && e.code == "attestation_rejected") {
                KillitLog.w(KillitLog.KEYS, "Attestation rejected: ${e.reason}")
                throw AttestationRejectedException(e.reason ?: "unknown")
            }
            throw e
        } catch (e: IOException) {
            // The answer may have been lost after the server registered the key.
            if (!runCatching { serverKnows() }.getOrDefault(false)) throw e
        }
        prefs.setRegistered(identity.fp)
        return identity
    }

    /**
     * True when the server answers for the current key, false when it says it doesn't know it.
     *
     * @throws IOException when the server can't tell either way.
     */
    private suspend fun serverKnows(): Boolean =
        try {
            api.me()
            true
        } catch (e: ApiError) {
            if (e.status == 401 && e.reason == "invalid") false else throw e
        }

    private companion object {
        const val CHALLENGE_BYTES = 32
    }
}

/**
 * The server refused this phone's key attestation (SPEC.md §5.2), so this phone can't pair.
 *
 * @param reason the server's reason, e.g. `security_level` for a phone without secure hardware.
 */
class AttestationRejectedException(val reason: String) : Exception("attestation rejected: $reason")

/**
 * The server doesn't know the identity key this phone's peers trust: its record was lost. The key
 * can't be registered again (its attestation is spent), and replacing it would cut every pairing.
 */
class IdentityLostException : Exception("the server no longer knows this phone's identity")
