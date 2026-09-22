package com.itzdfplayer.opengimbal

import androidx.annotation.StringRes
import com.itzdfplayer.opengimbal.accessibility.PixelShutter
import com.itzdfplayer.opengimbal.accessibility.ShutterDetector
import com.itzdfplayer.opengimbal.accessibility.ShutterPixelDetector
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pixel shutter finder against the six real camera screenshots in
 * `src/test/resources/screenshots`, plus synthetic images for the rejections.
 *
 * The screenshots were scaled to 300px wide and quantised to 48 colours so they can
 * ship with the tests. Every threshold in the detector is relative to the image
 * width, so the results match the full-resolution originals.
 */
class ShutterPixelDetectorTest {

    /** One real screenshot and where its shutter actually is. */
    private data class Case(
        val file: String,
        val note: String,
        val centerX: Int,
        val centerY: Int,
        @param:StringRes val colourRes: Int,
    )

    private val cases = listOf(
        Case("miui-video-red", "Mi 11 Ultra, video mode: a red disc", 150, 585, R.string.shutter_colour_red),
        Case("miui-photo-white", "Mi 11 Ultra, photo mode: a white disc", 150, 590, R.string.shutter_colour_white),
        Case("miui-recording-ring", "Mi 11 Ultra, recording: a hollow white ring", 150, 590, R.string.shutter_colour_white),
        Case("unspektra-white", "Unspektrawesome: a white disc", 150, 609, R.string.shutter_colour_white),
        Case("vwfndr-blue", "VWFNDR: a blue disc, so neither white nor red", 150, 634, R.string.shutter_colour_coloured),
        Case("vwfndr-blue-big", "VWFNDR, larger UI: a blue disc off the centre", 180, 595, R.string.shutter_colour_coloured),
    )

    // ---- the real screenshots ---------------------------------------------

    @Test
    fun `finds the shutter in every real camera screenshot`() {
        cases.forEach { case ->
            val image = load(case.file)
            val found = ShutterPixelDetector.find(image.pixels, image.width, image.height)

            assertNotNull("no shutter found in ${case.file} (${case.note})", found)
            found!!

            val offBy = abs(found.centerX - case.centerX) + abs(found.centerY - case.centerY)
            assertTrue(
                "picked ${found.centerX},${found.centerY} in ${case.file} (${case.note}), " +
                    "expected ${case.centerX},${case.centerY}",
                offBy <= 2,
            )
        }
    }

    @Test
    fun `reports the colour of the shutters it finds`() {
        cases.forEach { case ->
            val image = load(case.file)
            val found = ShutterPixelDetector.find(image.pixels, image.width, image.height)
            assertEquals("colour in ${case.file}", case.colourRes, found?.colourRes)
        }
    }

    @Test
    fun `the blue shutter is found even though it is neither white nor red`() {
        // The point of staying colour-agnostic: shape and position carry the decision,
        // colour only breaks ties.
        val found = findIn("vwfndr-blue")

        assertTrue("redness ${found.redness} should be low", found.redness < 0.55f)
        assertTrue("whiteness ${found.whiteness} should be low", found.whiteness < 0.55f)
        assertTrue(found.score >= ShutterPixelDetector.MIN_SCORE)
    }

    @Test
    fun `a hollow ring is accepted as a shutter`() {
        val ring = findIn("miui-recording-ring")
        val disc = findIn("miui-photo-white")

        assertTrue("a ring's box is mostly empty, fill=${ring.fill}", ring.fill < 0.3f)
        assertTrue("a disc is mostly full, fill=${disc.fill}", disc.fill > 0.7f)
        assertTrue("a filled disc should outrank a hollow ring", disc.score > ring.score)
    }

    @Test
    fun `every real shutter sits inside the centre band`() {
        val tolerance = width * ShutterDetector.CENTRE_TOLERANCE_FRACTION
        cases.forEach { case ->
            val image = load(case.file)
            val found = ShutterPixelDetector.find(image.pixels, image.width, image.height)
            assertNotNull(found)
            val offset = abs(found!!.centerX - image.width / 2f)
            assertTrue("${case.file} is $offset px from the middle", offset < tolerance)
        }
    }

    // ---- synthetic images -------------------------------------------------

    private fun blank(): IntArray = IntArray(width * height) { DARK }

    private fun IntArray.disc(centerX: Int, centerY: Int, radius: Int, colour: Int) {
        for (y in (centerY - radius)..(centerY + radius)) {
            if (y !in 0 until height) continue
            for (x in (centerX - radius)..(centerX + radius)) {
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy <= radius * radius) this[y * width + x] = colour
            }
        }
    }

    private fun IntArray.findShutter() = ShutterPixelDetector.find(this, width, height)

    @Test
    fun `a shutter above the bottom half is ignored`() {
        assertNull(blank().also { it.disc(150, 200, 25, WHITE) }.findShutter())
    }

    @Test
    fun `a shutter-sized blob away from the centre is ignored`() {
        // This gate is what keeps a bright patch of the scene from being mistaken for a
        // button: 0.4 of the width off centre is well outside the band.
        assertNull(blank().also { it.disc(30, 560, 25, WHITE) }.findShutter())
    }

    @Test
    fun `an icon and an oversized blob are both ignored`() {
        assertNull("a 21px icon", blank().also { it.disc(150, 560, 10, WHITE) }.findShutter())
        assertNull("a 161px blob", blank().also { it.disc(150, 560, 80, WHITE) }.findShutter())
    }

    @Test
    fun `a screen with no bright blob yields nothing`() {
        assertNull(blank().findShutter())
    }

    @Test
    fun `a uniformly bright screen yields nothing`() {
        // The whole lower half becomes one blob as wide as the screen, which is not a
        // shutter - and is what a white settings page looks like.
        assertNull(IntArray(width * height) { WHITE }.findShutter())
    }

    @Test
    fun `a centred disc wins over a larger off-centre one`() {
        val image = blank()
        image.disc(150, 560, 20, WHITE)   // centred, smaller
        image.disc(225, 560, 27, WHITE)   // to the side, larger

        val found = image.findShutter()

        assertNotNull(found)
        assertEquals(150, found!!.centerX)
    }

    @Test
    fun `a red disc outscores an otherwise identical blue one`() {
        val blue = blank().also { it.disc(150, 560, 20, BLUE) }.findShutter()
        val red = blank().also { it.disc(150, 560, 20, RED) }.findShutter()

        assertNotNull(blue)
        assertNotNull(red)
        assertEquals("a blue disc is neither white nor red", R.string.shutter_colour_coloured, blue!!.colourRes)
        assertEquals("a red disc reads as red", R.string.shutter_colour_red, red!!.colourRes)
        assertTrue("the colour preference should favour red", red.score > blue.score)
    }

    @Test
    fun `degenerate arguments are handled`() {
        assertNull(ShutterPixelDetector.find(IntArray(0), 0, 0))
        assertNull(ShutterPixelDetector.find(IntArray(10), 100, 100))
        assertNull(ShutterPixelDetector.find(IntArray(100), -1, 100))
    }

    // ---- helpers ----------------------------------------------------------

    private class LoadedImage(val pixels: IntArray, val width: Int, val height: Int)

    private fun load(name: String): LoadedImage {
        val stream = javaClass.getResourceAsStream("/screenshots/$name.png")
            ?: error("missing test resource /screenshots/$name.png")
        val image: BufferedImage = stream.use { ImageIO.read(it) }
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        return LoadedImage(pixels, image.width, image.height)
    }

    private fun findIn(name: String): PixelShutter {
        val image = load(name)
        return ShutterPixelDetector.find(image.pixels, image.width, image.height)
            ?: error("no shutter found in $name")
    }

    private companion object {
        const val width = 300
        const val height = 668

        const val DARK = 0xFF101010.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val RED = 0xFFE02820.toInt()
        const val BLUE = 0xFF2060FF.toInt()
    }
}
