package com.itzdfplayer.opengimbal.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.itzdfplayer.opengimbal.gimbal.GimbalManager

/**
 * Handles the actions on the connection notification.
 *
 * Not exported: only the PendingIntent we created can reach it.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISCONNECT = "com.itzdfplayer.opengimbal.DISCONNECT"
        const val ACTION_RECONNECT = "com.itzdfplayer.opengimbal.RECONNECT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val client = GimbalManager.client(context)
        when (intent.action) {
            ACTION_DISCONNECT -> client.disconnect()

            ACTION_RECONNECT -> {
                // Fall back to a scan when the last gimbal is no longer paired.
                if (!GimbalManager.reconnectLast(context)) {
                    client.startScan()
                }
            }
        }
    }
}
