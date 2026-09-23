package com.itzdfplayer.opengimbal.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Watches camera availability so mappings can be limited to camera apps.
 *
 * Registering the callback needs no permission and does not open a camera, so it
 * is safe to keep running for the lifetime of the process. Started by the
 * accessibility service (so the gate works headlessly) and by the Settings screen
 * (so the current state can be shown while configuring).
 */
object CameraUsageMonitor {

    private const val TAG = "OpenGimbal"

    private val availability = CameraAvailability()
    private var registered = false

    /** Compose-observable mirror of [CameraAvailability.inUse]. */
    var cameraInUse by mutableStateOf(false)
        private set

    var unavailableIds by mutableStateOf<List<String>>(emptyList())
        private set

    var knownCameraCount by mutableStateOf(0)
        private set

    /** False when the platform refused to register the callback. */
    var watching by mutableStateOf(false)
        private set

    /**
     * Whether the platform is describing any camera at all.
     *
     * False before the first callback arrives, and false if the registration was refused.
     * The distinction matters because "no camera is in use" means nothing when this is false -
     * it is not evidence that the user is not in a camera app, it is the absence of any
     * evidence either way. Anything that hides itself on "no camera is in use" has to fall
     * back to showing when this is false, or it hides for good on the strength of a fact it
     * never had.
     */
    val reported: Boolean get() = watching && knownCameraCount > 0

    /**
     * When a camera was last seen held by another app, or 0 if it never has been.
     *
     * Kept because the state cannot be observed from the app itself: looking at these rows
     * means leaving the camera app, which is exactly what releases the camera. So the useful
     * question afterwards is not "is a camera in use now" - it never is - but "was one, a
     * moment ago", and that is what this answers.
     */
    var lastInUseAt by mutableStateOf(0L)
        private set

    fun start(context: Context) {
        if (registered) return
        val manager = context.getSystemService(CameraManager::class.java) ?: return

        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                availability.onAvailable(cameraId)
                publish()
            }

            override fun onCameraUnavailable(cameraId: String) {
                availability.onUnavailable(cameraId)
                publish()
            }
        }

        try {
            // Deliver on the main executor so the observable state is safe to read
            // from any thread without extra synchronisation.
            manager.registerAvailabilityCallback(ContextCompat.getMainExecutor(context), callback)
            registered = true
            watching = true
            publish()
            Log.i(TAG, "Watching camera availability")
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch camera availability: ${e.message}")
        }
    }

    private fun publish() {
        val wasInUse = cameraInUse
        cameraInUse = availability.inUse
        unavailableIds = availability.unavailableIds
        knownCameraCount = availability.knownCount
        if (cameraInUse) lastInUseAt = SystemClock.uptimeMillis()

        if (wasInUse != cameraInUse) {
            // One line each time it changes, because this single flag decides both the
            // "only while a camera app is running" gate and whether the floating button
            // exists at all - and when that goes wrong there is nothing on screen to say
            // which half of it failed.
            Log.i(
                TAG,
                "Camera in use: $cameraInUse (held=${availability.unavailableIds}, " +
                    "known=${availability.knownCount})",
            )
        }
    }
}
