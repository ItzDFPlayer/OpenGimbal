package com.itzdfplayer.opengimbal.accessibility

import kotlin.math.abs
import kotlin.math.min

/** A screen rectangle in pixels, kept free of Android types so the rule is testable. */
data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val empty: Boolean get() = width <= 0 || height <= 0
}

/** The parts of a view node the shutter heuristic cares about. */
data class ShutterCandidate(
    val bounds: ScreenBounds,
    val clickable: Boolean,
    val contentDescription: String? = null,
    val viewIdName: String? = null,
)

/** A candidate that scored well enough to act on. [index] points back into the input list. */
data class ShutterMatch(val index: Int, val candidate: ShutterCandidate, val score: Float)

/**
 * Locates a camera app's shutter button from the accessibility tree.
 *
 * An accessibility tree carries no pixel data, so "the big white or red circle at
 * the bottom of the screen" is approximated geometrically:
 *
 *  * in the lower half of the screen
 *  * roughly square (a circle's bounding box is)
 *  * sized like a shutter, not like a small icon or the whole preview
 *  * usually close to the horizontal centre
 *
 * A shutter-ish content description or view id is treated as strong evidence when
 * the app happens to expose one, which many do.
 *
 * The bounds and the ranking terms live here rather than in one of the callers, so
 * [ShutterPixelDetector] can score a blob found in a screenshot by the same rules.
 */
object ShutterDetector {

    /** Below this, we assume there is no shutter on screen. */
    const val MIN_SCORE = 2.5f

    /** Only the bottom part of the screen is searched; camera controls live there. */
    const val SEARCH_TOP_FRACTION = 0.50f

    /** A shutter's box, as a fraction of the screen width. */
    const val MIN_SIZE_FRACTION = 0.09f
    const val MAX_SIZE_FRACTION = 0.45f

    /** A circle's bounding box is square; this is how far off we tolerate. */
    const val MIN_RATIO = 0.70f
    const val MAX_RATIO = 1.45f

    /** How far sideways a shutter may sit, as a fraction of the screen width. */
    const val CENTRE_TOLERANCE_FRACTION = 0.30f

    private val SHUTTER_WORDS = listOf(
        "shutter",
        "capture",
        "takephoto",
        "take photo",
        "takepicture",
        "take picture",
        "shoot",
        "snap",
        "camera_button",
        "btn_shutter",
        "btn_capture",
        "shutter_button",
    )

    /**
     * Cheap geometry gate. Used to skip expensive per-node checks during the tree
     * walk, and reused by [score] so the two can never disagree.
     */
    fun isPlausible(bounds: ScreenBounds, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        if (bounds.empty) return false

        // Lower half of the screen.
        if (bounds.centerY < screenHeight * SEARCH_TOP_FRACTION) return false

        // Roughly square.
        val ratio = bounds.width.toFloat() / bounds.height.toFloat()
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) return false

        // Shutter-sized.
        val relative = min(bounds.width, bounds.height).toFloat() / screenWidth
        return relative >= MIN_SIZE_FRACTION && relative <= MAX_SIZE_FRACTION
    }

    /**
     * Full credit (1.5) in the middle, nothing at or beyond the tolerance band.
     * Weighted above every other term, because a camera app's shutter is centred.
     */
    fun centeringScore(centerX: Int, screenWidth: Int): Float {
        if (screenWidth <= 0) return 0f
        val tolerance = screenWidth * CENTRE_TOLERANCE_FRACTION
        return 1.5f * (1f - abs(centerX - screenWidth / 2f) / tolerance).coerceIn(0f, 1f)
    }

    /** 1 for a perfect square, 0 at the edge of the tolerated ratio band. */
    fun shapeScore(bounds: ScreenBounds): Float {
        if (bounds.empty) return 0f
        val ratio = bounds.width.toFloat() / bounds.height.toFloat()
        return (1f - abs(ratio - 1f) / (MAX_RATIO - 1f)).coerceIn(0f, 1f)
    }

    /** 0 at the smallest tolerated shutter, 1 at the largest. */
    fun sizeScore(bounds: ScreenBounds, screenWidth: Int): Float {
        if (screenWidth <= 0 || bounds.empty) return 0f
        val relative = min(bounds.width, bounds.height).toFloat() / screenWidth
        val span = MAX_SIZE_FRACTION - MIN_SIZE_FRACTION
        return ((relative - MIN_SIZE_FRACTION) / span).coerceIn(0f, 1f)
    }

    /** `null` when the candidate is disqualified outright. */
    fun score(candidate: ShutterCandidate, screenWidth: Int, screenHeight: Int): Float? {
        if (!isPlausible(candidate.bounds, screenWidth, screenHeight)) return null

        val bounds = candidate.bounds
        var score = 1f

        // Shutters sit near the middle horizontally.
        score += centeringScore(bounds.centerX, screenWidth)

        // The closer to a perfect circle, the better.
        score += 0.5f * shapeScore(bounds)

        // Big, but not as big as the preview behind it.
        score += 0.5f * sizeScore(bounds, screenWidth)

        if (candidate.clickable) score += 0.5f
        if (mentionsShutter(candidate)) score += 3f

        return score
    }

    fun findBest(
        candidates: List<ShutterCandidate>,
        screenWidth: Int,
        screenHeight: Int,
    ): ShutterMatch? = candidates
        .mapIndexedNotNull { index, candidate ->
            score(candidate, screenWidth, screenHeight)?.let { ShutterMatch(index, candidate, it) }
        }
        .filter { it.score >= MIN_SCORE }
        .maxByOrNull { it.score }

    /** Whether a node names itself as a shutter, which is the strongest hint there is. */
    fun mentionsShutter(candidate: ShutterCandidate): Boolean =
        namesShutter(candidate.contentDescription, candidate.viewIdName)

    /** As [mentionsShutter], for callers that have not built a candidate yet. */
    fun namesShutter(contentDescription: String?, viewIdName: String?): Boolean {
        val haystack = listOfNotNull(contentDescription, viewIdName)
            .joinToString(" ")
            .lowercase()
        return haystack.isNotBlank() && SHUTTER_WORDS.any { haystack.contains(it) }
    }
}
