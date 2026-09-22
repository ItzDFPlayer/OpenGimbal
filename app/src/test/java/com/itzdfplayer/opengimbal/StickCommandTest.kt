package com.itzdfplayer.opengimbal

import com.itzdfplayer.opengimbal.gimbal.AimStep
import com.itzdfplayer.opengimbal.gimbal.GimbalModel
import com.itzdfplayer.opengimbal.gimbal.GimbalProtocol
import com.itzdfplayer.opengimbal.gimbal.StickVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-screen stick speaks two commands, both worked out from the official app's
 * `com.honji.device_gimbal.comm.c`: `l()` for the M01/M0X family and `k()` for the rest.
 * The bytes are asserted literally, because the whole point is to reproduce frames
 * another program expects.
 */
class StickCommandTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    // ---- OnePTZ (M01, M0X) ------------------------------------------------

    @Test
    fun `the M01 families are the OnePTZ ones`() {
        assertTrue(GimbalModel.M01.onePtz)
        assertTrue(GimbalModel.M0X.onePtz)
        assertFalse("the Q18 steers differently", GimbalModel.Q18.onePtz)
        assertFalse("so do the others", GimbalModel.Q09.onePtz)
        assertFalse(GimbalModel.M07.onePtz)
        assertFalse(GimbalModel.NONE.onePtz)
    }

    @Test
    fun `a centred OnePTZ frame says nothing at all`() {
        // cc 01 80 80 11 01 00 df 33
        // sum = cc + 01 + 80 + 80 + 11 + 01 = 0x1df, low byte df
        val frame = GimbalProtocol.onePtzStickFrame(
            x = GimbalProtocol.STICK_CENTRE,
            y = GimbalProtocol.STICK_CENTRE,
            orientation = GimbalProtocol.STICK_ORIENTATION_PORTRAIT,
            appDriven = true,
        )

        assertEquals(9, frame.size)
        assertArrayEquals(
            bytes(0xCC, 0x01, 0x80, 0x80, 0x11, 0x01, 0x00, 0xDF, 0x33),
            frame,
        )
    }

    @Test
    fun `the OnePTZ axis values are little endian bytes, centre 128`() {
        val frame = GimbalProtocol.onePtzStickFrame(
            x = 200,
            y = 56,
            orientation = GimbalProtocol.STICK_ORIENTATION_LANDSCAPE,
            appDriven = false,
        )

        assertEquals(0xC8.toByte(), frame[2])
        assertEquals(0x38.toByte(), frame[3])
        assertEquals(GimbalProtocol.STICK_ORIENTATION_LANDSCAPE.toByte(), frame[4])
        assertEquals(0.toByte(), frame[5])
        assertEquals(0.toByte(), frame[6])
        assertEquals(0x33.toByte(), frame[8])
    }

    @Test
    fun `the OnePTZ checksum is the sum of the six fields`() {
        val frame = GimbalProtocol.onePtzStickFrame(
            x = 200,
            y = 56,
            orientation = GimbalProtocol.STICK_ORIENTATION_LANDSCAPE,
            appDriven = true,
        )

        // The official code adds the signed bytes and truncates, which is the same low
        // byte as adding the unsigned ones modulo 256.
        val signed = (-52 + 1 + 200 + 56 + 1 + 1) and 0xFF
        val unsigned = (0xCC + 0x01 + 200 + 56 + 1 + 1) and 0xFF
        assertEquals(unsigned, signed)
        assertEquals(signed.toByte(), frame[7])
    }

    @Test
    fun `the orientation bytes are the two the official app uses`() {
        assertEquals(17, GimbalProtocol.STICK_ORIENTATION_PORTRAIT)
        assertEquals(1, GimbalProtocol.STICK_ORIENTATION_LANDSCAPE)
    }

    // ---- older models -----------------------------------------------------

    @Test
    fun `a legacy frame is six bytes with an XOR and offset checksum`() {
        // 48 06 01 <x> <y> <(06 ^ 01 ^ x ^ y) + x>
        val frame = GimbalProtocol.legacyStickFrame(x = 128, y = 128)

        assertEquals(6, frame.size)
        assertArrayEquals(bytes(0x48, 0x06, 0x01, 0x80, 0x80, 0x87), frame)
    }

    @Test
    fun `the legacy checksum survives the extremes`() {
        listOf(0, 1, 128, 254, 255).forEach { x ->
            listOf(0, 1, 128, 254, 255).forEach { y ->
                val frame = GimbalProtocol.legacyStickFrame(x, y)
                val expected = (0x06 xor 0x01 xor (x and 0xFF) xor (y and 0xFF)) + (x and 0xFF)
                assertEquals(
                    "x=$x y=$y",
                    (expected and 0xFF).toByte(),
                    frame[5],
                )
                assertEquals(x.toByte(), frame[3])
                assertEquals(y.toByte(), frame[4])
            }
        }
    }

    // ---- the stick mapping ------------------------------------------------

    @Test
    fun `a centred stick asks for no movement`() {
        assertEquals(GimbalProtocol.STICK_CENTRE to GimbalProtocol.STICK_CENTRE, StickVector.toCommand(0f, 0f))
    }

    @Test
    fun `the dead zone keeps a resting thumb still`() {
        assertTrue(StickVector.isCentred(0f, 0f))
        assertTrue(StickVector.isCentred(0.05f, -0.05f))
        assertFalse(StickVector.isCentred(0.5f, 0f))

        // Just inside the dead zone: no movement on either axis.
        val (x, y) = StickVector.toCommand(0.10f, 0.10f)
        assertEquals(GimbalProtocol.STICK_CENTRE, x)
        assertEquals(GimbalProtocol.STICK_CENTRE, y)
    }

    @Test
    fun `pushing right raises x and pushing up lowers y`() {
        // Screen up is negative y, and the command counts up the other way.
        val right = StickVector.toCommand(1f, 0f)
        assertEquals(255, right.first)
        assertEquals(GimbalProtocol.STICK_CENTRE, right.second)

        val up = StickVector.toCommand(0f, 1f)
        assertEquals(GimbalProtocol.STICK_CENTRE, up.first)
        assertEquals(0, up.second)

        val down = StickVector.toCommand(0f, -1f)
        assertEquals(255, down.second)
    }

    @Test
    fun `inverting an axis mirrors it about the centre`() {
        val (right, _) = StickVector.toCommand(1f, 0f)
        val (left, _) = StickVector.toCommand(1f, 0f, invertX = true)
        assertEquals(255, right)
        assertEquals(0, left)

        val (_, up) = StickVector.toCommand(0f, 1f)
        val (_, upInverted) = StickVector.toCommand(0f, 1f, invertY = true)
        assertEquals(0, up)
        assertEquals(255, upInverted)
    }

    @Test
    fun `half deflection is slower than full deflection`() {
        val (half, _) = StickVector.toCommand(0.5f, 0f)
        val (full, _) = StickVector.toCommand(1f, 0f)

        assertTrue("half should pass the centre but not reach full", half > GimbalProtocol.STICK_CENTRE)
        assertTrue(half < full)
    }

    @Test
    fun `the dead zone does not eat the start of the travel`() {
        // Just past the dead zone must be just past the centre, not a jump.
        val (small, _) = StickVector.toCommand(StickVector.DEAD_ZONE + 0.001f, 0f)
        assertTrue(
            "expected about ${GimbalProtocol.STICK_CENTRE} but was $small",
            kotlin.math.abs(small - GimbalProtocol.STICK_CENTRE) <= 1,
        )
    }

    @Test
    fun `offsets beyond full deflection are clamped`() {
        val (x, y) = StickVector.toCommand(4f, -4f)
        assertEquals(255, x)
        assertEquals(255, y)
    }

    // ---- the aim command --------------------------------------------------

    /**
     * `angleConvertByte` packs the aim as one mode byte and a little-endian word:
     * speed in bits 0..15, then pitch, roll and yaw in 15-bit slots at 17, 33 and 49,
     * each holding `degrees * 5`.
     */
    @Test
    fun `an aim frame is a type 2 frame with a nine byte payload`() {
        val frame = GimbalProtocol.angleFrame(GimbalProtocol.GimbalAim())

        assertEquals(13, frame.size)
        assertEquals(0x48.toByte(), frame[0])
        assertEquals(13.toByte(), frame[1])
        assertEquals(2.toByte(), frame[2])
    }

    @Test
    fun `the trailer is the checksum every packet carries`() {
        val frame = GimbalProtocol.angleFrame(
            GimbalProtocol.GimbalAim(yaw = 100f, pitch = 40f, roll = 0f, speed = 0, mode = 0)
        )

        assertEquals(0x00.toByte(), frame[3])
        assertEquals(0x00.toByte(), frame[4])
        assertEquals(0x90.toByte(), frame[5])
        assertEquals(0x01.toByte(), frame[6])
        assertEquals(0x00.toByte(), frame[7])
        assertEquals(0x00.toByte(), frame[8])
        assertEquals(0xE8.toByte(), frame[9])
        assertEquals(0x03.toByte(), frame[10])
        assertEquals(0x00.toByte(), frame[11])

        // Worked out by hand: 0d ^ 02 ^ 00 ^ 00 ^ 90 ^ 01 ^ 00 ^ 00 ^ e8 ^ 03 ^ 00, then
        // plus the byte after the header, which is the speed low byte and is zero here.
        assertEquals(0x75.toByte(), frame[12])
    }

    @Test
    fun `the mode byte trails the word, which is the order the native encoder writes`() {
        // Getting this backwards shuffles every field into its neighbour, and on hardware
        // that shows up as the gimbal moving about a hundred times too far.
        val aiming = GimbalProtocol.angleFrame(
            GimbalProtocol.GimbalAim(mode = GimbalProtocol.AIM_MODE, speed = 0)
        )
        val reset = GimbalProtocol.angleFrame(
            GimbalProtocol.GimbalAim(mode = GimbalProtocol.RESET_MODE, speed = 0)
        )

        assertEquals(0.toByte(), aiming[11])
        assertEquals(15.toByte(), reset[11])
        assertEquals("the word starts at the first payload byte", 0.toByte(), reset[3])
    }

    @Test
    fun `speed sits in the first two bytes of the word`() {
        val frame = GimbalProtocol.angleFrame(GimbalProtocol.GimbalAim(speed = 6000))

        // 6000 = 0x1770, little endian at the start of the payload.
        assertEquals(0x70.toByte(), frame[3])
        assertEquals(0x17.toByte(), frame[4])
    }

    @Test
    fun `each angle is stored as fifths of a degree in its own fifteen bit slot`() {
        val frame = GimbalProtocol.angleFrame(
            GimbalProtocol.GimbalAim(yaw = 100f, pitch = 40f, roll = 0f, speed = 0)
        )
        val word = wordOf(frame)

        assertEquals(100L * 5, (word shr 49) and 0x7FFF)
        assertEquals(40L * 5, (word shr 17) and 0x7FFF)
        assertEquals(0L, (word shr 33) and 0x7FFF)
        assertEquals(0L, word and 0xFFFF)
    }

    @Test
    fun `a fraction of a degree is kept, because the slot holds fifths`() {
        val frame = GimbalProtocol.angleFrame(GimbalProtocol.GimbalAim(yaw = 0.4f, speed = 0))

        assertEquals("0.4 degrees is two fifths", 2L, (wordOf(frame) shr 49) and 0x7FFF)
    }

    /** The eight payload bytes after the header, as a little-endian word. */
    private fun wordOf(frame: ByteArray): Long =
        (3..10).fold(0L) { acc, index ->
            acc or ((frame[index].toLong() and 0xFF) shl ((index - 3) * 8))
        }

    @Test
    fun `a negative angle wraps into its slot rather than corrupting its neighbours`() {
        val frame = GimbalProtocol.angleFrame(
            GimbalProtocol.GimbalAim(yaw = 0f, pitch = -25f, roll = 0f, speed = 0)
        )
        val word = wordOf(frame)

        assertEquals("negative needs fifteen bits", 0x7F82, (word shr 17) and 0x7FFF)
        assertEquals("yaw must stay untouched", 0L, (word shr 49) and 0x7FFF)
        assertEquals("speed must stay untouched", 0L, word and 0xFFFF)
    }

    @Test
    fun `the wrapper is the three bytes the official app prepends`() {
        val frame = GimbalProtocol.angleFrame(GimbalProtocol.GimbalAim())
        val wrapped = GimbalProtocol.wrap(frame)

        assertEquals(frame.size + 3, wrapped.size)
        assertEquals(1.toByte(), wrapped[0])
        assertEquals(9.toByte(), wrapped[1])
        assertEquals(frame.size.toByte(), wrapped[2])
        assertArrayEquals(frame, wrapped.copyOfRange(3, wrapped.size))
    }

    // ---- stepping the aim -------------------------------------------------

    /**
     * The sweep is a rate, not a fixed amount per tick, so a late loop cannot change how
     * fast the gimbal turns.
     */
    @Test
    fun `a centred stick never moves the aim`() {
        assertEquals(0f, AimStep.advance(0f, ratePerSecond = 20f, seconds = 0.08f, offset = 0f, limit = 180f))
        assertEquals(10f, AimStep.advance(10f, ratePerSecond = 20f, seconds = 0.08f, offset = 0f, limit = 180f))
    }

    @Test
    fun `the aim moves by the rate times the time held`() {
        // 20 degrees a second for an eighth of a second is 2.5 degrees.
        assertEquals(
            2.5f,
            AimStep.advance(0f, ratePerSecond = 20f, seconds = 0.125f, offset = 1f, limit = 180f),
            0.001f,
        )
    }

    @Test
    fun `the same time at the same rate moves the same distance whatever the tick length`() {
        // Eight short ticks and one long one must agree, which is the whole point of
        // expressing the speed per second.
        var stepped = 0f
        repeat(8) {
            stepped = AimStep.advance(stepped, ratePerSecond = 10f, seconds = 0.0125f, offset = 1f, limit = 180f)
        }
        val oneGo = AimStep.advance(0f, ratePerSecond = 10f, seconds = 0.1f, offset = 1f, limit = 180f)

        assertEquals(oneGo, stepped, 0.01f)
    }

    @Test
    fun `part deflection sweeps more slowly`() {
        val full = AimStep.advance(0f, ratePerSecond = 10f, seconds = 0.1f, offset = 1f, limit = 180f)
        val half = AimStep.advance(0f, ratePerSecond = 10f, seconds = 0.1f, offset = 0.5f, limit = 180f)

        assertEquals(1f, full, 0.001f)
        assertEquals(0.5f, half, 0.001f)
    }

    @Test
    fun `the aim stops at the limit instead of running away`() {
        assertEquals(
            60f,
            AimStep.advance(59.5f, ratePerSecond = 40f, seconds = 0.25f, offset = 1f, limit = 60f),
        )
        assertEquals(
            -60f,
            AimStep.advance(-59.5f, ratePerSecond = 40f, seconds = 0.25f, offset = -1f, limit = 60f),
        )
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            "expected ${GimbalProtocol.toHex(expected)} but was ${GimbalProtocol.toHex(actual)}",
            GimbalProtocol.toHex(expected),
            GimbalProtocol.toHex(actual),
        )
    }
}
