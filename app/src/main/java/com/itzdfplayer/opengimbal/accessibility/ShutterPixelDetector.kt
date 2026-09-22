package com.itzdfplayer.opengimbal.accessibility

import kotlin.math.max
import kotlin.math.min

import androidx.annotation.StringRes
import com.itzdfplayer.opengimbal.R

/** A shutter found in a screenshot, in screen pixel coordinates. */
data class PixelShutter(
    val centerX: Int,
    val centerY: Int,
    val width: Int,
    val height: Int,
    /** Share of the bounding box that is actually part of the blob. A disc is ~0.79, a ring ~0.16. */
    val fill: Float,
    /** Share of the blob that is nearly white, and that is clearly red. */
    val whiteness: Float,
    val redness: Float,
    val score: Float,
) {
    /** A readable name for the colour, for the report in Settings. */
    @get:StringRes
    val colourRes: Int
        get() = when {
            whiteness >= 0.55f -> R.string.shutter_colour_white
            redness >= 0.55f -> R.string.shutter_colour_red
            fill < 0.3f -> R.string.shutter_colour_ring
            else -> R.string.shutter_colour_coloured
        }
}

/**
 * Finds the shutter in a screenshot.
 *
 * This exists because not every camera app puts the shutter in its accessibility
 * tree - some draw the whole UI on a surface, and then there is nothing to query.
 * Here the pixels are inspected instead, which also means colour is available, so
 * "the big white or red circle at the bottom" can be taken almost literally.
 *
 * A blob qualifies when it is a single connected region that
 *
 *  * stands out from the preview behind it - very light, or strongly saturated
 *  * sits in the lower half, near the horizontal centre
 *  * has the square bounding box and the size of a shutter
 *
 * and it is then ranked with the same terms as the accessibility-tree candidate, so
 * a filled white disc, a red disc and a hollow ring all score as a shutter should.
 * A warm colour bonus is added for white and red.
 *
 * Deliberately not a colour *filter*: two of the screenshots used to develop this
 * have a blue shutter, and treating a hue as a requirement would miss them.
 *
 * All coordinates are in [argb] pixel space, which is the screen's own resolution.
 */
object ShutterPixelDetector {

    /** Below this, we assume there is no shutter on screen. */
    const val MIN_SCORE = 0.6f

    /** A ring is thin, so the floor is low; this only rejects speckle. */
    const val MIN_FILL = 0.05f

    /** One channel this light, on its own, counts as "stands out". */
    private const val LIGHT_CHANNEL = 190

    /** ...or this much spread between the channels, once bright enough to matter. */
    private const val SPREAD = 80
    private const val SPREAD_MIN_BRIGHTNESS = 120

    /** A pixel counted as white/red when measuring the blob's colour. */
    private const val NEARLY_WHITE = 150
    private const val RED_DOMINANCE = 40

    /**
     * @param argb row-major ARGB_8888, as produced by `Bitmap.getPixels`, at least
     *   `width * height` long.
     */
    fun find(argb: IntArray, width: Int, height: Int): PixelShutter? {
        if (width <= 0 || height <= 0) return null
        if (argb.size < width * height) return null

        val firstRow = (height * ShutterDetector.SEARCH_TOP_FRACTION).toInt()
        if (firstRow >= height) return null

        // 1. A mask of everything that could be part of a shutter.
        val mask = BooleanArray(width * height)
        var at = firstRow * width
        while (at < mask.size) {
            mask[at] = standsOut(argb[at])
            at++
        }

        // 2. Score every connected region. The mask doubles as the visited set, so
        //    each pixel is touched a constant number of times.
        var best: PixelShutter? = null
        var stack = IntArray(1 shl 12)
        var stackSize = 0
        var start = firstRow * width

        while (start < mask.size) {
            if (!mask[start]) {
                start++
                continue
            }

            mask[start] = false
            stack[0] = start
            stackSize = 1

            var minX = width
            var maxX = -1
            var minY = height
            var maxY = -1
            var area = 0
            var white = 0
            var red = 0

            while (stackSize > 0) {
                val cursor = stack[--stackSize]
                val x = cursor % width
                val y = cursor / width

                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                val pixel = argb[cursor]
                if (isWhite(pixel)) white++
                if (isRed(pixel)) red++

                if (x > 0 && mask[cursor - 1]) {
                    mask[cursor - 1] = false
                    if (stackSize == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[stackSize++] = cursor - 1
                }
                if (x < width - 1 && mask[cursor + 1]) {
                    mask[cursor + 1] = false
                    if (stackSize == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[stackSize++] = cursor + 1
                }
                if (y > 0 && mask[cursor - width]) {
                    mask[cursor - width] = false
                    if (stackSize == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[stackSize++] = cursor - width
                }
                if (y < height - 1 && mask[cursor + width]) {
                    mask[cursor + width] = false
                    if (stackSize == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[stackSize++] = cursor + width
                }
            }

            val bounds = ScreenBounds(minX, minY, maxX + 1, maxY + 1)
            if (!ShutterDetector.isPlausible(bounds, width, height)) continue

            val fill = area.toFloat() / (bounds.width * bounds.height)
            if (fill < MIN_FILL) continue

            val centering = ShutterDetector.centeringScore(bounds.centerX, width)
            // Outside the centre band there is no credit at all, which is what keeps
            // bright patches of the preview from being mistaken for a shutter.
            if (centering <= 0f) continue

            var score = centering
            score += 0.5f * ShutterDetector.shapeScore(bounds)
            score += 0.5f * ShutterDetector.sizeScore(bounds, width)
            score += 0.5f * min(fill, 1f)
            val whiteness = white.toFloat() / area
            val redness = red.toFloat() / area
            if (whiteness >= 0.55f) score += 0.6f
            if (redness >= 0.55f) score += 0.6f

            if (score < MIN_SCORE) continue
            if (best != null && score <= best.score) continue

            best = PixelShutter(
                centerX = bounds.centerX,
                centerY = bounds.centerY,
                width = bounds.width,
                height = bounds.height,
                fill = fill,
                whiteness = whiteness,
                redness = redness,
                score = score,
            )
        }

        return best
    }

    /** True when a pixel is too light or too colourful to be the scene behind the UI. */
    private fun standsOut(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        if (low > LIGHT_CHANNEL) return true
        return high - low > SPREAD && high > SPREAD_MIN_BRIGHTNESS
    }

    private fun isWhite(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return min(r, min(g, b)) > NEARLY_WHITE
    }

    private fun isRed(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return r - max(g, b) > RED_DOMINANCE
    }
}
