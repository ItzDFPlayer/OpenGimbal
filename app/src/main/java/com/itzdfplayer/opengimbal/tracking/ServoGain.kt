package com.itzdfplayer.opengimbal.tracking

import kotlin.math.abs

/**
 * Learns how far the picture shifts for one degree of turn, on one axis.
 *
 * The number this replaces was a guess. It is the camera's field of view spread across the
 * width of the screen, and no phone publishes that - while getting it wrong is not harmless.
 * Guessing it too low means every correction moves the object further than intended: it
 * sails past the middle of the screen, the next correction sends it back the other way, and
 * the object ends up circling the centre instead of settling into it, which looks like a
 * broken gimbal and is really just an oversized step.
 *
 * Rather than guess, the loop can measure. It knows how many degrees it asked for, and the
 * next frame shows how far the object actually moved. That ratio is the answer, and having
 * it is what lets a correction be sized in pixels the object will really travel.
 */
class ServoGain(
    /**
     * Where to start, and what [reset] goes back to - the number an instance is built with
     * is part of what makes it that instance, so a gain seeded from a frame size returns to
     * that seed rather than to a fixed constant when a new object is picked.
     */
    private val initialPixelsPerDegree: Float = DEFAULT_PIXELS_PER_DEGREE,
) {

    companion object {
        /**
         * Where to start when the caller knows nothing about the camera: a narrow-ish field
         * of view, which makes the first corrections too small rather than too large.
         * Under-correcting converges over a few steps; over-correcting oscillates. A first
         * guess should err towards the boring failure.
         */
        const val DEFAULT_PIXELS_PER_DEGREE = 45f

        /**
         * The field of view a phone's main camera can be assumed to have, in degrees.
         *
         * Anything from an ultra-wide to a modest telephoto is outside this, but every
         * ordinary phone camera sits close to it whatever the screen it is shown on, which is
         * what makes it usable as a starting point. See [seedFor].
         */
        const val ASSUMED_FIELD_OF_VIEW_DEGREES = 65f

        /**
         * Measurements outside this band are refused. A phone camera cannot be outside it,
         * so a number out there is noise, motion of the subject, or an axis that does not
         * behave the way the model says - and none of those should be believed.
         */
        const val MIN_PIXELS_PER_DEGREE = 10f
        const val MAX_PIXELS_PER_DEGREE = 120f

        /** Below this the command was too small for the resulting movement to mean much. */
        const val MIN_COMMAND_DEGREES = 0.3f

        /**
         * How much of each accepted measurement to believe, and how much to keep.
         *
         * Raised from a third. A measurement is taken from two matches that both passed the
         * similarity and peak-margin tests, so it is not the wild guess the cautious blend
         * was written for - while a lagging estimate is not harmless: for the first couple of
         * seconds of every session the loop under-corrects by whatever the seed was wrong by,
         * and those are the seconds in which the object is most likely to be lost.
         */
        const val BLEND = 0.5f

        /**
         * A first guess from the size of the frame the patches are cut from.
         *
         * Pixels per degree is the camera's field of view spread across the frame, and the
         * field of view barely changes from phone to phone - it is the screen that does.
         * Dividing the frame by [ASSUMED_FIELD_OF_VIEW_DEGREES] therefore lands within tens
         * of percent almost everywhere, where the fixed [DEFAULT_PIXELS_PER_DEGREE] was out
         * by nearly a factor of three on a typical 1080-wide frame. Being out by three means
         * every correction for the first two seconds asks for a third of the turn it needs,
         * which from the outside is indistinguishable from a tracker that does not work.
         */
        fun seedFor(frameSize: Int): Float = (frameSize / ASSUMED_FIELD_OF_VIEW_DEGREES)
            .coerceIn(MIN_PIXELS_PER_DEGREE, MAX_PIXELS_PER_DEGREE)
    }

    var pixelsPerDegree = initialPixelsPerDegree
        private set

    /** Measurements taken into account. */
    var accepted = 0
        private set

    /**
     * Measurements that came out backwards: the picture moved opposite to the way this axis
     * is meant to move it. A run of these means the axis is pointing the wrong way, which is
     * something the user can fix with the direction switches - so it is worth counting and
     * showing rather than silently ignoring.
     */
    var reversed = 0
        private set

    /** Measurements thrown away for being outside the possible band. */
    var discarded = 0
        private set

    /** Degrees per pixel, which is the form the correction arithmetic wants. */
    val degreesPerPixel: Float get() = 1f / pixelsPerDegree

    /**
     * Takes in the outcome of a command.
     *
     * @param shiftedPixels how far the picture moved in the direction a *positive* command
     *   is meant to move it, so a negative value means it went the other way.
     * @param commandedDegrees the command that was sent.
     * @return true when the estimate changed.
     */
    fun observe(shiftedPixels: Float, commandedDegrees: Float): Boolean {
        if (abs(commandedDegrees) < MIN_COMMAND_DEGREES) return false

        val measured = shiftedPixels / commandedDegrees
        if (measured < 0f) {
            // The picture went the wrong way. Learning a negative size would flip the loop's
            // understanding of its own axes, so this is a direction problem, not a gain one.
            reversed++
            return false
        }
        if (measured < MIN_PIXELS_PER_DEGREE || measured > MAX_PIXELS_PER_DEGREE) {
            discarded++
            return false
        }

        pixelsPerDegree += (measured - pixelsPerDegree) * BLEND
        accepted++
        return true
    }

    /**
     * Back to the starting guess, for a new target on a new scene.
     *
     * Goes back to the value this instance was built with rather than to the fixed default,
     * so a gain that was seeded from the frame size stays seeded.
     */
    fun reset() {
        pixelsPerDegree = initialPixelsPerDegree
        accepted = 0
        reversed = 0
        discarded = 0
    }
}
