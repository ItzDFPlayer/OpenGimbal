package com.itzdfplayer.opengimbal.tracking

import kotlin.math.abs

/**
 * How far the gimbal has to turn to bring a patch back to the middle of the frame.
 *
 * Pure, and deliberately separate from the loop that uses it, because two things here are
 * expensive to get wrong: the sign of the correction, and the size of the step.
 *
 * Signs, worked out from the geometry rather than by trying it, since the two axes turn out
 * not to share one and assuming they do is the mistake this comment exists to prevent:
 *
 *  * A camera pans right and the scene slides *left* in the frame. For a 1300 pixel wide
 *    screen and a 65 degree field of view, an object 10 degrees to the right of the aim
 *    sits 200 pixels right of centre, and 200 / 20 = 10 degrees is what has to be added to
 *    the yaw. So yaw takes the error's own sign.
 *  * A camera tilts up and the scene slides *down*, in the same direction as screen y. An
 *    object 10 degrees above the aim sits 150 pixels above centre, which is a *negative*
 *    offset because screen y grows downwards, and the pitch has to increase by 10 degrees.
 *    So pitch takes the opposite of the error's sign.
 *
 * Checked against a case that can be reasoned about without any of this: something drifting
 * to the top right of the frame needs the camera panned right and tilted up, which is a
 * positive yaw and a positive pitch.
 *
 * This assumes positive yaw means "pan right" and positive pitch means "tilt up", which is
 * how the on-screen stick treats them. If a particular gimbal has that backwards, the two
 * invert switches on the Control screen describe the hardware and fix the stick and the
 * tracker together.
 *
 * Size is the other half, and it is bounded in *pixels* rather than in degrees. Degrees are
 * what gets sent, but pixels are what the object does, and a step that moves the object
 * further than the search can follow loses it on the spot - which is exactly what an
 * over-sized step does. Budgeting the step as a distance on screen keeps the object inside
 * the searchable window even while the gain estimate is still wrong, which it is for the
 * first few steps of every session.
 */
object AimCorrection {

    /**
     * The share of the error corrected in one step, at 100% strength.
     *
     * Deliberately half of it. Correcting the whole error in one go leaves no margin: any
     * error in the gain estimate turns the step into an overshoot, and an overshoot is what
     * makes the object swing past the middle and come back. Half means the gain can be wrong
     * by a factor of two and the loop still settles instead of ringing.
     */
    const val DEFAULT_DAMPING = 0.5f

    /**
     * Error smaller than this share of the short edge counts as centred.
     *
     * Without a dead zone the tracker chases its own noise: every frame would produce a
     * sub-degree correction and the gimbal would sit there jittering instead of holding
     * still.
     */
    const val DEAD_ZONE_FRACTION = 0.04f

    /**
     * The correction for a patch sitting at ([offsetX], [offsetY]) pixels from the middle
     * of the frame, or `null` when it is close enough to the middle to do nothing.
     *
     * Offsets are in screen pixels, positive right and positive down.
     *
     * @param horizontalDegreesPerPixel how much turn moves the object one pixel sideways.
     * @param verticalDegreesPerPixel the same for up and down.
     * @param maxPixelsPerStep the furthest this step may aim to move the object, which is
     *   what keeps it inside the window the next search will look in.
     * @param damping the share of the error to correct, from [DEFAULT_DAMPING].
     */
    fun degrees(
        offsetX: Float,
        offsetY: Float,
        frameWidth: Int,
        frameHeight: Int,
        horizontalDegreesPerPixel: Float,
        verticalDegreesPerPixel: Float,
        maxPixelsPerStep: Float,
        damping: Float = DEFAULT_DAMPING,
        invertX: Boolean = false,
        invertY: Boolean = false,
    ): FloatArray? {
        val deadZone = minOf(frameWidth, frameHeight) * DEAD_ZONE_FRACTION
        val errorX = if (abs(offsetX) < deadZone) 0f else offsetX
        val errorY = if (abs(offsetY) < deadZone) 0f else offsetY
        if (errorX == 0f && errorY == 0f) return null

        val wantedX = (errorX * damping).coerceIn(-maxPixelsPerStep, maxPixelsPerStep)
        val wantedY = (errorY * damping).coerceIn(-maxPixelsPerStep, maxPixelsPerStep)

        return floatArrayOf(
            wantedX * horizontalDegreesPerPixel * (if (invertX) -1f else 1f),
            -wantedY * verticalDegreesPerPixel * (if (invertY) -1f else 1f),
        )
    }
}
