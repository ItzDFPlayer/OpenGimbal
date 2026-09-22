package com.itzdfplayer.opengimbal.camera

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Notices when the gimbal has swung the phone far enough round to invert the picture.
 *
 * A 180 degree flip leaves the second half of a recording upside down, so the fix is to
 * end the clip and start a new one the right way up. The trigger is the angle between
 * the gravity direction now and the direction it pointed when the reference was taken,
 * both measured in the phone's own frame:
 *
 *  * a **flip** - the phone turned over about the axis pointing out of the screen -
 *    reverses gravity in that frame, so the angle goes to 180 degrees, and it is
 *    exactly the rotation that turns the video upside down;
 *  * a **pan** - the gimbal turning to follow you - does not move gravity at all, so
 *    the angle stays near zero even though the phone has turned 180 degrees in the world.
 *
 * That distinction cannot be made from the gimbal's yaw, but it falls out of the
 * accelerometer for free, and needs no permission and no new protocol work.
 *
 * Pure and synchronous, so the firing rules are testable without sensors.
 */
class FlipWatch(
    private val flipDegrees: Float = DEFAULT_FLIP_DEGREES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {

    private var reference: FloatArray? = null
    private var firedAt: Long? = null

    /** How far the phone is from the orientation the last clip started in. */
    var angleFromReference: Float = 0f
        private set

    /** Whether we have a reference orientation to compare against yet. */
    val ready: Boolean get() = reference != null

    /**
     * Feeds one gravity reading, in m/s^2, along with a monotonic clock reading.
     * Returns true on the single reading that should restart the recording.
     */
    fun onGravity(x: Float, y: Float, z: Float, now: Long): Boolean {
        val current = normalise(x, y, z) ?: return false

        val previous = reference
        if (previous == null) {
            // The first reading defines "the right way up" for this clip.
            reference = current
            angleFromReference = 0f
            return false
        }

        val angle = angleBetween(previous, current) ?: return false
        angleFromReference = angle
        if (angle < flipDegrees) return false

        firedAt?.let { if (now - it < cooldownMs) return false }

        // The new orientation becomes the reference, so one flip cannot fire twice
        // while the gimbal is still settling, and flipping back can fire again later.
        reference = current
        angleFromReference = 0f
        firedAt = now
        return true
    }

    /** Takes the current orientation as the new "right way up", discarding any history. */
    fun reset() {
        reference = null
        angleFromReference = 0f
        firedAt = null
    }

    companion object {

        /**
         * A flip is not always the full 180 degrees, but it has to be far past the
         * tilts a hand or a walking bounce produces.
         */
        const val DEFAULT_FLIP_DEGREES = 120f

        /** Long enough for a stop, the encoder to finish, and a start. */
        const val DEFAULT_COOLDOWN_MS = 6_000L

        /**
         * Angle between two vectors in degrees, or `null` when either is degenerate -
         * a free-falling device reports no gravity at all, and that is not a flip.
         */
        fun angleBetween(a: FloatArray, b: FloatArray): Float? {
            val lengthA = length(a)
            val lengthB = length(b)
            if (lengthA <= 0f || lengthB <= 0f) return null

            val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (lengthA * lengthB)
            return Math.toDegrees(acos(dot.coerceIn(-1f, 1f)).toDouble()).toFloat()
        }

        private fun normalise(x: Float, y: Float, z: Float): FloatArray? {
            val vector = floatArrayOf(x, y, z)
            val size = length(vector)
            if (size <= 0f) return null
            vector[0] /= size
            vector[1] /= size
            vector[2] /= size
            return vector
        }

        private fun length(v: FloatArray): Float =
            sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    }
}
