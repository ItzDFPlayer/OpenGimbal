package com.itzdfplayer.opengimbal.camera

import androidx.annotation.StringRes
import com.itzdfplayer.opengimbal.R

/**
 * What the audio system is willing to say about the microphone.
 *
 * [UNAVAILABLE] is a first-class state rather than an exception because reading this
 * needs no permission of its own but is still not guaranteed: if the platform
 * refuses, the app must say so rather than pretend nothing is recording.
 */
enum class MicrophoneState(@get:StringRes val labelRes: Int) {
    /** Nothing is recording. */
    IDLE(R.string.value_idle),

    /** At least one app holds the microphone. */
    IN_USE(R.string.value_in_use),

    /** The platform would not say. */
    UNAVAILABLE(R.string.microphone_not_readable),
}

/**
 * Whether the foreground camera app looks like it is recording video.
 *
 * The camera being held open is not enough on its own - it is open in photo mode too.
 * A camera app that records video also holds the microphone, so camera plus microphone
 * is the signal, and it needs no permission beyond the accessibility service already
 * having the camera gate.
 *
 * [MicrophoneState.UNAVAILABLE] deliberately does not count. Assuming "recording"
 * whenever the camera is open would stop and restart the video during a photo session
 * and take two pictures instead; assuming "not recording" merely leaves this one
 * feature idle, and the Settings screen says why.
 */
fun isRecordingVideo(cameraInUse: Boolean, microphone: MicrophoneState): Boolean =
    cameraInUse && microphone == MicrophoneState.IN_USE
