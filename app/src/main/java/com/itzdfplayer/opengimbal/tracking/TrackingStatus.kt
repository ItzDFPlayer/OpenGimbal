package com.itzdfplayer.opengimbal.tracking

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Where the tracking session has got to. */
enum class TrackingPhase {
    /** The overlay is switched off; no floating button. */
    OFF,

    /** The floating button is showing, waiting to be pressed. */
    IDLE,

    /** The floating button was pressed and the capture prompt is up. */
    CONNECTING,

    /** The screen is dimmed and the user is picking something to follow. */
    SELECTING,

    /** Following. */
    TRACKING,

    /** The patch could not be found in the last frame. The gimbal is holding still. */
    LOST,
}

/**
 * The one place the tracking feature keeps its state, following the pattern the rest of the
 * app already uses for camera and shutter status: whoever produces the state writes it,
 * whoever shows it reads it, and neither has to know about the other.
 *
 * The overlay lives in the accessibility service and the frames come from a foreground
 * service, so there is no single owner to hold this.
 */
object TrackingStatus {

    /**
     * Backed by private state rather than declared as a `var` with a private setter: Kotlin
     * still emits a JVM setter for a private-set `var`, which would clash with the
     * [setEnabled] function below.
     */
    private var enabledState by mutableStateOf(false)

    /** Whether the user has switched the floating button on. */
    val enabled: Boolean get() = enabledState

    private var phaseState by mutableStateOf(TrackingPhase.OFF)

    /** Where the session has got to. Written with [setPhase]. */
    val phase: TrackingPhase get() = phaseState

    /** Frames per second actually being analysed, so the capture rate can be seen. */
    var fps by mutableStateOf(0f)

    /** How well the patch matched in the last frame, 0..1. */
    var similarity by mutableStateOf(0f)

    /**
     * The size the object was matched at, where 1 is the size it was picked at.
     *
     * Worth showing because it is the one number that explains a whole class of loss: an
     * object walking towards the camera and outgrowing its template looks exactly like a
     * tracker that has simply given up.
     */
    var scale by mutableStateOf(1f)

    /**
     * Why the last attempt did not get going, if it did not, as a string resource.
     *
     * A resource id rather than the text itself because the writers (the tracker, the
     * capture service, the consent activity) have no business knowing the user's language,
     * and one of them has no Context at all.
     */
    @get:StringRes
    var messageRes: Int? by mutableStateOf(null)

    /**
     * Whether a camera app is holding the camera right now, which is also what decides
     * whether the floating button is on screen. Published here so the Control screen can
     * explain the button's absence instead of leaving it a mystery.
     */
    var cameraReady by mutableStateOf(false)

    /** Where the patch was last seen, in screen pixels, and how big it was. */
    var markerX by mutableStateOf(0)
    var markerY by mutableStateOf(0)
    var markerSize by mutableStateOf(0)

    /**
     * How far the object is from the middle of the screen, in pixels, positive right and
     * down. Shown while tracking so it is possible to see whether the loop is pulling the
     * object in or pushing it away - a wrong direction looks identical to a broken gimbal
     * from the outside, and this is the difference.
     */
    var offsetX by mutableStateOf(0)
    var offsetY by mutableStateOf(0)

    /** The last correction asked for, in degrees. */
    var correctionYaw by mutableStateOf(0f)
    var correctionPitch by mutableStateOf(0f)

    /**
     * What the session has learned about the camera: how far the picture moves for a degree
     * of turn. Starts as a guess and becomes a measurement, so seeing it move off the
     * starting value is the sign that the loop is learning rather than guessing.
     */
    var pixelsPerDegreeHorizontal by mutableStateOf(ServoGain.DEFAULT_PIXELS_PER_DEGREE)
    var pixelsPerDegreeVertical by mutableStateOf(ServoGain.DEFAULT_PIXELS_PER_DEGREE)

    /**
     * Corrections whose effect came out backwards - the picture moved opposite to the way
     * this axis is meant to move it. A steady count means the direction switches need
     * flipping, which is worth knowing precisely because the alternative explanation,
     * "the gimbal is broken", looks the same from the outside.
     */
    var reversedMeasurements by mutableStateOf(0)

    /**
     * Set by the overlay when the user taps the screen, read by the tracker on the next
     * frame. Volatile because the two are on different threads and this is a one-shot
     * handover rather than something either side watches.
     */
    @Volatile
    var pendingSelection: IntArray? = null

    fun setEnabled(value: Boolean) {
        enabledState = value
        if (!value) phaseState = TrackingPhase.OFF
    }

    fun setPhase(value: TrackingPhase) {
        if (phaseState != value) phaseState = value
    }

    fun clearMarker() {
        markerX = 0
        markerY = 0
        markerSize = 0
    }

    fun reset() {
        phaseState = if (enabledState) TrackingPhase.IDLE else TrackingPhase.OFF
        fps = 0f
        similarity = 0f
        scale = 1f
        offsetX = 0
        offsetY = 0
        correctionYaw = 0f
        correctionPitch = 0f
        pendingSelection = null
        clearMarker()
    }
}
