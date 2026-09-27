package org.tamodak.killit.data

/**
 * Which camera the QR scanner opens with.
 *
 * Persisted as a user preference rather than chosen per scan, because the right answer depends on
 * how the two phones are being held and stays the same for the whole pairing session: the back
 * camera for the usual case of pointing one phone at another, the front camera when the two screens
 * face each other.
 *
 * The enum *name* is what gets persisted, so these constants must not be renamed without a
 * migration — an unrecognised name silently falls back to [DEFAULT].
 */
enum class CameraFacing {
    /** The rear camera. Used when pointing this phone at another phone's screen. */
    BACK,

    /** The selfie camera. Used when the two devices are screen to screen. */
    FRONT;

    companion object {
        /** Chosen when nothing is stored: the rear camera, which every camera phone has. */
        val DEFAULT = BACK

        /**
         * Parses a persisted name.
         *
         * @param name the stored enum name, or null when nothing has been written yet.
         * @return the matching constant, or [DEFAULT] for anything unrecognised or absent.
         */
        fun fromName(name: String?): CameraFacing =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
