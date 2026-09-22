package com.itzdfplayer.opengimbal.gimbal

/**
 * When to connect without asking.
 *
 * Only fires when the choice is unambiguous: exactly one supported gimbal is
 * visible, auto-connect is switched on, and the link is not already busy. With
 * two gimbals in range we stay out of the way and let the user pick.
 */
object AutoConnectPolicy {

    fun shouldConnect(
        availableGimbals: Int,
        enabled: Boolean,
        isConnected: Boolean,
        isConnecting: Boolean,
    ): Boolean = enabled &&
        !isConnected &&
        !isConnecting &&
        availableGimbals == 1
}
