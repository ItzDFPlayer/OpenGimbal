package com.itzdfplayer.opengimbal.gimbal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** How the stick talks to the gimbal. */
enum class StickMode {
    /**
     * Ask for an angle in degrees, and let the gimbal go there and hold. The app keeps the
     * aim and the stick moves it, so releasing the stick leaves the gimbal pointing at
     * something rather than drifting to a stop.
     *
     * This is the command the official app aims with, and the only one it has an explicit
     * M01 branch for.
     */
    AIM,

    /**
     * Ask for a velocity and keep asking while the stick is held, the way the official
     * app's tracking loop steers.
     *
     * Kept as an alternative because the two are different commands in the protocol and
     * only real hardware can say which one a given gimbal acts on.
     */
    STEER,
}

/**
 * The stick position and how to interpret it, as plain values, so neither [StickVector]
 * nor the controller has to know about preferences or Android.
 */
data class StickSettings(
    val mode: StickMode = StickMode.AIM,
    /** Which way the gimbal goes for a given value can only be seen on real hardware. */
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    /** The flag the official app sets when the phone is the thing doing the aiming. */
    val appDriven: Boolean = true,
    val landscape: Boolean = false,
    /** Degrees per second at full deflection when aiming. */
    val aimRate: Float = 8f,
)

/**
 * Moves an aim by one step of stick travel.
 *
 * Expressed as a rate rather than a fixed amount per tick, so the sweep speed does not
 * change if the loop is late. Pure, so the arithmetic that decides how far the gimbal
 * turns is testable.
 */
object AimStep {

    /**
     * @param seconds since the previous step, already clamped by the caller: a stall of
     *   minutes must not fling the gimbal across the room.
     */
    fun advance(
        current: Float,
        ratePerSecond: Float,
        seconds: Float,
        offset: Float,
        limit: Float,
    ): Float = (current + offset * ratePerSecond * seconds).coerceIn(-limit, limit)
}

/**
 * Turns a stick position into the gimbal's steering command, as plain numbers.
 *
 * A stick offset is each in -1..1, where +x is right and +y is up. The steering command
 * wants two 0..255 values with [GimbalProtocol.STICK_CENTRE] meaning "hold still", so this
 * is where the two representations meet - and where the axis directions are decided, which
 * is the one thing the decompiled code does not make obvious.
 *
 * Pure, so the mapping and the dead zone are testable.
 */
object StickVector {

    /** How far the stick has to leave the centre before anything moves. */
    const val DEAD_ZONE = 0.12f

    /**
     * Maps a stick offset to the pair of command values. Positive offset means right and
     * up, matching how the stick on screen is drawn.
     */
    fun toCommand(
        offsetX: Float,
        offsetY: Float,
        invertX: Boolean = false,
        invertY: Boolean = false,
    ): Pair<Int, Int> = axis(offsetX, invertX) to axis(-offsetY, invertY)

    /**
     * One axis, where positive means "further along the command's own direction" and 1 is
     * full deflection. Screen "up" is negative y, which is why the caller flips it.
     */
    private fun axis(offset: Float, invert: Boolean): Int {
        val clamped = offset.coerceIn(-1f, 1f)
        if (abs(clamped) < DEAD_ZONE) return GimbalProtocol.STICK_CENTRE

        // Rescale, so the dead zone does not cost the first part of the travel.
        val magnitude = (abs(clamped) - DEAD_ZONE) / (1f - DEAD_ZONE)
        val direction = if (clamped < 0f) -1f else 1f
        val signed = if (invert) -direction else direction

        val span = GimbalProtocol.STICK_CENTRE.toFloat()
        val value = GimbalProtocol.STICK_CENTRE + (span * magnitude * signed)
        return value.roundToInt().coerceIn(0, 255)
    }

    /** True when the stick is close enough to the middle to mean "stop". */
    fun isCentred(offsetX: Float, offsetY: Float): Boolean =
        hypot(offsetX.toDouble(), offsetY.toDouble()) < DEAD_ZONE
}

/**
 * Drives the gimbal from the on-screen stick.
 *
 * Which command goes on the wire depends on [StickSettings.mode]; see [StickMode]. Either
 * way the gimbal has to be told repeatedly while the stick is held, because neither
 * command is a one-shot: a steering frame is a velocity, and an aim is only reached if the
 * target keeps being sent while the gimbal is still travelling to it.
 *
 * Call from the main thread - it owns the [Handler] that paces everything.
 */
class StickController(
    private val client: GimbalBleClient,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val settings: () -> StickSettings,
) {

    private companion object {
        const val TAG = "OpenGimbal"

        /** The official app's tracking loop uses 80 ms; matching it is the safe choice. */
        const val DEFAULT_INTERVAL_MS = 80L

        /** How many times the official app repeats a one-off command. */
        const val REPEATS = 3
        const val REPEAT_INTERVAL_MS = 50L

        /**
         * Longest step the aim is allowed to take in one go. A late or blocked main
         * thread must not turn into a sudden jump.
         */
        const val MAX_STEP_SECONDS = 0.25f
    }

    private val main = Handler(Looper.getMainLooper())

    private var offsetX = 0f
    private var offsetY = 0f
    private var streaming = false

    private var repeating: ByteArray? = null
    private var repeatsLeft = 0

    /** When the previous aim step was taken, so the sweep rate survives a late loop. */
    private var lastStepAt = 0L

    /** Where the gimbal has been asked to point. Only used by [StickMode.AIM]. */
    val aimYaw: Float get() = AimState.yaw

    val aimPitch: Float get() = AimState.pitch

    /** True while commands are being sent. */
    val active: Boolean get() = streaming

    /**
     * Points the gimbal in the direction of the stick. Starts the loop on the first call;
     * later calls only change where it is pointing.
     *
     * Returns false when there is nothing to steer.
     */
    fun deflect(offsetX: Float, offsetY: Float): Boolean {
        this.offsetX = offsetX
        this.offsetY = offsetY

        if (!client.isConnected) return false

        cancelRepeats()
        if (streaming) return true

        streaming = true
        lastStepAt = SystemClock.uptimeMillis()
        main.post(tickRunnable)
        return true
    }

    /**
     * Lets go of the stick.
     *
     * An aim needs nothing: it is absolute, so the gimbal simply arrives and stays. A
     * steering frame is not absolute, so the gimbal keeps the last velocity and has to be
     * told to stop - several times, because one frame is easy to miss.
     */
    fun release() {
        main.removeCallbacks(tickRunnable)
        val wasStreaming = streaming
        streaming = false
        offsetX = 0f
        offsetY = 0f

        if (!wasStreaming || !client.isConnected) return
        val current = settings()
        if (current.mode == StickMode.STEER) sendRepeated(steeringFrame(0f, 0f, current))
    }

    /** Stops on the spot, without trailing frames. For a disconnect or leaving the screen. */
    fun abandon() {
        main.removeCallbacks(tickRunnable)
        cancelRepeats()
        streaming = false
        offsetX = 0f
        offsetY = 0f
    }

    /** Sends the gimbal back to level and forgets the aim. */
    fun recentre() {
        AimState.level()
        if (!client.isConnected) return
        val reset = GimbalProtocol.GimbalAim(mode = GimbalProtocol.RESET_MODE)
        sendRepeated(aimFrame(reset))
    }

    // ---- the loops --------------------------------------------------------

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!streaming) return
            if (!client.isConnected) {
                // Nothing to steer any more; do not leave a timer running.
                abandon()
                return
            }
            send(offsetX, offsetY)
            main.postDelayed(this, intervalMs)
        }
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val frame = repeating
            val left = repeatsLeft
            if (frame == null || left <= 0) {
                cancelRepeats()
                return
            }
            repeatsLeft = left - 1
            if (!client.send(frame)) cancelRepeats() else main.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private fun sendRepeated(frame: ByteArray) {
        main.removeCallbacks(repeatRunnable)
        repeating = frame
        repeatsLeft = REPEATS
        repeatRunnable.run()
    }

    private fun cancelRepeats() {
        repeatsLeft = 0
        repeating = null
        main.removeCallbacks(repeatRunnable)
    }

    // ---- the wire ---------------------------------------------------------

    private fun send(offsetX: Float, offsetY: Float) {
        val current = settings()
        if (!client.send(aimOrSteeringFrame(offsetX, offsetY, current))) {
            Log.d(TAG, "Frame dropped, the gimbal is not connected")
        }
    }

    private fun aimOrSteeringFrame(
        offsetX: Float,
        offsetY: Float,
        current: StickSettings,
    ): ByteArray = if (current.mode == StickMode.AIM) {
        stepAim(offsetX, offsetY, current)
    } else {
        steeringFrame(offsetX, offsetY, current)
    }

    /**
     * Moves the aim by one tick of stick travel and asks the gimbal to go there. The step
     * comes from the elapsed time and a rate in degrees per second, so holding the stick
     * sweeps at a predictable speed.
     */
    private fun stepAim(offsetX: Float, offsetY: Float, current: StickSettings): ByteArray {
        val now = SystemClock.uptimeMillis()
        val seconds = ((now - lastStepAt).toFloat() / 1000f).coerceIn(0f, MAX_STEP_SECONDS)
        lastStepAt = now

        val horizontal = if (current.invertX) -offsetX else offsetX
        val vertical = if (current.invertY) -offsetY else offsetY

        // The aim lives in AimState rather than here: the tracker nudges the same value,
        // and two private copies would drift apart as soon as both are in use.
        AimState.moveTo(
            AimStep.advance(AimState.yaw, current.aimRate, seconds, horizontal, AimState.YAW_LIMIT),
            AimStep.advance(AimState.pitch, current.aimRate, seconds, vertical, AimState.PITCH_LIMIT),
        )

        return AimState.frameFor(client)
    }

    /** M01 takes the frame bare; every other model wants the `01 09 <len>` prefix. */
    private fun aimFrame(aim: GimbalProtocol.GimbalAim): ByteArray {
        val frame = GimbalProtocol.angleFrame(aim)
        return if (client.connectedModel == GimbalModel.M01) {
            frame
        } else {
            GimbalProtocol.wrap(frame)
        }
    }

    private fun steeringFrame(
        offsetX: Float,
        offsetY: Float,
        current: StickSettings,
    ): ByteArray {
        val (x, y) = StickVector.toCommand(offsetX, offsetY, current.invertX, current.invertY)
        return if (client.connectedModel.onePtz) {
            GimbalProtocol.onePtzStickFrame(
                x = x,
                y = y,
                orientation = if (current.landscape) {
                    GimbalProtocol.STICK_ORIENTATION_LANDSCAPE
                } else {
                    GimbalProtocol.STICK_ORIENTATION_PORTRAIT
                },
                appDriven = current.appDriven,
            )
        } else {
            GimbalProtocol.legacyStickFrame(x, y)
        }
    }
}
