package com.itzdfplayer.opengimbal.mapping

import androidx.annotation.StringRes
import com.itzdfplayer.opengimbal.R

/**
 * Something the gimbal can send that is worth remapping.
 *
 * The gimbal reports raw values that the official app interprets; see
 * `GimbalProtocol.decodeState`. Each entry here corresponds to one interpreted
 * event, so the user can map it independently.
 */
enum class GimbalTrigger(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
    /** Continuous triggers carry a signed value while held/moved. */
    val continuous: Boolean = false,
) {
    ZOOM_SLIDER(
        key = "zoom_slider",
        labelRes = R.string.trigger_zoom_slider,
        hintRes = R.string.trigger_zoom_slider_hint,
        continuous = true,
    ),
    TRIGGER_SINGLE(
        key = "trigger_single",
        labelRes = R.string.trigger_single,
        hintRes = R.string.trigger_single_hint,
    ),
    TRIGGER_DOUBLE(
        key = "trigger_double",
        labelRes = R.string.trigger_double,
        hintRes = R.string.trigger_double_hint,
    ),
    TRIGGER_LONG(
        key = "trigger_long",
        labelRes = R.string.trigger_long,
        hintRes = R.string.trigger_long_hint,
    ),
    SHUTTER_2(
        key = "shutter_2",
        labelRes = R.string.trigger_shutter_2,
        hintRes = R.string.trigger_shutter_2_hint,
    ),
    SHUTTER_3(
        key = "shutter_3",
        labelRes = R.string.trigger_shutter_3,
        hintRes = R.string.trigger_shutter_3_hint,
    ),
    M_SINGLE(
        key = "m_single",
        labelRes = R.string.trigger_m_single,
        hintRes = R.string.trigger_m_single_hint,
    ),
    M_DOUBLE(
        key = "m_double",
        labelRes = R.string.trigger_m_double,
        hintRes = R.string.trigger_m_double_hint,
    );

    companion object {
        fun byKey(key: String): GimbalTrigger? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What to do when a trigger fires.
 *
 * Only [PINCH_ZOOM] and [VOLUME_STEP] are continuous; they are driven by the
 * signed value of a continuous trigger.
 */
enum class GimbalAction(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val continuous: Boolean = false,
) {
    NONE(R.string.action_none, R.string.action_none_description),
    PINCH_ZOOM(
        R.string.action_pinch_zoom,
        R.string.action_pinch_zoom_description,
        continuous = true,
    ),
    VOLUME_STEP(
        R.string.action_volume_step,
        R.string.action_volume_step_description,
        continuous = true,
    ),

    SCREEN_SHUTTER(
        R.string.action_screen_shutter,
        R.string.action_screen_shutter_description,
    ),

    VOLUME_UP(R.string.action_volume_up, R.string.action_volume_up_description),
    VOLUME_DOWN(R.string.action_volume_down, R.string.action_volume_down_description),
    PLAY_PAUSE(R.string.action_play_pause, R.string.action_media_description),
    MEDIA_NEXT(R.string.action_media_next, R.string.action_media_description),
    MEDIA_PREVIOUS(R.string.action_media_previous, R.string.action_media_description),
    BACK(R.string.action_back, R.string.action_global_description),
    HOME(R.string.action_home, R.string.action_global_description),
    RECENTS(R.string.action_recents, R.string.action_global_description),
    SWIPE_UP(R.string.action_swipe_up, R.string.action_swipe_description),
    SWIPE_DOWN(R.string.action_swipe_down, R.string.action_swipe_description),
    SWIPE_LEFT(R.string.action_swipe_left, R.string.action_swipe_horizontal_description),
    SWIPE_RIGHT(R.string.action_swipe_right, R.string.action_swipe_horizontal_description);

    companion object {
        /** Options offered for continuous triggers such as the zoom slider. */
        val continuousOptions: List<GimbalAction> = listOf(NONE, PINCH_ZOOM, VOLUME_STEP)

        /** Options offered for one-shot buttons. */
        val discreteOptions: List<GimbalAction> = entries.filterNot { it.continuous }

        fun optionsFor(trigger: GimbalTrigger): List<GimbalAction> =
            if (trigger.continuous) continuousOptions else discreteOptions

        fun byName(name: String?): GimbalAction? = entries.firstOrNull { it.name == name }
    }
}
