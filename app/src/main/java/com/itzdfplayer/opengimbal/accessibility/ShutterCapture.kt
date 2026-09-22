package com.itzdfplayer.opengimbal.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Reads the screen through the accessibility service so the shutter can be found in
 * pixels rather than in the view tree.
 *
 * This is what makes surface-rendered camera UIs work: when an app draws its whole
 * interface on a surface there are no nodes to query, but the screenshot still shows
 * the button.
 *
 * Needs `canTakeScreenshot` in the service config, so API 30 and up. On older
 * releases [supported] is false and callers fall back to the tree. The platform also
 * rate-limits captures, so a recent find is reused for a few seconds, which doubles
 * as making a burst of shots cheap.
 */
class ShutterCapture(private val service: AccessibilityService) {

    private companion object {
        const val TAG = "OpenGimbal"

        /** Spam guard, not the platform's own limit - that is handled on failure. */
        const val MIN_INTERVAL_MS = 150L

        /** How long a find stays usable; the shutter does not move between shots. */
        const val CACHE_MS = 5_000L
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gimbal-shutter-capture")
    }

    private var lastAttemptAt = 0L

    private var cached: PixelShutter? = null
    private var cachedAt = 0L

    @Volatile
    private var inFlight = false

    /** Screenshots need API 30 and the `canTakeScreenshot` flag. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Call on the main thread. [onResult] runs on a background thread and receives
     * `null` when the screen could not be read or holds no shutter.
     */
    fun capture(onResult: (PixelShutter?) -> Unit) {
        val now = SystemClock.uptimeMillis()

        // A recent find is as good as a new one, and costs the platform nothing.
        cached?.let { recent ->
            if (now - cachedAt <= CACHE_MS) {
                onResult(recent)
                return
            }
        }

        if (!supported || inFlight || now - lastAttemptAt < MIN_INTERVAL_MS) {
            onResult(null)
            return
        }

        inFlight = true
        lastAttemptAt = now
        request(onResult)
    }

    /** Drops the cached position, for example when the mapping changes. */
    fun forget() {
        cached = null
        cachedAt = 0L
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun request(onResult: (PixelShutter?) -> Unit) {
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val shutter = runCatching { analyse(result) }
                    .onFailure { Log.w(TAG, "Could not analyse the screenshot", it) }
                    .getOrNull()
                if (shutter != null) {
                    cached = shutter
                    cachedAt = SystemClock.uptimeMillis()
                    Log.i(TAG, "Shutter found in screenshot at ${shutter.centerX},${shutter.centerY}")
                } else {
                    Log.i(TAG, "No shutter in the screenshot")
                }
                finish(onResult, shutter)
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "takeScreenshot refused, error $errorCode")
                finish(onResult, null)
            }
        }

        val queued = runCatching {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
        }.isSuccess
        if (!queued) {
            Log.w(TAG, "takeScreenshot could not be started")
            finish(onResult, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun analyse(result: AccessibilityService.ScreenshotResult): PixelShutter? {
        val buffer = result.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            // A hardware bitmap refuses getPixels, so take a software copy of it.
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val width = software.width
            val height = software.height
            if (width <= 0 || height <= 0) {
                software.recycle()
                return null
            }

            val pixels = IntArray(width * height)
            software.getPixels(pixels, 0, width, 0, 0, width, height)
            software.recycle()

            return ShutterPixelDetector.find(pixels, width, height)
        } finally {
            buffer.close()
        }
    }

    private fun finish(onResult: (PixelShutter?) -> Unit, shutter: PixelShutter?) {
        inFlight = false
        onResult(shutter)
    }
}
