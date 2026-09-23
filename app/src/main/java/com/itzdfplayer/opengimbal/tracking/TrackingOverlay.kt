package com.itzdfplayer.opengimbal.tracking

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.itzdfplayer.opengimbal.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The windows the tracking feature draws over other apps.
 *
 * They are added by the accessibility service, using `TYPE_ACCESSIBILITY_OVERLAY`, which is
 * the one window type that can go over other apps without asking the user for the "display
 * over other apps" permission - a service is trusted for that already.
 *
 * Plain views rather than Compose on purpose. A Compose window needs a lifecycle owner and a
 * saved-state registry plumbed into `setViewTreeLifecycleOwner`, which is a lot of machinery
 * for a button and two rectangles drawn by a service that has no activity.
 *
 * The marker is deliberately drawn *around* the patch rather than over it: the marker appears
 * in the captured frames, so anything drawn inside the tracked area would be matched against
 * itself.
 */
class TrackingOverlay(private val service: AccessibilityService) {

    private companion object {
        const val TAG = "OpenGimbal"

        /** Diameter of the floating button, in dp. */
        const val BUTTON_DP = 48

        /** How far the floating button may sit from the edge, in dp. */
        const val BUTTON_MARGIN_DP = 12

        /**
         * The marker is drawn this much larger than the patch.
         *
         * The marker is in the mirrored frames like everything else on screen, so its
         * outline has to fall outside the pixels that are actually matched - otherwise the
         * tracker would be looking for a patch with part of the marker painted on it.
         */
        const val MARKER_SCALE = 1.35f
    }

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var button: View? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var scrim: View? = null
    private var marker: View? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var chip: TextView? = null

    /** Screen size, so the button can be kept on screen and the marker sized in pixels. */
    var screenWidth = 0
        private set
    var screenHeight = 0
        private set

    /** Whether any of the windows are currently up. */
    /**
     * Whether the floating button window is up.
     *
     * Not the same question as whether the feature is switched on, and asking the wrong one
     * of the two is what once stopped the button ever coming back: it is removed whenever no
     * camera app is running, so anything that waits for a window to exist before doing
     * overlay work stops for good the first time the button goes away.
     */
    val isShowing: Boolean get() = button != null

    fun setScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    // ---- the floating button ----------------------------------------------

    /** Adds the floating button, which is what the user presses to start a session. */
    fun showButton() {
        if (button != null || screenWidth == 0) return

        val size = dp(BUTTON_DP)
        val margin = dp(BUTTON_MARGIN_DP)
        val view = FloatingButton(service) { onButtonPressed() }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Anchored to the right edge by gravity, rather than placed at a coordinate worked
            // out from the measured screen width. The two say the same thing while the
            // measurement is right, and only one of them survives it being wrong: this window
            // is allowed outside the display, so an x derived from a stale width - a size
            // taken before a rotation, say - puts the button off the screen entirely, where it
            // is invisible and, being a window, gives no other sign that it exists at all.
            // Resolved against the display by the system, the margin stays a margin.
            //
            // END rather than RIGHT because that is the non-deprecated one, which makes this
            // the right edge for every locale the app ships. An RTL locale would resolve it to
            // the left edge, and the drag below assumes the anchor it was laid out with.
            gravity = Gravity.TOP or Gravity.END
            x = margin
            y = screenHeight / 3
        }

        attachDrag(view, params)

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                Log.w(TAG, "Could not add the floating button: ${it.message}")
                return
            }
        button = view
        buttonParams = params
    }

    fun hideButton() {
        button?.let { runCatching { windowManager.removeView(it) } }
        button = null
        buttonParams = null
    }

    /**
     * Drag to move, tap to act. Tracked by hand rather than with `setOnClickListener` because
     * a drag would otherwise also register as a click.
     */
    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).roundToInt()
                    val dy = (event.rawY - touchY).roundToInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    // Minus dx, not plus: the gravity anchors the button to the right edge, so
                    // x counts inwards from there and a finger moving right makes it smaller.
                    // The clamp is the same range either way - nought is flush against the
                    // right edge, and the width of the screen less the button is flush against
                    // the left.
                    params.x = (startX - dx)
                        .coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
                    params.y = (startY + dy)
                        .coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun onButtonPressed() {
        onButton?.invoke()
    }

    /** What the floating button does. Set by the accessibility service. */
    var onButton: (() -> Unit)? = null

    // ---- the selection scrim ----------------------------------------------

    /**
     * Dims the screen and waits for a tap. The scrim is full screen and takes touches, so the
     * camera app underneath is not disturbed while a target is picked.
     */
    fun showSelection() {
        if (scrim != null || screenWidth == 0) return

        val view = SelectionScrim(service) { x, y ->
            TrackingStatus.pendingSelection = intArrayOf(x, y)
            hideSelection()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                Log.w(TAG, "Could not add the selection overlay: ${it.message}")
                return
            }
        scrim = view
    }

    fun hideSelection() {
        scrim?.let { runCatching { windowManager.removeView(it) } }
        scrim = null
    }

    // ---- the marker and the status chip -----------------------------------

    /** Draws a box around the patch so the user can see what is being followed. */
    fun updateMarker(centerX: Int, centerY: Int, patchSize: Int) {
        if (screenWidth == 0 || patchSize <= 0) return

        val size = (patchSize * MARKER_SCALE).roundToInt()
        val view = marker ?: TargetMarker(service).also { created ->
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            runCatching { windowManager.addView(created, params) }
                .onFailure {
                    Log.w(TAG, "Could not add the marker: ${it.message}")
                    return
                }
            marker = created
            markerParams = params
        }

        val params = markerParams ?: return
        params.width = size
        params.height = size
        params.x = centerX - size / 2
        params.y = centerY - size / 2
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun hideMarker() {
        marker?.let { runCatching { windowManager.removeView(it) } }
        marker = null
        markerParams = null
    }

    /**
     * A small line of text at the top of the screen with the session state.
     *
     * Kept to the very top edge and out of the middle, because whatever it covers is captured
     * in the frames and cannot be tracked.
     */
    fun updateChip(text: String?) {
        if (text == null) {
            chip?.let { runCatching { windowManager.removeView(it) } }
            chip = null
            return
        }

        val view = chip ?: TextView(service).also { created ->
            created.setBackgroundColor(Color.argb(150, 0, 0, 0))
            created.setTextColor(Color.WHITE)
            created.textSize = 12f
            created.setPadding(dp(10), dp(4), dp(10), dp(4))
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(8)
            }
            runCatching { windowManager.addView(created, params) }
                .onFailure {
                    Log.w(TAG, "Could not add the status chip: ${it.message}")
                    return
                }
            chip = created
        }
        view.text = text
    }

    fun hideAll() {
        hideSelection()
        hideMarker()
        updateChip(null)
        hideButton()
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).roundToInt()

    private fun dp(value: Float): Int =
        (value * service.resources.displayMetrics.density).roundToInt()
}

/** The draggable round button. Drawn rather than composed, see the class comment. */
private class FloatingButton(context: Context, onClick: () -> Unit) : View(context) {

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0xC6, 0x28, 0x28)
    }

    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    init {
        isClickable = true
        setOnClickListener { onClick() }
        contentDescription = context.getString(R.string.overlay_track_object)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 2f
        canvas.drawCircle(cx, cy, radius, background)

        // A crosshair, so the button reads as "aim at something" rather than a generic dot.
        val arm = radius * 0.45f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, glyph)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, glyph)
        canvas.drawCircle(cx, cy, arm * 1.5f, glyph)
    }
}

/**
 * The dimmed full-screen layer that asks for a tap. Dim enough to read the instruction
 * against, light enough to still see what is being picked.
 */
private class SelectionScrim(context: Context, private val onPick: (Int, Int) -> Unit) : View(context) {

    private val instruction = context.getString(R.string.overlay_press_object)
    private val note = context.getString(R.string.overlay_camera_keeps_running)

    private val dim = Paint().apply { color = Color.argb(90, 0, 0, 0) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawText(
            instruction,
            width / 2f,
            height * 0.42f,
            text,
        )
        text.textSize = 26f
        canvas.drawText(
            note,
            width / 2f,
            height * 0.42f + 46f,
            text,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            onPick(event.x.roundToInt(), event.y.roundToInt())
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** A thin square outline marking what is being followed. */
private class TargetMarker(context: Context) : View(context) {

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0x2E, 0xCC, 0x71)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        box.set(2f, 2f, width - 2f, height - 2f)
        canvas.drawRect(box, outline)
    }
}
