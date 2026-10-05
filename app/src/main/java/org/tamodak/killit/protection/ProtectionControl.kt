package org.tamodak.killit.protection

/**
 * Starts and stops protection for the UI.
 *
 * An interface rather than direct calls to [Protection], because those need a `Context` and the
 * ViewModel must not hold one; it also lets a test run the ViewModel without starting a service.
 */
interface ProtectionControl {

    /** Starts the protection service and its periodic check if protection should run. */
    suspend fun ensureRunning()

    /** Stops the protection service and cancels its periodic check. */
    fun stop()
}
