package com.itzdfplayer.opengimbal.camera

import android.content.Context
import android.hardware.camera2.CameraManager
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
        cameraInUse = availability.inUse
        unavailableIds = availability.unavailableIds
        knownCameraCount = availability.knownCount
    }
}
