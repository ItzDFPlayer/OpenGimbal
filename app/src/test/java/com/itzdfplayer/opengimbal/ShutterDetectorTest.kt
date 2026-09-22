package com.itzdfplayer.opengimbal

import com.itzdfplayer.opengimbal.accessibility.ScreenBounds
import com.itzdfplayer.opengimbal.accessibility.ShutterCandidate
import com.itzdfplayer.opengimbal.accessibility.ShutterDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometric stand-in for "the big white or red circle at the bottom":
 * an accessibility tree has no pixel data, so the shutter is identified by shape,
 * position and size, with labels as a strong hint when an app exposes them.
 */
class ShutterDetectorTest {

    private val screenWidth = 1080
    private val screenHeight = 2400

    private fun bounds(left: Int, top: Int, size: Int) =
        ScreenBounds(left, top, left + size, top + size)

    /** A textbook shutter: centred, round-ish, in the bottom half. */
    private fun shutter(
        size: Int = 190,
        clickable: Boolean = true,
        description: String? = null,
        viewId: String? = null,
    ): ShutterCandidate {
        val left = (screenWidth - size) / 2
        // 80% down the screen, which is where camera apps put it.
        val top = (screenHeight * 0.8f).toInt() - size / 2
        return ShutterCandidate(bounds(left, top, size), clickable, description, viewId)
    }

    @Test
    fun `accepts a big round button in the bottom half`() {
        val candidate = shutter()

        val score = ShutterDetector.score(candidate, screenWidth, screenHeight)

        assertNotNull(score)
        assertTrue(score!! >= ShutterDetector.MIN_SCORE)
    }

    @Test
    fun `rejects anything in the top half`() {
        val topHalf = ShutterCandidate(bounds(445, 200, 190), clickable = true)

        assertNull(ShutterDetector.score(topHalf, screenWidth, screenHeight))
    }

    @Test
    fun `rejects a wide bar such as a mode selector`() {
        val bar = ShutterCandidate(ScreenBounds(190, 1900, 890, 2000), clickable = true)

        assertNull(ShutterDetector.score(bar, screenWidth, screenHeight))
    }

    @Test
    fun `rejects a tiny icon`() {
        val icon = ShutterCandidate(bounds(600, 1900, 48), clickable = true)

        assertNull(ShutterDetector.score(icon, screenWidth, screenHeight))
    }

    @Test
    fun `rejects something as big as the preview`() {
        // 70% of the screen width is not a button.
        val huge = ShutterCandidate(bounds(150, 1300, 780), clickable = true)

        assertNull(ShutterDetector.score(huge, screenWidth, screenHeight))
    }

    @Test
    fun `prefers the candidate nearer the horizontal centre`() {
        val centred = shutter(size = 180)
        val offToTheSide = ShutterCandidate(bounds(20, 1900, 180), clickable = true)

        val best = ShutterDetector.findBest(
            listOf(offToTheSide, centred),
            screenWidth,
            screenHeight,
        )

        assertNotNull(best)
        assertEquals(1, best!!.index)
    }

    @Test
    fun `a shutter label outranks a plain but better placed button`() {
        // A named shutter wins on the strength of its name alone, even from further
        // out, which is what lets the service press it without reading the screen.
        val plainAndCentred = shutter(description = null)
        val namedAndOffCentre = ShutterCandidate(
            bounds = bounds(120, 1900, 180),
            clickable = true,
            contentDescription = "Shutter",
        )

        val best = ShutterDetector.findBest(
            listOf(plainAndCentred, namedAndOffCentre),
            screenWidth,
            screenHeight,
        )

        assertNotNull(best)
        assertEquals(1, best!!.index)
    }

    @Test
    fun `outside the centre band a candidate gets no credit for its position`() {
        val edge = bounds(0, 1900, 180)

        assertEquals(0f, ShutterDetector.centeringScore(edge.centerX, screenWidth), 0.001f)
        assertEquals(1.5f, ShutterDetector.centeringScore(screenWidth / 2, screenWidth), 0.001f)
    }

    @Test
    fun `a shutter-like view id counts too`() {
        val generic = shutter()
        val named = shutter(viewId = "com.example.camera:id/btn_shutter")

        val best = ShutterDetector.findBest(
            listOf(generic, named),
            screenWidth,
            screenHeight,
        )

        assertEquals(1, best!!.index)
    }

    @Test
    fun `returns nothing when the screen holds no plausible button`() {
        val candidates = listOf(
            ShutterCandidate(bounds(40, 100, 96), clickable = true),
            ShutterCandidate(ScreenBounds(0, 0, 1080, 120), clickable = false),
        )

        assertNull(ShutterDetector.findBest(candidates, screenWidth, screenHeight))
    }

    @Test
    fun `the geometry gate and the scorer agree`() {
        // Whatever isPlausible rejects, score must reject as well.
        val samples = listOf(
            bounds(445, 200, 190),
            bounds(600, 1900, 48),
            ScreenBounds(190, 1900, 890, 2000),
            bounds(150, 1300, 780),
            bounds(20, 1900, 180),
            ScreenBounds(0, 0, 1080, 120),
        )

        samples.forEach { sample ->
            val candidate = ShutterCandidate(sample, clickable = true)
            if (!ShutterDetector.isPlausible(sample, screenWidth, screenHeight)) {
                assertNull(
                    "score accepted ${sample.width}x${sample.height} at y=${sample.centerY}",
                    ShutterDetector.score(candidate, screenWidth, screenHeight),
                )
            }
        }
    }

    @Test
    fun `empty and degenerate bounds are rejected`() {
        assertNull(ShutterDetector.score(ShutterCandidate(ScreenBounds(10, 10, 10, 10), true), screenWidth, screenHeight))
        assertNull(ShutterDetector.score(shutter(), 0, 0))
    }
}
