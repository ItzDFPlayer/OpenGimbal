package com.itzdfplayer.opengimbal

import com.itzdfplayer.opengimbal.gimbal.AutoConnectPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectPolicyTest {

    @Test
    fun `connects when exactly one gimbal is available`() {
        assertTrue(AutoConnectPolicy.shouldConnect(1, enabled = true, isConnected = false, isConnecting = false))
    }

    @Test
    fun `stays out of the way when there is a choice`() {
        assertFalse(AutoConnectPolicy.shouldConnect(2, enabled = true, isConnected = false, isConnecting = false))
        assertFalse(AutoConnectPolicy.shouldConnect(5, enabled = true, isConnected = false, isConnecting = false))
    }

    @Test
    fun `does nothing when there is no gimbal at all`() {
        assertFalse(AutoConnectPolicy.shouldConnect(0, enabled = true, isConnected = false, isConnecting = false))
    }

    @Test
    fun `respects the auto-connect setting`() {
        assertFalse(AutoConnectPolicy.shouldConnect(1, enabled = false, isConnected = false, isConnecting = false))
    }

    @Test
    fun `never interrupts an existing or pending link`() {
        assertFalse(AutoConnectPolicy.shouldConnect(1, enabled = true, isConnected = true, isConnecting = false))
        assertFalse(AutoConnectPolicy.shouldConnect(1, enabled = true, isConnected = false, isConnecting = true))
    }
}
