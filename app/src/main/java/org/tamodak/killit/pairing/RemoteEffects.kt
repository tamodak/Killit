package org.tamodak.killit.pairing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.tamodak.killit.core.KillitLog
import org.tamodak.killit.protection.RemoteBlock
import org.tamodak.killit.protocol.AppInfo
import org.tamodak.killit.protocol.ListedApp

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

    /**
     * Blocks an app a guardian chose to block, whatever its certificate (SPEC.md §11.11); never
     * Killit itself.
     */
    suspend fun blockRemotely(packageName: String, certDigest: ByteArray): RemoteBlock

    /**
     * The apps a guardian may see and manage (G5): every installed app the user installed or that
     * has a launcher icon, Killit excepted, with its state and a 16 × 16 icon (SPEC.md §11.10).
     */
    suspend fun listForGuardians(): List<ListedApp>
}

/**
 * The complete list of effects network input can have on this phone (PLAN.md §6.2 rule 1).
 *
 * Everything a message can change outside pairing's own records passes through [apply], so the
 * whitelist is one `when` long:
 *
 * - (a) unblock an app after every guardian approved this phone's own request for it;
 * - (b) open the gate for the live unlock session whose in-memory nonce approvals were bound to;
 * - (c) block or unblock a named app on its guardians' signed, sequence-numbered commands, with the
 *   quorum of G2, and only while this phone lets its guardians manage its apps. The check sits here
 *   as well as in the code that decides, so no other path can reach these effects with management off.
 *
 * The fourth effect, (d) changing the peer list, stays inside pairing's tables: adding, removing or
 * handing over a pairing. There is no effect for hardening, uninstalling, releasing device owner,
 * wiping, the passkey or any toggle, and adding one means adding a case here, in review.
 *
 * @param apps carries out (a) and (c).
 * @param managementEnabled whether this phone lets its guardians manage its apps (PLAN.md §3.8).
 */
class RemoteEffects(
    private val apps: AppBlocker,
    private val managementEnabled: suspend () -> Boolean,
) {

    /** One permitted effect. */
    sealed interface Effect {
        /** (a) Every guardian approved the request for this app and certificate. */
        class UnblockApp(val packageName: String, val certDigest: ByteArray) : Effect

        /**
         * (b) Every guardian approved the live unlock whose session is [sessionId]. The gate opens
         * only if that session is still the one on screen, in the foreground.
         */
        class OpenGate(val sessionId: Long) : Effect

        /** (c) A guardian blocks this app; the certificate only names what the guardian saw. */
        class ManagedBlock(val packageName: String, val certDigest: ByteArray) : Effect

        /** (c) Every guardian unblocked this app, still signed by this certificate. */
        class ManagedUnblock(val packageName: String, val certDigest: ByteArray) : Effect
    }

    /** What applying an effect did. */
    enum class Outcome {
        /** It took effect. */
        APPLIED,

        /** Nothing needed doing: the app was blocked already, or isn't installed. */
        UNCHANGED,

        /** Android, or the app's certificate, stood in the way. */
        REFUSED,

        /** The effect isn't permitted now: management is off. */
        NOT_ALLOWED,
    }

    private val gate = MutableSharedFlow<Effect.OpenGate>(extraBufferCapacity = 1)

    /** Approved live unlocks, for the screen that is waiting for one. Nobody waiting, no effect. */
    val gateOpenings: SharedFlow<Effect.OpenGate> = gate.asSharedFlow()

    /** Carries out one effect. */
    suspend fun apply(effect: Effect): Outcome = when (effect) {
        is Effect.UnblockApp -> unblock(effect.packageName, effect.certDigest)
        is Effect.OpenGate -> if (gate.tryEmit(effect)) Outcome.APPLIED else Outcome.UNCHANGED
        is Effect.ManagedBlock -> if (!managementEnabled()) {
            Outcome.NOT_ALLOWED
        } else {
            when (apps.blockRemotely(effect.packageName, effect.certDigest)) {
                RemoteBlock.BLOCKED -> Outcome.APPLIED
                RemoteBlock.ALREADY_BLOCKED, RemoteBlock.NOT_INSTALLED -> Outcome.UNCHANGED
                RemoteBlock.REFUSED -> Outcome.REFUSED
            }.also { KillitLog.i(KillitLog.REQ, "Guardian blocked ${effect.packageName}: $it") }
        }
        is Effect.ManagedUnblock -> if (managementEnabled()) unblock(effect.packageName, effect.certDigest) else Outcome.NOT_ALLOWED
    }

    private suspend fun unblock(packageName: String, certDigest: ByteArray): Outcome {
        val unblocked = apps.unblockApproved(packageName, certDigest)
        KillitLog.i(KillitLog.REQ, "Approved app $packageName: ${if (unblocked) "unblocked" else "left blocked"}")
        return if (unblocked) Outcome.APPLIED else Outcome.REFUSED
    }
}
