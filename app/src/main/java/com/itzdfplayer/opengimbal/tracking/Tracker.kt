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
     * The tuning values, and the reasoning behind each.
     *
     * Nearly every number here has been moved at least once in response to what the loop did
     * on a real gimbal, so these comments are a record of what went wrong rather than a
     * description of an obvious choice.
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
         * Raised from a quarter, which was the largest single reason this loop could not hold
         * anything that moved. A quarter of the search radius is 3% of the short edge, and
         * with one command per [SETTLE_MS] that caps the whole gimbal at a few degrees a
         * second - slower than somebody walking across the frame, so the object left the
         * window and was lost, and slow enough that closing a large error took eleven steps
         * and several seconds. Six tenths still leaves the step inside the window even when
         * the gain estimate is wrong by a factor of 1.6, and the gain is measured rather than
         * assumed, so the loop can go on measuring itself right.
         */
        const val STEP_RADIUS_FRACTION = 0.6f

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
         * Frames that must arrive, after a tap, before the patch is cut.
         *
         * The wait above is a length of time, and time is the wrong unit for the question
         * actually being asked. What has to be true is that the dimming overlay is out of the
         * picture, and the reader keeps a couple of images ready, so at a low frame rate
         * "250 ms" can be fewer frames than there are stale pictures still queued behind it.
         * That is not a theoretical risk: a patch cut from a frame that still had the dimming
         * in it describes the object through a grey wash, nothing in the frames that follow
         * matches it, and the session is lost before it has started - reported as a similarity
         * of 0% with nothing to say why. Counting frames as well costs a fraction of a second
         * and removes it.
         */
        const val SELECTION_SETTLE_FRAMES = 4

        /** The share of the template replaced per update. Small on purpose: a single blurred
         * or slightly wrong frame must not be able to take the template over.
         */
        const val TEMPLATE_UPDATE_FRACTION = 0.25f

        /**
         * Consecutive frames without a match before the object is called lost.
         *
         * One failed frame is not the object being gone: it can be motion blur, someone
         * walking in front of it, or a patch that happened to be hard to match on one
         * particular frame. Until this many frames have failed in a row the loop keeps
         * steering on the estimated motion, which is the difference between a momentary miss
         * and the session ending - and the session ending is what used to happen, because a
         * lost object also stopped the search centre moving, so one bad frame could only ever
         * get worse.
         */
        const val MAX_COAST_FRAMES = 5

        /**
         * The most of the search radius a motion lead may claim.
         *
         * Half, so that a lead and the error it is added to can between them still be
         * expected to leave the object inside the window the next search will look in.
         */
        const val LEAD_LIMIT_FRACTION = 0.5f

        /**
         * A speed above this is not believed, as a share of the short edge per second.
         *
         * Two screens a second is far faster than anything a person holding a phone can
         * follow, so a number above it is a wrong match rather than a fast object.
         */
        const val MAX_SPEED_FRACTIONS_PER_SECOND = 2f
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

    /** Frames that have arrived since that tap; see [SELECTION_SETTLE_FRAMES]. */
    private var pendingFrames = 0

    /** When a patch was last really matched, which is what a coasting step is measured from. */
    private var lastMatchAt = 0L

    /** Consecutive frames in which nothing was matched. Reset by any frame that matches. */
    private var misses = 0

    /** True when a frame was missed between a command being sent and its effect measured. */
    private var missedSinceCommand = false

    /**
     * Where the object is heading, so a correction can lead it rather than trail it.
     *
     * Rebuilt for each new object, because a speed measured on one is not the speed of the
     * other - and built with the frame size, which is what makes its bound on a believable
     * speed mean anything.
     */
    private var motion: TargetMotion? = null

    /**
     * What the picture did for a degree of turn, learned while tracking rather than assumed.
     *
     * Replaced per object rather than kept for the session, so the starting guess can be
     * derived from the frame the patch was cut from; see [ServoGain.seedFor].
     */
    private var horizontalGain = ServoGain()
    private var verticalGain = ServoGain()

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
        // one would be a guess dressed up as knowledge. What goes in its place is a starting
        // guess derived from the frame the patch was cut from, which is as close to knowledge
        // as this loop can get before it has measured anything.
        horizontalGain = ServoGain(ServoGain.seedFor(frame.width))
        verticalGain = ServoGain(ServoGain.seedFor(frame.height))
        motion = TargetMotion(
            maxPixelsPerSecond =
            minOf(frame.width, frame.height) * MAX_SPEED_FRACTIONS_PER_SECOND,
        )
        misses = 0
        missedSinceCommand = false
        lastMatchAt = 0L
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
        pendingFrames = 0
        awaiting = null
        motion = null
        misses = 0
        missedSinceCommand = false
        lastMatchAt = 0L
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
        // already left. Both the wait and the frame count have to be satisfied; see
        // SELECTION_SETTLE_FRAMES for why the count is the one that carries the weight.
        TrackingStatus.pendingSelection?.let { pending ->
            if (pendingSince == 0L) {
                pendingSince = now
                pendingFrames = 0
            }
            pendingFrames++
            if (pendingFrames >= SELECTION_SETTLE_FRAMES &&
                now - pendingSince >= SELECTION_SETTLE_MS
            ) {
                TrackingStatus.pendingSelection = null
                pendingSince = 0L
                pendingFrames = 0
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

        if (found == null) {
            // Nothing matched here, which is not yet the object being gone. Follow where it
            // was heading for a few frames first; once that runs out it really is lost.
            if (coast(frame, now, radius)) return
            reportLost()
            return
        }

        lastX = found.centerX
        lastY = found.centerY
        lastMatchAt = now
        misses = 0

        // How fast the object is going, measured from where it has really been matched rather
        // than from anything the loop asked for.
        motion?.observe(found.centerX, found.centerY, now)

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
            // Cut at the size the object was matched at, then brought back to the template's
            // own size, because the mix needs the two to line up pixel for pixel. Left at the
            // matched size instead, the two differ in shape whenever the object has grown or
            // shrunk, the mix is refused, and the template quietly stops being refreshed for
            // the rest of the session - going stale exactly while the object is changing size,
            // which is when it most needs refreshing.
            val aligned = seen?.resampledTo(current.width, current.height)
            if (aligned != null && aligned.count == current.count) {
                current.blendWith(aligned, TEMPLATE_UPDATE_FRACTION)
            }
        }

        val offsetX = found.centerX - frame.width / 2
        val offsetY = found.centerY - frame.height / 2

        main.post {
            TrackingStatus.similarity = found.similarity
            TrackingStatus.scale = found.scale
            TrackingStatus.markerX = found.centerX
            TrackingStatus.markerY = found.centerY
        }

        steer(frame.width, frame.height, offsetX, offsetY, radius, matched = true, now = now)
    }

    /**
     * Measures the last command, works out the next one, sends it, and publishes the state.
     *
     * Shared by the two ways a frame can produce a position - a real match, and a step along
     * the estimated path - because from here on both are the same question: given where the
     * object is believed to be, how far is it from the middle, and how much of that should be
     * asked for in turn.
     *
     * @param matched whether this frame came from a real match. A command's worth is read off
     *   how far the object moved between two positions, so it can only be measured from
     *   positions that were really matched - and only when every frame since the command was
     *   sent was matched too. A position that had to be guessed at, or that arrives after the
     *   object was briefly lost and picked up again, would measure the object's own travel
     *   across the gap as though the gimbal had caused it.
     */
    private fun steer(
        frameWidth: Int,
        frameHeight: Int,
        offsetX: Int,
        offsetY: Int,
        radius: Int,
        matched: Boolean,
        now: Long,
    ) {
        // How did the last correction actually land? This is the measurement the whole loop
        // is built on: it is what turns an unknowable field of view into a number.
        awaiting?.let { command ->
            if (now - command.at >= SETTLE_MS) {
                if (matched && !missedSinceCommand) {
                    // Panning right slides the scene left, so a correction that worked shows
                    // up as the patch moving the opposite way across the frame. Tilting up
                    // slides the scene down, the same way screen y runs. Those two signs are
                    // why the horizontal one is negated here and the vertical one is not.
                    horizontalGain.observe(
                        shiftedPixels = (command.offsetX - offsetX).toFloat(),
                        commandedDegrees = command.deltaYaw,
                    )
                    verticalGain.observe(
                        shiftedPixels = (offsetY - command.offsetY).toFloat(),
                        commandedDegrees = command.deltaPitch,
                    )
                }
                // Cleared whether or not it was worth measuring. Leaving it outstanding is
                // what used to stop the loop dead: nothing new is sent while a command is
                // being waited on, so once the object was lost no further correction was ever
                // asked for, and the loss could only get worse.
                awaiting = null
                missedSinceCommand = false
            }
        }

        val current = settings()
        val correction = if (awaiting == null) {
            correctionFor(offsetX, offsetY, frameWidth, frameHeight, radius, current)
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
            TrackingStatus.offsetX = offsetX
            TrackingStatus.offsetY = offsetY
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
     * Follows the estimated motion for a frame or two when nothing matched.
     *
     * The search centre is moved along the object's estimated path, which is a deliberate
     * reversal of what this used to do. Leaving the centre still is right once the object is
     * genuinely *lost*, because moving it to wherever a rejected match happened to land is
     * exactly how a tracker starts following the scenery instead. But it is wrong for a single
     * bad frame in an otherwise good run: the object carries on moving, the centre does not,
     * and every frame afterwards looks in the wrong place - so one blurred frame ends the
     * session, which is what "it loses the object even when it is barely moving" looks like
     * from the outside.
     *
     * Following the measured speed for a bounded number of frames covers the momentary
     * failures and then gives up exactly as before, with the centre left where the object was
     * last certainly seen.
     *
     * @return true when a path was followed, false when there is nothing to go on.
     */
    private fun coast(frame: Gray, now: Long, radius: Int): Boolean {
        val movement = motion ?: return false
        if (!movement.ready || misses >= MAX_COAST_FRAMES) return false

        val elapsed = (now - lastMatchAt).coerceIn(1L, TargetMotion.MAX_SAMPLE_GAP_MS)
        val step = movement.lead(elapsed)
        val limit = radius * LEAD_LIMIT_FRACTION
        val dx = step[0].coerceIn(-limit, limit)
        val dy = step[1].coerceIn(-limit, limit)
        if (dx == 0f && dy == 0f) return false

        lastX = (lastX + dx).roundToInt()
        lastY = (lastY + dy).roundToInt()
        misses++
        missedSinceCommand = true

        main.post {
            // The score here is the real one, from the frame that failed to match. What the
            // user needs to see is that the match is poor and the loop is carrying on
            // regardless - not a made-up figure for a position that was guessed.
            TrackingStatus.similarity = TemplateTracker.lastScore
            TrackingStatus.markerX = lastX
            TrackingStatus.markerY = lastY
        }

        steer(
            frameWidth = frame.width,
            frameHeight = frame.height,
            offsetX = lastX - frame.width / 2,
            offsetY = lastY - frame.height / 2,
            radius = radius,
            matched = false,
            now = now,
        )
        return true
    }

    /**
     * Says the object could not be found, and holds still.
     *
     * The search centre is deliberately left where the object was last certainly seen: moving
     * it to wherever a rejected match happened to land is exactly how a lost object turns into
     * a gimbal that wanders off following the scenery.
     */
    private fun reportLost() {
        main.post {
            // Published on every failing frame, not only at the moment the phase changes. The
            // number is the one piece of evidence about *how* badly it is failing - 8% is a
            // different problem from 44% - and freezing it at the transition threw that away,
            // which is how the readout came to sit at 0% and stay there.
            TrackingStatus.similarity = TemplateTracker.lastScore
            TrackingStatus.setPhase(TrackingPhase.LOST)
        }
    }

    /**
     * How far to turn to bring the object back to the middle, in degrees, or `null` when
     * there is nothing worth asking for.
     *
     * Two parts. The first is the error itself, sized by [AimCorrection] from what this
     * session has measured rather than from a fixed guess, so the step is expressed in pixels
     * the object will really move - and the arithmetic and the sign convention live there,
     * where they can be read on their own, because the two axes not sharing a sign is not
     * obvious and getting it wrong looks like a hardware fault.
     *
     * The second part is where the object is expected to have got to by the time that turn has
     * happened, which is what this loop was missing entirely: with only the first part, every
     * correction aims at where the object was [SETTLE_MS] ago, so the loop always trails it
     * and the faster the object moves the further behind it is.
     *
     * The lead is added here rather than inside [AimCorrection] deliberately, so that the dead
     * zone there still applies to the raw error - which is what stops the aim twitching at a
     * still object - while the lead gets its own bound as a share of the search radius, so a
     * wrong speed cannot fling the aim across the scene.
     */
    private fun correctionFor(
        offsetX: Int,
        offsetY: Int,
        frameWidth: Int,
        frameHeight: Int,
        radius: Int,
        current: TrackingSettings,
    ): FloatArray? {
        val base = AimCorrection.degrees(
            offsetX = offsetX.toFloat(),
            offsetY = offsetY.toFloat(),
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            horizontalDegreesPerPixel = horizontalGain.degreesPerPixel,
            verticalDegreesPerPixel = verticalGain.degreesPerPixel,
            maxPixelsPerStep = radius * STEP_RADIUS_FRACTION,
            damping = current.damping,
            invertX = current.invertX,
            invertY = current.invertY,
        )

        val lead = motion?.lead(SETTLE_MS)
        val limit = radius * LEAD_LIMIT_FRACTION
        val leadX = (lead?.get(0) ?: 0f).coerceIn(-limit, limit)
        val leadY = (lead?.get(1) ?: 0f).coerceIn(-limit, limit)
        if (base == null && leadX == 0f && leadY == 0f) return null

        // The same sign conventions AimCorrection works in: a positive yaw pans right, so the
        // error keeps its own sign, while a positive pitch tilts up, which is the opposite of
        // the way screen y runs.
        val invertX = if (current.invertX) -1f else 1f
        val invertY = if (current.invertY) -1f else 1f
        return floatArrayOf(
            (base?.get(0) ?: 0f) + leadX * horizontalGain.degreesPerPixel * invertX,
            (base?.get(1) ?: 0f) - leadY * verticalGain.degreesPerPixel * invertY,
        )
    }
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
