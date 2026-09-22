package com.itzdfplayer.opengimbal.gimbal

import androidx.annotation.StringRes
import com.itzdfplayer.opengimbal.R
import java.util.UUID
import kotlin.math.roundToInt

/** Gimbal families known to the Hohem/Honji ("HJ360") OEM protocol. */
enum class GimbalModel(@StringRes val labelRes: Int, val ble: Boolean = true) {
    NONE(R.string.value_unknown),
    M01(R.string.model_m01),
    M07(R.string.model_m07),
    M0X(R.string.model_m0x),
    Q09(R.string.model_q09),
    Q18(R.string.model_q18),
    GIMBAL_AI(R.string.model_gimbal_ai),

    /** Wi-Fi based camera, not reachable over the BLE UART profile. */
    PK01(R.string.model_pk01, ble = false);

    /**
     * The M01 and M0X families steer with [GimbalProtocol.onePtzStickFrame]; the rest
     * take the older [GimbalProtocol.legacyStickFrame]. Taken from `isOnePTZ()` in the
     * official app's `com.honji.base.model.DeviceModel`.
     */
    val onePtz: Boolean get() = this == M01 || this == M0X
}

/** Gimbal stabilisation mode, reported in bits 24..27 of every state packet. */
enum class GimbalMode(val code: Int, @StringRes val labelRes: Int) {
    LL_FOLLOW(0, R.string.gimbal_mode_ll_follow),
    PAN_FOLLOW(1, R.string.gimbal_mode_pan_follow),
    ALL_LOCK(2, R.string.gimbal_mode_all_lock),
    FPV(3, R.string.gimbal_mode_fpv),
    SPIN_SHOT(4, R.string.gimbal_mode_spin_shot),
    PAN_FOLLOW_PLUS(5, R.string.gimbal_mode_pan_follow_plus),
    ALL_FOLLOW_PLUS(6, R.string.gimbal_mode_all_follow_plus);

    /**
     * Diagnostics only: the packet log names modes the way the protocol does, which is
     * deliberately not translated. The UI reads [labelRes] instead.
     */
    val label: String
        get() = when (this) {
            LL_FOLLOW -> "Follow (LL)"
            PAN_FOLLOW -> "Pan Follow"
            ALL_LOCK -> "All Lock"
            FPV -> "FPV"
            SPIN_SHOT -> "Spin Shot"
            PAN_FOLLOW_PLUS -> "Pan Follow+"
            ALL_FOLLOW_PLUS -> "All Follow+"
        }

    companion object {
        fun of(code: Int): GimbalMode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One decoded packet-type-2 status word.
 *
 * The native parser (`DataConvertJNI.convertState` in `libdataConvert-lib.so`) reads
 * the first eight payload bytes as a single little-endian `uint64` and unpacks bit
 * fields from it. Bit numbering below is relative to that word.
 */
data class GimbalState(
    val captureButton: Int = 0,
    val modelButton: Int = 0,
    val triggerButton: Int = 0,
    val knob: Int = 0,
    val mode: Int = 0,
    val directionState: Int = 0,
    val direction: Int = 0,
    val voltage: Int = 0,
    /** Signed: > 0 zoom in / focus +, < 0 zoom out / focus -, 0 = stopped. */
    val zoomValue: Int = 0,
) {
    val modeEnum: GimbalMode? get() = GimbalMode.of(mode)

    @get:StringRes
    val triggerLabelRes: Int get() = triggerNameRes(triggerButton)

    @get:StringRes
    val captureLabelRes: Int get() = captureNameRes(captureButton)

    @get:StringRes
    val modelButtonLabelRes: Int get() = modelButtonNameRes(modelButton)
}

/**
 * Raw meaning of the trigger nibble.
 *
 * `15` / `0xF` is the press marker that opens every press; a second one while the
 * button is still down is the long press. See [TriggerDecoder].
 *
 * The `*Res` functions return the resources the UI shows. The plain String versions
 * further down are for the packet log, which names raw protocol values rather than
 * talking to the user and is deliberately not translated.
 */
@StringRes
fun triggerNameRes(v: Int): Int = when (v) {
    0 -> R.string.value_released
    TriggerDecoder.PRESS -> R.string.value_press_hold
    TriggerDecoder.SINGLE -> R.string.trigger_single_value
    TriggerDecoder.DOUBLE -> R.string.trigger_double_value
    TriggerDecoder.LONG -> R.string.trigger_long_value
    else -> R.string.value_unknown_state
}

/** Diagnostics only: the packet log. */
fun triggerName(v: Int): String = when (v) {
    0 -> "released"
    TriggerDecoder.PRESS -> "press / hold"
    TriggerDecoder.SINGLE -> "single click"
    TriggerDecoder.DOUBLE -> "double click"
    TriggerDecoder.LONG -> "long press"
    else -> "unknown"
}

@StringRes
fun captureNameRes(v: Int): Int = when (v) {
    0 -> R.string.value_released
    2 -> R.string.capture_switch_video
    3 -> R.string.capture_switch_camera
    else -> R.string.value_unknown_with_code
}

/** Diagnostics only: the packet log. */
fun captureName(v: Int): String = when (v) {
    0 -> "released"
    2 -> "switch to VIDEO mode"
    3 -> "switch camera"
    else -> "unknown($v)"
}

@StringRes
fun modelButtonNameRes(v: Int): Int = when (v) {
    0 -> R.string.value_released
    1 -> R.string.model_button_next_mode
    2 -> R.string.model_button_close_tracking
    else -> R.string.value_unknown_with_code
}

/** Diagnostics only: the packet log. */
fun modelButtonName(v: Int): String = when (v) {
    0 -> "released"
    1 -> "single press -> next gimbal mode"
    2 -> "double press -> close tracking"
    else -> "unknown($v)"
}

/** A validated packet received from the gimbal. */
class GimbalPacket(
    val type: Int,
    val payload: ByteArray,
    val raw: ByteArray,
    val state: GimbalState? = null,
) {
    val typeName: String get() = when (type) {
        TYPE_DEVICE_INFO -> "DeviceInfo"
        TYPE_ANGLE -> "Angle"
        TYPE_STATE -> "State"
        TYPE_ANGLE_2 -> "Angle2"
        else -> "Type$type"
    }

    companion object {
        const val TYPE_DEVICE_INFO = 0
        const val TYPE_ANGLE = 1
        const val TYPE_STATE = 2
        const val TYPE_ANGLE_2 = 4
    }
}

/**
 * Wire protocol for the BLE-UART profile used by these gimbals.
 *
 * Layout of every frame the gimbal sends:
 * ```
 * 84 <len> <type> <payload...> <checksum>
 *   len      == total frame length (so payload length == len - 4)
 *   checksum == XOR(bytes[1 .. len-2]) + payload[0]
 * ```
 * Some models additionally wrap the frame in `02 08 <innerLen>`, which is stripped
 * before validation. Outgoing commands use header `48`; some models require an
 * `01 09 <len>` prefix instead.
 */
object GimbalProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    /** Phone -> gimbal. */
    val WRITE_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    /** Gimbal -> phone (notify). */
    val NOTIFY_UUID: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Poll/announce frame, sent raw. */
    val HANDSHAKE_RAW: ByteArray = byteArrayOf(0x48, 0x04, 0x00, 0x04)

    /** Same frame wrapped with the `01 09 <len>` prefix; the official app alternates both. */
    val HANDSHAKE_WRAPPED: ByteArray = byteArrayOf(0x01, 0x09, 0x04, 0x48, 0x04, 0x00, 0x04)

    const val HANDSHAKE_INTERVAL_MS = 500L
    const val HANDSHAKE_MAX_ATTEMPTS = 20

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * Parses one notification frame. Returns `null` when the frame is not a valid
     * packet (bad header, bad length or bad checksum), which is normal while the
     * gimbal is still streaming fragments.
     */
    fun parse(input: ByteArray): GimbalPacket? {
        var b = input

        // Strip the optional `02 08 <innerLen>` wrapper.
        if (b.size >= 7 &&
            b[0] == 0x02.toByte() &&
            b[1] == 0x08.toByte() &&
            (b[2].toInt() and 0xFF) == b.size - 3
        ) {
            b = b.copyOfRange(3, b.size)
        }

        if (b.size < 4 || b[0] != 0x84.toByte()) return null

        val len = b[1].toInt() and 0xFF
        if (len != b.size) return null

        val type = b[2].toInt() and 0xFF
        val payload = b.copyOfRange(3, b.size - 1)

        if (checksum(b) != (b[b.size - 1].toInt() and 0xFF)) return null

        val state = if (type == GimbalPacket.TYPE_STATE) decodeState(payload) else null
        return GimbalPacket(type = type, payload = payload, raw = input, state = state)
    }

    /**
     * Unpacks the bit-packed status word from a type-2 payload.
     *
     * | bits  | field            |
     * |-------|------------------|
     * | 0-3   | captureButton    |
     * | 4-7   | modelButton      |
     * | 8-11  | triggerButton    |
     * | 12-15 | knob             |
     * | 16-23 | unused           |
     * | 24-27 | mode             |
     * | 28-31 | directionState   |
     * | 32-35 | direction        |
     * | 36-39 | voltage          |
     * | 40-47 | zoomValue (int8) |
     */
    fun decodeState(payload: ByteArray): GimbalState? {
        if (payload.size < 6) return null

        val w0 = payload[0].toInt() and 0xFF
        val w1 = payload[1].toInt() and 0xFF
        val w3 = payload[3].toInt() and 0xFF
        val w4 = payload[4].toInt() and 0xFF
        val w5 = payload[5].toInt() and 0xFF

        return GimbalState(
            captureButton = w0 and 0x0F,
            modelButton = (w0 shr 4) and 0x0F,
            triggerButton = w1 and 0x0F,
            knob = (w1 shr 4) and 0x0F,
            mode = w3 and 0x0F,
            directionState = (w3 shr 4) and 0x0F,
            direction = w4 and 0x0F,
            voltage = (w4 shr 4) and 0x0F,
            // bits 40..47, sign-extended
            zoomValue = w5.toByte().toInt(),
        )
    }

    // ---- outgoing steering ------------------------------------------------

    /** Neither axis is asked to move, which is also how the gimbal is told to stop. */
    const val STICK_CENTRE = 128

    /**
     * The orientation byte of [onePtzStickFrame]. The gimbal swaps which physical axis
     * each value drives depending on how the phone is held, so it has to be told. These
     * two values come from `i(PhotoScale, rotation)` in the official app, which maps a
     * `Surface.ROTATION_90` or `ROTATION_270` to 1, and 0 or 180 degrees to 17.
     */
    const val STICK_ORIENTATION_PORTRAIT = 17
    const val STICK_ORIENTATION_LANDSCAPE = 1

    /**
     * Steering command for the M01/M0X families.
     *
     * Reversed from `com.honji.device_gimbal.comm.c.l(int, int, byte, boolean)` in the
     * official app:
     * ```
     * cc 01 <x> <y> <orientation> <flag> 00 <sum> 33
     * sum = (0xcc + 0x01 + x + y + orientation + flag) & 0xff
     * ```
     * Both axes run 0..255 with [STICK_CENTRE] meaning "hold still", so this is a
     * *velocity* command rather than a destination: it has to be repeated for as long as
     * the gimbal should keep moving, and cancelled with a centred frame. The official
     * app streams it every 80 ms while tracking and stops with three centred frames.
     *
     * [appDriven] is the flag the official app sets when the phone is the thing choosing
     * where to point, which is what the on-screen stick is doing.
     */
    fun onePtzStickFrame(x: Int, y: Int, orientation: Int, appDriven: Boolean): ByteArray {
        val flag = if (appDriven) 1 else 0
        val body = byteArrayOf(
            0xCC.toByte(),
            0x01,
            x.toByte(),
            y.toByte(),
            orientation.toByte(),
            flag.toByte(),
            0,
        )
        // The official code sums the signed bytes and truncates; adding the unsigned
        // values modulo 256 gives the same low byte.
        val sum = body.fold(0) { total, byte -> total + (byte.toInt() and 0xFF) }
        return body + byteArrayOf((sum and 0xFF).toByte(), 0x33)
    }

    /**
     * Steering command for the models that are not OnePTZ.
     *
     * Reversed from `com.honji.device_gimbal.comm.c.k(int, int)`:
     * ```
     * 48 06 01 <x> <y> <checksum>    checksum = (0x06 ^ 0x01 ^ x ^ y) + x
     * ```
     * Same range and centre as [onePtzStickFrame]. Models other than M01 also have the
     * frame wrapped in an `01 09 <len>` prefix, which is the caller's business.
     */
    fun legacyStickFrame(x: Int, y: Int): ByteArray {
        val lowX = x and 0xFF
        val lowY = y and 0xFF
        val checksum = (0x06 xor 0x01 xor lowX xor lowY) + lowX
        return byteArrayOf(
            0x48,
            0x06,
            0x01,
            lowX.toByte(),
            lowY.toByte(),
            (checksum and 0xFF).toByte(),
        )
    }

    // ---- aiming at an angle ----------------------------------------------

    /** Speed field of an aim command. The official app aims with 6000. */
    const val AIM_SPEED = 6000

    /** Mode field of an aim command: go to the angles given. */
    const val AIM_MODE = 0

    /** Mode field that sends the gimbal back to level. */
    const val RESET_MODE = 15

    /**
     * Where to point the gimbal, in degrees, as the official app's `GimbalAngle` carries
     * it. This is an absolute aim, not a velocity: the gimbal goes there and holds.
     *
     * Degrees are fractional because the wire format has room for fifths, and at a slow
     * sweep rate a whole degree at a time is visible as stepping.
     */
    data class GimbalAim(
        val yaw: Float = 0f,
        val pitch: Float = 0f,
        val roll: Float = 0f,
        val speed: Int = AIM_SPEED,
        val mode: Int = AIM_MODE,
    )

    /**
     * Aim command, `type 2` again but the other direction.
     *
     * Reversed from `Java_com_data_convert_DataConvertJNI_angleConvertByte` in
     * `libdataConvert-lib.so`, which reads five `GimbalAngle` fields and builds a
     * nine-byte payload: a little-endian word first, and the mode byte last.
     *
     * ```
     * 48 0d 02 <speed:16> <unused:1> <pitch:15> <roll:15> <yaw:15> <mode> <checksum>
     * ```
     *
     * each angle held as `degrees * 5` in its 15-bit slot, and the frame ended with the
     * same `XOR(bytes[1..len-2]) + payload[0]` checksum every packet uses - which is why
     * that trailing addition is the *speed* byte here, not the mode.
     *
     * The order matters and is easy to get wrong: the native code stores the word at
     * `sp` and the mode byte at `sp + 8`, so the mode trails. Building it the other way
     * round scrambles every field into its neighbour, which on real hardware shows up as
     * the gimbal moving about a hundred times further than asked.
     *
     * That the M01 takes this command is not a guess: `PanoramicFragment.sendAngleData`
     * in the official app sets `yaw` in degrees and branches on `DeviceModel.M01`
     * explicitly, then sends it through this same encoder.
     */
    fun angleFrame(aim: GimbalAim): ByteArray {
        val word = (aim.speed.toLong() and 0xFFFF) or
            (slot(aim.pitch) shl 17) or
            (slot(aim.roll) shl 33) or
            (slot(aim.yaw) shl 49)

        val frame = ByteArray(13)
        frame[0] = 0x48
        frame[1] = 13
        frame[2] = 2
        for (byte in 0 until 8) {
            frame[3 + byte] = ((word shr (byte * 8)) and 0xFF).toByte()
        }
        frame[11] = (aim.mode and 0xFF).toByte()
        frame[12] = checksum(frame).toByte()
        return frame
    }

    /** Angles are stored as fifths of a degree, in fifteen bits. */
    private const val ANGLE_SCALE = 5f

    /** One angle, in fifths of a degree, ready to be shifted into its slot. */
    private fun slot(degrees: Float): Long =
        (degrees * ANGLE_SCALE).roundToInt().toLong() and 0x7FFE

    /**
     * The trailer every frame carries: the XOR of the bytes after the header, plus the
     * first payload byte. Shared with the incoming parser so the two cannot drift.
     */
    fun checksum(frame: ByteArray): Int {
        var value = 0
        for (i in 1 until frame.size - 1) {
            value = value xor (frame[i].toInt() and 0xFF)
        }
        // The first payload byte is counted twice, but only when there is one.
        if (frame.size >= 5) {
            value = (value + (frame[3].toInt() and 0xFF)) and 0xFF
        }
        return value
    }

    /** The `01 09 <len>` prefix non-M01 models want in front of an outgoing frame. */
    fun wrap(frame: ByteArray): ByteArray {
        val wrapped = ByteArray(frame.size + 3)
        wrapped[0] = 1
        wrapped[1] = 9
        wrapped[2] = frame.size.toByte()
        frame.copyInto(wrapped, 3)
        return wrapped
    }
}
