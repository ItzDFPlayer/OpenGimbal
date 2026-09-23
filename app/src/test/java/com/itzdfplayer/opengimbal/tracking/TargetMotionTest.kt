package com.itzdfplayer.opengimbal.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lead this produces is what lets the servo loop aim ahead of the object instead of
 * behind it, so most of what matters here is what it *refuses* to believe: a gap it did not
 * travel across, the wobble of a still object, and a wrong peak dressed up as an enormous
 * speed.
 */
class TargetMotionTest {

    @Test
    fun `has nothing to say before it has seen anything`() {
        val motion = TargetMotion()

        assertFalse(motion.ready)
        assertEquals(0f, motion.speed, 0.001f)
        assertEquals(0f, motion.lead(300)[0], 0.001f)
        assertEquals(0f, motion.lead(300)[1], 0.001f)
        assertEquals(0, motion.samples)
    }

    @Test
    fun `one position is not a speed`() {
        val motion = TargetMotion()

        motion.observe(500, 400, 1_000)

        // A single position says where the object is, never how fast it is going.
        assertFalse(motion.ready)
        assertEquals(0f, motion.speed, 0.001f)
        assertEquals(0, motion.samples)
    }

    @Test
    fun `leads by the distance the object covers in the horizon`() {
        val motion = TargetMotion(maxPixelsPerSecond = 10_000f, blend = 1f)

        motion.observe(100, 100, 1_000)
        // 40 pixels in 100 ms is 400 pixels per second.
        motion.observe(140, 100, 1_100)

        assertTrue(motion.ready)
        assertEquals(400f, motion.speed, 0.5f)
        // A quarter of a second of travel at that speed.
        assertEquals(100f, motion.lead(250)[0], 0.5f)
        assertEquals(0f, motion.lead(250)[1], 0.001f)
    }

    @Test
    fun `leads on both axes at once`() {
        val motion = TargetMotion(maxPixelsPerSecond = 10_000f, blend = 1f)

        motion.observe(100, 100, 1_000)
        motion.observe(140, 130, 1_100)

        assertEquals(120f, motion.lead(300)[0], 0.5f)
        assertEquals(90f, motion.lead(300)[1], 0.5f)
    }

    @Test
    fun `a still object is not led, however its peak wobbles`() {
        val motion = TargetMotion(blend = 1f)

        motion.observe(100, 100, 1_000)
        // One pixel of drift in 40 ms is only 25 pixels a second: the peak landing a pixel to
        // one side, not the object moving.
        motion.observe(101, 100, 1_040)

        assertFalse(motion.ready)
        assertEquals(0f, motion.lead(300)[0], 0.001f)
    }

    @Test
    fun `a gap is not a measurement of movement`() {
        val motion = TargetMotion(blend = 1f)

        motion.observe(100, 100, 1_000)
        // Far apart, but five seconds apart: this is two scenes, not a speed.
        motion.observe(400, 100, 6_000)

        assertEquals(0f, motion.speed, 0.001f)
        assertFalse(motion.ready)
    }

    @Test
    fun `the first reading after a gap restarts rather than divides`() {
        val motion = TargetMotion(blend = 1f)

        motion.observe(100, 100, 1_000)
        motion.observe(400, 100, 6_000)
        // The very next reading does follow the last one, so it is a real measurement again.
        motion.observe(404, 100, 6_050)

        assertEquals(80f, motion.speed, 0.5f)
    }

    @Test
    fun `an impossible single jump is bounded rather than believed`() {
        val motion = TargetMotion(maxPixelsPerSecond = 1_000f, blend = 1f)

        motion.observe(0, 0, 1_000)
        // A whole screen width in 30 ms is 36 000 pixels a second, which is a wrong peak.
        motion.observe(1_080, 0, 1_030)

        assertEquals(1_000f, motion.speed, 1f)
    }

    @Test
    fun `the bounding keeps the direction it was pointing`() {
        val motion = TargetMotion(maxPixelsPerSecond = 500f, blend = 1f)

        motion.observe(1_000, 1_000, 1_000)
        motion.observe(1_000, 0, 1_040)

        // Straight up, just slower than it claimed.
        assertEquals(0f, motion.velocityX, 0.5f)
        assertEquals(-500f, motion.velocityY, 1f)
    }

    @Test
    fun `two readings at the same instant do not divide by zero`() {
        val motion = TargetMotion(blend = 1f)

        motion.observe(100, 100, 1_000)
        motion.observe(200, 100, 1_000)

        assertEquals(0f, motion.speed, 0.001f)
    }

    @Test
    fun `a reading that does not move the object does not add speed`() {
        val motion = TargetMotion(blend = 1f)

        motion.observe(100, 100, 1_000)
        motion.observe(140, 100, 1_100)
        // The object stopped. The estimate falls away rather than staying at the old speed.
        repeat(8) { motion.observe(140, 100, 1_100L + (it + 1) * 100L) }

        assertFalse(motion.ready)
    }

    @Test
    fun `resetting forgets the speed`() {
        val motion = TargetMotion(maxPixelsPerSecond = 10_000f, blend = 1f)
        motion.observe(100, 100, 1_000)
        motion.observe(140, 100, 1_100)

        motion.reset()

        assertEquals(0f, motion.speed, 0.001f)
        assertEquals(0, motion.samples)
        assertFalse(motion.ready)
    }
}
