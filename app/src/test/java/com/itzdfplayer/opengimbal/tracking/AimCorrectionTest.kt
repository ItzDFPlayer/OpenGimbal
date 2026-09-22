package com.itzdfplayer.opengimbal.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The correction arithmetic, and above all its signs.
 *
 * This is worth pinning down because a wrong sign does not fail loudly - it produces a
 * gimbal that drives away from the thing it is meant to follow, which reads as broken
 * hardware and is very hard to diagnose on a phone.
 */
class AimCorrectionTest {

    private companion object {
        const val WIDTH = 1300
        const val HEIGHT = 1000

        /** 20 pixels of movement per degree, as a clear-eyed starting guess. */
        const val PIXELS_PER_DEGREE = 20f

        /** The same thing the other way up, which is the form the arithmetic takes. */
        const val DEGREES_PER_PIXEL = 1f / PIXELS_PER_DEGREE

        /** Large enough that it never binds, for the tests about the dead zone and signs. */
        const val NO_STEP_LIMIT = 10_000f
    }

    private fun degrees(
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        maxPixelsPerStep: Float = NO_STEP_LIMIT,
        damping: Float = 1f,
        invertX: Boolean = false,
        invertY: Boolean = false,
    ) = AimCorrection.degrees(
        offsetX = offsetX,
        offsetY = offsetY,
        frameWidth = WIDTH,
        frameHeight = HEIGHT,
        horizontalDegreesPerPixel = DEGREES_PER_PIXEL,
        verticalDegreesPerPixel = DEGREES_PER_PIXEL,
        maxPixelsPerStep = maxPixelsPerStep,
        damping = damping,
        invertX = invertX,
        invertY = invertY,
    )

    // ---- the dead zone -----------------------------------------------------

    @Test
    fun `a centred patch asks for nothing`() {
        assertNull(degrees())
    }

    @Test
    fun `a patch inside the dead zone asks for nothing`() {
        // 4% of the 1000 pixel short edge is 40 pixels either way.
        assertNull(degrees(offsetX = 39f))
        assertNull(degrees(offsetX = -39f))
        assertNull(degrees(offsetY = 39f))
        assertNull(degrees(offsetY = -39f))
    }

    @Test
    fun `a patch just outside the dead zone does ask`() {
        assertNotNull(degrees(offsetX = 41f))
        assertNotNull(degrees(offsetY = 41f))
    }

    @Test
    fun `one axis inside the dead zone does not silence the other`() {
        val correction = degrees(offsetX = 200f, offsetY = 10f)

        requireNotNull(correction)
        assertTrue(correction[0] != 0f)
        assertEquals(0f, correction[1], 0.0001f)
    }

    // ---- the signs ---------------------------------------------------------

    @Test
    fun `a patch to the right turns the gimbal right`() {
        val correction = degrees(offsetX = 200f)

        requireNotNull(correction)
        // 200 pixels at a twentieth of a degree each.
        assertEquals(10f, correction[0], 0.0001f)
        assertEquals(0f, correction[1], 0.0001f)
    }

    @Test
    fun `a patch to the left turns the gimbal left`() {
        val correction = degrees(offsetX = -200f)

        requireNotNull(correction)
        assertEquals(-10f, correction[0], 0.0001f)
    }

    @Test
    fun `a patch above the middle tilts the gimbal up`() {
        val correction = degrees(offsetY = -200f)

        requireNotNull(correction)
        // Up the screen is negative y, and tilting up is positive pitch, so the sign flips.
        assertEquals(10f, correction[1], 0.0001f)
        assertEquals(0f, correction[0], 0.0001f)
    }

    @Test
    fun `a patch below the middle tilts the gimbal down`() {
        val correction = degrees(offsetY = 200f)

        requireNotNull(correction)
        assertEquals(-10f, correction[1], 0.0001f)
    }

    /**
     * The case that can be reasoned about without any of the field-of-view arithmetic: an
     * object in the top right corner needs the camera panned right and tilted up.
     */
    @Test
    fun `something in the top right needs a right pan and an up tilt`() {
        val correction = degrees(offsetX = 300f, offsetY = -250f)

        requireNotNull(correction)
        assertTrue("pan right", correction[0] > 0f)
        assertTrue("tilt up", correction[1] > 0f)
    }

    @Test
    fun `the two axes are not mirror images of each other`() {
        val correction = degrees(offsetX = 200f, offsetY = 200f)

        requireNotNull(correction)
        // Both offsets are the same magnitude and the same direction on screen, and the
        // answers must come out with opposite signs. Assuming they do not is the mistake
        // this pins down.
        assertTrue(correction[0] > 0f)
        assertTrue(correction[1] < 0f)
    }

    // ---- the size of the step ----------------------------------------------

    @Test
    fun `half the error is corrected by default`() {
        val correction = degrees(offsetX = 200f, damping = AimCorrection.DEFAULT_DAMPING)

        requireNotNull(correction)
        // Half of 200 pixels is 100, at a twentieth of a degree each.
        assertEquals(5f, correction[0], 0.0001f)
    }

    /**
     * The important one. A step that aims to move the object further than the next search
     * will look loses it on the spot, so the step is budgeted as a distance, not as an
     * angle - and that bound has to hold even while the gain estimate is still wrong.
     */
    @Test
    fun `a step never aims to move the object further than its budget`() {
        val correction = degrees(offsetX = 900f, damping = 1f, maxPixelsPerStep = 30f)

        requireNotNull(correction)
        // 30 pixels at a twentieth of a degree each, not the 45 degrees the raw error asked
        // for.
        assertEquals(1.5f, correction[0], 0.0001f)
    }

    @Test
    fun `the budget applies to each axis on its own`() {
        val correction = degrees(
            offsetX = 900f,
            offsetY = -900f,
            damping = 1f,
            maxPixelsPerStep = 30f,
        )

        requireNotNull(correction)
        assertEquals(1.5f, correction[0], 0.0001f)
        assertEquals(1.5f, correction[1], 0.0001f)
    }

    @Test
    fun `a step inside the budget is left alone`() {
        val correction = degrees(offsetX = 100f, damping = 1f, maxPixelsPerStep = 500f)

        requireNotNull(correction)
        assertEquals(5f, correction[0], 0.0001f)
    }

    // ---- the direction switches --------------------------------------------

    @Test
    fun `the invert switches flip the correction`() {
        val normal = degrees(offsetX = 200f, offsetY = 200f)
        val flipped = degrees(offsetX = 200f, offsetY = 200f, invertX = true, invertY = true)

        requireNotNull(normal)
        requireNotNull(flipped)
        assertEquals(-normal[0], flipped[0], 0.0001f)
        assertEquals(-normal[1], flipped[1], 0.0001f)
    }

    @Test
    fun `the dead zone does not depend on which way round the screen is`() {
        val landscape = AimCorrection.degrees(30f, 0f, WIDTH, HEIGHT, DEGREES_PER_PIXEL, DEGREES_PER_PIXEL, NO_STEP_LIMIT)
        val portrait = AimCorrection.degrees(30f, 0f, HEIGHT, WIDTH, DEGREES_PER_PIXEL, DEGREES_PER_PIXEL, NO_STEP_LIMIT)

        // 30 pixels is inside the dead zone either way, because it is a share of the short
        // edge and the short edge does not change.
        assertNull(landscape)
        assertNull(portrait)
    }

    // ---- what the settings do ----------------------------------------------

    @Test
    fun `damping cannot be pushed into guaranteed overshoot territory`() {
        // The strength setting is a share of the error to remove per step. Whatever it is
        // set to, the loop must never be asked to remove more than all of it in one go.
        assertTrue(TrackingSettings(strength = 400f).damping <= 1f)
        assertTrue(TrackingSettings(strength = 100f).damping < 1f)
        assertTrue(TrackingSettings(strength = 10f).damping > 0f)
        assertEquals(
            AimCorrection.DEFAULT_DAMPING,
            TrackingSettings(strength = 100f).damping,
            0.001f,
        )
    }
}
