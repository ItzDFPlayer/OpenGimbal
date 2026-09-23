package com.itzdfplayer.opengimbal.tracking

import kotlin.math.hypot

/**
 * Estimates where the object is heading, so a correction can aim at where it is going to be
 * rather than at where it was.
 *
 * The loop this feeds has two delays in it that no cleverness removes: the gimbal takes time
 * to physically move, and the mirrored picture showing that movement arrives later still.
 * Correcting only for the error measured in the last frame therefore always aims at a stale
 * position, and the faster the object moves the further behind that position is - which is
 * precisely why a purely proportional loop cannot hold anything that is walking.
 *
 * Knowing the object's speed turns that into a solvable problem. The error the correction is
 * sized from becomes the distance from the middle *plus* the distance the object is expected
 * to cover before the next look, so the gimbal is aimed at where the object will be instead of
 * where it was and the loop leads rather than trails.
 *
 * The speed is measured, not assumed: it is the movement actually seen between two matched
 * frames divided by the time between them. Three things are refused, and all three matter,
 * because a wrong peak looks exactly like a very fast object:
 *
 *  * A gap too long to be a measurement of movement - the object's own travel is no longer
 *    what is being divided.
 *  * A speed no camera could produce, which is a wrong peak rather than a fast object.
 *  * A speed so low that it is the peak landing either side of a still object, which would
 *    otherwise have the gimbal creeping for no reason.
 */
class TargetMotion(
    /**
     * A speed above this is not believed.
     *
     * A wrong peak can put the object anywhere on screen, and one of those divided by a frame
     * interval is an enormous speed that would fling the aim across the scene. The caller sets
     * this from the frame size, since only it knows what a plausible distance is.
     */
    private val maxPixelsPerSecond: Float = MAX_PIXELS_PER_SECOND,

    /** How much of each new measurement to believe, and how much to keep. */
    private val blend: Float = BLEND,

    /** A gap longer than this is not the same measurement, it is a different scene. */
    private val maxSampleGapMs: Long = MAX_SAMPLE_GAP_MS,
) {

    companion object {
        /** Fallback bound, for a caller that does not know the frame size. */
        const val MAX_PIXELS_PER_SECOND = 2000f

        /**
         * Below this the object counts as not moving.
         *
         * A matched position is a whole number of pixels, so a perfectly still object still
         * reports the odd one-pixel wobble as the correlation peak lands either side of it.
         * Divided by a frame interval that is tens of pixels a second, and leading by it would
         * have the gimbal creeping while the user holds the phone still.
         */
        const val MIN_SPEED_PIXELS_PER_SECOND = 30f

        /**
         * How much of each measurement to believe.
         *
         * A single bad match is not that unlikely, so a measurement is mixed in rather than
         * adopted - but the blend is high, because a lagging estimate is worse than a noisy
         * one: the whole point is to be ahead of the object, and an estimate that takes a
         * second to catch up is no better than not having one.
         */
        const val BLEND = 0.4f

        /** Longer than this and the two positions are not a movement, they are two scenes. */
        const val MAX_SAMPLE_GAP_MS = 400L

        private val ZERO = floatArrayOf(0f, 0f)
    }

    /** Estimated speed along the screen's x axis, positive right, in pixels per second. */
    var velocityX = 0f
        private set

    /** The same along y, positive down. */
    var velocityY = 0f
        private set

    /** Measurements taken since the last [reset]. */
    var samples = 0
        private set

    private var lastX = 0
    private var lastY = 0
    private var lastAt = 0L
    private var started = false

    /** How fast the object is moving, in pixels per second. */
    val speed: Float get() = hypot(velocityX, velocityY)

    /**
     * True once there is a speed worth leading by.
     *
     * False for a still object as well as for one that has not been seen yet, so a caller can
     * ask this one question instead of comparing against the floor itself.
     */
    val ready: Boolean get() = started && speed > MIN_SPEED_PIXELS_PER_SECOND

    /**
     * Takes in a position that was really matched, and when.
     *
     * A position that does not follow the previous one in time - the first of a session, or
     * one after a gap - restarts the estimate rather than being divided by a gap it did not
     * travel across.
     */
    fun observe(x: Int, y: Int, now: Long) {
        if (!started) {
            restart(x, y, now)
            return
        }

        val elapsed = now - lastAt
        if (elapsed <= 0L || elapsed > maxSampleGapMs) {
            restart(x, y, now)
            return
        }

        val seconds = elapsed / 1000f
        val bounded = bounded((x - lastX) / seconds, (y - lastY) / seconds)
        velocityX += (bounded[0] - velocityX) * blend
        velocityY += (bounded[1] - velocityY) * blend
        samples++
        lastX = x
        lastY = y
        lastAt = now
    }

    /**
     * How far the object is expected to travel in [aheadMs], as a pixel offset per axis.
     *
     * Zero while the object is moving too slowly for the estimate to mean anything, which is
     * what keeps the loop from leading on noise alone.
     */
    fun lead(aheadMs: Long): FloatArray {
        if (!ready) return ZERO
        val seconds = aheadMs / 1000f
        return floatArrayOf(velocityX * seconds, velocityY * seconds)
    }

    /** Forgets everything, for a new object on a new scene. */
    fun reset() {
        velocityX = 0f
        velocityY = 0f
        samples = 0
        started = false
        lastAt = 0L
    }

    private fun restart(x: Int, y: Int, now: Long) {
        velocityX = 0f
        velocityY = 0f
        lastX = x
        lastY = y
        lastAt = now
        started = true
    }

    /** Caps a speed at [maxPixelsPerSecond] without changing which way it points. */
    private fun bounded(vx: Float, vy: Float): FloatArray {
        val speed = hypot(vx, vy)
        if (speed == 0f || speed <= maxPixelsPerSecond) return floatArrayOf(vx, vy)
        val factor = maxPixelsPerSecond / speed
        return floatArrayOf(vx * factor, vy * factor)
    }
}
