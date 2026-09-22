package com.itzdfplayer.opengimbal.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.itzdfplayer.opengimbal.MainActivity
import com.itzdfplayer.opengimbal.R

/**
 * Keeps a single notification up to date with the state of the gimbal link.
 *
 * The notification is replaced in place rather than reposted, so it never buzzes
 * after the first time. When nothing has ever been connected we stay silent
 * instead of yelling "disconnected" on first launch.
 */
object ConnectionNotifier {

    private const val CHANNEL_ID = "gimbal_connection"
    private const val NOTIFICATION_ID = 1001

    private const val REQUEST_OPEN = 0
    private const val REQUEST_DISCONNECT = 1
    private const val REQUEST_RECONNECT = 2

    fun showConnecting(context: Context, name: String) {
        post(
            context = context,
            title = context.getString(R.string.notification_connecting_title),
            text = name,
            ongoing = true,
            actionLabel = null,
            actionIntent = null,
        )
    }

    fun showConnected(context: Context, name: String, @StringRes modelLabelRes: Int) {
        val model = context.getString(modelLabelRes)
        val text = if (model.isBlank()) name else "$name  •  $model"
        post(
            context = context,
            title = context.getString(R.string.notification_connected_title),
            text = text,
            ongoing = true,
            actionLabel = context.getString(R.string.action_disconnect),
            actionIntent = broadcast(context, NotificationActionReceiver.ACTION_DISCONNECT, REQUEST_DISCONNECT),
        )
    }

    /**
     * Only shown once something has actually been connected, so we do not clutter
     * the shade before the user has even paired a gimbal.
     */
    fun showDisconnected(context: Context, lastKnownName: String?) {
        if (lastKnownName == null) return
        post(
            context = context,
            title = context.getString(R.string.notification_disconnected_title),
            text = lastKnownName,
            ongoing = false,
            actionLabel = context.getString(R.string.action_reconnect),
            actionIntent = broadcast(context, NotificationActionReceiver.ACTION_RECONNECT, REQUEST_RECONNECT),
        )
    }

    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    // ---- internals --------------------------------------------------------

    private fun post(
        context: Context,
        title: String,
        text: String,
        ongoing: Boolean,
        actionLabel: String?,
        actionIntent: PendingIntent?,
    ) {
        if (!canPost(context)) return
        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gimbal)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(false)

        if (actionLabel != null && actionIntent != null) {
            builder.addAction(
                NotificationCompat.Action.Builder(0, actionLabel, actionIntent).build()
            )
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.connection_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.connection_channel_description)
                setShowBadge(false)
            }
        )
    }

    private fun openApp(context: Context): PendingIntent? {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, pendingFlags())
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent? {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, requestCode, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
