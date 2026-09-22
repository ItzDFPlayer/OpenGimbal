package com.itzdfplayer.opengimbal

import com.itzdfplayer.opengimbal.camera.MicrophoneState
import com.itzdfplayer.opengimbal.camera.isRecordingVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Video detection is camera plus microphone, and the four combinations are the whole
 * of the rule - including the one that matters most, where the platform will not say.
 */
class VideoRecordingTest {

    @Test
    fun `camera plus microphone is a recording`() {
        assertTrue(isRecordingVideo(cameraInUse = true, microphone = MicrophoneState.IN_USE))
    }

    @Test
    fun `a camera app holding the camera for a photo is not a recording`() {
        assertFalse(isRecordingVideo(cameraInUse = true, microphone = MicrophoneState.IDLE))
    }

    @Test
    fun `the microphone alone is not a recording`() {
        assertFalse(isRecordingVideo(cameraInUse = false, microphone = MicrophoneState.IN_USE))
    }

    @Test
    fun `nothing in use is not a recording`() {
        assertFalse(isRecordingVideo(cameraInUse = false, microphone = MicrophoneState.IDLE))
    }

    @Test
    fun `an unreadable microphone never counts as recording`() {
        // Assuming "recording" here would stop and restart video during a photo session
        // and take two pictures instead, so the feature stays idle and Settings says why.
        assertFalse(isRecordingVideo(cameraInUse = true, microphone = MicrophoneState.UNAVAILABLE))
        assertFalse(isRecordingVideo(cameraInUse = false, microphone = MicrophoneState.UNAVAILABLE))
    }

    @Test
    fun `each state has its own label for the settings readout`() {
        // Compared as resources rather than as English, which is the point of the move to
        // strings.xml - and distinctness is what the readout actually depends on.
        assertEquals(R.string.value_idle, MicrophoneState.IDLE.labelRes)
        assertEquals(R.string.value_in_use, MicrophoneState.IN_USE.labelRes)
        assertEquals(R.string.microphone_not_readable, MicrophoneState.UNAVAILABLE.labelRes)

        val ids = MicrophoneState.entries.map { it.labelRes }
        assertEquals("two states sharing a label would be indistinguishable", ids.size, ids.distinct().size)
    }
}
