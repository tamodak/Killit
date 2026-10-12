package org.tamodak.killit.pairing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.protocol.AppInfo

/**
 * What pairing may do to this phone's apps, and what it may ask of them.
 *
 * Backed by default-blocking's `PackageGuard`; an interface so the engine can be tested without a
 * device owner.
 */
interface AppBlocker {
    /**
     * Describes an installed app for an UNBLOCK_APP request: its signing digest, label, installer,
     * first install time and icon (SPEC.md §11.2).
     *
     * @return the description, or null when the app isn't installed.
     */
    suspend fun describe(packageName: String): AppInfo?

    /** True when Killit blocks [packageName] now. */
    suspend fun isBlocked(packageName: String): Boolean

    /**
     * Unblocks an app every guardian approved, but only while it is still the app they approved:
     * signed by [certDigest]. A different app under the same name stays blocked.
     *
     * @return true when the app is now allowed.
     */
    suspend fun unblockApproved(packageName: String, certDigest: ByteArray): Boolean
}

/**
 * The complete list of effects network input can have on this phone (PLAN.md §6.2 rule 1).
 *
 * Everything a message can change outside pairing's own records passes through [apply], so the
 * whitelist is one `when` long:
 *
 * - (a) unblock an app after every guardian approved this phone's own request for it;
 * - (b) open the gate for the live unlock session whose in-memory nonce approvals were bound to.
 *
 * The third effect, (d) changing the peer list, stays inside pairing's tables: adding or removing
 * a pairing. (c), guardians managing apps, is Phase 5b and doesn't exist. There is no effect for
 * hardening, uninstalling, releasing device owner, wiping, the passkey or any toggle, and adding
 * one means adding a case here, in review.
 *
 * @param apps carries out (a).
 */
class RemoteEffects(private val apps: AppBlocker) {

    /** One permitted effect. */
    sealed interface Effect {
        /** (a) Every guardian approved the request for this app and certificate. */
        class UnblockApp(val packageName: String, val certDigest: ByteArray) : Effect

        /**
         * (b) Every guardian approved the live unlock whose session is [sessionId]. The gate opens
         * only if that session is still the one on screen, in the foreground.
         */
        class OpenGate(val sessionId: Long) : Effect
    }

    private val gate = MutableSharedFlow<Effect.OpenGate>(extraBufferCapacity = 1)

    /** Approved live unlocks, for the screen that is waiting for one. Nobody waiting, no effect. */
    val gateOpenings: SharedFlow<Effect.OpenGate> = gate.asSharedFlow()

    /**
     * Carries out one effect.
     *
     * @return true when it took effect.
     */
    suspend fun apply(effect: Effect): Boolean = when (effect) {
        is Effect.UnblockApp -> apps.unblockApproved(effect.packageName, effect.certDigest).also {
            KillitLog.i(KillitLog.REQ, "Approved app ${effect.packageName}: ${if (it) "unblocked" else "left blocked"}")
        }
        is Effect.OpenGate -> gate.tryEmit(effect)
    }
}
