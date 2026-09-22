package com.itzdfplayer.opengimbal

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.itzdfplayer.opengimbal.accessibility.GimbalAccessibilityService
import com.itzdfplayer.opengimbal.ui.OpenGimbalApp
import com.itzdfplayer.opengimbal.ui.theme.OpenGimbalTheme

class MainActivity : ComponentActivity() {

    private val accessibilityEnabled = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        accessibilityEnabled.value = GimbalAccessibilityService.isEnabled(this)

        setContent {
            OpenGimbalTheme {
                OpenGimbalApp(
                    accessibilityEnabled = accessibilityEnabled.value,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have toggled the service while we were in Settings.
        accessibilityEnabled.value = GimbalAccessibilityService.isEnabled(this)
    }
}
