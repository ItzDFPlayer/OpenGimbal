package com.itzdfplayer.opengimbal

import com.itzdfplayer.opengimbal.mapping.GimbalAction
import com.itzdfplayer.opengimbal.mapping.GimbalTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GimbalMappingTest {

    @Test
    fun `the zoom slider is the only continuous trigger`() {
        val continuous = GimbalTrigger.entries.filter { it.continuous }

        assertEquals(listOf(GimbalTrigger.ZOOM_SLIDER), continuous)
    }

    @Test
    fun `continuous triggers only offer continuous actions`() {
        val options = GimbalAction.optionsFor(GimbalTrigger.ZOOM_SLIDER)

        assertEquals(listOf(GimbalAction.NONE, GimbalAction.PINCH_ZOOM, GimbalAction.VOLUME_STEP), options)
        assertTrue("pinch zoom must be offered", options.contains(GimbalAction.PINCH_ZOOM))
        assertTrue("volume stepping must be offered", options.contains(GimbalAction.VOLUME_STEP))
    }

    @Test
    fun `discrete triggers never offer continuous actions`() {
        GimbalTrigger.entries.filterNot { it.continuous }.forEach { trigger ->
            val options = GimbalAction.optionsFor(trigger)
            assertFalse(
                "${trigger.key} offered a continuous action",
                options.any { it.continuous },
            )
            assertTrue("${trigger.key} must offer NONE", options.contains(GimbalAction.NONE))
        }
    }

    @Test
    fun `trigger keys are unique so preferences cannot collide`() {
        val keys = GimbalTrigger.entries.map { it.key }

        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { assertTrue(GimbalTrigger.byKey(it) != null) }
    }

    @Test
    fun `actions round trip through their stored name`() {
        GimbalAction.entries.forEach { action ->
            assertEquals(action, GimbalAction.byName(action.name))
        }
        assertEquals(null, GimbalAction.byName("not_an_action"))
        assertEquals(null, GimbalAction.byName(null))
    }
}
