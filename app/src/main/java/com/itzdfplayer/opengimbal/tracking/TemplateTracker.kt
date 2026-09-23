package com.itzdfplayer.opengimbal.tracking

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An 8-bit luminance image, which is all the tracker needs of a screen frame. */
class Gray(val pixels: IntArray, val width: Int, val height: Int) {

    init {
        require(pixels.size >= width * height) { "buffer is smaller than the image" }
    }

    /**
     * A copy averaged down by [factor]. Used for the first pass of the search, where being
     * four times smaller makes it sixteen times cheaper and costs almost nothing in
     * accuracy - the fine pass puts the position back to the pixel.
     */
    fun downscaled(factor: Int): Gray {
        val outWidth = max(1, width / factor)
        val outHeight = max(1, height / factor)
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val sourceRow = y * factor
            for (x in 0 until outWidth) {
                val sourceColumn = x * factor
                var total = 0
                var count = 0
                for (dy in 0 until factor) {
                    val sy = sourceRow + dy
                    if (sy >= height) break
                    val rowStart = sy * width
                    for (dx in 0 until factor) {
                        val sx = sourceColumn + dx
                        if (sx >= width) break
                        total += pixels[rowStart + sx]
                        count++
                    }
                }
                out[y * outWidth + x] = if (count == 0) 0 else total / count
            }
        }
        return Gray(out, outWidth, outHeight)
    }
}

/**
 * The piece of screen the user selected, kept as luminance.
 *
 * Carries its own mean and variance, computed once, because the matching needs them at every
 * position it tries and recomputing them there would cost as much again as the matching.
 */
class Patch(val pixels: IntArray, val width: Int, val height: Int) {

    val count: Int get() = width * height

    /** Mean luminance across the patch. */
    val mean: Double

    /** Variance of the patch's luminance, against that mean. */
    val variance: Double

    init {
        var total = 0L
        for (value in pixels) total += value
        val average = total.toDouble() / count
        var spread = 0.0
        for (value in pixels) {
            val difference = value - average
            spread += difference * difference
        }
        mean = average
        variance = spread / count
    }

    /** How much the patch varies at all. A patch with none cannot be tracked. */
    val hasDetail: Boolean get() = variance > MIN_VARIANCE

    /**
     * A copy averaged down by [factor], so the search can start on a smaller version of both
     * the frame and the patch and stay affordable.
     */
    fun downscaled(factor: Int): Patch {
        val outWidth = max(1, width / factor)
        val outHeight = max(1, height / factor)
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                var total = 0
                var count = 0
                var dy = 0
                while (dy < factor) {
                    val sy = y * factor + dy
                    if (sy >= height) break
                    var dx = 0
                    while (dx < factor) {
                        val sx = x * factor + dx
                        if (sx >= width) break
                        total += pixels[sy * width + sx]
                        count++
                        dx++
                    }
                    dy++
                }
                out[y * outWidth + x] = if (count == 0) 0 else total / count
            }
        }
        return Patch(out, outWidth, outHeight)
    }

    /**
     * The same patch, resampled by [factor], for looking for an object that is nearer or
     * further away than when it was picked.
     *
     * Averaged when shrinking and interpolated when growing, rather than nearest-neighbour:
     * dropping pixels when shrinking keeps whatever detail survives the smaller grid, and the
     * alternative - sampling one pixel out of each block - turns fine texture into noise that
     * matches nothing, which would make the smaller scales useless on exactly the objects
     * that need them.
     */
    fun resampled(factor: Float): Patch {
        val outWidth = max(8, (width * factor).roundToInt())
        val outHeight = max(8, (height * factor).roundToInt())
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val sourceY = y / factor
            for (x in 0 until outWidth) {
                val sourceX = x / factor
                out[y * outWidth + x] = sampleArea(sourceX, sourceY, factor)
            }
        }
        return Patch(out, outWidth, outHeight)
    }

    /**
     * The same patch at an exact [outWidth] by [outHeight].
     *
     * For bringing a window that was cut at the size the object was matched at back to the
     * template's own shape, so the two can be mixed. [resampled] is the wrong tool for that:
     * it targets a size by multiplying by a factor and rounding, which is fine when the point
     * is just to try a neighbouring size, but here the caller is going to blend the result
     * with a patch of a known size and the two grids have to agree exactly. A pixel of
     * disagreement makes the counts differ, the blend is refused, and it is refused silently -
     * on the frames where the object was changing size, which are the ones that needed it.
     */
    fun resampledTo(outWidth: Int, outHeight: Int): Patch {
        val out = IntArray(outWidth * outHeight)
        // How many source pixels one output pixel covers.
        val spanX = width.toFloat() / outWidth
        val spanY = height.toFloat() / outHeight
        for (y in 0 until outHeight) {
            val sourceY = (y + 0.5f) * spanY - 0.5f
            for (x in 0 until outWidth) {
                val sourceX = (x + 0.5f) * spanX - 0.5f
                // The reciprocal, because sampleArea derives its span by dividing by it.
                out[y * outWidth + x] = sampleArea(sourceX, sourceY, 1f / max(spanX, spanY))
            }
        }
        return Patch(out, outWidth, outHeight)
    }

    /** Average of the source block a scaled pixel covers, clamped to the patch. */
    private fun sampleArea(sourceX: Float, sourceY: Float, factor: Float): Int {
        val span = if (factor >= 1f) 1f else 1f / factor
        var total = 0
        var count = 0
        val startX = sourceX - (span - 1f) / 2f
        val startY = sourceY - (span - 1f) / 2f
        var dy = 0
        while (dy < max(1, span.toInt())) {
            val y = (startY + dy).roundToInt().coerceIn(0, height - 1)
            var dx = 0
            while (dx < max(1, span.toInt())) {
                val x = (startX + dx).roundToInt().coerceIn(0, width - 1)
                total += pixels[y * width + x]
                count++
                dx++
            }
            dy++
        }
        return if (count == 0) 0 else total / count
    }

    /** A copy, for the template that is kept as it was picked. */
    fun copy(): Patch = Patch(pixels.copyOf(), width, height)

    /**
     * Mixes in what the object looks like now.
     *
     * Only ever called for frames that matched well - see the caller - and only part of the
     * way, so no single frame can take the template over.
     */
    fun blendWith(source: Patch, fraction: Float) {
        if (source.count != count) return
        val keep = 1f - fraction
        for (i in pixels.indices) {
            pixels[i] = (pixels[i] * keep + source.pixels[i] * fraction).roundToInt().coerceIn(0, 255)
        }
    }

    companion object {
        /** Below this the patch is flat, and flat things cannot be told apart from each other. */
        const val MIN_VARIANCE = 25.0
    }
}

/** Where the patch was found, how well it matched, and at what size. */
data class Match(
    val centerX: Int,
    val centerY: Int,
    val similarity: Float,
    /** The scale of the template that won: below 1 means the object looks smaller than it did. */
    val scale: Float = 1f,
)

/**
 * Finds the patch the user picked in each new frame.
 *
 * Three decisions in here account for most of whether this holds an object or loses it, and
 * each replaced something that did not work:
 *
 *  * **Correlation, not difference.** Matching by how different the pixels are seems
 *    obvious and is a trap: a camera preview changes brightness constantly as automatic
 *    exposure follows the scene, and a plain difference reads that as the object having
 *    changed. Worse, the score says nothing about *how much* better the best place is than
 *    the next one, so a patch of wall scores nearly as well as the object and the threshold
 *    that is meant to catch that has to be set so loosely it catches nothing. Correlation
 *    subtracts the mean and divides by the spread, so brightness and contrast drop out
 *    entirely, and the result means something: 1 is the same thing, 0 is unrelated, and the
 *    gap to the runner-up measures how certain the answer is.
 *  * **More than one size.** An object walking towards the camera is the same object, but
 *    the patch that described it is the wrong size within seconds. The search re-tries a
 *    couple of neighbouring sizes, so the tracker can follow that without being told the
 *    object is changing size.
 *  * **More than one template.** The template has to be refreshed or it goes stale as the
 *    object turns and the light changes - but refreshing it is also how a tracker slowly
 *    slides off onto the background, because every refresh is a small guess that is never
 *    revisited. Keeping the patch as it was picked, and falling back to it when the adapted
 *    one stops matching, means the tracker can always return to what the user actually chose.
 *
 * This is still not object recognition. It knows what the object looked like, not what it is,
 * so something that turns right around, or is briefly hidden, will still be lost - and once
 * lost it cannot look for the object again, because it has no idea what to look for. Getting
 * that back needs a trained detector, which is a model file and an inference pass per frame
 * rather than arithmetic.
 */
object TemplateTracker {

    /**
     * The best score the last [find] looked at, whether or not it was good enough to act on.
     *
     * For reporting only; nothing in here decides anything from it. It exists because [find]
     * returns nothing at all below [MIN_SIMILARITY], so a caller that shows the match quality
     * has nothing to show on a failing frame and ends up printing 0% - which says "no
     * resemblance whatsoever" when the truth may be "nearly, and it is about to come back".
     * The difference between those two is the difference between a scene that has changed
     * completely and a tracker that is holding on, and it is the first thing worth knowing
     * when this goes wrong on someone else's phone.
     *
     * Treat it as a floor rather than a figure: it may come from the coarse pass, whose pixels
     * are averaged into blocks, and averaging tends to lose the fine detail a correlation is
     * made of. It therefore reads low rather than high, which is the safe direction for a
     * number people will make decisions about.
     */
    @Volatile
    var lastScore = 0f
        private set

    /** Highest candidate score seen during the current [find] call. */
    private var bestSeen = Float.NEGATIVE_INFINITY

    /** First pass runs on the frame divided by this. */
    const val COARSE_FACTOR = 4

    /**
     * Coarse positions are tried every this many coarse pixels.
     *
     * One, and it has to be one. Skipping every other position looks like free speed - the
     * fine pass works to the pixel anyway - but on a frame divided by four it means the search
     * steps over the object in strides of eight real pixels. A correlation peak that narrow
     * is not a freak case, it is what any finely textured subject produces once its detail is
     * averaged into blocks, and the failure is quiet: the peak is never visited, the best
     * sampled position is a corner of the window scoring barely above nothing, and the fine
     * pass - which trusts the coarse answer for where to look - then searches the wrong place
     * entirely and reports the object lost while it is sitting in the middle of the frame.
     */
    const val COARSE_STRIDE = 1

    /**
     * How far to re-examine at full resolution around the coarse answer.
     *
     * Reduced from ten, which made the fine pass the most expensive thing in the tracker
     * rather than the cheap correction it is meant to be: a radius of ten examines 441
     * positions at full resolution against the coarse pass's 4 225 at a sixteenth of the
     * pixels each, so the pass meant to be precise but small was costing three times what the
     * pass meant to be thorough was. Six still covers the coarse grid's own error several
     * times over - the coarse winner is right to within half a cell, which is two pixels - so
     * nothing is lost but the time, and the frame rate is what the whole loop runs on.
     */
    const val REFINE_RADIUS = 6

    /**
     * Below this, the patch is treated as lost rather than matched.
     *
     * Readable directly, unlike the difference measure this replaced: correlation is 1 for
     * the same thing, around 0 for unrelated things, and negative for inverted ones. An
     * object under new lighting, at a slightly different angle, still scores well above this;
     * the scenery it sits in front of does not.
     */
    const val MIN_SIMILARITY = 0.55f

    /**
     * How far the best position must beat the next distinct one, or the match is a guess.
     *
     * The search returns the best place in a window, and the best of a bad lot is still a
     * best. When several places are nearly as good, the winner is arbitrary, and following it
     * means steering at a random point - so a winner without daylight behind it is refused.
     */
    const val MIN_PEAK_MARGIN = 0.10f

    /**
     * Two answers closer together than this many pixels are the same feature seen twice,
     * not two rival candidates, so they do not count as beating each other.
     */
    const val PEAK_SUPPRESSION = 12

    /**
     * Below this, a winner is not worth comparing against anything.
     *
     * The margin below is a test for the case where the frame contains the object *and* a
     * convincing imitation of it: two answers that are both good means the search cannot tell
     * which is which, and steering on a coin toss is worse than saying so. That argument only
     * holds while the winner is actually good. A winner barely above nothing - which is what
     * a coarse pass produces whenever the object's detail does not survive being averaged
     * into blocks - has equally bad rivals, and refusing on a gap between two worthless
     * numbers rejects objects the next pass could have found perfectly well.
     */
    const val RIVALRY_FLOOR = 0.5f

    /**
     * The most the object may appear to have moved in one frame, as a share of the search
     * radius. A large jump is not the object having moved, it is the match being wrong.
     */
    const val MAX_JUMP_FRACTION = 0.75f

    /**
     * The sizes tried, as factors on the patch the user picked.
     *
     * The straight-ahead size is tried first and the others only if it does badly, so an
     * object that is not changing size costs nothing, and one that is costs a second search
     * only while it is growing or shrinking.
     */
    val SCALES = floatArrayOf(1f, 0.8f, 1.25f)

    /** Below this the straight-ahead size is not convincing, so the others are tried. */
    const val SCALE_RETRY_BELOW = 0.7f

    /** How much of the screen a patch covers, as a divisor of the short edge. */
    const val PATCH_DIVISOR = 10

    /** Bounds on the selection size, in pixels, for very small and very large screens. */
    const val MIN_PATCH = 48
    const val MAX_PATCH = 160

    /** Converts a packed ARGB frame to luminance, which is all the matching needs. */
    fun toGray(pixels: IntArray, width: Int, height: Int): Gray {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Rec. 601 luma, integer arithmetic.
            out[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        return Gray(out, width, height)
    }

    /** The patch size to use for a screen of this size. */
    fun patchSizeFor(width: Int, height: Int): Int {
        val shortEdge = min(width, height)
        return (shortEdge / PATCH_DIVISOR).coerceIn(MIN_PATCH, MAX_PATCH)
    }

    /**
     * Cuts a patch centred on a point, or `null` when the selection would run off the
     * screen. Keeping the patch fully inside matters: a template that includes the screen
     * edge would only ever match at that edge.
     */
    fun patchAt(frame: Gray, centerX: Int, centerY: Int, size: Int): Patch? {
        val half = size / 2
        val left = centerX - half
        val top = centerY - half
        if (left < 0 || top < 0) return null
        if (left + size > frame.width || top + size > frame.height) return null

        val out = IntArray(size * size)
        for (y in 0 until size) {
            val rowStart = (top + y) * frame.width + left
            frame.pixels.copyInto(out, y * size, rowStart, rowStart + size)
        }
        return Patch(out, size, size)
    }

    /** Cuts the same window out of a frame, for refreshing a template in place. */
    fun patchAround(frame: Gray, centerX: Int, centerY: Int, size: Int): Patch? =
        patchAt(frame, centerX, centerY, size)

    /**
     * Looks for [patch] within [radius] pixels of ([centerX], [centerY]).
     *
     * [fallbacks] are tried as well when the adapted template stops matching - normally the
     * patch as it was originally picked, which is the one thing about the object that is
     * known to be right.
     *
     * Returns `null` when the patch cannot fit anywhere in the window, when the best position
     * is not clearly better than the next one, or when the answer is too far from where the
     * patch was last seen to be the patch at all.
     */
    fun find(
        frame: Gray,
        patch: Patch,
        centerX: Int,
        centerY: Int,
        radius: Int,
        fallbacks: List<Patch> = emptyList(),
    ): Match? {
        bestSeen = Float.NEGATIVE_INFINITY
        if (patch.count == 0 || frame.width < patch.width || frame.height < patch.height) {
            lastScore = 0f
            return null
        }

        // Downscaled once for the whole call rather than once per size tried. It is a pass
        // over every pixel of the frame and the sizes are tried in turn, so doing it inside
        // the search meant paying for it three times over on exactly the frames that are
        // already the slowest - the ones where the first size did badly and the others had to
        // be tried after it.
        val coarseFrame = frame.downscaled(COARSE_FACTOR)

        var best = search(frame, coarseFrame, patch, centerX, centerY, radius)
        if ((best?.similarity ?: -1f) < FALLBACK_BELOW) {
            for (fallback in fallbacks) {
                val alternative =
                    search(frame, coarseFrame, fallback, centerX, centerY, radius) ?: continue
                if (best == null || alternative.similarity > best.similarity) best = alternative
            }
        }

        lastScore = bestSeen.coerceIn(0f, 1f)

        // A match nobody would act on is not a match. Without this, a rejected answer at the
        // right size can be replaced by a piece of noise at the wrong one: the sizes are tried
        // in turn and the best low score wins by default, so a frame that is genuinely
        // ambiguous - two identical things, or nothing recognisable at all - comes back as a
        // confident-looking match at a nonsense position. The threshold belongs here, with the
        // metric it is a statement about, rather than being something every caller has to
        // remember to re-apply.
        if (best == null || best.similarity < MIN_SIMILARITY) return null
        return best
    }

    /**
     * One template, tried at the sizes that matter and then pinned down to the pixel.
     *
     * @param coarseFrame the frame already downscaled by [COARSE_FACTOR]. The caller does that
     *   once for all the sizes, rather than each size doing it again here.
     */
    private fun search(
        frame: Gray,
        coarseFrame: Gray,
        patch: Patch,
        centerX: Int,
        centerY: Int,
        radius: Int,
    ): Match? {
        if (!patch.hasDetail) return null

        val coarseRadius = max(1, radius / COARSE_FACTOR)

        var best: Match? = null
        for (scale in SCALES) {
            // The straight-ahead size leads, so the others are only paid for when they are
            // needed - an object at a steady distance never touches them.
            if (scale != 1f && (best?.similarity ?: -1f) >= SCALE_RETRY_BELOW) continue

            val scaled = if (scale == 1f) patch else patch.resampled(scale)
            if (!scaled.hasDetail) continue
            if (coarseFrame.width < scaled.width / COARSE_FACTOR) continue

            val coarsePatch = scaled.downscaled(COARSE_FACTOR)
            if (coarseFrame.width < coarsePatch.width || coarseFrame.height < coarsePatch.height) continue

            val coarse = bestPosition(
                frame = coarseFrame,
                patch = coarsePatch,
                centerX = centerX / COARSE_FACTOR,
                centerY = centerY / COARSE_FACTOR,
                radius = coarseRadius,
                stride = COARSE_STRIDE,
            ) ?: continue

            val fine = bestPosition(
                frame = frame,
                patch = scaled,
                centerX = coarse.centerX * COARSE_FACTOR,
                centerY = coarse.centerY * COARSE_FACTOR,
                radius = COARSE_FACTOR * COARSE_STRIDE + REFINE_RADIUS,
                stride = 1,
            ) ?: continue

            val candidate = fine.copy(scale = scale)
            if (best == null || candidate.similarity > best.similarity) best = candidate
        }

        val found = best ?: return null

        // Measured against the original radius, not the fine one: the fine pass only ever
        // explores a small window, so its own limit would say nothing about how far the
        // object has travelled.
        val maxJump = radius * MAX_JUMP_FRACTION
        val moved = hypot((found.centerX - centerX).toDouble(), (found.centerY - centerY).toDouble())
        if (moved > maxJump) return null
        return found
    }

    /**
     * The best position in the window, searched one pixel - or [stride] pixels - at a time.
     *
     * Returns `null` when the winner is convincing but not clearly ahead of the next distinct
     * candidate; see [MIN_PEAK_MARGIN] and [RIVALRY_FLOOR]. The few best answers are kept
     * rather than just the winner, because how close the runner-up was is the difference
     * between a match and a guess, and that cannot be worked out once the winner has been
     * chosen.
     *
     * The margin test has to happen here and nowhere else, which is worth stating because it
     * looks like something the coarse pass could safely check and the fine pass would confirm.
     * It cannot: the fine pass only examines a small window around the coarse answer, so a
     * second convincing subject anywhere further away is invisible to it, and the decision
     * about whether this frame is ambiguous would never be made. The coarse pass covers the
     * whole search radius, so it is the only one of the two that can see a rival - which is
     * also why a refusal from it costs the whole size, and why [RIVALRY_FLOOR] exists to stop
     * that refusal being made on the strength of two worthless numbers.
     */
    private fun bestPosition(
        frame: Gray,
        patch: Patch,
        centerX: Int,
        centerY: Int,
        radius: Int,
        stride: Int,
    ): Match? {
        val halfWidth = patch.width / 2
        val halfHeight = patch.height / 2

        val minX = max(halfWidth, centerX - radius)
        val maxX = min(frame.width - patch.width + halfWidth, centerX + radius)
        val minY = max(halfHeight, centerY - radius)
        val maxY = min(frame.height - patch.height + halfHeight, centerY + radius)
        if (minX > maxX || minY > maxY) return null

        val peaks = Peaks()
        var y = minY
        while (y <= maxY) {
            var x = minX
            while (x <= maxX) {
                val correlation = correlationAt(frame, patch, x - halfWidth, y - halfHeight)
                peaks.offer(correlation, x, y)
                x += stride
            }
            y += stride
        }

        val index = peaks.bestIndex()
        if (index < 0) return null

        val score = peaks.scores[index]
        // Recorded for reporting, from whichever pass saw it. Nothing here decides anything
        // from the number, which matters because a score measured on pixels averaged into
        // blocks is not directly comparable with one measured at full resolution - the
        // averaging smooths fine texture away, so it tends to read low rather than high. As a
        // floor on how much the frame resembles the object that is exactly the right way for
        // it to be wrong. It is also the only score available at all in the case that matters
        // most: when this pass refuses the answer for being ambiguous, the fine pass never
        // runs, and reporting nothing would leave "two of them" looking identical to "gone".
        if (score > bestSeen) bestSeen = score

        if (score >= RIVALRY_FLOOR) {
            val runnerUp = peaks.runnerUpExcept(index)
            if (runnerUp != null && score - runnerUp < MIN_PEAK_MARGIN) return null
        }

        return Match(peaks.xs[index], peaks.ys[index], score)
    }

    /**
     * How alike the patch and the frame are when the patch's top left sits at ([left], [top]).
     *
     * Zero-normalised cross-correlation: 1 identical up to brightness and contrast, 0
     * unrelated, -1 inverted. Brightness and contrast drop out because both sides have their
     * mean removed and their spread divided out, which is what makes this survive a camera
     * preview re-exposing between frames - the thing a plain pixel difference cannot do.
     *
     * Returns 0 when either side is flat, since a flat thing resembles everything equally and
     * there is no meaningful answer to give.
     */
    fun correlationAt(frame: Gray, patch: Patch, left: Int, top: Int): Float {
        var sumFrame = 0L
        var sumFrameSquares = 0L
        var sumProducts = 0L

        for (y in 0 until patch.height) {
            var frameIndex = (top + y) * frame.width + left
            var patchIndex = y * patch.width
            for (x in 0 until patch.width) {
                val pixel = frame.pixels[frameIndex]
                sumFrame += pixel
                sumFrameSquares += pixel.toLong() * pixel
                sumProducts += pixel.toLong() * patch.pixels[patchIndex]
                frameIndex++
                patchIndex++
            }
        }

        val count = patch.count
        val meanFrame = sumFrame.toDouble() / count
        val varianceFrame = sumFrameSquares.toDouble() / count - meanFrame * meanFrame
        val denominator = sqrt(varianceFrame * patch.variance)
        if (denominator < 1e-6) return 0f

        val covariance = sumProducts.toDouble() / count - patch.mean * meanFrame
        return (covariance / denominator).toFloat().coerceIn(-1f, 1f)
    }

    /** Below this the adapted template is not trusted, so the originals are tried too. */
    const val FALLBACK_BELOW = 0.6f
}

/**
 * The handful of best *distinct* answers found in a search window.
 *
 * Distinct is the whole point. What the caller needs to know is how far ahead the winner was,
 * and the runner-up in raw score terms is almost always the neighbouring pixel of the winner -
 * the same feature, measured a pixel to one side. So a candidate that is close to one already
 * kept is treated as that same feature and only replaces it if it scores better; only
 * genuinely separate places become separate entries. Without that, a completely featureless
 * area looks like a confident match: every position scores the same, the runner-up is a
 * neighbour, and nothing appears to compete with it.
 */
private class Peaks(private val size: Int = 6) {
    val scores = FloatArray(size) { Float.NEGATIVE_INFINITY }
    val xs = IntArray(size)
    val ys = IntArray(size)

    fun offer(score: Float, x: Int, y: Int) {
        var weakest = 0
        for (i in 0 until size) {
            if (scores[i] == Float.NEGATIVE_INFINITY) {
                weakest = i
                break
            }
            if (scores[i] < scores[weakest]) weakest = i

            val sameFeature = abs(xs[i] - x) < TemplateTracker.PEAK_SUPPRESSION &&
                abs(ys[i] - y) < TemplateTracker.PEAK_SUPPRESSION
            if (sameFeature) {
                if (score > scores[i]) {
                    scores[i] = score
                    xs[i] = x
                    ys[i] = y
                }
                return
            }
        }
        if (score > scores[weakest]) {
            scores[weakest] = score
            xs[weakest] = x
            ys[weakest] = y
        }
    }

    /** Index of the highest score, or -1 when nothing was offered. */
    fun bestIndex(): Int {
        var best = -1
        for (i in 0 until size) {
            if (scores[i] == Float.NEGATIVE_INFINITY) continue
            if (best == -1 || scores[i] > scores[best]) best = i
        }
        return best
    }

    /**
     * The best score belonging to a place that is not the winner, or `null` when nothing
     * else was ever in the running - in which case the winner is unopposed.
     */
    fun runnerUpExcept(index: Int): Float? {
        var best = Float.NEGATIVE_INFINITY
        for (i in 0 until size) {
            if (i == index) continue
            if (scores[i] == Float.NEGATIVE_INFINITY) continue
            if (scores[i] > best) best = scores[i]
        }
        return if (best == Float.NEGATIVE_INFINITY) null else best
    }
}
