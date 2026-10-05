package org.tamodak.killit.core

import android.os.SystemClock
import android.util.Log
import org.tamodak.killit.BuildConfig
import java.security.MessageDigest

/**
 * Killit's logging front end.
 *
 * Everything in the app logs through here rather than calling [Log] directly, for three reasons:
 *
 * 1. **One switch.** [verbose] turns the chatty tracing on and off in a single place, including on
 *    a release build (see below), so a problem that only reproduces on a user's phone can still be
 *    traced without shipping a special APK.
 * 2. **Zero cost when off.** The `d`/`v` helpers take a lambda instead of a `String` and are
 *    `inline`, so when [verbose] is false the message is never built — no concatenation, no
 *    allocation. This matters in hot paths such as icon decoding, which runs once per list row.
 * 3. **Nothing secret gets out.** Killit holds a passkey. Logging is exactly how such a thing
 *    accidentally leaks, so raw credentials, salts and hashes must never be passed to a log call.
 *    Use [describeSecret] and [fingerprint] instead; both are one-way.
 *
 * ### Turning tracing on for a release build
 *
 * Debug builds trace by default and release builds stay quiet. To trace a release build on a
 * device that is misbehaving in the field:
 *
 * ```
 * adb shell setprop log.tag.KillitVerbose DEBUG
 * adb shell am force-stop org.tamodak.killit     # re-read on next launch
 * ```
 *
 * [verbose] is also a plain mutable flag, so a test or a debug affordance can flip it directly.
 *
 * Timings are a special case: an operation that blows its budget is reported at WARN whether or
 * not tracing is on, so a slow device reports itself from a stock build. See [timed].
 *
 * ### Reading the logs
 *
 * Every tag starts with `Killit.`, so one filter catches the whole app:
 *
 * ```
 * adb logcat -s Killit.App Killit.Ui Killit.Vm Killit.Repo Killit.Cred Killit.Prefs \
 *              Killit.Durable Killit.Dpc Killit.Apps Killit.Shizuku Killit.Priv Killit.Admin \
 *              Killit.Guard
 * ```
 *
 * or, more simply, `adb logcat | grep Killit.`
 */
object KillitLog {

    // ---------------------------------------------------------------- tags
    //
    // One tag per layer. Android truncates tags over 23 characters, so these stay short.

    /** Application startup and the dependency graph. */
    const val APP = "Killit.App"

    /** Composable screen hosting, navigation between screens, lifecycle-driven re-locking. */
    const val UI = "Killit.Ui"

    /** ViewModel: user intents in, state transitions out. */
    const val VM = "Killit.Vm"

    /** Credential repository: reconciliation between the two stores, verification outcomes. */
    const val REPO = "Killit.Repo"

    /** Credential hashing: salt generation and passkey verification. */
    const val CRED = "Killit.Cred"

    /** DataStore-backed local preferences. */
    const val PREFS = "Killit.Prefs"

    /** The clear-data-proof copy held in the device owner's application restrictions. */
    const val DURABLE = "Killit.Durable"

    /** Every call into DevicePolicyManager. */
    const val DPC = "Killit.Dpc"

    /** Installed-package inventory and icon decoding. */
    const val APPS = "Killit.Apps"

    /** Shizuku provisioning, app-process side. */
    const val SHIZUKU = "Killit.Shizuku"

    /** Shizuku provisioning, shell-privileged process side. Logs land under uid 2000. */
    const val PRIV = "Killit.Priv"

    /** Device admin receiver and the provisioning activities. */
    const val ADMIN = "Killit.Admin"

    /** Default-blocking: package scans, the decisions made on them, and the protection service. */
    const val GUARD = "Killit.Guard"

    // ---------------------------------------------------------------- configuration

    /**
     * The tag whose loggable level acts as the runtime switch. Not used for output.
     *
     * Android exposes a per-tag level that can be set without reinstalling anything:
     * ```
     * adb shell setprop log.tag.KillitVerbose DEBUG   # then restart the app
     * ```
     */
    private const val VERBOSE_SWITCH_TAG = "KillitVerbose"

    /**
     * Whether the chatty `d`/`v` levels and per-operation timings are emitted.
     *
     * On in debug builds, and in *any* build — release included — when the switch property above
     * is set. That second route is the point: a device nobody on the team owns can be traced
     * without shipping it a special APK.
     *
     * `@Volatile` because it is read from every thread the app uses — main, IO, and Default, where
     * the passkey is hashed — and may be flipped from any of them.
     *
     * Note that `KillitLog` is named in `src/main/keepRules/`. Without that, R8 proves nothing in
     * the app assigns this field, folds it to a constant `false`, and deletes every traced branch
     * — which would quietly make the release-build switch a no-op.
     */
    @Volatile
    @JvmStatic
    var verbose: Boolean = BuildConfig.DEBUG || Log.isLoggable(VERBOSE_SWITCH_TAG, Log.DEBUG)

    /**
     * An operation slower than this is reported at WARN by [timed] **even when [verbose] is off**.
     *
     * That is deliberate: the whole point is that a device nobody on the team owns — a budget
     * phone that is slow to hash the passkey, a handset whose device policy service is slow to
     * answer — reports its own problem from a stock release build, rather than needing to be
     * reproduced first. 500 ms is roughly where a delay stops reading as "responsive".
     */
    const val SLOW_OPERATION_MILLIS = 500L

    // ---------------------------------------------------------------- levels

    /**
     * Logs fine-grained tracing. The message is built only when [verbose] is on.
     *
     * ```
     * KillitLog.d(KillitLog.DPC) { "isDeviceOwner=$owner" }
     * ```
     *
     * @param tag one of the layer tags declared above.
     * @param message built lazily, so an expensive interpolation costs nothing while tracing is off.
     */
    inline fun d(tag: String, message: () -> String) {
        if (verbose) Log.d(tag, message())
    }

    /**
     * Logs the highest-volume traces — per list row, per gesture sample. Otherwise as [d].
     *
     * @param tag one of the layer tags declared above.
     * @param message built lazily, so an expensive interpolation costs nothing while tracing is off.
     */
    inline fun v(tag: String, message: () -> String) {
        if (verbose) Log.v(tag, message())
    }

    /**
     * Logs a milestone worth keeping in a release build: provisioning, passkey set, admin released.
     *
     * @param tag one of the layer tags declared above.
     * @param message emitted unconditionally, so it must never carry credential material.
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    /**
     * Logs that something failed but Killit carried on with a documented fallback.
     *
     * @param tag one of the layer tags declared above.
     * @param message emitted unconditionally, so it must never carry credential material.
     * @param error the cause, when one is worth a stack trace.
     */
    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
    }

    /**
     * Logs that something failed in a way the user will notice.
     *
     * @param tag one of the layer tags declared above.
     * @param message emitted unconditionally, so it must never carry credential material.
     * @param error the cause, when one is worth a stack trace.
     */
    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
    }

    // ---------------------------------------------------------------- timing

    /**
     * Runs [block] and reports how long it took.
     *
     * Logged at DEBUG when [verbose] is on, and at WARN whenever it exceeds
     * [SLOW_OPERATION_MILLIS] regardless of [verbose] — so a slow device is self-reporting.
     *
     * `inline` with a plain (non-`crossinline`) lambda, which means the block keeps the caller's
     * context: `timed` can wrap suspending code without itself being a suspend function, and adds
     * no object allocation.
     *
     * Uses [SystemClock.elapsedRealtime] rather than wall-clock time, so a clock adjustment or an
     * NTP sync mid-operation cannot produce a nonsense duration.
     *
     * ```
     * val hash = KillitLog.timed(KillitLog.CRED, "Argon2id") { credentials.hash(credential, salt) }
     * ```
     *
     * @param tag one of the layer tags declared above.
     * @param label names the operation in the log line; keep it stable so timings can be grepped.
     * @param block the operation to measure. Timed even if it throws.
     * @return whatever [block] returns.
     */
    inline fun <T> timed(tag: String, label: String, block: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= SLOW_OPERATION_MILLIS) {
                Log.w(tag, "$label took ${elapsed}ms — over the ${SLOW_OPERATION_MILLIS}ms budget")
            } else if (verbose) {
                Log.d(tag, "$label took ${elapsed}ms")
            }
        }
    }

    // ---------------------------------------------------------------- redaction
    //
    // Killit's whole job is to hold a passkey nobody else can use. A log line that carries the
    // credential, or enough of it to narrow the search, hands that away to anything that can read
    // logcat. The two helpers below are the only sanctioned way to mention key material at all.

    /**
     * Describes a credential without revealing it: length only, never the value.
     *
     * Length alone is already a small hint to an attacker reading logs, which is why this is
     * limited to what is genuinely needed to debug an input problem ("the pattern arrived with
     * three dots, so the length check rejected it").
     *
     * @param value the raw credential. Never reproduced in the result.
     * @return a placeholder carrying the length and nothing else.
     */
    fun describeSecret(value: String): String = "<redacted len=${value.length}>"

    /**
     * Derives a short, one-way fingerprint for correlating stored values across log lines.
     *
     * Tells whether the durable copy and the local copy hold the *same* record, without printing
     * either.
     *
     * Only ever pass already-derived material (a stored hash, a salt). Never a credential: a
     * fingerprint of a 4-digit PIN would be brute-forceable from the log line itself.
     *
     * @param bytes derived material to fingerprint, or null.
     * @return the first four bytes of the SHA-256 digest as hex, or `"none"` for null input.
     */
    fun fingerprint(bytes: ByteArray?): String {
        if (bytes == null) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(4).joinToString(separator = "") { "%02x".format(it) }
    }
}
