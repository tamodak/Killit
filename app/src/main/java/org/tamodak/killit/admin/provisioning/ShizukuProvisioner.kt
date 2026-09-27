package org.tamodak.killit.admin.provisioning

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import org.tamodak.killit.core.KillitLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Provisions Killit as device owner through Shizuku.
 *
 * ### Why Shizuku
 *
 * Shizuku hands out shell (uid 2000) privileges, the same privilege level `adb shell` has — so
 * `dpm set-device-owner` works from there. The advantage over the adb path is that Shizuku can be
 * started from the device itself over wireless debugging, so **no computer is needed**. The
 * platform preconditions are unchanged: no accounts on the device, no existing device owner.
 *
 * ### Shape of the flow
 *
 * 1. [status] — is Shizuku even running, and new enough?
 * 2. [requestPermission] — Shizuku shows its own consent dialog, in its own process.
 * 3. [setDeviceOwner] — bind a user service running as shell, ask it to do the one thing it can.
 *
 * Each step logs, because this flow spans three processes (Killit, the Shizuku server, and the
 * privileged user service) and a failure in any of them surfaces here as one opaque string.
 *
 * @param context any context; only its application context is retained.
 * @param ioDispatcher where the binder calls to the Shizuku server run.
 */
class ShizukuProvisioner(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Held rather than the passed context, so this class can outlive any activity. */
    private val appContext = context.applicationContext

    /**
     * How Shizuku is asked to start the privileged process.
     *
     * - `daemon(false)` — the process dies with Killit rather than lingering; it is needed for one
     *   command, not for the app's lifetime.
     * - `version(...)` — bump [SERVICE_VERSION] whenever [KillitPrivilegedService] changes, or
     *   Shizuku will happily reuse an already-running instance built from the old code.
     */
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, KillitPrivilegedService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("privileged")
        .debuggable(false)
        .version(SERVICE_VERSION)

    /**
     * Reports whether Shizuku can be used for provisioning right now.
     *
     * Pings the Shizuku server over binder, so it stays off the main thread.
     *
     * @return the current status. Shizuku not being installed at all reads as
     *   [ShizukuStatus.NOT_RUNNING], since from the user's side the two are the same problem with
     *   the same fix.
     */
    suspend fun status(): ShizukuStatus = withContext(ioDispatcher) {
        val status = runCatching {
            when {
                !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
                Shizuku.isPreV11() -> ShizukuStatus.TOO_OLD
                else -> ShizukuStatus.READY
            }
        }.getOrElse { error ->
            // Shizuku not installed at all throws rather than returning false.
            KillitLog.d(KillitLog.SHIZUKU) { "pingBinder threw (${error.javaClass.simpleName}); treating as not running" }
            ShizukuStatus.NOT_RUNNING
        }
        KillitLog.i(KillitLog.SHIZUKU, "Shizuku status: $status")
        status
    }

    /**
     * Reports whether Killit already holds Shizuku permission.
     *
     * @return true when permission is granted; false when it is not, or when Shizuku is not there
     *   to ask.
     */
    suspend fun hasPermission(): Boolean = withContext(ioDispatcher) {
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrElse { error ->
            KillitLog.d(KillitLog.SHIZUKU) { "checkSelfPermission threw: ${error.javaClass.simpleName}" }
            false
        }
        KillitLog.d(KillitLog.SHIZUKU) { "hasPermission=$granted" }
        granted
    }

    /**
     * Requests Shizuku permission, suspending until the user answers its dialog.
     *
     * The listener is removed on every exit path — grant, denial, throw, and coroutine
     * cancellation — because Shizuku holds listeners statically and a leaked one would fire
     * against a dead continuation on the next request.
     *
     * @return true when permission is held afterwards. Returns immediately when it already was.
     */
    suspend fun requestPermission(): Boolean {
        if (hasPermission()) return true

        KillitLog.i(KillitLog.SHIZUKU, "Requesting Shizuku permission (code $PERMISSION_REQUEST_CODE)")
        return suspendCancellableCoroutine { continuation ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    // Another component in this process may be requesting something else.
                    if (requestCode != PERMISSION_REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    KillitLog.i(KillitLog.SHIZUKU, "Shizuku permission ${if (granted) "GRANTED" else "DENIED"}")
                    if (continuation.isActive) continuation.resume(granted)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation {
                KillitLog.d(KillitLog.SHIZUKU) { "Permission request cancelled; removing listener" }
                Shizuku.removeRequestPermissionResultListener(listener)
            }
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
                .onFailure { error ->
                    KillitLog.e(KillitLog.SHIZUKU, "requestPermission threw", error)
                    Shizuku.removeRequestPermissionResultListener(listener)
                    if (continuation.isActive) continuation.resume(false)
                }
        }
    }

    /**
     * Runs `dpm set-device-owner` as shell.
     *
     * The raw output is returned verbatim: the most common failure is "not allowed to set the
     * device owner because there are already some accounts on the device", and paraphrasing that
     * would just hide the fix from the user.
     *
     * The command itself is assembled on the privileged side; this side only asks for it.
     *
     * @return whatever `dpm` printed, or a description of how the round trip failed. Never throws,
     *   so the caller can put the string straight on screen.
     */
    suspend fun setDeviceOwner(): String {
        KillitLog.i(KillitLog.SHIZUKU, "Asking the privileged service to set device owner")
        return KillitLog.timed(KillitLog.SHIZUKU, "setDeviceOwner round trip") {
            runPrivileged { it.setDeviceOwner() }
        }.also { output ->
            KillitLog.i(KillitLog.SHIZUKU, "dpm output: $output")
        }
    }

    /**
     * Binds the privileged user service, runs [call] against it, and unbinds.
     *
     * Everything funnels through the `settled` flag: [ServiceConnection] can deliver both a
     * connect and a disconnect, `bindUserService` can throw *after* a callback has already fired,
     * and the coroutine can be cancelled at any point — but a continuation may only be resumed
     * once. The flag makes whichever path arrives first the winner and turns the rest into no-ops.
     *
     * @param call what to invoke against the privileged service once it is connected.
     * @return the service's output, or a description of how the binding failed.
     */
    private suspend fun runPrivileged(call: (IKillitPrivilegedService) -> String?): String =
        suspendCancellableCoroutine { continuation ->
            val settled = AtomicBoolean(false)

            fun finish(result: String, connection: ServiceConnection?) {
                if (!settled.compareAndSet(false, true)) {
                    KillitLog.d(KillitLog.SHIZUKU) { "Ignoring a second outcome: $result" }
                    return
                }
                if (connection != null) {
                    runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
                        .onFailure { KillitLog.w(KillitLog.SHIZUKU, "unbindUserService failed", it) }
                }
                if (continuation.isActive) continuation.resume(result)
            }

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    KillitLog.d(KillitLog.SHIZUKU) { "Privileged service connected: $name" }
                    if (binder == null || !binder.pingBinder()) {
                        KillitLog.e(KillitLog.SHIZUKU, "Shizuku handed back a dead binder")
                        finish("Shizuku returned a dead service binder.", this)
                        return
                    }
                    val output = runCatching {
                        call(IKillitPrivilegedService.Stub.asInterface(binder))
                    }.getOrElse { error ->
                        KillitLog.e(KillitLog.SHIZUKU, "Privileged call failed", error)
                        "Failed to run command: ${error.message ?: error::class.java.simpleName}"
                    }
                    finish(output.orEmpty(), this)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    KillitLog.w(KillitLog.SHIZUKU, "Privileged service disconnected before finishing")
                    finish("Shizuku service disconnected before the command finished.", null)
                }
            }

            continuation.invokeOnCancellation {
                KillitLog.d(KillitLog.SHIZUKU) { "Privileged call cancelled; unbinding" }
                runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
            }

            KillitLog.d(KillitLog.SHIZUKU) { "Binding the privileged user service (v$SERVICE_VERSION)" }
            runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
                .onFailure { error ->
                    KillitLog.e(KillitLog.SHIZUKU, "bindUserService failed", error)
                    finish("Could not start the Shizuku service: ${error.message}", null)
                }
        }

    private companion object {
        /** Arbitrary, but must be stable: it is how the permission callback is recognised. */
        const val PERMISSION_REQUEST_CODE = 4919

        /** Bump whenever [KillitPrivilegedService] changes, or Shizuku reuses the old process. */
        const val SERVICE_VERSION = 1
    }
}

/** Whether Shizuku can be used for provisioning right now. */
enum class ShizukuStatus {
    /** Not installed, or installed but the server has not been started. */
    NOT_RUNNING,

    /** Pre-v11 Shizuku, whose user-service API Killit does not support. */
    TOO_OLD,

    /** Running and new enough; permission may still need granting. */
    READY,
}
