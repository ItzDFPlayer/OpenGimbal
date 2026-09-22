package com.itzdfplayer.opengimbal.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.itzdfplayer.opengimbal.R

/** Top-level destinations shown in the adaptive navigation suite. */
enum class AppDestination(
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val icon: Int,
) {
    /** Connect the gimbal, enable the service, watch incoming input. */
    DEVICE(R.string.nav_gimbal, R.drawable.ic_gimbal),

    /** Configure what each button and the slider do. */
    MAPPINGS(R.string.nav_mappings, R.drawable.ic_tune),

    /** Steer the gimbal from the screen, in place of the physical knob. */
    CONTROL(R.string.nav_control, R.drawable.ic_gamepad),

    /** Preferences plus a live status summary. */
    SETTINGS(R.string.nav_settings, R.drawable.ic_settings),
}
