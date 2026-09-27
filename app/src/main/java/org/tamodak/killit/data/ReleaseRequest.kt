package org.tamodak.killit.data

/**
 * A pending request to unblock everything and give up device owner.
 *
 * Releasing is deliberately not immediate. The whole value of Killit is that it cannot be undone in
 * the moment it is most tempting to undo it, so pressing the button only starts a clock; the
 * release itself has to be confirmed again once [availableAtMillis] has passed.
 *
 * The request is stored in **both** the durable and the local store, exactly like the credential
 * record. If it lived only in the app's data directory, "Clear data" would reset the clock and the
 * delay would be worth nothing.
 *
 * @param requestedAtMillis when the button was pressed, wall clock. Kept so the countdown can tell
 *   a clock that has been wound *backwards* from normal time passing.
 * @param availableAtMillis the earliest wall-clock time the release may be carried out.
 */
data class ReleaseRequest(
    val requestedAtMillis: Long,
    val availableAtMillis: Long,
) {
    /**
     * Reports the wait still outstanding, floored at zero so a ready request reads as `0` rather
     * than a negative duration the countdown would have to special-case.
     *
     * @param now current wall-clock time.
     * @return milliseconds left before the release may be carried out.
     */
    fun remainingMillis(now: Long): Long = (availableAtMillis - now).coerceAtLeast(0L)

    /**
     * Reports whether the wait has elapsed and the release may now be confirmed.
     *
     * @param now current wall-clock time.
     * @return true once [availableAtMillis] has been reached.
     */
    fun isReady(now: Long): Boolean = now >= availableAtMillis

    /**
     * Reports whether the clock has moved backwards since the request was made.
     *
     * Winding the clock *forwards* to skip the wait is the obvious attack, and the device owner
     * restriction `DISALLOW_CONFIG_DATE_TIME` is what closes it. This flag catches the other
     * direction, which is harmless but makes the countdown look broken, and is worth logging.
     *
     * @param now current wall-clock time.
     * @return true when [now] precedes [requestedAtMillis].
     */
    fun clockWentBackwards(now: Long): Boolean = now < requestedAtMillis
}
