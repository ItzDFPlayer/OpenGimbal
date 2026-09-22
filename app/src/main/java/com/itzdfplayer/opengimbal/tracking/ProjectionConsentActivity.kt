package com.itzdfplayer.opengimbal.tracking

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.itzdfplayer.opengimbal.R

/**
 * Transparent, no-history activity whose only job is to ask for screen capture permission.
 *
 * `MediaProjection` consent can only be requested from an activity, and it cannot be kept:
 * from Android 14 a projection has to be re-consented for each session, so a fresh prompt is
 * the expected behaviour rather than a limitation of this app. That is why the prompt belongs
 * to pressing the floating button, where the user has just asked for something to happen.
 *
 * Nothing is drawn. The activity finishes as soon as the dialog is answered either way.
 */
class ProjectionConsentActivity : ComponentActivity() {

    companion object {
        private const val TAG = "OpenGimbal"

        /** Starts the consent flow, from anywhere with a context to launch an activity. */
        fun request(context: Context) {
            context.startActivity(
                Intent(context, ProjectionConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Log.w(TAG, "No media projection service on this device")
            TrackingStatus.messageRes = R.string.tracking_message_capture_unavailable
            TrackingStatus.reset()
            finish()
            return
        }

        TrackingStatus.setPhase(TrackingPhase.CONNECTING)
        resultLauncher.launch(manager.createScreenCaptureIntent())
    }

    private val resultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            TrackingService.start(this, result.resultCode, data)
        } else {
            // Refusing is an ordinary outcome, not an error worth shouting about.
            TrackingStatus.messageRes = R.string.tracking_message_capture_not_allowed
            TrackingStatus.reset()
        }
        finish()
    }
}
