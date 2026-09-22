package com.itzdfplayer.opengimbal.tracking

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.itzdfplayer.opengimbal.R
import com.itzdfplayer.opengimbal.gimbal.AimState
import com.itzdfplayer.opengimbal.gimbal.GimbalBleClient
import kotlin.math.roundToInt

/**
 * Follows the patch the user picked and steers the gimbal to keep it centred.
 *
 * A visual servo loop, closed through the camera: the patch drifts off centre, the aim is
 * nudged so the gimbal turns back, and the next frame shows whether that worked. The gain is
 * in degrees per pixel, which is what the camera's field of view and the screen size amount
 * to - a value nobody documents, so it is a setting and the strength slider is how it gets
 * tuned.
 *
 * Runs on the capture thread: matching a frame is the expensive part and must not be on the
 * main thread, while the aim is only sent from there.
 */
class Tracker(
    private val client: GimbalBleClient,
    private val main: Handler,
    private val settings: () -> TrackingSettings,
) {

    /**
     * How long to wait after a tap before cutting the patch.
     *
     * The screen is mirrored, so the selection overlay is *in* the frames it produces.
     * Cutting the patch on the very next frame would take it through the dimming layer, and
     * every frame after that - with the dimming gone - would fail to match it. The wait
     * covers the tap reaching this thread, the overlay being removed, and the reader's queue
     * draining.
     */
    private companion object {
        const val TAG = "OpenGimbal"

        /**
         * How far the patch may have moved since the last frame, as a share of the screen.
         *
         * Also the thing every step is budgeted against: a step that moves the object
         * further than this loses it immediately, because the next search will not look
         * there.
         */
        const val SEARCH_RADIUS_FRACTION = 0.12f

        /**
         * The most a single step may aim to move the object, as a share of the search
         * radius.
         *
         * A quarter leaves room for the estimate being wrong in the dangerous direction. A
         * step that moves the object four times as far as intended still lands inside the
         * window, so the loop can survive being wrong and go on to measure itself right.
         */
        const val STEP_RADIUS_FRACTION = 0.25f

        /** How often the frames-per-second readout is refreshed. */
        const val FPS_WINDOW_MS = 1000L

        /**
         * How long to allow for a correction to show up in the picture before deciding what
         * to do next.
         *
         * There are two delays in this loop that no cleverness removes: the gimbal takes
         * time to physically move, and the mirrored picture showing that movement arrives
         * later still. Correcting on every frame therefore corrects the same error several
         * times over, because the error has not had the chance to change yet - the aim runs
         * away from the target and the gimbal swings past it. This wait is also what makes
         * the measurement below possible at all.
         */
        const val SETTLE_MS = 300L

        /**
         * Only frames that matched at least this well are allowed to refresh the template.
         * Below it, what was found is not certainly the object, and copying it in would let
         * the template drift onto the surroundings.
         */
        const val TEMPLATE_UPDATE_SIMILARITY = 0.8f

        /**
         * How long to wait after a tap before cutting the patch.
         *
         * The screen is mirrored, so the selection overlay is *in* the frames it produces.
         * Cutting the patch on the very next frame would take it through the dimming layer,
         * and every frame after that - with the dimming gone - would fail to match it. The
         * wait covers the tap reaching this thread, the overlay being removed, and the
         * reader's queue draining.
         */
        const val SELECTION_SETTLE_MS = 250L

        /**
         * The share of the template replaced per update. Small on purpose: a single blurred
         * or slightly wrong frame must not be able to take the template over.
         */
        const val TEMPLATE_UPDATE_FRACTION = 0.25f
    }

    /** The patched being followed, refreshed as the object changes. */
    private var patch: Patch? = null

    /**
     * The patch exactly as the user picked it, never refreshed.
     *
     * Kept because the refreshed one can drift: every refresh is a small assumption that the
     * best match was really the object, and nothing ever revisits those assumptions until the
     * template has slid onto the background. When that happens this is what the tracker has to
     * go back to - the one description of the object that is known to have been right.
     */
    private var original: Patch? = null

    private var lastX = 0
    private var lastY = 0

    private var framesInWindow = 0
    private var windowStartedAt = 0L

    /** When the tap that is waiting to be acted on first turned up. */
    private var pendingSince = 0L

    /**
     * What the picture did for a degree of turn, learned while tracking rather than assumed.
     * Both start on the cautious side; see [ServoGain].
     */
    private val horizontalGain = ServoGain()
    private val verticalGain = ServoGain()

    /**
     * The correction that has been sent and whose effect has not been measured yet.
     *
     * Nothing new is sent while one of these is outstanding, so the loop always finishes
     * looking at what it did before deciding what to do next.
     */
    private var awaiting: Command? = null

    private class Command(
        /** Where the object was when the correction was worked out. */
        val offsetX: Int,
        val offsetY: Int,
        /** What was actually asked for, after the step limits. */
        val deltaYaw: Float,
        val deltaPitch: Float,
        val at: Long,
    )

    /** True while a patch is being followed. */
    val isTracking: Boolean get() = patch != null

    /** Cuts a patch out of the current frame around the point the user picked. */
    fun select(frame: Gray, x: Int, y: Int): Boolean {
        val size = TemplateTracker.patchSizeFor(frame.width, frame.height)
        val cut = TemplateTracker.patchAt(frame, x, y, size)
        if (cut == null) {
            main.post {
                TrackingStatus.messageRes = R.string.tracking_message_pick_further_from_edge
            }
            return false
        }
        if (!cut.hasDetail) {
            // Nothing to correlate against. Said now rather than after a second of confusing
            // "lost" reports, and the answer is actionable: pick something with texture.
            main.post { TrackingStatus.messageRes = R.string.tracking_message_pick_detail }
            return false
        }
        patch = cut
        original = cut.copy()
        lastX = x
        lastY = y
        // A new object means a new scene and a fresh measurement: an estimate from the last
        // one would be a guess dressed up as knowledge.
        horizontalGain.reset()
        verticalGain.reset()
        awaiting = null
        main.post {
            TrackingStatus.messageRes = null
            TrackingStatus.similarity = 0f
            TrackingStatus.scale = 1f
            TrackingStatus.markerX = x
            TrackingStatus.markerY = y
            TrackingStatus.markerSize = size
            TrackingStatus.setPhase(TrackingPhase.TRACKING)
        }
        return true
    }

    /** Forgets the patch. The gimbal is left where it is. */
    fun clear() {
        patch = null
        original = null
        framesInWindow = 0
        pendingSince = 0L
        awaiting = null
        main.post { TrackingStatus.similarity = 0f }
    }

    /** One frame, from the capture thread. */
    fun onFrame(frame: Gray) {
        val current = patch

        // Frames per second of the capture, whether or not anything is being followed.
        val now = SystemClock.uptimeMillis()
        if (windowStartedAt == 0L) windowStartedAt = now
        framesInWindow++
        if (now - windowStartedAt >= FPS_WINDOW_MS) {
            val fps = framesInWindow * 1000f / (now - windowStartedAt)
            framesInWindow = 0
            windowStartedAt = now
            main.post { TrackingStatus.fps = fps }
        }

        // A selection waiting to be applied, taken from a frame the selection overlay has
        // already left.
        TrackingStatus.pendingSelection?.let { pending ->
            if (pendingSince == 0L) pendingSince = now
            if (now - pendingSince >= SELECTION_SETTLE_MS) {
                TrackingStatus.pendingSelection = null
                pendingSince = 0L
                select(frame, pending[0], pending[1])
            }
        }

        if (current == null) return
        if (frame.width < 1 || frame.height < 1) return

        val radius = (minOf(frame.width, frame.height) * SEARCH_RADIUS_FRACTION).toInt()
        val fallback = original?.takeIf { it !== current }
        val found = TemplateTracker.find(
            frame = frame,
            patch = current,
            centerX = lastX,
            centerY = lastY,
            radius = radius,
            fallbacks = listOfNotNull(fallback),
        )
        if (found == null || found.similarity < TemplateTracker.MIN_SIMILARITY) {
            // Hold still and say so rather than steering on a guess. Note that the search
            // centre is deliberately left where it was: moving it to wherever a rejected
            // match happened to land is exactly how a lost object turns into a gimbal that
            // wanders off following the scenery.
            if (TrackingStatus.phase != TrackingPhase.LOST) {
                main.post {
                    TrackingStatus.similarity = found?.similarity ?: 0f
                    TrackingStatus.setPhase(TrackingPhase.LOST)
                }
            }
            return
        }

        lastX = found.centerX
        lastY = found.centerY

        // Keep the template describing the object. Left alone it goes stale - the gimbal
        // moving changes the angle, the lighting and the motion blur - and the match decays
        // until the object is lost for no reason the user could see.
        if (found.similarity >= TEMPLATE_UPDATE_SIMILARITY) {
            val seen = TemplateTracker.patchAround(
                frame = frame,
                centerX = found.centerX,
                centerY = found.centerY,
                size = (current.width * found.scale).roundToInt().coerceAtLeast(8),
            )
            if (seen != null && seen.count == current.count) {
                current.blendWith(seen, TEMPLATE_UPDATE_FRACTION)
            }
        }

        val offsetX = found.centerX - frame.width / 2
        val offsetY = found.centerY - frame.height / 2

        // How did the last correction actually land? This is the measurement the whole loop
        // is built on: it is what turns an unknowable field of view into a number.
        awaiting?.let { command ->
            if (now - command.at >= SETTLE_MS) {
                // Panning right slides the scene left, so a correction that worked shows up
                // as the patch moving the opposite way across the frame. Tilting up slides
                // the scene down, the same way screen y runs. Those two signs are why the
                // horizontal one is negated here and the vertical one is not.
                horizontalGain.observe(
                    shiftedPixels = (command.offsetX - offsetX).toFloat(),
                    commandedDegrees = command.deltaYaw,
                )
                verticalGain.observe(
                    shiftedPixels = (offsetY - command.offsetY).toFloat(),
                    commandedDegrees = command.deltaPitch,
                )
                awaiting = null
            }
        }

        val correction = if (awaiting == null) {
            correctionFor(frame, found, radius, settings())
        } else {
            null
        }

        var stepYaw = 0f
        var stepPitch = 0f
        if (correction != null) {
            // Clamped here rather than by the aim, so that what is recorded as sent is
            // exactly what was sent - a measurement of a command that was quietly reduced
            // would be wrong.
            stepYaw = correction[0].coerceIn(
                -AimState.MAX_STEP_DEGREES,
                AimState.MAX_STEP_DEGREES,
            )
            stepPitch = correction[1].coerceIn(
                -AimState.MAX_STEP_DEGREES,
                AimState.MAX_STEP_DEGREES,
            )
            awaiting = Command(offsetX, offsetY, stepYaw, stepPitch, now)
        }

        main.post {
            TrackingStatus.similarity = found.similarity
            TrackingStatus.scale = found.scale
            TrackingStatus.offsetX = offsetX
            TrackingStatus.offsetY = offsetY
            TrackingStatus.markerX = found.centerX
            TrackingStatus.markerY = found.centerY
            TrackingStatus.pixelsPerDegreeHorizontal = horizontalGain.pixelsPerDegree
            TrackingStatus.pixelsPerDegreeVertical = verticalGain.pixelsPerDegree
            TrackingStatus.reversedMeasurements =
                horizontalGain.reversed + verticalGain.reversed
            if (TrackingStatus.phase == TrackingPhase.LOST) {
                TrackingStatus.setPhase(TrackingPhase.TRACKING)
            }
            if (correction != null) {
                TrackingStatus.correctionYaw = stepYaw
                TrackingStatus.correctionPitch = stepPitch
                AimState.nudge(stepYaw, stepPitch)
                if (!client.send(AimState.frameFor(client))) {
                    Log.d(TAG, "Tracking frame dropped, the gimbal is not connected")
                }
            }
        }
    }

    /**
     * How far to turn to bring the patch back to the middle, in degrees, or `null` when it
     * is close enough already.
     *
     * The gain comes from what this session has measured rather than from a fixed guess, so
     * the step is sized in pixels the object will really move. The arithmetic and the sign
     * convention live in [AimCorrection], where they can be read on their own - and where
     * the reasoning behind the two axes not sharing a sign is written down, because that is
     * not obvious and getting it wrong looks like a hardware fault.
     */
    private fun correctionFor(
        frame: Gray,
        found: Match,
        radius: Int,
        current: TrackingSettings,
    ): FloatArray? = AimCorrection.degrees(
        offsetX = (found.centerX - frame.width / 2).toFloat(),
        offsetY = (found.centerY - frame.height / 2).toFloat(),
        frameWidth = frame.width,
        frameHeight = frame.height,
        horizontalDegreesPerPixel = horizontalGain.degreesPerPixel,
        verticalDegreesPerPixel = verticalGain.degreesPerPixel,
        maxPixelsPerStep = radius * STEP_RADIUS_FRACTION,
        damping = current.damping,
        invertX = current.invertX,
        invertY = current.invertY,
    )
}

/** What the tracker needs from the settings, read fresh every frame. */
data class TrackingSettings(
    /**
     * How much of the error to try to remove in one step. 100% is the working default;
     * raising it chases harder and leaves less margin for the gain estimate being wrong.
     */
    val strength: Float = 100f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
) {
    /** The share of the error corrected per step, never enough to guarantee an overshoot. */
    val damping: Float
        get() = (AimCorrection.DEFAULT_DAMPING * (strength / 100f)).coerceIn(0.05f, 1f)
}
