package org.tamodak.killit.admin.provisioning

import android.content.ComponentName
import org.tamodak.killit.BuildConfig
import org.tamodak.killit.admin.KillitDeviceAdminReceiver
import org.tamodak.killit.core.KillitLog
import kotlin.system.exitProcess

/**
 * The shell-privileged half of the Shizuku path.
 *
 * Shizuku starts this in its own process running as uid 2000, so a child process it spawns
 * inherits shell privileges — which is exactly the privilege level `dpm set-device-owner`
 * requires. **Nothing here runs in Killit's own process**, which is worth remembering when reading
 * logs: these lines come from a different pid and uid than the rest of the app, and they survive
 * only as long as the command takes.
 *
 * ### Why the interface is one no-argument call
 *
 * Running as shell is the most dangerous capability Killit ever touches. An interface that accepted
 * an arbitrary command would put a general-purpose privileged executor on a binder, and the admin
 * component to target would be caller-supplied. Instead the binder surface is a single method with
 * no parameters, process spawning is private, and the component is rebuilt here from compile-time
 * constants — so there is nothing for a caller to steer.
 */
class KillitPrivilegedService : IKillitPrivilegedService.Stub() {

    /**
     * Promotes Killit to device owner and returns the combined stdout/stderr verbatim.
     *
     * The output is not interpreted here. `dpm` reports the interesting failures as plain English
     * on stderr — "there are already some accounts on the device" being by far the most common —
     * and the UI shows that text as-is rather than replacing it with a generic error.
     *
     * @return the combined stdout/stderr of the `dpm` invocation, or the exit code when it printed
     *   nothing.
     */
    override fun setDeviceOwner(): String {
        val component = ComponentName(
            BuildConfig.APPLICATION_ID,
            KillitDeviceAdminReceiver::class.java.name,
        )
        KillitLog.i(KillitLog.PRIV, "Running dpm set-device-owner for ${component.flattenToString()}")
        return runCommand("dpm", "set-device-owner", component.flattenToString())
    }

    /**
     * Shizuku calls this on the fixed transaction id declared in the AIDL when it stops the user
     * service. Killing the process is the whole contract — there is no state to unwind.
     */
    override fun destroy() {
        KillitLog.i(KillitLog.PRIV, "Privileged service shutting down")
        exitProcess(0)
    }

    /**
     * Not part of the binder interface.
     *
     * Arguments are passed as an argv array to [ProcessBuilder], never through a shell, so there
     * is no command string for anything to be injected into. stderr is merged into stdout so a
     * failure message reaches the UI instead of vanishing.
     *
     * @param command the argv to execute, program name first.
     * @return the trimmed output, or a description of whatever went wrong. Never throws: the caller
     *   is a binder method, and an exception crossing it would surface as a dead service rather
     *   than a message.
     */
    private fun runCommand(vararg command: String): String {
        KillitLog.d(KillitLog.PRIV) { "exec: ${command.joinToString(" ")}" }
        return try {
            KillitLog.timed(KillitLog.PRIV, "exec ${command.firstOrNull()}") {
                val process = ProcessBuilder(command.toList())
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                KillitLog.i(KillitLog.PRIV, "exit=$exitCode output=${output.trim().ifBlank { "(empty)" }}")
                // An empty stdout with a zero exit is success with nothing to say; surface the code
                // so the dialog is never blank.
                if (output.isBlank()) "exit code $exitCode" else output.trim()
            }
        } catch (t: Throwable) {
            KillitLog.e(KillitLog.PRIV, "exec failed", t)
            "Failed to run command: ${t.message ?: t::class.java.simpleName}"
        }
    }
}
