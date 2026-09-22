package com.itzdfplayer.opengimbal

import com.itzdfplayer.opengimbal.camera.FlipWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flip rule: a rotation that inverts the picture fires once, a pan or a tilt does
 * not fire at all, and one swing cannot fire twice.
 *
 * Gravity readings below are in m/s^2, in the phone's own frame, so the vector is
 * normalised inside the class and only the direction matters.
 */
class FlipWatchTest {

    private val g = 9.8f

    @Test
    fun `the first reading only sets the reference`() {
        val watch = FlipWatch()

        assertFalse(watch.onGravity(0f, g, 0f, now = 0L))
        assertTrue(watch.ready)
        assertEquals(0f, watch.angleFromReference, 0.01f)
    }

    @Test
    fun `a full flip fires once`() {
        val watch = FlipWatch()
        watch.onGravity(0f, g, 0f, now = 0L)

        assertTrue("180 degrees from the reference", watch.onGravity(0f, -g, 0f, now = 1_000L))
    }

    @Test
    fun `the reference moves to the new orientation, so the same flip does not fire again`() {
        val watch = FlipWatch()
        watch.onGravity(0f, g, 0f, now = 0L)
        assertTrue(watch.onGravity(0f, -g, 0f, now = 1_000L))

        assertFalse("already flipped", watch.onGravity(0f, -g, 0f, now = 1_100L))
        assertFalse("already flipped", watch.onGravity(0f, -g, 0f, now = 2_000L))
        assertEquals(0f, watch.angleFromReference, 0.01f)
    }

    @Test
    fun `flipping back after the cooldown fires again`() {
        val watch = FlipWatch(cooldownMs = 6_000L)
        watch.onGravity(0f, g, 0f, now = 0L)
        assertTrue(watch.onGravity(0f, -g, 0f, now = 1_000L))

        assertTrue("flipped back", watch.onGravity(0f, g, 0f, now = 10_000L))
    }

    @Test
    fun `a second flip inside the cooldown is swallowed`() {
        val watch = FlipWatch(cooldownMs = 6_000L)
        watch.onGravity(0f, g, 0f, now = 0L)
        assertTrue(watch.onGravity(0f, -g, 0f, now = 1_000L))

        assertFalse(watch.onGravity(0f, g, 0f, now = 4_000L))
        assertTrue(watch.onGravity(0f, g, 0f, now = 8_000L))
    }

    @Test
    fun `a pan does not move gravity, so it never fires`() {
        // Turning to follow a subject changes the heading only; gravity in the phone's
        // frame is untouched, which is exactly why this cannot be done from yaw.
        val watch = FlipWatch()
        watch.onGravity(0f, g, 0f, now = 0L)

        for (step in 1..20) {
            assertFalse("pan step $step", watch.onGravity(0f, g, 0f, now = step * 200L))
        }
        assertEquals(0f, watch.angleFromReference, 0.01f)
    }

    @Test
    fun `a turn from portrait to landscape is not a flip`() {
        // 90 degrees, well short of the 120 degree threshold, and a common thing to do.
        val watch = FlipWatch()
        watch.onGravity(0f, g, 0f, now = 0L)

        assertFalse(watch.onGravity(g, 0f, 0f, now = 1_000L))
        assertEquals(90f, watch.angleFromReference, 1f)
    }

    @Test
    fun `a tilt past the threshold does fire, because the picture really is inverted`() {
        // 135 degrees: half way between landscape and upside down.
        val watch = FlipWatch()
        watch.onGravity(0f, g, 0f, now = 0L)

        val tilt = g * 0.707f
        assertTrue(watch.onGravity(tilt, -tilt, 0f, now = 1_000L))
    }

    @Test
    fun `free fall is not a flip`() {
        val watch = FlipWatch()
        watch.onGravity(0f, g, 0f, now = 0L)

        assertFalse("a zero vector carries no direction", watch.onGravity(0f, 0f, 0f, now = 1_000L))
        assertEquals(0f, watch.angleFromReference, 0.01f)
    }

    @Test
    fun `reset forgets the orientation, so the next reading becomes the reference`() {
        val watch = FlipWatch()
        watch.onGravity(0f, g, 0f, now = 0L)

        watch.reset()
        assertFalse(watch.ready)
        assertFalse(watch.onGravity(0f, -g, 0f, now = 1_000L))
        assertTrue(watch.ready)
        assertEquals(0f, watch.angleFromReference, 0.01f)
    }

    @Test
    fun `the angle is unaffected by the magnitude of the reading`() {
        // The gimbal is always moving slightly, so the vector length wanders.
        val watch = FlipWatch()
        watch.onGravity(0f, 10.4f, 0f, now = 0L)

        assertTrue(watch.onGravity(0f, -9.1f, 0f, now = 1_000L))
    }

    @Test
    fun `angleBetween measures the angle, and rejects a vector with no direction`() {
        val a = floatArrayOf(0f, 1f, 0f)

        assertEquals(0f, FlipWatch.angleBetween(a, floatArrayOf(0f, 2f, 0f))!!, 0.01f)
        assertEquals(90f, FlipWatch.angleBetween(a, floatArrayOf(1f, 0f, 0f))!!, 0.01f)
        assertEquals(180f, FlipWatch.angleBetween(a, floatArrayOf(0f, -1f, 0f))!!, 0.01f)

        assertNull(FlipWatch.angleBetween(a, floatArrayOf(0f, 0f, 0f)))
        assertNull(FlipWatch.angleBetween(floatArrayOf(0f, 0f, 0f), a))
    }

    @Test
    fun `the default threshold leaves room above a landscape turn`() {
        assertTrue(FlipWatch.DEFAULT_FLIP_DEGREES > 90f)
    }
}
