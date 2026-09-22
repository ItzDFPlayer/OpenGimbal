package com.itzdfplayer.opengimbal.tracking

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlin.math.min

/**
 * Mirrors the display into memory and hands out luminance frames.
 *
 * MediaProjection rather than an accessibility screenshot because this has to run per frame:
 * the platform deliberately rate-limits accessibility screenshots to a handful a second,
 * which is far too slow to hold a lock on something the gimbal is chasing.
 *
 * The conversion to luminance happens here, while copying out of the reader's buffer, rather
 * than in a second pass over three million pixels - at these rates that pass would cost more
 * than the matching does.
 *
 * The mirrored size is the display size, so a point in a frame and a point on screen are the
 * same coordinates. That is what lets the overlay's selection be used directly as a position
 * in a frame.
 */
class ScreenCapture(
    context: Context,
    private val projection: MediaProjection,
    private val handler: Handler,
    private val onFrame: (Gray) -> Unit,
) {

    private companion object {
        const val TAG = "OpenGimbal"
        const val DISPLAY_NAME = "opengimbal-tracking"
    }

    private val appContext = context.applicationContext

    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var row: ByteArray = ByteArray(0)

    /** Size of the mirrored display, which is also the size of the frames handed out. */
    var width = 0
        private set
    var height = 0
        private set

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user can revoke capture from the system UI at any time.
            Log.i(TAG, "Screen capture stopped by the system")
            stop()
        }
    }

    /** @return false when the display could not be mirrored at all. */
    fun start(): Boolean {
        val size = displaySize() ?: return false
        return start(size[0], size[1])
    }

    private fun start(frameWidth: Int, frameHeight: Int): Boolean = try {
        projection.registerCallback(callback, handler)
        width = frameWidth
        height = frameHeight

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            DISPLAY_NAME,
            width,
            height,
            appContext.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            handler,
        )
        reader?.setOnImageAvailableListener({ available -> publish(available) }, handler)
        Log.i(TAG, "Capturing the screen at ${width}x$height")
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not mirror the display: ${e.message}")
        stop()
        false
    }

    fun stop() {
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        virtualDisplay = null
        reader = null
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
    }

    private fun displaySize(): IntArray? {
        val windowManager = appContext.getSystemService(WindowManager::class.java) ?: return null
        val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            intArrayOf(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            intArrayOf(metrics.widthPixels, metrics.heightPixels)
        }
        return size.takeIf { it[0] > 0 && it[1] > 0 }
    }

    private fun publish(available: ImageReader) {
        val image = available.acquireLatestImage() ?: return
        try {
            onFrame(toGray(image))
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a frame: ${e.message}")
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * The reader gives RGBA rows with padding, so the row stride has to be respected.
     * Assuming tightly packed rows would work on some devices and shear the image on others.
     */
    private fun toGray(image: Image): Gray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (row.size < rowStride) row = ByteArray(rowStride)
        val out = IntArray(width * height)

        for (y in 0 until height) {
            buffer.position(y * rowStride)
            val available = min(rowStride, buffer.remaining())
            if (available <= 0) break
            buffer.get(row, 0, available)

            var x = 0
            var offset = 0
            val rowBase = y * width
            while (x < width && offset + 2 < available) {
                val r = row[offset].toInt() and 0xFF
                val g = row[offset + 1].toInt() and 0xFF
                val b = row[offset + 2].toInt() and 0xFF
                out[rowBase + x] = (r * 77 + g * 150 + b * 29) shr 8
                x++
                offset += pixelStride
            }
        }
        return Gray(out, width, height)
    }
}
