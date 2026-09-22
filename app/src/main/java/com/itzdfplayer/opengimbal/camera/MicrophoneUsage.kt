package com.itzdfplayer.opengimbal.camera

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.util.Log

/**
 * Asks the audio system whether anything holds the microphone.
 *
 * A camera app that records video keeps a recording session open, so a non-empty
 * list of active recording configurations is the signal that video - not a photo -
 * is being taken. Only the emptiness of the list is used; no audio is read, and the
 * per-app details are not even looked at.
 */
object MicrophoneUsage {

    private const val TAG = "OpenGimbal"

    /** One-shot read, for when the answer is needed right now. */
    fun read(context: Context): MicrophoneState {
        val manager = context.getSystemService(AudioManager::class.java)
            ?: return MicrophoneState.UNAVAILABLE
        return runCatching { stateOf(manager.activeRecordingConfigurations) }
            .getOrElse {
                Log.w(TAG, "Could not read the microphone state: ${it.message}")
                MicrophoneState.UNAVAILABLE
            }
    }

    /**
     * Pushes updates while they are wanted, so a status display does not have to
     * poll. Returns a function that stops them, or `null` when the platform refuses
     * to report recordings at all.
     *
     * The callback runs on the calling thread's looper, so call this from the main
     * thread when the result feeds UI state.
     */
    fun observe(context: Context, onState: (MicrophoneState) -> Unit): (() -> Unit)? {
        val manager = context.getSystemService(AudioManager::class.java) ?: return null

        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                onState(stateOf(configs))
            }
        }

        return try {
            manager.registerAudioRecordingCallback(callback, null)
            // The callback only fires on a change, so publish the current answer once.
            onState(stateOf(manager.activeRecordingConfigurations))
            stopWatching(manager, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the microphone: ${e.message}")
            null
        }
    }

    private fun stopWatching(
        manager: AudioManager,
        callback: AudioManager.AudioRecordingCallback,
    ): () -> Unit = { runCatching { manager.unregisterAudioRecordingCallback(callback) } }

    private fun stateOf(configs: List<AudioRecordingConfiguration>?): MicrophoneState =
        if (configs.isNullOrEmpty()) MicrophoneState.IDLE else MicrophoneState.IN_USE
}
