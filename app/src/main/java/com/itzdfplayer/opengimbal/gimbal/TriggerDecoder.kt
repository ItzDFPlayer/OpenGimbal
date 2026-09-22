package com.itzdfplayer.opengimbal.gimbal

/** Something the trigger button did. */
enum class TriggerEvent(val label: String) {
    PRESS_DOWN("press down"),
    SINGLE_CLICK("single click"),
    DOUBLE_CLICK("double click"),
    LONG_PRESS("long press"),
}

/** A [TriggerEvent], plus how long the button had been held (long press only). */
data class TriggerTransition(val event: TriggerEvent, val holdMs: Long = 0L)

/**
 * Decodes the trigger button from its raw status nibble.
 *
 * Measured on a Proove Axis-M01, one press looks like this:
 *
 * ```
 * 15      press marker
 * 0       brief idle gap (present on every press)
 * 1       -> single click
 * 2       -> double click
 * 15      -> long press, meaning "still holding"
 * ```
 *
 * The brief `0` is the part that matters. It must **not** be treated as a release,
 * otherwise the second `15` merely looks like the start of a fresh press and the
 * hold is never seen. Instead the `0` is remembered as a gap, and a `15` arriving
 * after a gap is the hold marker.
 *
 * Gimbal Show only reacts to `1`/`2`/`3` on a `0 -> value` edge, which cannot express
 * any of this, so the decoder tracks the gesture instead of edges.
 *
 * [onState] must be called for **every** status packet, not only on changes: the
 * deciding value is usually a repeat of one already seen.
 */
class TriggerDecoder(
    private val maxHoldMs: Long = DEFAULT_MAX_HOLD_MS,
) {
    companion object {
        /** No button activity. */
        const val IDLE = 0

        /** Press/hold marker sent at the start of every press. */
        const val PRESS = 0xF

        const val SINGLE = 1
        const val DOUBLE = 2

        /** Distinct long-press code. Some models report this instead of a second [PRESS]. */
        const val LONG = 3

        /**
         * How long a press may stay unresolved before a `15` is treated as the start
         * of a *new* press rather than a hold on the previous one. Guards against a
         * press that never received a classification.
         */
        const val DEFAULT_MAX_HOLD_MS = 2_000L
    }

    /** The last raw value seen. */
    var raw: Int = IDLE
        private set

    /** `true` while a press is in progress and not yet classified. */
    var isPressed: Boolean = false
        private set

    private var pressedAt = 0L

    /** A `0` has been seen since the press marker. */
    private var sawIdle = false

    /** This press has already produced an event. */
    private var resolved = false

    fun onState(rawValue: Int, now: Long): List<TriggerTransition> {
        val transitions = mutableListOf<TriggerTransition>()
        // Explicit flag rather than a timestamp sentinel: a timestamp of 0 is legal.
        val heldMs = if (isPressed) now - pressedAt else 0L

        when (rawValue) {
            PRESS -> {
                when {
                    // Nothing in flight: this is the start of a press.
                    !isPressed -> transitions += startPress(now)

                    // A press that never got classified, long ago. Start over.
                    now - pressedAt > maxHoldMs -> transitions += startPress(now)

                    // The marker came back after the idle gap: the button is held.
                    sawIdle && !resolved -> {
                        resolved = true
                        sawIdle = false
                        transitions += TriggerTransition(TriggerEvent.LONG_PRESS, heldMs)
                    }

                    // Idle after a gesture that already finished: a new press.
                    sawIdle -> transitions += startPress(now)

                    else -> Unit
                }
            }

            // A brief idle between the marker and the classification. This must NOT
            // end the press; see the class docs.
            IDLE -> if (isPressed) sawIdle = true

            // Click codes are edge triggered, so a repeated value is not re-reported.
            SINGLE -> if (raw != SINGLE) {
                transitions += TriggerTransition(TriggerEvent.SINGLE_CLICK, heldMs)
                endPress()
            }

            DOUBLE -> if (raw != DOUBLE) {
                transitions += TriggerTransition(TriggerEvent.DOUBLE_CLICK, heldMs)
                endPress()
            }

            LONG -> if (raw != LONG) {
                transitions += TriggerTransition(TriggerEvent.LONG_PRESS, heldMs)
                endPress()
            }

            // Anything unexpected ends the gesture so we cannot get stuck pressed.
            else -> endPress()
        }

        raw = rawValue
        return transitions
    }

    private fun startPress(now: Long): TriggerTransition {
        isPressed = true
        resolved = false
        sawIdle = false
        pressedAt = now
        return TriggerTransition(TriggerEvent.PRESS_DOWN)
    }

    private fun endPress() {
        isPressed = false
        resolved = true
        sawIdle = false
        pressedAt = 0L
    }

    fun reset() {
        raw = IDLE
        isPressed = false
        resolved = false
        sawIdle = false
        pressedAt = 0L
    }
}
