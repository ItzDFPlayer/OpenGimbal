package com.itzdfplayer.opengimbal.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** A shutter we decided to press. */
data class DetectedShutter(
    val node: AccessibilityNodeInfo,
    val centerX: Int,
    val centerY: Int,
    val score: Float,
    val label: String?,
)

/**
 * Walks the foreground window's accessibility tree looking for the shutter button.
 *
 * Needs `canRetrieveWindowContent` in the service config. The walk is bounded so a
 * deep or huge tree cannot stall the gesture path.
 */
class ShutterFinder(private val service: AccessibilityService) {

    private companion object {
        const val MAX_NODES = 1500
        const val MAX_CLICKABLE_ANCESTORS = 6
        const val TAP_DURATION_MS = 40L
    }

    /**
     * @param requireShutterName when true only nodes that describe themselves as a
     *   shutter are considered. That is a much higher-confidence answer than shape
     *   alone, so it is worth asking for separately before falling back to pixels.
     */
    fun find(
        screenWidth: Int,
        screenHeight: Int,
        requireShutterName: Boolean = false,
    ): DetectedShutter? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null

        // Index aligned: candidates[i] describes candidateNodes[i].
        val candidateNodes = ArrayList<AccessibilityNodeInfo>()
        val candidates = ArrayList<ShutterCandidate>()

        // A single reused Rect keeps the per-node cost down on large trees.
        val rect = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(rect)
            val bounds = ScreenBounds(rect.left, rect.top, rect.right, rect.bottom)

            // Cheap gate first: only then pay for the ancestor walk.
            if (!ShutterDetector.isPlausible(bounds, screenWidth, screenHeight)) continue

            val description = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName
            if (requireShutterName && !ShutterDetector.namesShutter(description, viewId)) continue

            candidates.add(
                ShutterCandidate(
                    bounds = bounds,
                    clickable = node.isClickable || hasClickableAncestor(node),
                    contentDescription = description,
                    viewIdName = viewId,
                )
            )
            candidateNodes.add(node)
        }

        val match = ShutterDetector.findBest(candidates, screenWidth, screenHeight) ?: return null
        val node = candidateNodes.getOrNull(match.index) ?: return null

        return DetectedShutter(
            node = node,
            centerX = match.candidate.bounds.centerX,
            centerY = match.candidate.bounds.centerY,
            score = match.score,
            label = match.candidate.contentDescription ?: match.candidate.viewIdName,
        )
    }

    /**
     * Prefers a real click on the node, because that respects whatever the app
     * expects. Falls back to a tap at the centre, which is what gets through when a
     * camera app handles its own touch events on a custom surface.
     */
    fun press(shutter: DetectedShutter): Boolean {
        var target: AccessibilityNodeInfo? = shutter.node
        var depth = 0
        while (target != null && !target.isClickable && depth < MAX_CLICKABLE_ANCESTORS) {
            target = target.parent
            depth++
        }

        if (target != null && target.isClickable &&
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            return true
        }

        return tap(shutter.centerX, shutter.centerY)
    }

    /** Taps a raw screen coordinate, for a shutter found in a screenshot. */
    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            // A hair of travel keeps the stroke from being degenerate.
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (parent != null && depth < MAX_CLICKABLE_ANCESTORS) {
            if (parent.isClickable) return true
            parent = runCatching { parent.parent }.getOrNull()
            depth++
        }
        return false
    }
}
