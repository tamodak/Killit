package org.tamodak.killit.pairing

import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.OutgoingRequestEntity
import org.tamodak.killit.pairing.store.PairingEntity
import org.tamodak.killit.protocol.AppChange

/**
 * What the pairing engine tells the person holding the phone (PLAN.md §15.8). Texts are built on
 * this phone, in its own language, from these structured events (E-F3).
 *
 * Called under the engine's lock, as part of handling what the event is about. Every function has
 * an empty default, so tests and the engine itself can ignore what they don't need.
 */
interface PairingNotifier {
    /** Guardian side: a phone this phone guards asks for something. */
    suspend fun requestArrived(from: PairingEntity, request: IncomingRequestEntity) {}

    /** Guardian side: a request this phone was asked about is closed, or no longer waits for it. */
    suspend fun requestSettled(from: PairingEntity, request: IncomingRequestEntity) {}

    /** Protected side: one of this phone's requests was approved, denied, or timed out. */
    suspend fun requestClosed(request: OutgoingRequestEntity) {}

    /** Protected side: one guardian answered one of this phone's requests. */
    suspend fun guardianAnswered(guardian: PairingEntity, request: OutgoingRequestEntity, approved: Boolean, note: String) {}

    /** Protected side: a guardian blocked or unblocked one of this phone's apps by itself (G4). */
    suspend fun appManaged(guardian: PairingEntity, label: String, change: AppChange) {}

    /** A pairing ended: a guardian left, this phone was removed as a guardian, or an UNPAIR completed. */
    suspend fun pairingEnded(pairing: PairingEntity, event: EventEntity) {}

    /** Guardian side: a NOTICE arrived (D1, A4, B5, C8, H6). */
    suspend fun noticeArrived(from: PairingEntity, event: EventEntity) {}

    /** A peer moved to a new phone (SPEC.md §11.13); [event] is the history line. */
    suspend fun peerMoved(pairing: PairingEntity, event: EventEntity) {}

    /** Guardian side: a guarded phone has been silent for too long (H8). */
    suspend fun silence(pairing: PairingEntity) {}

    /** A message this version can't read arrived (E-F1). */
    suspend fun updateNeeded() {}
}
