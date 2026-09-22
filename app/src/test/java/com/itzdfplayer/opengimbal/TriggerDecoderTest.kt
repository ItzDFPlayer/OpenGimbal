package com.itzdfplayer.opengimbal

import com.itzdfplayer.opengimbal.gimbal.TriggerDecoder
import com.itzdfplayer.opengimbal.gimbal.TriggerEvent
import com.itzdfplayer.opengimbal.gimbal.TriggerTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the trigger sequence measured on a Proove Axis-M01:
 *
 * ```
 * 15   press marker
 * 0    brief idle gap, present on every press
 * 1/2  click count
 * 15   long press ("still holding")
 * ```
 *
 * The idle gap is the subtle part — treating it as a release is what previously
 * made the hold invisible.
 */
class TriggerDecoderTest {

    private fun decoder() = TriggerDecoder(maxHoldMs = 2_000)

    private fun List<TriggerTransition>.events() = map { it.event }

    /** The full shape of one press: idle, `15`, the brief gap, then the outcome. */
    private fun pressEndingWith(
        decoder: TriggerDecoder,
        outcome: Int,
    ): List<TriggerTransition> {
        decoder.onState(TriggerDecoder.IDLE, 0)
        decoder.onState(TriggerDecoder.PRESS, 40)
        decoder.onState(TriggerDecoder.IDLE, 70)
        return decoder.onState(outcome, 140)
    }

    @Test
    fun `the leading 15 is reported as a press`() {
        val decoder = decoder()

        assertEquals(
            listOf(TriggerEvent.PRESS_DOWN),
            decoder.onState(TriggerDecoder.PRESS, 40).events(),
        )
        assertTrue(decoder.isPressed)
    }

    @Test
    fun `single click is 15 gap 1`() {
        val decoder = decoder()

        assertEquals(
            listOf(TriggerEvent.SINGLE_CLICK),
            pressEndingWith(decoder, TriggerDecoder.SINGLE).events(),
        )
        assertFalse(decoder.isPressed)
    }

    @Test
    fun `double click is 15 gap 2`() {
        val decoder = decoder()

        assertEquals(
            listOf(TriggerEvent.DOUBLE_CLICK),
            pressEndingWith(decoder, TriggerDecoder.DOUBLE).events(),
        )
    }

    /** The case that was being missed. */
    @Test
    fun `long press is 15 gap 15`() {
        val decoder = decoder()

        decoder.onState(TriggerDecoder.IDLE, 0)
        decoder.onState(TriggerDecoder.PRESS, 40)
        decoder.onState(TriggerDecoder.IDLE, 70)

        val hold = decoder.onState(TriggerDecoder.PRESS, 900)

        assertEquals(listOf(TriggerEvent.LONG_PRESS), hold.events())
        assertEquals(860L, hold.single().holdMs)
    }

    @Test
    fun `the idle gap does not end the press`() {
        val decoder = decoder()

        decoder.onState(TriggerDecoder.PRESS, 0)
        decoder.onState(TriggerDecoder.IDLE, 20)

        assertTrue("a brief 0 must not look like a release", decoder.isPressed)
    }

    @Test
    fun `repeated idle packets keep the press alive`() {
        val decoder = decoder()

        decoder.onState(TriggerDecoder.PRESS, 0)
        for (t in 20..300 step 20) {
            decoder.onState(TriggerDecoder.IDLE, t.toLong())
        }

        assertTrue(decoder.isPressed)
        assertEquals(
            listOf(TriggerEvent.LONG_PRESS),
            decoder.onState(TriggerDecoder.PRESS, 800).events(),
        )
    }

    @Test
    fun `a repeated 15 with no gap is not a hold`() {
        val decoder = decoder()

        decoder.onState(TriggerDecoder.PRESS, 0)
        var fired = 0
        for (t in 20..500 step 20) {
            fired += decoder.onState(TriggerDecoder.PRESS, t.toLong()).size
        }

        assertEquals(0, fired)
    }

    @Test
    fun `a hold is reported only once`() {
        val decoder = decoder()

        decoder.onState(TriggerDecoder.PRESS, 0)
        decoder.onState(TriggerDecoder.IDLE, 20)
        assertEquals(
            listOf(TriggerEvent.LONG_PRESS),
            decoder.onState(TriggerDecoder.PRESS, 800).events(),
        )

        assertTrue(decoder.onState(TriggerDecoder.PRESS, 850).isEmpty())
        assertTrue(decoder.onState(TriggerDecoder.IDLE, 900).isEmpty())
    }

    @Test
    fun `a new press after a completed hold is detected`() {
        val decoder = decoder()

        decoder.onState(TriggerDecoder.PRESS, 0)
        decoder.onState(TriggerDecoder.IDLE, 20)
        decoder.onState(TriggerDecoder.PRESS, 800) // hold
        decoder.onState(TriggerDecoder.IDLE, 1_200) // release

        assertEquals(
            listOf(TriggerEvent.PRESS_DOWN),
            decoder.onState(TriggerDecoder.PRESS, 1_600).events(),
        )
    }

    @Test
    fun `two clicks in a row both register`() {
        val decoder = decoder()

        assertEquals(
            listOf(TriggerEvent.SINGLE_CLICK),
            pressEndingWith(decoder, TriggerDecoder.SINGLE).events(),
        )
        assertEquals(
            listOf(TriggerEvent.SINGLE_CLICK),
            pressEndingWith(decoder, TriggerDecoder.SINGLE).events(),
        )
    }

    @Test
    fun `a repeated click code is not reported twice`() {
        val decoder = decoder()

        pressEndingWith(decoder, TriggerDecoder.SINGLE)

        assertTrue(decoder.onState(TriggerDecoder.SINGLE, 160).isEmpty())
        assertTrue(decoder.onState(TriggerDecoder.SINGLE, 180).isEmpty())
    }

    @Test
    fun `a distinct code 3 is a long press`() {
        val decoder = decoder()

        decoder.onState(TriggerDecoder.PRESS, 0)

        assertEquals(
            listOf(TriggerEvent.LONG_PRESS),
            decoder.onState(TriggerDecoder.LONG, 500).events(),
        )
    }

    @Test
    fun `an unknown value does not leave the decoder stuck`() {
        val decoder = decoder()

        decoder.onState(TriggerDecoder.PRESS, 0)
        decoder.onState(9, 100)

        assertFalse(decoder.isPressed)
        assertEquals(
            listOf(TriggerEvent.PRESS_DOWN),
            decoder.onState(TriggerDecoder.PRESS, 200).events(),
        )
    }

    @Test
    fun `a stale pending press does not swallow the next one`() {
        val decoder = decoder()

        // A press that never received a classification.
        decoder.onState(TriggerDecoder.PRESS, 0)
        decoder.onState(TriggerDecoder.IDLE, 20)

        // Past maxHoldMs, so this starts over instead of reading as a hold.
        assertEquals(
            listOf(TriggerEvent.PRESS_DOWN),
            decoder.onState(TriggerDecoder.PRESS, 5_000).events(),
        )
    }

    @Test
    fun `reset clears the press state`() {
        val decoder = decoder()

        decoder.onState(TriggerDecoder.PRESS, 0)
        assertTrue(decoder.isPressed)

        decoder.reset()

        assertFalse(decoder.isPressed)
        assertEquals(TriggerDecoder.IDLE, decoder.raw)
    }
}
