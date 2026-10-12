package org.tamodak.killit.pairing

import org.tamodak.killit.pairing.store.EventEntity
import org.tamodak.killit.pairing.store.IncomingRequestEntity
import org.tamodak.killit.pairing.store.OutgoingRequestEntity
import org.tamodak.killit.pairing.store.PairingEntity

/**
 * What the pairing engine tells the person holding the phone (PLAN.md §15.8). Texts are built on
 * this phone, in its own language, from these structured events (E-F3).
 *
 * Every function has an empty default, so tests and the engine itself can ignore what they don't
 * need.
 */
interface PairingNotifier {
    /** Guardian side: a phone this phone guards asks for something. */
    fun requestArrived(from: PairingEntity, request: IncomingRequestEntity) {}

    /** Guardian side: a request this phone was asked about is closed, or no longer waits for it. */
    fun requestSettled(from: PairingEntity, request: IncomingRequestEntity) {}

    /** Protected side: one of this phone's requests was approved, denied, or timed out. */
    fun requestClosed(request: OutgoingRequestEntity) {}

    /** Protected side: one guardian answered one of this phone's requests. */
    fun guardianAnswered(guardian: PairingEntity, request: OutgoingRequestEntity, approved: Boolean, note: String) {}

    /** A pairing ended: a guardian left, this phone was removed as a guardian, or an UNPAIR completed. */
    fun pairingEnded(pairing: PairingEntity, event: EventEntity) {}

    /** Guardian side: a NOTICE arrived (D1, A4, B5, C8, H6). */
    fun noticeArrived(from: PairingEntity, event: EventEntity) {}

    /** Guardian side: a guarded phone has been silent for too long (H8). */
    fun silence(pairing: PairingEntity) {}

    /** A message this version can't read arrived (E-F1). */
    fun updateNeeded() {}
}
