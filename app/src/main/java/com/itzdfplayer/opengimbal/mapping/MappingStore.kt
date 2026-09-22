package com.itzdfplayer.opengimbal.mapping

import android.content.Context
import androidx.core.content.edit
import kotlin.math.abs

/**
 * Reads a number that was stored under [key], whatever boxed type it happens to be.
 *
 * `SharedPreferences` keys are not typed: the value keeps the class it was written with,
 * and `getFloat` on an entry holding an `Integer` throws rather than converting. That is
 * easy to hit by accident, because changing a setting from a whole number to a fractional
 * one is an ordinary edit that leaves every existing install holding the old type. Reading
 * through here means such a change degrades to a conversion instead of a crash.
 *
 * Returns `null` for a missing key, or for anything that is not a number at all.
 */
internal fun storedNumber(value: Any?): Float? = when (value) {
    is Float -> value
    is Int -> value.toFloat()
    is Long -> value.toFloat()
    is Double -> value.toFloat()
    is String -> value.toFloatOrNull()
    else -> null
}

/**
 * Persists trigger -> action mappings plus a few tuning values.
 *
 * Backed by SharedPreferences so the accessibility service can read the current
 * mapping on every event; a change in the UI therefore takes effect immediately,
 * without restarting the service.
 */
object MappingStore {

    private const val PREFS = "open_gimbal_mappings"
    private const val KEY_ACTION_PREFIX = "action_"
    private const val KEY_PINCH_STRENGTH = "pinch_strength"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_LAST_ADDRESS = "last_address"
    private const val KEY_CAMERA_ONLY = "camera_only"
    private const val KEY_FLIP_RESTART = "flip_restart"
    private const val KEY_STICK_INVERT_X = "stick_invert_x"
    private const val KEY_STICK_INVERT_Y = "stick_invert_y"
    private const val KEY_STICK_APP_DRIVEN = "stick_app_driven"
    private const val KEY_STICK_MODE = "stick_mode"
    private const val KEY_AIM_RATE = "aim_rate"
    private const val KEY_TRACKING_OVERLAY = "tracking_overlay"
    private const val KEY_TRACKING_STRENGTH = "tracking_strength"

    /**
     * Sweep rates offered, in degrees per second at full deflection.
     *
     * Deliberately a wide, preset list rather than a fine-grained slider: the unit the
     * gimbal wants for an angle is not documented anywhere and had to be worked out by
     * watching it move, so the useful value is somewhere in here rather than at any exact
     * number.
     */
    val AIM_RATES = listOf(0.5f, 1f, 2f, 4f, 8f, 15f, 25f, 40f)

    const val DEFAULT_AIM_RATE = 4f

    /** Percentage of the tracker's base gain: 100 is the starting point, 10..400 allowed. */
    const val DEFAULT_TRACKING_STRENGTH = 100f

    /**
     * Percentage of the base finger travel per pinch gesture, so this is the zoom speed.
     *
     * Halved from what it was. At 100 the fingers travelled far enough in one gesture that
     * the zoom ran away from the slider, which is not what a slider is for - the point of a
     * slide control is to move a small amount when moved a small amount, and a zoom that
     * jumps to the end in one gesture cannot be used that way.
     */
    const val DEFAULT_PINCH_STRENGTH = 50

    /** Bounds on the setting. The floor is well below the default so slower is reachable. */
    const val MIN_PINCH_STRENGTH = 20
    const val MAX_PINCH_STRENGTH = 200

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- mappings ---------------------------------------------------------

    fun action(context: Context, trigger: GimbalTrigger): GimbalAction {
        val stored = prefs(context).getString(KEY_ACTION_PREFIX + trigger.key, null)
        return GimbalAction.byName(stored) ?: defaultFor(trigger)
    }

    fun setAction(context: Context, trigger: GimbalTrigger, action: GimbalAction) {
        prefs(context).edit { putString(KEY_ACTION_PREFIX + trigger.key, action.name) }
    }

    fun allActions(context: Context): Map<GimbalTrigger, GimbalAction> =
        GimbalTrigger.entries.associateWith { action(context, it) }

    private fun defaultFor(trigger: GimbalTrigger): GimbalAction = when (trigger) {
        // Out of the box the slider pinches, which works in most camera apps.
        GimbalTrigger.ZOOM_SLIDER -> GimbalAction.PINCH_ZOOM
        else -> GimbalAction.NONE
    }

    // ---- tuning -----------------------------------------------------------

    /** Percentage, [MIN_PINCH_STRENGTH]..[MAX_PINCH_STRENGTH]. Higher zooms faster. */
    fun pinchStrength(context: Context): Int =
        prefs(context).getInt(KEY_PINCH_STRENGTH, DEFAULT_PINCH_STRENGTH)

    fun setPinchStrength(context: Context, value: Int) {
        prefs(context).edit {
            putInt(KEY_PINCH_STRENGTH, value.coerceIn(MIN_PINCH_STRENGTH, MAX_PINCH_STRENGTH))
        }
    }

    // ---- reconnect --------------------------------------------------------

    fun autoConnect(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_CONNECT, value) }
    }

    fun lastAddress(context: Context): String? = prefs(context).getString(KEY_LAST_ADDRESS, null)

    fun setLastAddress(context: Context, address: String?) {
        prefs(context).edit { putString(KEY_LAST_ADDRESS, address) }
    }

    // ---- mapping gate -----------------------------------------------------

    /**
     * When true, mappings are ignored unless another app (a camera app) is holding
     * the camera. Off by default so the app does not change behaviour on its own.
     */
    fun cameraOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_CAMERA_ONLY, false)

    fun setCameraOnly(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_CAMERA_ONLY, value) }
    }
    // ---- video recording --------------------------------------------------

    /**
     * When true, a flip of the phone while a video is being recorded ends the clip and
     * starts a new one, so the second half is not upside down. Off by default: it takes
     * control of the camera app, so it should be a deliberate choice.
     */
    fun flipRestart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLIP_RESTART, false)

    fun setFlipRestart(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_FLIP_RESTART, value) }
    }

    // ---- on-screen stick --------------------------------------------------

    /**
     * Which way the gimbal moves for a given steering value cannot be read out of the
     * app, only watched on the device, so both axes can be flipped from the Control
     * screen rather than needing a rebuild.
     */
    fun stickInvertX(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STICK_INVERT_X, false)

    fun setStickInvertX(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_STICK_INVERT_X, value) }
    }

    fun stickInvertY(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STICK_INVERT_Y, false)

    fun setStickInvertY(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_STICK_INVERT_Y, value) }
    }

    /**
     * Which command the on-screen stick sends. Stored by name so adding a mode later does
     * not shuffle the meaning of an existing value.
     */
    fun stickMode(context: Context): String =
        prefs(context).getString(KEY_STICK_MODE, null) ?: "AIM"

    fun setStickMode(context: Context, value: String) {
        prefs(context).edit { putString(KEY_STICK_MODE, value) }
    }

    /**
     * How fast the gimbal sweeps while the stick is held, in degrees per second at full
     * deflection, so the number means the same thing however long a tick takes.
     *
     * Read through [storedNumber] rather than `getFloat`: this setting used to be an Int,
     * and an install that stored one would crash on `getFloat` instead of just being
     * converted.
     */
    fun aimRate(context: Context): Float =
        storedNumber(prefs(context).all[KEY_AIM_RATE]) ?: DEFAULT_AIM_RATE

    fun setAimRate(context: Context, value: Float) {
        prefs(context).edit { putFloat(KEY_AIM_RATE, value) }
    }

    /** The preset closest to [value], so the slider always lands on a real stop. */
    fun nearestAimRate(value: Float): Float =
        AIM_RATES.minByOrNull { abs(it - value) } ?: DEFAULT_AIM_RATE

    /**
     * The flag the official app sets when it is the phone doing the aiming, which the
     * on-screen stick is. Only used by the steering command.
     */
    fun stickAppDriven(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STICK_APP_DRIVEN, true)

    fun setStickAppDriven(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_STICK_APP_DRIVEN, value) }
    }

    // ---- tracking overlay --------------------------------------------------

    /**
     * Whether the floating button is up. Off by default: it is a window over every other
     * app, so it should only appear when asked for.
     */
    fun trackingOverlay(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRACKING_OVERLAY, false)

    fun setTrackingOverlay(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_TRACKING_OVERLAY, value) }
    }

    /**
     * How hard the tracker corrects, as a percentage of its starting gain.
     *
     * The starting gain is a guess at the camera's field of view over the screen width and
     * nobody publishes that number for an arbitrary phone, so how much correction is too
     * much can only be found by watching it hunt. Too high and it overshoots and rings; too
     * low and the object drifts off. Also read through [storedNumber] for the same reason
     * as [aimRate].
     */
    fun trackingStrength(context: Context): Float =
        storedNumber(prefs(context).all[KEY_TRACKING_STRENGTH]) ?: DEFAULT_TRACKING_STRENGTH

    fun setTrackingStrength(context: Context, value: Float) {
        prefs(context).edit { putFloat(KEY_TRACKING_STRENGTH, value.coerceIn(10f, 400f)) }
    }
}
