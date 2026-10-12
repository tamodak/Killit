package org.tamodak.killit.pairing.store

/*
 * The enumerations pairing keeps in its tables.
 *
 * They are separate from the protocol's: the protocol's names may change freely because only their
 * wire codes travel, while these are stored by *name*, so renaming one would silently invalidate
 * stored rows. Killit has no users yet, so until its first release a rename only costs a reset of
 * the development devices.
 */

/** This phone's side of a pairing. */
enum class PairRole {
    /** This phone guards the peer: it decides on the peer's requests. */
    GUARDIAN,

    /** The peer guards this phone. */
    PROTECTED,
}

/** The requests a protected phone sends its guardians. */
enum class RequestType { UNBLOCK_APP, UNLOCK, UNPAIR }

/** Where a request stands. */
enum class RequestState {
    OPEN,
    APPROVED,
    DENIED,
    CANCELLED;

    val isOpen: Boolean get() = this == OPEN
}

/** Why a request was cancelled. */
enum class CancelCause {
    /** The protected phone withdrew it: it left the unlock screen, took a call, or changed its mind. */
    WITHDRAWN,

    /** The app it was about was uninstalled (H11); no chance is used. */
    APP_UNINSTALLED,

    /** It was settled directly in an unlocked session (G11). */
    RESOLVED_BY_UNLOCK,

    /** Its guardian was removed or left, so this copy no longer counts. */
    GUARDIAN_REMOVED,
}

/** One guardian's answer to one request. */
enum class Vote { WAITING, APPROVED, DENIED }

/** Which part of the invite ceremony this phone plays (SPEC.md §6.2). Never the pairing role. */
enum class CeremonySide { CREATOR, JOINER }

/**
 * How far a pairing ceremony has come. The creator and the joiner pass through the steps in a
 * different order; [org.tamodak.killit.pairing.CeremonyEngine] documents both.
 */
enum class CeremonyStage {
    /** Creator: showing the code, waiting for someone to join. */
    WAITING_FOR_JOINER,

    /** Creator: the joiner committed and this phone revealed; waiting for the joiner's reveal. */
    WAITING_FOR_JOINER_REVEAL,

    /** Joiner: committed; waiting for the creator's reveal. */
    WAITING_FOR_CREATOR,

    /** Both: the safety code is on screen, waiting for the people to compare it. */
    COMPARING,

    /** Both: this phone confirmed the code; waiting for the other one to. */
    CONFIRMED,

    /** Both: linked; the creator's PAIR_OFFER and the joiner's PAIR_ANSWER are under way. */
    LINKED,

    /** Both: paired; the user may still edit the peer's nickname. */
    PAIRED,

    /** Ended without a pairing; see [CeremonyFailure]. */
    FAILED,
}

/** Why a ceremony ended without a pairing. */
enum class CeremonyFailure {
    /** Someone cancelled, or the invite was replaced by a newer one. */
    CANCELLED,

    /** The invite ran out of time. */
    EXPIRED,

    /** The people said the safety codes don't match: someone may be interfering. */
    MISMATCH,

    /** The code was wrong, used or expired when joining. */
    CODE_NOT_FOUND,

    /** Something the other phone or the server sent didn't verify. */
    INVALID,

    /** Both phones wanted the same role. */
    ROLE_CONFLICT,

    /** The other phone declined. */
    DECLINED,

    /** One of the phones already has as many links as the server allows. */
    LINK_LIMIT,
}

/** What the local history records (PLAN.md I10: 90 days). */
enum class EventType {
    PAIRED,
    GUARDIAN_LEFT,
    GUARDIAN_REMOVED,
    REMOVED_AS_GUARDIAN,
    APP_INSTALLED,
    GUARDIAN_ADDED,
    RELEASE_STARTED,
    RELEASE_CANCELLED,
    PROTECTION_CHANGED,
    MANAGEMENT_CHANGED,
    REQUEST_APPROVED,
    REQUEST_DENIED,
    REQUEST_CANCELLED,

    /** A NOTICE with a code this version doesn't know (E-F1). */
    UNKNOWN_NOTICE,
}
