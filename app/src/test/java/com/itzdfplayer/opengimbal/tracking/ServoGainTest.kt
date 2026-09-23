package com.itzdfplayer.opengimbal.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gain estimate is what the whole servo loop is sized by, and it is learned from
 * measurements that can be wrong for reasons that have nothing to do with the gimbal - the
 * subject moving on its own, a frame arriving late, a match that was not the object. So the
 * interesting behaviour here is mostly about what it *refuses* to believe.
 */
class ServoGainTest {

    @Test
    fun `starts at a guess on the cautious side`() {
        val gain = ServoGain()

        assertEquals(ServoGain.DEFAULT_PIXELS_PER_DEGREE, gain.pixelsPerDegree, 0.001f)
        // A high pixels-per-degree means a small correction for a given error, so an
        // unmeasured loop under-corrects rather than overshoots.
        assertTrue(gain.degreesPerPixel < 0.05f)
        assertEquals(0, gain.accepted)
    }

    @Test
    fun `learns the size from how far the picture actually moved`() {
        val gain = ServoGain()

        // Two degrees of turn moved the object 40 pixels: 20 pixels per degree.
        assertTrue(gain.observe(shiftedPixels = 40f, commandedDegrees = 2f))
        assertEquals(1, gain.accepted)

        // Blended towards the measurement, not replaced by it, so one odd frame cannot swing
        // the loop's whole idea of its own scale.
        val expected = ServoGain.DEFAULT_PIXELS_PER_DEGREE +
            (20f - ServoGain.DEFAULT_PIXELS_PER_DEGREE) * ServoGain.BLEND
        assertEquals(expected, gain.pixelsPerDegree, 0.001f)
    }

    @Test
    fun `converges on the truth when fed consistent measurements`() {
        val gain = ServoGain()

        repeat(20) { gain.observe(shiftedPixels = 30f, commandedDegrees = 2f) }

        // 15 pixels per degree, the case of a camera much wider than the starting guess.
        assertEquals(15f, gain.pixelsPerDegree, 0.5f)
    }

    @Test
    fun `refuses a measurement that came out backwards`() {
        val gain = ServoGain()
        val before = gain.pixelsPerDegree

        // The picture moved the other way, so this axis does not behave as modelled. Learning
        // a negative size here would flip the loop's understanding of its own axes; the
        // direction switches are the right fix, so this is counted and ignored.
        assertFalse(gain.observe(shiftedPixels = -40f, commandedDegrees = 2f))
        assertEquals(before, gain.pixelsPerDegree, 0.001f)
        assertEquals(1, gain.reversed)
        assertEquals(0, gain.accepted)
    }

    @Test
    fun `refuses a measurement outside what a phone camera can do`() {
        val gain = ServoGain()

        // Almost no movement for a large command: not a camera, a mismatch or a lost target.
        assertFalse(gain.observe(shiftedPixels = 1f, commandedDegrees = 4f))
        // A vast movement for a tiny command.
        assertFalse(gain.observe(shiftedPixels = 900f, commandedDegrees = 1f))

        assertEquals(2, gain.discarded)
        assertEquals(0, gain.accepted)
    }

    @Test
    fun `refuses a measurement from a command too small to mean anything`() {
        val gain = ServoGain()

        assertFalse(gain.observe(shiftedPixels = 5f, commandedDegrees = 0.1f))

        assertEquals(0, gain.accepted)
        assertEquals(0, gain.discarded)
        assertEquals(0, gain.reversed)
    }

    @Test
    fun `accepts a command right at the smallest measurable size`() {
        val gain = ServoGain()

        // 30 pixels per degree, from a command exactly on the smallest size worth measuring.
        assertTrue(
            gain.observe(
                shiftedPixels = ServoGain.MIN_COMMAND_DEGREES * 30f,
                commandedDegrees = ServoGain.MIN_COMMAND_DEGREES,
            )
        )
        assertEquals(1, gain.accepted)
    }

    @Test
    fun `refuses a command just below the smallest measurable size`() {
        val gain = ServoGain()

        assertFalse(
            gain.observe(
                shiftedPixels = 30f,
                commandedDegrees = ServoGain.MIN_COMMAND_DEGREES - 0.01f,
            )
        )
        assertEquals(0, gain.accepted)
    }

    @Test
    fun `resetting forgets what was measured`() {
        val gain = ServoGain()
        gain.observe(shiftedPixels = 40f, commandedDegrees = 2f)
        gain.observe(shiftedPixels = -40f, commandedDegrees = 2f)

        gain.reset()

        assertEquals(ServoGain.DEFAULT_PIXELS_PER_DEGREE, gain.pixelsPerDegree, 0.001f)
        assertEquals(0, gain.accepted)
        assertEquals(0, gain.reversed)
        assertEquals(0, gain.discarded)
    }

    @Test
    fun `the band it accepts spans the cameras a phone actually has`() {
        val gain = ServoGain()

        // An ultra-wide lens: a lot of scene per degree, so few pixels per degree.
        assertTrue(gain.observe(shiftedPixels = ServoGain.MIN_PIXELS_PER_DEGREE * 2f, commandedDegrees = 2f))
        gain.reset()
        // A long lens: very little scene per degree, so many pixels per degree.
        assertTrue(gain.observe(shiftedPixels = ServoGain.MAX_PIXELS_PER_DEGREE * 2f, commandedDegrees = 2f))
    }

    @Test
    fun `a frame size gives a starting guess much closer than the flat one`() {
        // 1080 pixels across a 65 degree camera is about 17 pixels per degree, where the flat
        // guess says 45. Being out by nearly three is not a detail: it is every correction for
        // the first seconds of a session asking for a third of the turn it needs, which from
        // outside is indistinguishable from a tracker that does not work at all.
        val seeded = ServoGain(ServoGain.seedFor(1080))

        assertEquals(
            1080f / ServoGain.ASSUMED_FIELD_OF_VIEW_DEGREES,
            seeded.pixelsPerDegree,
            0.01f,
        )
        assertTrue(
            "a seeded gain must be nearer the truth than the flat guess",
            seeded.pixelsPerDegree < ServoGain.DEFAULT_PIXELS_PER_DEGREE / 2f,
        )
    }

    @Test
    fun `a seeded gain returns to its own seed, not to the flat guess`() {
        val gain = ServoGain(ServoGain.seedFor(1080))
        gain.observe(shiftedPixels = 34f, commandedDegrees = 2f)

        gain.reset()

        // The seed is part of what makes this instance useful; going back to the flat guess on
        // a new object would throw away the only thing known about the camera.
        assertEquals(ServoGain.seedFor(1080), gain.pixelsPerDegree, 0.001f)
    }

    @Test
    fun `a seed from any frame size stays inside what a camera can do`() {
        for (size in listOf(320, 720, 1080, 1440, 2400, 4320)) {
            val seeded = ServoGain.seedFor(size)
            assertTrue("$size is below the band", seeded >= ServoGain.MIN_PIXELS_PER_DEGREE)
            assertTrue("$size is above the band", seeded <= ServoGain.MAX_PIXELS_PER_DEGREE)
        }
    }
}
