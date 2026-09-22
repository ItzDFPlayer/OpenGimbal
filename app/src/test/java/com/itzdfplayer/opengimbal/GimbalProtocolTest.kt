package com.itzdfplayer.opengimbal

import com.itzdfplayer.opengimbal.gimbal.DeviceTable
import com.itzdfplayer.opengimbal.gimbal.GimbalMode
import com.itzdfplayer.opengimbal.gimbal.GimbalModel
import com.itzdfplayer.opengimbal.gimbal.GimbalPacket
import com.itzdfplayer.opengimbal.gimbal.GimbalProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in the wire format that was reverse engineered from Gimbal Show and its
 * `libdataConvert-lib.so`.
 */
class GimbalProtocolTest {

    /** Builds a `84 <len> <type> <payload> <checksum>` frame the way a gimbal would. */
    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 4
        val body = byteArrayOf(0x84.toByte(), len.toByte(), type.toByte()) + payload
        var checksum = 0
        for (i in 1 until body.size) checksum = checksum xor (body[i].toInt() and 0xFF)
        checksum = (checksum + (payload[0].toInt() and 0xFF)) and 0xFF
        return body + checksum.toByte()
    }

    /** Packs the nine status fields into the bit layout used by the gimbal. */
    private fun packState(
        capture: Int = 0,
        modelButton: Int = 0,
        trigger: Int = 0,
        knob: Int = 0,
        mode: Int = 1,
        directionState: Int = 0,
        direction: Int = 0,
        voltage: Int = 7,
        zoom: Int = 0,
    ): ByteArray = byteArrayOf(
        (capture or (modelButton shl 4)).toByte(),
        (trigger or (knob shl 4)).toByte(),
        0,
        (mode or (directionState shl 4)).toByte(),
        (direction or (voltage shl 4)).toByte(),
        zoom.toByte(),
    )

    @Test
    fun `decodes every status field`() {
        val payload = packState(
            capture = 3,
            modelButton = 2,
            trigger = 1,
            knob = 1,
            mode = GimbalMode.SPIN_SHOT.code,
            directionState = 5,
            direction = 4,
            voltage = 9,
            zoom = 100,
        )

        val packet = GimbalProtocol.parse(frame(GimbalPacket.TYPE_STATE, payload))

        assertNotNull(packet)
        val state = packet!!.state!!
        assertEquals(3, state.captureButton)
        assertEquals(2, state.modelButton)
        assertEquals(1, state.triggerButton)
        assertEquals(1, state.knob)
        assertEquals(GimbalMode.SPIN_SHOT, state.modeEnum)
        assertEquals(5, state.directionState)
        assertEquals(4, state.direction)
        assertEquals(9, state.voltage)
        assertEquals(100, state.zoomValue)
    }

    @Test
    fun `zoom value is signed`() {
        val negative = GimbalProtocol.parse(frame(GimbalPacket.TYPE_STATE, packState(zoom = -100)))
        assertEquals(-100, negative!!.state!!.zoomValue)

        val positive = GimbalProtocol.parse(frame(GimbalPacket.TYPE_STATE, packState(zoom = 127)))
        assertEquals(127, positive!!.state!!.zoomValue)
    }

    @Test
    fun `accepts the 02 08 wrapper`() {
        val inner = frame(GimbalPacket.TYPE_STATE, packState(trigger = 1))
        val wrapped = byteArrayOf(0x02, 0x08, inner.size.toByte()) + inner

        val packet = GimbalProtocol.parse(wrapped)

        assertNotNull(packet)
        assertEquals(GimbalPacket.TYPE_STATE, packet!!.type)
        assertEquals(1, packet.state!!.triggerButton)
    }

    @Test
    fun `rejects a corrupted checksum`() {
        val corrupted = frame(GimbalPacket.TYPE_STATE, packState(trigger = 1))
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()

        assertNull(GimbalProtocol.parse(corrupted))
    }

    @Test
    fun `rejects unknown headers and bad lengths`() {
        assertNull(GimbalProtocol.parse(byteArrayOf(0x00, 0x08, 0x02, 0, 0, 0, 0, 0)))
        assertNull(GimbalProtocol.parse(byteArrayOf(0x84.toByte(), 0x30, 0x02, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `identifies the Proove Axis as an M01`() {
        val entry = DeviceTable.detect("Proove Axis-M01-1303A6")

        assertNotNull(entry)
        assertEquals(GimbalModel.M01, entry!!.model)
        assertEquals("Proove Axis-M01", entry.namePrefix)
    }

    @Test
    fun `specific prefixes win over generic ones`() {
        assertEquals(GimbalModel.M01, DeviceTable.modelOf("SelfieShow-M01"))
        assertEquals(GimbalModel.Q18, DeviceTable.modelOf("SelfieShow-Q18"))
        assertEquals(GimbalModel.M0X, DeviceTable.modelOf("SelfieShow-M0X"))
        assertEquals(GimbalModel.Q09, DeviceTable.modelOf("SelfieShow"))
        assertEquals(GimbalModel.NONE, DeviceTable.modelOf("Some Random Speaker"))
        assertEquals(GimbalModel.NONE, DeviceTable.modelOf(null))
    }
}
