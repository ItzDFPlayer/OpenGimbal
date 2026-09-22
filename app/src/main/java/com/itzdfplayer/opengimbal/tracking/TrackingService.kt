package com.itzdfplayer.opengimbal.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.itzdfplayer.opengimbal.MainActivity
import com.itzdfplayer.opengimbal.R
import com.itzdfplayer.opengimbal.gimbal.GimbalManager
import com.itzdfplayer.opengimbal.mapping.MappingStore

/**
 * Foreground service that mirrors the screen and runs the tracker.
 *
 * It has to be a foreground service: MediaProjection refuses to work from the background, and
 * the platform requires a visible, ongoing notification whenever an app is capturing the
 * screen. Being a service rather than part of the accessibility service also keeps the frame
 * callback off the accessibility thread, which is shared with gestures.
 */
class TrackingService : Service() {

    companion object {
        private const val TAG = "OpenGimbal"
        const val CHANNEL_ID = "gimbal_screen_capture"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.itzdfplayer.opengimbal.START_TRACKING"
        const val ACTION_STOP = "com.itzdfplayer.opengimbal.STOP_TRACKING"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** Starts a session. The consent activity has already been given the go-ahead. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            // stopService rather than an ACTION_STOP intent: the caller may be shutting the
            // feature down and the service may not be running, and startService from the
            // background would either throw or resurrect it.
            runCatching { context.stopService(Intent(context, TrackingService::class.java)) }
        }
    }

    private var thread: HandlerThread? = null
    private var capture: ScreenCapture? = null
    private var tracker: Tracker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null) {
                    Log.w(TAG, "No capture consent in the start intent")
                    stopEverything()
                    return START_NOT_STICKY
                }
                startSession(resultCode, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession(resultCode: Int, data: Intent) {
        // The notification has to be up before the projection starts, so the capture is
        // never running without it.
        startForegroundCompat()

        if (capture != null) return

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection: MediaProjection? = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.w(TAG, "The system refused the capture consent: ${e.message}")
            null
        }
        if (projection == null) {
            TrackingStatus.messageRes = R.string.tracking_message_capture_refused
            stopEverything()
            return
        }

        val worker = HandlerThread("gimbal-tracking").also { it.start() }
        thread = worker
        val handler = Handler(worker.looper)

        val client = GimbalManager.client(applicationContext)
        val activeTracker = Tracker(client, Handler(mainLooper)) {
            TrackingSettings(
                strength = MappingStore.trackingStrength(this),
                invertX = MappingStore.stickInvertX(this),
                invertY = MappingStore.stickInvertY(this),
            )
        }
        tracker = activeTracker

        val screenCapture = ScreenCapture(this, projection, handler) { frame ->
            activeTracker.onFrame(frame)
        }
        capture = screenCapture

        if (!screenCapture.start()) {
            TrackingStatus.messageRes = R.string.tracking_message_mirror_failed
            stopEverything()
            return
        }

        TrackingStatus.setPhase(TrackingPhase.SELECTING)
    }

    private fun stopEverything() {
        capture?.stop()
        capture = null
        tracker?.clear()
        tracker = null
        thread?.quitSafely()
        thread = null
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        capture?.stop()
        capture = null
        tracker?.clear()
        tracker = null
        thread?.quitSafely()
        thread = null
        TrackingStatus.messageRes = null
        TrackingStatus.reset()
        super.onDestroy()
    }

    // ---- notification ------------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tracking_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.tracking_capture_channel_description)
                setShowBadge(false)
            }
        )
    }

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setSmallIcon(R.drawable.ic_gimbal)
            .setContentIntent(open)
            .addAction(0, getString(R.string.tracking_notification_stop), stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
