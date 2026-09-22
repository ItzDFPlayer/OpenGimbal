package com.itzdfplayer.opengimbal

import com.itzdfplayer.opengimbal.camera.CameraAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind "apply mappings only while a camera app is running".
 */
class CameraAvailabilityTest {

    @Test
    fun `starts with the camera free`() {
        val availability = CameraAvailability()

        assertFalse(availability.inUse)
        assertTrue(availability.unavailableIds.isEmpty())
    }

    @Test
    fun `a camera becoming unavailable means another app has it`() {
        val availability = CameraAvailability()

        availability.onUnavailable("0")

        assertTrue(availability.inUse)
        assertEquals(listOf("0"), availability.unavailableIds)
    }

    @Test
    fun `handing the camera back clears the flag`() {
        val availability = CameraAvailability()

        availability.onUnavailable("0")
        availability.onAvailable("0")

        assertFalse(availability.inUse)
    }

    @Test
    fun `one busy camera is enough even when others are free`() {
        val availability = CameraAvailability()

        availability.onAvailable("0")
        availability.onAvailable("1")
        availability.onUnavailable("1")

        assertTrue(availability.inUse)
        assertEquals(listOf("1"), availability.unavailableIds)
    }

    @Test
    fun `ids are tracked once and counted for diagnostics`() {
        val availability = CameraAvailability()

        availability.onAvailable("0")
        availability.onAvailable("1")
        availability.onAvailable("1")
        availability.onUnavailable("2")

        assertEquals(3, availability.knownCount)
        assertEquals(listOf("2"), availability.unavailableIds)
    }

    @Test
    fun `a camera reported available then unavailable is seen as busy`() {
        val availability = CameraAvailability()

        // Registration usually reports the current state first.
        availability.onAvailable("0")
        availability.onAvailable("1")

        assertFalse(availability.inUse)

        availability.onUnavailable("0")

        assertTrue(availability.inUse)
    }

    @Test
    fun `reset clears everything`() {
        val availability = CameraAvailability()

        availability.onUnavailable("0")
        availability.reset()

        assertFalse(availability.inUse)
        assertEquals(0, availability.knownCount)
    }
}
