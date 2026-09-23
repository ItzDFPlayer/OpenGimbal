package com.itzdfplayer.opengimbal.tracking

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher is the risky half of object tracking: it is what decides whether the gimbal
 * keeps following or gives up, and a mistake in it looks like the hardware misbehaving.
 * Everything it does is pure arithmetic over an [IntArray], so all of it can be pinned here.
 *
 * The metric is zero-normalised cross-correlation, so a score is not "how close the pixels
 * are" but "how alike the shapes are": 1 is the same thing under any brightness and contrast,
 * 0 is unrelated. The re-exposure tests below are the ones that pin that down, because it is
 * exactly the difference between this metric and the pixel difference it replaced.
 */
class TemplateTrackerTest {

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 320
        const val SIZE = 48

        /** Where the block sits in the reference frame. */
        const val BLOCK_X = 200
        const val BLOCK_Y = 160

        /** Square, and comfortably more than a patch across so a patch fits inside it. */
        const val BLOCK_SIZE = 96

        /** A point in the middle of the block, which is where a user would tap. */
        const val TAP_X = BLOCK_X + BLOCK_SIZE / 2
        const val TAP_Y = BLOCK_Y + BLOCK_SIZE / 2

        const val BACKGROUND = 128

        /** The block is textured in cells this many pixels across. */
        const val CELL = 4
    }

    /**
     * Two-valued noise, which is what makes the fixtures behave.
     *
     * Flat grey for the background and a hard black-and-white texture for the block means
     * the two can never be confused: a test that expects a match gets a similarity of one,
     * and a test that expects a miss gets about zero. Softer textures would average out
     * to something close to the background and would let a poor matcher pass.
     */
    private fun speckle(count: Int, seed: Long): IntArray {
        val out = IntArray(count)
        var state = seed
        for (i in out.indices) {
            state = state * 6364136223846793005L + 1442695040888963407L
            out[i] = if ((state ushr 40) and 1L == 1L) 255 else 0
        }
        return out
    }

    /** One coarse value per [CELL] square, so the block stays distinctive when it is
     *  averaged down for the search's first pass. */
    private val cells = speckle((BLOCK_SIZE / CELL) * (BLOCK_SIZE / CELL), 0x5EEDL)

    /** A second texture from a different draw: same statistics, no shared structure. That is
     *  what "a different subject" has to look like for the tests that check two textured
     *  things are not taken for each other. */
    private val otherCells = speckle((BLOCK_SIZE / CELL) * (BLOCK_SIZE / CELL), 0xC0FFEEL)

    /**
     * The block's texture, addressed relative to the block so that it travels with it: a
     * frame built with the block somewhere else really is the reference frame shifted,
     * which is the situation the matcher exists for.
     *
     * Deliberately structured at a few pixels' scale rather than pixel by pixel. A block of
     * pixel-level noise averages down to flat grey, which is exactly what the background
     * is, and the search's coarse pass would then have nothing to go on. Real objects have
     * structure at this sort of scale; this is what the promise "good for a rigid, textured
     * subject" means in practice.
     */
    private fun blockValue(x: Int, y: Int): Int {
        val cell = cells[(y / CELL) * (BLOCK_SIZE / CELL) + (x / CELL)]
        val grain = if ((x * 3 + y * 5) % 4 < 2) 0 else 40
        return (cell + grain - 20).coerceIn(0, 255)
    }

    /** The same treatment from the other draw, so the two textures share no pattern at all. */
    private fun otherValue(x: Int, y: Int): Int {
        val cell = otherCells[(y / CELL) * (BLOCK_SIZE / CELL) + (x / CELL)]
        val grain = if ((x * 5 + y * 3) % 4 < 2) 0 else 40
        return (cell + grain - 20).coerceIn(0, 255)
    }

    private fun frameWithBlock(blockX: Int, blockY: Int): Gray =
        frameWithBlocks(listOf(blockX to blockY))

    /** The same block in several places, for the case where the scene is ambiguous. */
    private fun frameWithBlocks(blocks: List<Pair<Int, Int>>): Gray {
        val pixels = IntArray(WIDTH * HEIGHT) { BACKGROUND }
        for ((blockX, blockY) in blocks) {
            for (y in 0 until BLOCK_SIZE) {
                val fy = blockY + y
                if (fy < 0 || fy >= HEIGHT) continue
                for (x in 0 until BLOCK_SIZE) {
                    val fx = blockX + x
                    if (fx < 0 || fx >= WIDTH) continue
                    pixels[fy * WIDTH + fx] = blockValue(x, y)
                }
            }
        }
        return Gray(pixels, WIDTH, HEIGHT)
    }

    /** The same geometry with the other texture, for the tests that need a subject that is
     *  genuinely unrelated to the one being tracked. */
    private fun frameWithOtherBlock(blockX: Int, blockY: Int): Gray {
        val pixels = IntArray(WIDTH * HEIGHT) { BACKGROUND }
        for (y in 0 until BLOCK_SIZE) {
            val fy = blockY + y
            if (fy < 0 || fy >= HEIGHT) continue
            for (x in 0 until BLOCK_SIZE) {
                val fx = blockX + x
                if (fx < 0 || fx >= WIDTH) continue
                pixels[fy * WIDTH + fx] = otherValue(x, y)
            }
        }
        return Gray(pixels, WIDTH, HEIGHT)
    }

    /**
     * The same object rendered at a different size, which is what walking towards or away
     * from the camera does to it.
     *
     * The texture is still addressed relative to the block, just divided by [scale], so this
     * really is the same object larger or smaller and not a second subject that happens to
     * fill more of the screen. Sampling is nearest-neighbour to match what the patch's own
     * resampling does, so the test pins the search's ability to find the right size rather
     * than an interpolator's ability to paper over the difference.
     */
    private fun frameWithScaledBlock(blockX: Int, blockY: Int, scale: Float): Gray {
        val extent = (BLOCK_SIZE * scale).roundToInt()
        val pixels = IntArray(WIDTH * HEIGHT) { BACKGROUND }
        for (y in 0 until extent) {
            val fy = blockY + y
            if (fy < 0 || fy >= HEIGHT) continue
            for (x in 0 until extent) {
                val fx = blockX + x
                if (fx < 0 || fx >= WIDTH) continue
                val sourceX = (x / scale).roundToInt().coerceIn(0, BLOCK_SIZE - 1)
                val sourceY = (y / scale).roundToInt().coerceIn(0, BLOCK_SIZE - 1)
                pixels[fy * WIDTH + fx] = blockValue(sourceX, sourceY)
            }
        }
        return Gray(pixels, WIDTH, HEIGHT)
    }

    /** That object, still centred on the point the user tapped, whatever its size. */
    private fun scaledBlockCentred(scale: Float): Gray {
        val extent = (BLOCK_SIZE * scale).roundToInt()
        return frameWithScaledBlock(TAP_X - extent / 2, TAP_Y - extent / 2, scale)
    }

    /** The same scene seen through a different exposure: a gain and a lift on every pixel,
     *  background and subject alike, which is what auto-exposure does to a frame. */
    private fun reExposed(frame: Gray, gain: Float, lift: Int): Gray {
        val out = IntArray(frame.pixels.size) { i ->
            (frame.pixels[i] * gain + lift).roundToInt().coerceIn(0, 255)
        }
        return Gray(out, frame.width, frame.height)
    }

    /** The same scene with the block gone. */
    private fun frameWithoutBlock(): Gray =
        Gray(IntArray(WIDTH * HEIGHT) { BACKGROUND }, WIDTH, HEIGHT)

    // ---- luminance ---------------------------------------------------------

    @Test
    fun `luminance follows the eye's weighting`() {
        val pixels = intArrayOf(
            0xFF000000.toInt(), // black
            0xFFFFFFFF.toInt(), // white
            0xFFFF0000.toInt(), // red
            0xFF00FF00.toInt(), // green
            0xFF0000FF.toInt(), // blue
        )
        val gray = TemplateTracker.toGray(pixels, 5, 1)

        assertEquals(0, gray.pixels[0])
        assertEquals(255, gray.pixels[1])
        assertEquals(76, gray.pixels[2])
        assertEquals(149, gray.pixels[3])
        assertEquals(28, gray.pixels[4])

        // Green has to dominate and blue has to lag, or the matching would be reacting to
        // colour rather than to how the frame looks.
        assertTrue(gray.pixels[3] > gray.pixels[2])
        assertTrue(gray.pixels[2] > gray.pixels[4])
    }

    // ---- patch size --------------------------------------------------------

    @Test
    fun `patch size is a share of the short edge, within bounds`() {
        assertEquals(48, TemplateTracker.patchSizeFor(480, 320))
        assertEquals(108, TemplateTracker.patchSizeFor(1080, 2400))
        // Never smaller than the minimum, however small the screen.
        assertEquals(48, TemplateTracker.patchSizeFor(100, 200))
        // Never larger than the maximum, however large.
        assertEquals(160, TemplateTracker.patchSizeFor(4000, 4000))
    }

    // ---- cutting a patch ---------------------------------------------------

    @Test
    fun `a patch is refused when it would run off the screen`() {
        val frame = frameWithBlock(BLOCK_X, BLOCK_Y)

        assertNull("left edge", TemplateTracker.patchAt(frame, 10, 160, SIZE))
        assertNull("top edge", TemplateTracker.patchAt(frame, 200, 10, SIZE))
        assertNull("right edge", TemplateTracker.patchAt(frame, WIDTH - 10, 160, SIZE))
        assertNull("bottom edge", TemplateTracker.patchAt(frame, 200, HEIGHT - 10, SIZE))

        assertNotNull(TemplateTracker.patchAt(frame, TAP_X, TAP_Y, SIZE))
    }

    @Test
    fun `a patch is the block of pixels centred on the point`() {
        val frame = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(frame, TAP_X, TAP_Y, SIZE))

        val left = TAP_X - SIZE / 2
        val top = TAP_Y - SIZE / 2

        assertEquals(SIZE * SIZE, patch.count)
        assertEquals(blockValue(left - BLOCK_X, top - BLOCK_Y), patch.pixels[0])
        assertEquals(
            blockValue(left - BLOCK_X + SIZE - 1, top - BLOCK_Y + SIZE - 1),
            patch.pixels[SIZE * SIZE - 1],
        )
    }

    // ---- downscaling -------------------------------------------------------

    @Test
    fun `downscaling averages each block`() {
        val frame = Gray(intArrayOf(0, 100, 200, 250), 2, 2)
        val small = frame.downscaled(2)

        assertEquals(1, small.width)
        assertEquals(1, small.height)
        assertEquals(137, small.pixels[0])
    }

    // ---- finding it again --------------------------------------------------

    @Test
    fun `a patch is found where it still is`() {
        val frame = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(frame, TAP_X, TAP_Y, SIZE))

        val found = requireNotNull(TemplateTracker.find(frame, patch, TAP_X, TAP_Y, radius = 20))

        assertEquals(TAP_X, found.centerX)
        assertEquals(TAP_Y, found.centerY)
        assertEquals("the patch is the frame, pixel for pixel", 1f, found.similarity, 0.001f)
        assertEquals("a patch that has not changed size must say so", 1f, found.scale, 0.001f)
    }

    @Test
    fun `a patch is found after it has moved`() {
        val reference = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(reference, TAP_X, TAP_Y, SIZE))

        // The scene shifted by a few pixels, so the block is somewhere else in this frame.
        val dx = 7
        val dy = -5
        val moved = frameWithBlock(BLOCK_X + dx, BLOCK_Y + dy)

        val found = requireNotNull(TemplateTracker.find(moved, patch, TAP_X, TAP_Y, radius = 24))

        assertEquals(TAP_X + dx, found.centerX)
        assertEquals(TAP_Y + dy, found.centerY)
        assertEquals(1f, found.similarity, 0.0001f)
    }

    @Test
    fun `a moved patch is found even when the search starts far from it`() {
        val reference = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(reference, TAP_X, TAP_Y, SIZE))

        // Multiples of the search's coarse step, because that is the precision the first
        // pass works to: anything else would be testing the coarse stage's ability to
        // interpolate, which it does not claim to have.
        val moved = frameWithBlock(BLOCK_X + 20, BLOCK_Y + 16)
        val found = requireNotNull(TemplateTracker.find(moved, patch, TAP_X, TAP_Y, radius = 40))

        assertEquals(TAP_X + 20, found.centerX)
        assertEquals(TAP_Y + 16, found.centerY)
        assertEquals(1f, found.similarity, 0.0001f)
    }

    // ---- surviving a change of exposure ------------------------------------

    /**
     * The test the whole metric exists for.
     *
     * The object has not moved and has not changed shape; only the camera's exposure has
     * changed, which happens continuously and for reasons that have nothing to do with the
     * object. A plain pixel difference reads that as the object having been replaced: every
     * pixel of the patch is now tens of levels away from where it was, the score collapses,
     * and the tracker reports the object lost while it sits perfectly still in the middle of
     * the frame. That failure is why the metric was changed. Correlation removes the mean and
     * divides by the spread on both sides, so a gain and a lift applied to the whole frame
     * cancel out and the score stays at one.
     */
    @Test
    fun `a patch is still found when the whole picture is re-exposed`() {
        val reference = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(reference, TAP_X, TAP_Y, SIZE))

        // (v * 0.55 + 70) stays inside 0..255 for every value in the fixture, so this really
        // is the same object, only lit differently.
        val brighter = reExposed(reference, gain = 0.55f, lift = 70)
        val found = requireNotNull(TemplateTracker.find(brighter, patch, TAP_X, TAP_Y, radius = 24))

        assertEquals(TAP_X, found.centerX)
        assertEquals(TAP_Y, found.centerY)
        assertTrue(
            "a re-exposed picture is the same object, not a different one: ${found.similarity}",
            found.similarity > 0.95f,
        )
    }

    @Test
    fun `a patch is still found when the picture is darkened instead`() {
        val reference = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(reference, TAP_X, TAP_Y, SIZE))

        // The other direction, because coping with only one of them would be no use: the
        // camera goes both ways as the user turns it towards and away from a window.
        val darker = reExposed(reference, gain = 0.4f, lift = 20)
        val found = requireNotNull(TemplateTracker.find(darker, patch, TAP_X, TAP_Y, radius = 24))

        assertEquals(TAP_X, found.centerX)
        assertEquals(TAP_Y, found.centerY)
        assertTrue(
            "a darker picture is the same object, not a different one: ${found.similarity}",
            found.similarity > 0.95f,
        )
    }

    // ---- objects that change size ------------------------------------------

    @Test
    fun `a patch is found when the object has grown`() {
        val reference = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(reference, TAP_X, TAP_Y, SIZE))

        // The same object a quarter bigger, centred where it was. The patch that described it
        // is now the wrong size, which is what an object walking towards the camera does to
        // it, so the search has to try the neighbouring sizes and report which one won.
        val grown = scaledBlockCentred(1.25f)
        val found = requireNotNull(TemplateTracker.find(grown, patch, TAP_X, TAP_Y, radius = 40))

        assertEquals(
            "the object is larger than the patch, so the larger template is the right one",
            1.25f,
            found.scale,
            0.01f,
        )
        assertTrue(
            "the centre must stay where it was: ${found.centerX}, ${found.centerY}",
            abs(found.centerX - TAP_X) <= 2 && abs(found.centerY - TAP_Y) <= 2,
        )
        assertTrue(
            "the template that won must be the object: ${found.similarity}",
            found.similarity > 0.9f,
        )
    }

    @Test
    fun `a patch is found when the object has shrunk`() {
        val reference = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(reference, TAP_X, TAP_Y, SIZE))

        val shrunk = scaledBlockCentred(0.8f)
        val found = requireNotNull(TemplateTracker.find(shrunk, patch, TAP_X, TAP_Y, radius = 40))

        assertEquals(
            "the object is smaller than the patch, so the smaller template is the right one",
            0.8f,
            found.scale,
            0.01f,
        )
        assertTrue(
            "the centre must stay where it was: ${found.centerX}, ${found.centerY}",
            abs(found.centerX - TAP_X) <= 2 && abs(found.centerY - TAP_Y) <= 2,
        )
        assertTrue(
            "the template that won must be the object: ${found.similarity}",
            found.similarity > 0.9f,
        )
    }

    @Test
    fun `a patch with no detail is refused`() {
        // A patch off a flat wall, or the sky: it resembles every other flat place equally
        // well, so no position is the answer. Refusing it here is what becomes the actionable
        // "pick something with texture" message in the UI.
        val flat = frameWithoutBlock()
        val patch = requireNotNull(TemplateTracker.patchAt(flat, TAP_X, TAP_Y, SIZE))

        assertFalse("a flat patch has no detail to match on", patch.hasDetail)
        assertNull(TemplateTracker.find(flat, patch, TAP_X, TAP_Y, radius = 24))
    }

    @Test
    fun `a patch that is no longer in the frame is not reported at all`() {
        val reference = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(reference, TAP_X, TAP_Y, SIZE))

        // The block is gone and only the flat background is left. Every position in the
        // window scores the same, so the winner is arbitrary - which is precisely the case
        // that must not be reported, because acting on it steers the gimbal at nothing.
        val found = TemplateTracker.find(frameWithoutBlock(), patch, TAP_X, TAP_Y, radius = 24)
        assertNull("a frame with nothing recognisable in it must not produce a match", found)
    }

    @Test
    fun `a scene with two identical subjects is not reported as a match`() {
        // The same block twice, both inside the search window with equal scores. There is no
        // way to tell which is the object, so neither is followed.
        //
        // The separation is a multiple of the search's coarse step on purpose. The first pass
        // looks at every other coarse pixel, so a subject landing between two of those
        // positions is only ever seen a few pixels off, scores lower for that reason alone,
        // and the test would pass on a search artefact rather than because the ambiguity was
        // recognised. 96 pixels puts the second subject exactly on the grid.
        val twice = frameWithBlocks(listOf(BLOCK_X to BLOCK_Y, BLOCK_X + 96 to BLOCK_Y))
        val patch = requireNotNull(
            TemplateTracker.patchAt(frameWithBlock(BLOCK_X, BLOCK_Y), TAP_X, TAP_Y, SIZE)
        )

        val found = TemplateTracker.find(twice, patch, TAP_X, TAP_Y, radius = 120)
        assertNull("two equally good answers mean the match is a coin toss", found)
    }

    // ---- what the match was worth, for reporting ---------------------------

    @Test
    fun `a frame the object is in reports a usable score`() {
        val frame = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(frame, TAP_X, TAP_Y, SIZE))

        requireNotNull(TemplateTracker.find(frame, patch, TAP_X, TAP_Y, radius = 24))

        assertTrue(
            "a frame the object is in must report a score worth acting on",
            TemplateTracker.lastScore >= TemplateTracker.MIN_SIMILARITY,
        )
    }

    @Test
    fun `a frame the object has left reports a poor score`() {
        val frame = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(frame, TAP_X, TAP_Y, SIZE))
        // A good match first, so that a stale number would be a good one.
        requireNotNull(TemplateTracker.find(frame, patch, TAP_X, TAP_Y, radius = 24))

        assertNull(TemplateTracker.find(frameWithoutBlock(), patch, TAP_X, TAP_Y, radius = 24))
        assertTrue(
            "a frame with nothing in it must report a poor score, not the last good one",
            TemplateTracker.lastScore < TemplateTracker.MIN_SIMILARITY,
        )
    }

    @Test
    fun `a merely ambiguous frame reports a good score`() {
        val twice = frameWithBlocks(listOf(BLOCK_X to BLOCK_Y, BLOCK_X + 96 to BLOCK_Y))
        val patch = requireNotNull(
            TemplateTracker.patchAt(frameWithBlock(BLOCK_X, BLOCK_Y), TAP_X, TAP_Y, SIZE)
        )

        assertNull(TemplateTracker.find(twice, patch, TAP_X, TAP_Y, radius = 120))

        // The matcher gives the same answer - nothing - as it does for a frame the object has
        // left, and telling those two apart is the whole reason this number is published. One
        // means the scene has changed completely; the other means the object is right there,
        // twice, and the search cannot choose. A readout stuck on 0% cannot say either.
        assertTrue(
            "an ambiguous frame still resembles the object closely",
            TemplateTracker.lastScore >= TemplateTracker.MIN_SIMILARITY,
        )
    }

    @Test
    fun `a match that jumped further than the object could have is refused`() {
        val reference = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(reference, TAP_X, TAP_Y, SIZE))

        // Moved by 30 pixels while only 20 pixels around the last position are searched, so
        // the best the window contains is a place that cannot be the object.
        val moved = frameWithBlock(BLOCK_X + 30, BLOCK_Y)
        val found = TemplateTracker.find(moved, patch, TAP_X, TAP_Y, radius = 20)

        assertNull("a jump this large is a wrong answer, not a fast object", found)
    }

    // ---- resampling a patch ------------------------------------------------

    @Test
    fun `resampling keeps a constant patch constant`() {
        val constant = Patch(IntArray(SIZE * SIZE) { 90 }, SIZE, SIZE)

        // A patch with no variation has nothing to keep in proportion, so every pixel of the
        // result must still be what went in. Anything else would be the resampler inventing
        // an edge out of the patch's own border.
        assertFalse("a constant patch is flat by construction", constant.hasDetail)
        for (value in constant.resampled(0.8f).pixels) {
            assertEquals("every pixel of a constant patch stays 90", 90, value)
        }
        for (value in constant.resampled(1.25f).pixels) {
            assertEquals("every pixel of a constant patch stays 90", 90, value)
        }
    }

    @Test
    fun `resampling changes the size and never goes below the minimum`() {
        val patch = Patch(IntArray(SIZE * SIZE) { it % 200 }, SIZE, SIZE)

        assertEquals(SIZE, patch.resampled(1f).width)
        assertEquals(38, patch.resampled(0.8f).width)
        assertEquals(38, patch.resampled(0.8f).height)
        assertEquals(60, patch.resampled(1.25f).width)
        assertEquals(60, patch.resampled(1.25f).height)

        // However small the factor, the result keeps enough pixels to hold structure: a
        // template a couple of pixels a side would match everything and mean nothing.
        assertEquals(8, patch.resampled(0.05f).width)
        assertEquals(8, patch.resampled(0.05f).height)
    }

    @Test
    fun `resampling to an exact size lands on that size, always`() {
        val patch = Patch(IntArray(SIZE * SIZE) { it % 200 }, SIZE, SIZE)

        // The point of this one, as against resampled, is that the size is exact rather than
        // approximate. The caller blends the result with a patch of a known size, and a blend
        // between two differently shaped arrays is refused - silently, on the frames where the
        // object was growing or shrinking, which are the frames that needed the refresh.
        for (size in listOf(8, 24, 37, SIZE, 61, 97)) {
            val resized = patch.resampledTo(size, size)
            assertEquals("width at $size", size, resized.width)
            assertEquals("height at $size", size, resized.height)
            assertEquals("pixels at $size", size * size, resized.pixels.size)
        }
    }

    @Test
    fun `resampling to the same size leaves the patch exactly alone`() {
        val patch = Patch(IntArray(SIZE * SIZE) { (it * 7) % 256 }, SIZE, SIZE)

        // The identity case is the one that runs on almost every frame - an object at a steady
        // distance - so any drift here would be a small error added to the template again and
        // again, which is a template sliding off an object that is standing still.
        assertTrue(patch.resampledTo(SIZE, SIZE).pixels.contentEquals(patch.pixels))
    }

    @Test
    fun `resampling to a size keeps a constant patch constant`() {
        val constant = Patch(IntArray(SIZE * SIZE) { 90 }, SIZE, SIZE)

        // Whatever the shape, a patch with no variation in it must not acquire one: an edge
        // invented at the border would be the resampler matching its own invention.
        for (value in constant.resampledTo(30, 30).pixels) {
            assertEquals(90, value)
        }
    }

    // ---- refreshing the template -------------------------------------------

    @Test
    fun `blending moves a patch a quarter of the way towards the source and no further`() {
        val frame = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(frame, TAP_X, TAP_Y, SIZE))
        val before = patch.pixels.copyOf()
        val source = requireNotNull(
            TemplateTracker.patchAt(frameWithOtherBlock(BLOCK_X, BLOCK_Y), TAP_X, TAP_Y, SIZE)
        )

        patch.blendWith(source, 0.25f)

        for (i in before.indices) {
            val expected = (before[i] * 0.75f + source.pixels[i] * 0.25f).roundToInt()
            assertEquals("pixel $i", expected, patch.pixels[i])
        }

        // A quarter of the way there and no further. The template has to be refreshed or it
        // goes stale as the light and the angle change, but every refresh is a guess, and a
        // template that let one frame take it over would be dragged off the object for good -
        // which is the slide the fallback template exists to catch.
        val changed = before.indices.first { before[it] != source.pixels[it] }
        assertTrue("the template must move towards the source", patch.pixels[changed] != before[changed])
        assertTrue("the template must not become the source", patch.pixels[changed] != source.pixels[changed])
    }

    @Test
    fun `blending does nothing when the source is a different size`() {
        val frame = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(frame, TAP_X, TAP_Y, SIZE))
        val before = patch.pixels.copyOf()

        // A patch of another size is a different measurement of a different thing, so there
        // is no pixel-by-pixel correspondence to mix. Doing nothing is the only answer that
        // cannot corrupt the template.
        patch.blendWith(Patch(IntArray(24 * 24) { 255 }, 24, 24), 0.5f)
        assertTrue("a mismatched source must leave the template alone", before.contentEquals(patch.pixels))
    }

    // ---- falling back to the patch the user picked -------------------------

    @Test
    fun `a template that has drifted is rescued by the original as a fallback`() {
        val reference = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(reference, TAP_X, TAP_Y, SIZE))
        val original = patch.copy()

        // The working template refreshed once too often and ended up describing something
        // that was never the object - the slow slide adaptive trackers are prone to, where
        // each update is a reasonable guess that is never revisited. Blending it all the way
        // with an unrelated texture is the extreme of that, and it leaves the tracker with no
        // idea what it is looking for.
        val unrelated = requireNotNull(
            TemplateTracker.patchAt(frameWithOtherBlock(BLOCK_X, BLOCK_Y), TAP_X, TAP_Y, SIZE)
        )
        patch.blendWith(unrelated, 1f)

        // The object itself never moved, so a frame that still holds it is exactly the case
        // the fallback is for.
        val frame = frameWithBlock(BLOCK_X, BLOCK_Y)
        val alone = TemplateTracker.find(frame, patch, TAP_X, TAP_Y, radius = 24)
        assertTrue(
            "a template that no longer describes the object must not track (got $alone)",
            alone == null || alone.similarity < TemplateTracker.MIN_SIMILARITY,
        )

        val rescued = requireNotNull(
            TemplateTracker.find(
                frame = frame,
                patch = patch,
                centerX = TAP_X,
                centerY = TAP_Y,
                radius = 24,
                fallbacks = listOf(original),
            )
        )
        assertEquals(TAP_X, rescued.centerX)
        assertEquals(TAP_Y, rescued.centerY)
        assertTrue(
            "the patch as it was picked is the one thing known to be right: ${rescued.similarity}",
            rescued.similarity > 0.95f,
        )
    }

    // ---- the score itself --------------------------------------------------

    @Test
    fun `correlation is one for a patch against itself`() {
        val frame = frameWithBlock(BLOCK_X, BLOCK_Y)
        val patch = requireNotNull(TemplateTracker.patchAt(frame, TAP_X, TAP_Y, SIZE))

        val correlation =
            TemplateTracker.correlationAt(frame, patch, TAP_X - SIZE / 2, TAP_Y - SIZE / 2)
        assertEquals("a patch against its own pixels is the same thing", 1f, correlation, 0.0001f)
    }

    @Test
    fun `correlation ignores a constant added to every pixel of the frame`() {
        val reference = frameWithBlock(BLOCK_X, BLOCK_Y)

        // Squeezed into the middle of the range first, so that the constant added afterwards
        // cannot clip anything and the test measures the invariance rather than the clipping.
        val midRange = reExposed(reference, gain = 0.5f, lift = 60)
        val patch = requireNotNull(TemplateTracker.patchAt(midRange, TAP_X, TAP_Y, SIZE))
        val brighter = reExposed(midRange, gain = 1f, lift = 60)

        val correlation = TemplateTracker.correlationAt(
            brighter,
            patch,
            TAP_X - SIZE / 2,
            TAP_Y - SIZE / 2,
        )
        assertEquals("lifting every pixel together changes nothing", 1f, correlation, 0.0001f)
    }

    @Test
    fun `correlation is near zero for unrelated texture`() {
        val patch = requireNotNull(
            TemplateTracker.patchAt(frameWithBlock(BLOCK_X, BLOCK_Y), TAP_X, TAP_Y, SIZE)
        )

        // Same statistics, same cell size, drawn from a different seed: two textured things
        // sharing no structure. A raw difference cannot separate this from a match, and that
        // is why the score has to mean something on its own rather than needing a threshold
        // set by hand.
        val unrelated = frameWithOtherBlock(BLOCK_X, BLOCK_Y)
        val correlation = TemplateTracker.correlationAt(
            unrelated,
            patch,
            TAP_X - SIZE / 2,
            TAP_Y - SIZE / 2,
        )
        assertTrue(
            "unrelated textures must not correlate (got $correlation)",
            abs(correlation) < 0.3f,
        )
    }

    // ---- limits ------------------------------------------------------------

    @Test
    fun `a frame smaller than the patch is refused`() {
        val patch = requireNotNull(
            TemplateTracker.patchAt(frameWithBlock(BLOCK_X, BLOCK_Y), TAP_X, TAP_Y, SIZE)
        )

        val tiny = Gray(IntArray(10 * 10) { 0 }, 10, 10)
        assertNull(TemplateTracker.find(tiny, patch, 5, 5, radius = 4))
    }
}
