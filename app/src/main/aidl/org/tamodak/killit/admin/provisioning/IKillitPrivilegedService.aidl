package org.tamodak.killit.admin.provisioning;

/**
 * Runs inside the Shizuku user service process, which has shell (uid 2000) privileges.
 *
 * Deliberately narrow. The one thing Killit needs shell for is promoting itself to device owner,
 * so that is the only thing this interface can do — it is not a general command runner. There is
 * no argument to steer either: the target admin component is rebuilt from compile-time constants
 * on the privileged side, so a caller cannot point `dpm set-device-owner` at anything else.
 */
interface IKillitPrivilegedService {
    /** Runs `dpm set-device-owner` for Killit's own admin component; returns the shell output. */
    String setDeviceOwner() = 1;

    /** Shizuku requires this exact transaction id so it can stop the user service. */
    void destroy() = 16777114;
}
