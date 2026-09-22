package com.itzdfplayer.opengimbal

import com.itzdfplayer.opengimbal.mapping.MappingStore
import com.itzdfplayer.opengimbal.mapping.storedNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Preferences are not typed, so a setting that changes from a whole number to a
 * fractional one leaves existing installs holding the old type. Reading one as a Float
 * then throws, which is exactly how the sweep-rate setting managed to crash the Control
 * screen on launch.
 */
class MappingStoreTest {

    @Test
    fun `a number stored as an Int is read as a Float`() {
        assertEquals(8f, storedNumber(8)!!, 0f)
        assertEquals(0f, storedNumber(0)!!, 0f)
        assertEquals(-3f, storedNumber(-3)!!, 0f)
    }

    @Test
    fun `the other boxed numbers come back too`() {
        assertEquals(2.5f, storedNumber(2.5f)!!, 0f)
        assertEquals(7f, storedNumber(7L)!!, 0f)
        assertEquals(3.5f, storedNumber(3.5)!!, 0f)
        assertEquals(12.5f, storedNumber("12.5")!!, 0f)
    }

    @Test
    fun `anything that is not a number is treated as missing`() {
        assertNull(storedNumber(null))
        assertNull(storedNumber(true))
        assertNull(storedNumber("nonsense"))
        assertNull(storedNumber(listOf(1)))
    }

    @Test
    fun `the sweep rate slider always lands on a preset`() {
        MappingStore.AIM_RATES.forEach { rate ->
            assertEquals(rate, MappingStore.nearestAimRate(rate), 0f)
        }
    }

    @Test
    fun `an out of range sweep rate snaps to the nearest end`() {
        assertEquals(MappingStore.AIM_RATES.first(), MappingStore.nearestAimRate(0.01f))
        assertEquals(MappingStore.AIM_RATES.last(), MappingStore.nearestAimRate(500f))
        assertEquals(8f, MappingStore.nearestAimRate(9f))
    }

    @Test
    fun `the presets are ordered and start well below the old fixed rate`() {
        assertEquals(
            "the list must be sorted for the slider to make sense",
            MappingStore.AIM_RATES.sorted(),
            MappingStore.AIM_RATES,
        )
        assertEquals(
            "the list must be distinct for the index lookup to work",
            MappingStore.AIM_RATES.distinct().size,
            MappingStore.AIM_RATES.size,
        )
    }
}
