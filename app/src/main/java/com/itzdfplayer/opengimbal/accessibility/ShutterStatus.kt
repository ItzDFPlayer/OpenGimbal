package com.itzdfplayer.opengimbal.accessibility

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Outcome of the most recent on-screen shutter attempt.
 *
 * Shown on the Settings screen rather than as a toast: the attempt happens while a
 * camera app is in the foreground, so there is nowhere useful to display it at the
 * time. Trigger it from the camera app, then come back and read the result.
 */
object ShutterStatus {

    var lastReport by mutableStateOf<String?>(null)
        private set

    fun report(text: String) {
        lastReport = text
    }

    fun clear() {
        lastReport = null
    }
}
