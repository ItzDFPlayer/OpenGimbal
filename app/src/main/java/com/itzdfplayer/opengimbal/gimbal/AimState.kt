package com.itzdfplayer.opengimbal.gimbal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the app currently believes the gimbal is pointing, in degrees.
 *
 * One shared place rather than something each caller keeps to itself, because the aim
 * command is absolute: the on-screen stick and the tracker both nudge the same value, and
 * two ideas of the current target would fight each other.
 *
 * The obvious limitation: this is the app's *belief*, not a reading. Nothing tells us
 * where the gimbal actually is, so if it is physically moved the belief goes stale. That is
 * what [MAX_STEP_DEGREES] exists for - every nudge is clamped, so a stale aim converges
 * visibly over several steps instead of snapping all at once.
 */
object AimState {

    /**
     * Longest single correction, so a stale target cannot become a violent swing.
     *
     * Raised from 3 once the tracker started waiting for each correction to show up in the
     * picture before making the next one. At one correction every 250 ms this is a ceiling of
     * 24 degrees a second, which is quick enough to follow something without being a lurch -
     * and unlike a smaller cap it leaves the strength setting room to do something, since a
     * step is clamped here only for errors beyond 120 pixels.
     */
    const val MAX_STEP_DEGREES = 6f

    /** Kept inside what the official app's own aiming ever asks for. */
    const val YAW_LIMIT = 180f
    const val PITCH_LIMIT = 60f

    var yaw by mutableStateOf(0f)
        private set

    var pitch by mutableStateOf(0f)
        private set

    /** True once anything has moved the aim away from level. */
    val isLevel: Boolean get() = yaw == 0f && pitch == 0f

    /** Nudges the aim, clamped to a single step and to the range the gimbal accepts. */
    fun nudge(deltaYaw: Float, deltaPitch: Float) {
        yaw = (yaw + deltaYaw.coerceIn(-MAX_STEP_DEGREES, MAX_STEP_DEGREES))
            .coerceIn(-YAW_LIMIT, YAW_LIMIT)
        pitch = (pitch + deltaPitch.coerceIn(-MAX_STEP_DEGREES, MAX_STEP_DEGREES))
            .coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
    }

    /** Used when the stick drags the aim, where the step is already the user's choice. */
    fun moveTo(newYaw: Float, newPitch: Float) {
        yaw = newYaw.coerceIn(-YAW_LIMIT, YAW_LIMIT)
        pitch = newPitch.coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
    }

    fun level() {
        yaw = 0f
        pitch = 0f
    }

    /**
     * The frame for the current aim. M01 takes it bare; every other model wants the
     * `01 09 <len>` prefix in front.
     */
    fun frameFor(client: GimbalBleClient): ByteArray {
        val frame = GimbalProtocol.angleFrame(GimbalProtocol.GimbalAim(yaw = yaw, pitch = pitch))
        return if (client.connectedModel == GimbalModel.M01) frame else GimbalProtocol.wrap(frame)
    }

    /** The frame that sends the gimbal back to level. */
    fun levelFrameFor(client: GimbalBleClient): ByteArray {
        val frame = GimbalProtocol.angleFrame(
            GimbalProtocol.GimbalAim(mode = GimbalProtocol.RESET_MODE)
        )
        return if (client.connectedModel == GimbalModel.M01) frame else GimbalProtocol.wrap(frame)
    }
}
