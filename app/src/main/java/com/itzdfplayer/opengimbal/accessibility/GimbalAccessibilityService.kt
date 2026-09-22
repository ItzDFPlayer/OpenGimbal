package com.itzdfplayer.opengimbal.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.itzdfplayer.opengimbal.R
import com.itzdfplayer.opengimbal.camera.CameraUsageMonitor
import com.itzdfplayer.opengimbal.camera.MicrophoneState
import com.itzdfplayer.opengimbal.camera.MicrophoneUsage
import com.itzdfplayer.opengimbal.camera.OrientationWatcher
import com.itzdfplayer.opengimbal.camera.isRecordingVideo
import com.itzdfplayer.opengimbal.gimbal.AimState
import com.itzdfplayer.opengimbal.gimbal.GimbalManager
import com.itzdfplayer.opengimbal.mapping.GimbalAction
import com.itzdfplayer.opengimbal.mapping.GimbalTrigger
import com.itzdfplayer.opengimbal.mapping.MappingStore
import com.itzdfplayer.opengimbal.tracking.ProjectionConsentActivity
import com.itzdfplayer.opengimbal.tracking.TrackingOverlay
import com.itzdfplayer.opengimbal.tracking.TrackingPhase
import com.itzdfplayer.opengimbal.tracking.TrackingService
import com.itzdfplayer.opengimbal.tracking.TrackingStatus

/**
 * Turns gimbal events into system-wide input.
 *
 * Two mechanisms are used, because an accessibility service cannot inject
 * arbitrary key events:
 *
 *  * **Gestures** (`canPerformGestures` in the service config) for pinch-zoom,
 *    swipes and, via global actions, Back/Home/Recents. These reach whatever app
 *    is in the foreground.
 *  * **AudioManager** for volume. This changes the stream volume and shows the
 *    system UI, but note it does *not* synthesise a `KEYCODE_VOLUME_*` key event,
 *    so camera apps that treat the volume key as a shutter will not react to it.
 *    Real key injection needs a privileged backend such as Shizuku.
 */
class GimbalAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OpenGimbal"

        /** Roughly a frame; the next pinch is queued from the gesture callback. */
        private const val GESTURE_RETRY_MS = 16L
        private const val PINCH_DURATION_MS = 90L
        private const val SWIPE_DURATION_MS = 220L

        /**
         * How far from the screen edge a swipe starts and stops, as a share of the screen.
         *
         * The gesture stops short of the edges at both ends on purpose. A drag that reaches
         * the edge can be claimed before the app ever sees it - Android's own back gesture
         * lives in a strip down each side from Android 10 on - and an app with edge gestures
         * of its own will take it too. The margin is what keeps a swipe a swipe.
         *
         * Shared by all four directions so they cannot drift apart: they are the same gesture
         * on different axes, and one of them travelling further than the others is how you end
         * up with a direction that works and three that do not.
         */
        private const val SWIPE_MARGIN_FRACTION = 0.28f

        /** How fast the volume steps while the slider is held. */
        private const val VOLUME_STEP_INTERVAL_MS = 140L

        /**
         * How long to leave between stopping and restarting a recording after a flip.
         * The camera app has to finish the clip and free the encoder before it will
         * accept a start, and the gimbal is usually still settling at that point.
         */
        private const val FLIP_RESTART_DELAY_MS = 1_800L

        /**
         * How often the overlay windows are reconciled with [TrackingStatus].
         *
         * That state is Compose-backed and the overlay is not, so the two are synced by
         * polling rather than by pushing. No frame work happens here - the tracker has its
         * own thread - so a few times a second is plenty and costs nothing.
         */
        private const val TRACKING_TICK_MS = 150L

        /**
         * Whether the user has switched this service on in system settings.
         *
         * This reflects the system's own list, so it is correct even when our
         * process was not running when the service was enabled.
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, GimbalAccessibilityService::class.java)
            val enabled = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull() ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        @Volatile
        private var instance: GimbalAccessibilityService? = null

        /** The connected service, or null when it is not running. */
        fun instanceOrNull(): GimbalAccessibilityService? = instance
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    private val shutterFinder by lazy { ShutterFinder(this) }
    private val shutterCapture by lazy { ShutterCapture(this) }
    private val orientationWatcher by lazy { OrientationWatcher(this, ::onPhoneFlipped) }
    private val trackingOverlay by lazy { TrackingOverlay(this) }

    /** True while a flip has a stop/start sequence in flight. */
    private var restartingAfterFlip = false

    /** Kept current by a push callback rather than read on demand. */
    private var microphoneState = MicrophoneState.UNAVAILABLE
    private var stopMicrophoneWatch: (() -> Unit)? = null

    private var screenWidth = 0
    private var screenHeight = 0

    private var pinchActive = false
    private var pinchDirection = 0

    private var volumeActive = false
    private var volumeDirection = 0

    // ---- lifecycle --------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        measureScreen()

        GimbalManager.client(this).eventListener = ::onGimbalEvent
        // Needed headlessly, so the camera gate works with the app closed.
        CameraUsageMonitor.start(this)
        GimbalManager.reconnectLast(this)
        applyFlipWatch()
        applyTrackingOverlay()
        Log.i(TAG, "Accessibility service connected, screen ${screenWidth}x$screenHeight")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopContinuous()
        stopFlipWatch()
        stopTrackingOverlay()
        GimbalManager.client(this).eventListener = null
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopContinuous()
        stopFlipWatch()
        stopTrackingOverlay()
        shutterCapture.shutdown()
        instance = null
        super.onDestroy()
    }

    private fun stopFlipWatch() {
        orientationWatcher.stop()
        stopMicrophoneWatch?.invoke()
        stopMicrophoneWatch = null
        microphoneState = MicrophoneState.UNAVAILABLE
    }

    override fun onInterrupt() {
        stopContinuous()
    }

    // ---- the tracking overlay ---------------------------------------------

    /**
     * Starts or stops the floating button, to match the setting.
     *
     * Called when the service connects and whenever the switch on the Control screen is
     * moved, the same shape as [applyFlipWatch]. All three tracking windows belong to this
     * service rather than to the capture service, because an accessibility service is
     * allowed to draw over other apps without the user granting a separate permission -
     * and that is the only reason the floating button works at all.
     */
    fun applyTrackingOverlay() {
        if (!MappingStore.trackingOverlay(this) || screenWidth == 0) {
            stopTrackingOverlay()
            return
        }

        TrackingStatus.setEnabled(true)
        if (TrackingStatus.phase == TrackingPhase.OFF) {
            TrackingStatus.setPhase(TrackingPhase.IDLE)
        }

        trackingOverlay.setScreenSize(screenWidth, screenHeight)
        trackingOverlay.onButton = ::onTrackingButton
        // Deliberately not showing the button here. Whether it belongs on screen depends on
        // whether a camera app is running, which changes minute to minute, so the poll below
        // is the only thing that decides - one place, one answer.
        trackingOverlayActive = true
        main.removeCallbacks(trackingTick)
        main.post(trackingTick)
    }

    private fun stopTrackingOverlay() {
        trackingOverlayActive = false
        main.removeCallbacks(trackingTick)
        trackingOverlay.hideAll()
        endTrackingSession()
        TrackingStatus.setEnabled(false)
        TrackingStatus.reset()
    }

    /**
     * The floating button, which does something different depending on what is already
     * going on: start a session, or end the one in progress.
     */
    private fun onTrackingButton() {
        when (TrackingStatus.phase) {
            // The consent dialog is already up; ignore repeated taps.
            TrackingPhase.CONNECTING -> Unit

            // Any live session ends here, including a lost tracking lock.
            TrackingPhase.SELECTING,
            TrackingPhase.TRACKING,
            TrackingPhase.LOST,
            -> {
                endTrackingSession()
                TrackingStatus.reset()
            }

            TrackingPhase.IDLE, TrackingPhase.OFF -> {
                // Tracking a camera app is the whole point: if one is not in front there is
                // nothing worth following, so say so rather than capturing the screen.
                if (!CameraUsageMonitor.cameraInUse) {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_no_camera_app),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                ProjectionConsentActivity.request(this)
            }
        }
    }

    /** Stops the capture service, if there is a session to stop. */
    private fun endTrackingSession() {
        when (TrackingStatus.phase) {
            TrackingPhase.CONNECTING,
            TrackingPhase.SELECTING,
            TrackingPhase.TRACKING,
            TrackingPhase.LOST,
            -> TrackingService.stop(this)

            else -> Unit
        }
    }

    /**
     * Reconciles the windows with the phase. Polled rather than observed: the state is
     * Compose-backed and the overlay is plain views, so there is nothing to collect.
     */
    private val trackingTick = object : Runnable {
        override fun run() {
            syncTrackingWindows()
            main.postDelayed(this, TRACKING_TICK_MS)
        }
    }

    /**
     * True while the overlay feature is switched on, whether or not a button is showing.
     *
     * Distinct from whether a window exists, and the distinction is not cosmetic: the poll
     * below hides the button whenever a camera app is not running, so using "is a window on
     * screen" as the reason to keep polling would stop polling at the first moment the
     * button went away - and it would then never come back when the camera app opened.
     */
    private var trackingOverlayActive = false

    /**
     * Whether the floating button belongs on screen at all.
     *
     * Only over a camera app. Without one there is nothing worth following, and a button
     * sitting on top of everything all day is exactly the kind of thing the user switches
     * off and never switches back on. An active session keeps its button regardless, because
     * that button is how the session gets stopped.
     */
    private val floatingButtonWanted: Boolean
        get() = TrackingStatus.enabled && (
            CameraUsageMonitor.cameraInUse ||
                TrackingStatus.phase == TrackingPhase.CONNECTING ||
                TrackingStatus.phase == TrackingPhase.SELECTING ||
                TrackingStatus.phase == TrackingPhase.TRACKING ||
                TrackingStatus.phase == TrackingPhase.LOST
            )

    private fun syncTrackingWindows() {
        if (!trackingOverlayActive) return

        // Published so the Control screen can say why the button is not there.
        TrackingStatus.cameraReady = CameraUsageMonitor.cameraInUse

        val wanted = floatingButtonWanted
        if (wanted) trackingOverlay.showButton() else trackingOverlay.hideButton()

        when (TrackingStatus.phase) {
            TrackingPhase.CONNECTING -> {
                trackingOverlay.hideSelection()
                trackingOverlay.hideMarker()
                trackingOverlay.updateChip(
                    getString(R.string.overlay_chip_waiting_permission)
                )
            }

            TrackingPhase.SELECTING -> {
                trackingOverlay.hideMarker()
                trackingOverlay.showSelection()
                trackingOverlay.updateChip(null)
            }

            TrackingPhase.TRACKING -> {
                trackingOverlay.hideSelection()
                trackingOverlay.updateMarker(
                    TrackingStatus.markerX,
                    TrackingStatus.markerY,
                    TrackingStatus.markerSize,
                )
                val similarity = (TrackingStatus.similarity * 100f).toInt()
                trackingOverlay.updateChip(
                    getString(
                        R.string.overlay_chip_following,
                        "%.0f".format(TrackingStatus.fps),
                        similarity.toString(),
                    )
                )
            }

            TrackingPhase.LOST -> {
                trackingOverlay.hideSelection()
                // The square goes too. Leaving it sitting on whatever the last match was is
                // worse than useless: it marks a place nothing is being followed at, and it
                // is exactly the evidence someone would use to conclude the feature is still
                // working when it is not.
                trackingOverlay.hideMarker()
                val similarity = (TrackingStatus.similarity * 100f).toInt()
                trackingOverlay.updateChip(
                    getString(R.string.overlay_chip_lost, similarity.toString())
                )
            }

            TrackingPhase.IDLE, TrackingPhase.OFF -> {
                trackingOverlay.hideSelection()
                trackingOverlay.hideMarker()
                // No chip either while the button is away: a line of text floating over
                // whatever app is in front is more intrusive than the button it explains.
                trackingOverlay.updateChip(
                    if (wanted) {
                        TrackingStatus.messageRes?.let { getString(it) }
                            ?: getString(R.string.overlay_chip_idle)
                    } else {
                        null
                    }
                )
            }
        }
    }

    /** We are not interested in UI events, only in `canPerformGestures`. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    // ---- event plumbing ---------------------------------------------------

    private fun onGimbalEvent(trigger: GimbalTrigger, value: Int) {
        // Notifications arrive on the GATT thread; gesture dispatch belongs on main.
        main.post {
            // The gimbal snaps itself back to level when the trigger is pressed twice. That
            // happens in the hardware, so it is not something this app can intercept - only
            // react to. Done before the mapping gate below, because the gimbal has moved
            // whether or not this app is allowed to act on the press.
            if (isRecentreRequest(trigger)) onGimbalRecentred()

            if (!mappingAllowed()) {
                // A camera app may have closed mid-gesture; do not leave it running.
                stopContinuous()
                return@post
            }
            if (trigger.continuous) {
                handleContinuous(trigger, value)
            } else {
                performDiscrete(trigger)
            }
        }
    }

    /**
     * Whether this event is the user pressing the trigger twice.
     *
     * The gimbal reports its own double click as a distinct value rather than two single
     * clicks, so there is nothing to count here - the hardware already did it.
     */
    private fun isRecentreRequest(trigger: GimbalTrigger): Boolean =
        trigger == GimbalTrigger.TRIGGER_DOUBLE

    /**
     * Housekeeping for the gimbal levelling itself.
     *
     * Three things stop being true at that moment. A tracking session is now steering
     * towards something the gimbal is no longer pointing at, so it has to end rather than
     * fight the recentring. The windows marking that session have to go with it - taken down
     * here rather than left to the next poll, so the square disappears as the trigger is
     * pressed rather than a fraction of a second later. And the app's idea of the aim, which
     * the stick and the tracker both move, describes an angle the gimbal is no longer at, so
     * the next stick press would yank it back to where the app still thinks it is.
     */
    private fun onGimbalRecentred() {
        endTrackingSession()
        TrackingStatus.reset()
        AimState.level()
        trackingOverlay.hideSelection()
        trackingOverlay.hideMarker()
        trackingOverlay.updateChip(null)
    }

    /**
     * Mappings can optionally be limited to camera apps. The camera being held by
     * another app is the signal, because this app never opens one itself.
     */
    private fun mappingAllowed(): Boolean =
        !MappingStore.cameraOnly(this) || CameraUsageMonitor.cameraInUse

    private fun handleContinuous(trigger: GimbalTrigger, value: Int) {
        when (MappingStore.action(this, trigger)) {
            GimbalAction.PINCH_ZOOM -> {
                stopVolume()
                if (value == 0) stopPinch() else startPinch(if (value > 0) 1 else -1)
            }

            GimbalAction.VOLUME_STEP -> {
                stopPinch()
                if (value == 0) stopVolume() else startVolume(if (value > 0) 1 else -1)
            }

            else -> {
                stopPinch()
                stopVolume()
            }
        }
    }

    private fun performDiscrete(trigger: GimbalTrigger) {
        val action = MappingStore.action(this, trigger)
        Log.d(TAG, "Trigger ${trigger.key} -> $action")
        when (action) {
            GimbalAction.SCREEN_SHUTTER -> pressOnScreenShutter()
            GimbalAction.VOLUME_UP -> stepVolume(1)
            GimbalAction.VOLUME_DOWN -> stepVolume(-1)
            GimbalAction.PLAY_PAUSE -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            GimbalAction.MEDIA_NEXT -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            GimbalAction.MEDIA_PREVIOUS -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            GimbalAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GimbalAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GimbalAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GimbalAction.SWIPE_UP -> dispatchSwipe(Swipe.UP)
            GimbalAction.SWIPE_DOWN -> dispatchSwipe(Swipe.DOWN)
            GimbalAction.SWIPE_LEFT -> dispatchSwipe(Swipe.LEFT)
            GimbalAction.SWIPE_RIGHT -> dispatchSwipe(Swipe.RIGHT)
            else -> Unit
        }
    }

    private fun stopContinuous() {
        stopPinch()
        stopVolume()
    }

    // ---- video recording across a flip ------------------------------------

    /**
     * Starts or stops watching for a flip, to match the setting.
     *
     * Called when the service connects and again whenever the switch in Settings is
     * moved, so the sensor and the audio callback only run while the feature is
     * actually wanted.
     */
    fun applyFlipWatch() {
        if (!MappingStore.flipRestart(this)) {
            orientationWatcher.stop()
            stopMicrophoneWatch?.invoke()
            stopMicrophoneWatch = null
            microphoneState = MicrophoneState.UNAVAILABLE
            return
        }

        if (stopMicrophoneWatch == null) {
            stopMicrophoneWatch = MicrophoneUsage.observe(this) { state ->
                val starting = state == MicrophoneState.IN_USE &&
                    microphoneState != MicrophoneState.IN_USE
                microphoneState = state
                // Whichever app started the recording, the phone's orientation at that
                // moment is what "the right way up" means for the clip.
                if (starting) orientationWatcher.rearm()
            }
            if (stopMicrophoneWatch == null) {
                Log.w(TAG, "This device will not report whether the microphone is in use")
            }
        }

        if (!orientationWatcher.supported) {
            Log.w(TAG, "No accelerometer, so a flip cannot be detected")
            return
        }
        if (!orientationWatcher.start()) {
            Log.w(TAG, "Could not listen for orientation changes")
        }
    }

    /**
     * The gimbal has turned the phone over while a video was being recorded, so the
     * rest of the clip would be upside down. Ends the clip and starts a new one.
     *
     * Runs on the main thread: [android.hardware.SensorManager] delivers to the
     * registering looper, and the shutter press needs the main thread anyway.
     */
    private fun onPhoneFlipped() {
        if (!MappingStore.flipRestart(this)) return
        if (restartingAfterFlip) return

        // A camera app holding the camera is not enough - it is open for photos too.
        if (!isRecordingVideo(CameraUsageMonitor.cameraInUse, microphoneState)) return

        restartingAfterFlip = true
        Log.i(TAG, "Phone flipped while recording, restarting the video")
        ShutterStatus.report(getString(R.string.shutter_report_flipped))

        pressOnScreenShutter()
        main.postDelayed(
            {
                restartingAfterFlip = false
                // Deliberately not re-checking the microphone: stopping the clip just
                // turned it off, which is exactly the state we are undoing.
                if (MappingStore.flipRestart(this) && CameraUsageMonitor.cameraInUse) {
                    pressOnScreenShutter()
                }
            },
            FLIP_RESTART_DELAY_MS,
        )
    }

    /**
     * Presses the camera app's own shutter.
     *
     * Three routes are tried in order of how much they can be trusted:
     *
     *  1. a node that describes itself as a shutter - exact, and clickable;
     *  2. the pixels of a screenshot - the only way in when an app draws its whole
     *     interface on a surface and exposes no nodes at all;
     *  3. a node that merely has the shape and position of a shutter.
     *
     * The outcome is recorded rather than shown, because this runs while another app
     * is in the foreground - Settings is the only sensible place to read it back.
     */
    private fun pressOnScreenShutter() {
        // A press starts or ends a recording, so this is a good moment to re-take the
        // flip reference even when the microphone cannot be read at all. It also stops
        // ordinary handling of the phone from looking like a flip.
        orientationWatcher.rearm()

        val named = shutterFinder.find(screenWidth, screenHeight, requireShutterName = true)
        if (named != null && shutterFinder.press(named)) {
            val suffix = named.label?.let { getString(R.string.shutter_report_named_suffix, it) } ?: ""
            ShutterStatus.report(
                getString(
                    R.string.shutter_report_pressed_named,
                    named.centerX,
                    named.centerY,
                    suffix,
                )
            )
            return
        }

        // Nothing named it, or it would not take a press: look at the screen itself.
        if (shutterCapture.supported) {
            shutterCapture.capture { shutter -> main.post { onCapturedShutter(shutter) } }
            return
        }

        pressByShape()
    }

    /** Result of the screenshot route, back on the main thread. */
    private fun onCapturedShutter(shutter: PixelShutter?) {
        if (shutter != null) {
            val what = getString(
                R.string.shutter_report_pixel_description,
                getString(shutter.colourRes),
                shutter.width,
                shutter.height,
                "%.1f".format(shutter.score),
            )
            val pressed = shutterFinder.tap(shutter.centerX, shutter.centerY)
            ShutterStatus.report(
                if (pressed) {
                    getString(
                        R.string.shutter_report_pressed_pixels,
                        what,
                        shutter.centerX,
                        shutter.centerY,
                    )
                } else {
                    getString(
                        R.string.shutter_report_pixels_untappable,
                        what,
                        shutter.centerX,
                        shutter.centerY,
                    )
                }
            )
            return
        }

        pressByShape()
    }

    /** Last resort: whatever the tree has that is in the right place and the right shape. */
    private fun pressByShape() {        val shutter = shutterFinder.find(screenWidth, screenHeight)
        if (shutter == null) {
            ShutterStatus.report(getString(R.string.shutter_report_none))
            return
        }

        val pressed = shutterFinder.press(shutter)
        val score = "%.1f".format(shutter.score)
        ShutterStatus.report(
            if (pressed) {
                val suffix = shutter.label
                    ?.let { getString(R.string.shutter_report_shape_suffix, it) }
                    ?: ""
                getString(
                    R.string.shutter_report_pressed_by_shape,
                    shutter.centerX,
                    shutter.centerY,
                    score,
                    suffix,
                )
            } else {
                getString(
                    R.string.shutter_report_shape_unpressable,
                    shutter.centerX,
                    shutter.centerY,
                    score,
                )
            }
        )
    }

    // ---- continuous pinch zoom -------------------------------------------

    private fun startPinch(direction: Int) {
        if (pinchActive && pinchDirection == direction) return
        stopPinch()
        pinchDirection = direction
        pinchActive = true
        dispatchPinch()
    }

    private fun stopPinch() {
        pinchActive = false
        pinchDirection = 0
        main.removeCallbacks(pinchRetry)
    }

    private val pinchRetry = Runnable { dispatchPinch() }

    /**
     * Queues one pinch. The next one is only queued once this one finishes, which
     * keeps the zoom smooth instead of stuttering against a busy gesture queue.
     */
    private fun dispatchPinch() {
        if (!pinchActive) return
        val gesture = buildPinchGesture(pinchDirection)
        if (gesture == null || !dispatchGesture(gesture, gestureCallback, null)) {
            main.postDelayed(pinchRetry, GESTURE_RETRY_MS)
        }
    }

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) = dispatchPinch()

        override fun onCancelled(gestureDescription: GestureDescription?) = dispatchPinch()
    }

    /**
     * Two fingers moving apart (zoom in) or together (zoom out).
     *
     * Coordinates are clamped to the display, so an extreme strength setting can
     * never produce an out-of-bounds gesture that the system would reject.
     */
    private fun buildPinchGesture(direction: Int): GestureDescription? {
        val width = screenWidth.toFloat()
        val height = screenHeight.toFloat()
        if (width <= 0f || height <= 0f) return null

        val strength = MappingStore.pinchStrength(this) / 100f
        val centreX = width / 2f
        val centreY = height * 0.45f
        val spread = minOf(width, height) * 0.18f
        val travel = spread * 0.6f * strength
        // Zoom in means the fingers move apart.
        val sign = if (direction > 0) 1f else -1f

        fun clamped(x: Float) = x.coerceIn(8f, width - 8f)

        val left = Path().apply {
            moveTo(clamped(centreX - spread), centreY)
            lineTo(clamped(centreX - spread - travel * sign), centreY)
        }
        val right = Path().apply {
            moveTo(clamped(centreX + spread), centreY)
            lineTo(clamped(centreX + spread + travel * sign), centreY)
        }

        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(left, 0L, PINCH_DURATION_MS))
            .addStroke(GestureDescription.StrokeDescription(right, 0L, PINCH_DURATION_MS))
            .build()
    }

    // ---- continuous volume -----------------------------------------------

    private fun startVolume(direction: Int) {
        if (volumeActive && volumeDirection == direction) return
        stopVolume()
        volumeDirection = direction
        volumeActive = true
        main.post(volumeRunnable)
    }

    private fun stopVolume() {
        volumeActive = false
        volumeDirection = 0
        main.removeCallbacks(volumeRunnable)
    }

    private val volumeRunnable = object : Runnable {
        override fun run() {
            if (!volumeActive) return
            stepVolume(volumeDirection)
            main.postDelayed(this, VOLUME_STEP_INTERVAL_MS)
        }
    }

    private fun stepVolume(direction: Int) {
        runCatching {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
        }.onFailure { Log.w(TAG, "Volume adjust failed: ${it.message}") }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }.onFailure { Log.w(TAG, "Media key failed: ${it.message}") }
    }

    // ---- discrete gestures ------------------------------------------------

    /** Which way a swipe travels. */
    private enum class Swipe { UP, DOWN, LEFT, RIGHT }

    /**
     * One finger dragging across the screen, centred on it.
     *
     * Each swipe runs through the middle of the screen - along the middle column for the
     * vertical pair, along the middle row for the horizontal pair - and stops one margin short
     * of each edge. Between them that leaves the gesture well clear of any edge zone, and
     * centred, which is what makes it read as a plain swipe rather than as a gesture towards
     * something.
     *
     * Only one gesture can be in flight at a time, which `dispatchGesture` enforces - the
     * caller gets false and the attempt is dropped rather than queued.
     */
    private fun dispatchSwipe(direction: Swipe) {
        val width = screenWidth.toFloat()
        val height = screenHeight.toFloat()
        if (width <= 0f || height <= 0f) return

        val centreX = width / 2f
        val centreY = height / 2f
        val near = SWIPE_MARGIN_FRACTION
        val far = 1f - SWIPE_MARGIN_FRACTION
        val path = Path().apply {
            when (direction) {
                Swipe.UP -> {
                    moveTo(centreX, height * far)
                    lineTo(centreX, height * near)
                }

                Swipe.DOWN -> {
                    moveTo(centreX, height * near)
                    lineTo(centreX, height * far)
                }

                Swipe.LEFT -> {
                    moveTo(width * far, centreY)
                    lineTo(width * near, centreY)
                }

                Swipe.RIGHT -> {
                    moveTo(width * near, centreY)
                    lineTo(width * far, centreY)
                }
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS))
            .build()
        if (!dispatchGesture(gesture, null, null)) {
            Log.d(TAG, "Swipe rejected, a gesture is already running")
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun measureScreen() {
        val metrics = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val manager = getSystemService(WindowManager::class.java)
                val bounds = manager?.currentWindowMetrics?.bounds
                if (bounds != null) bounds.width() to bounds.height() else null
            } else {
                null
            }
        }.getOrNull()

        if (metrics != null) {
            screenWidth = metrics.first
            screenHeight = metrics.second
            return
        }

        @Suppress("DEPRECATION")
        val legacy = resources.displayMetrics
        screenWidth = legacy.widthPixels
        screenHeight = legacy.heightPixels
    }
}
