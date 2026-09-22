package com.itzdfplayer.opengimbal.gimbal

import android.bluetooth.BluetoothManager
import android.content.Context
import com.itzdfplayer.opengimbal.mapping.MappingStore

/**
 * Process-wide holder for the single gimbal connection.
 *
 * The connection is deliberately *not* tied to an Activity. An enabled
 * AccessibilityService keeps our process alive, so the gimbal stays connected
 * and mappings keep working while the app is in the background.
 */
object GimbalManager {

    @Volatile
    private var instance: GimbalBleClient? = null

    fun client(context: Context): GimbalBleClient {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: GimbalBleClient(context.applicationContext).also { instance = it }
        }
    }

    /**
     * Reconnects to the last gimbal that was used, if it is still paired with the
     * phone. Returns `true` when a connection attempt was started.
     */
    fun reconnectLast(context: Context): Boolean {
        if (!MappingStore.autoConnect(context)) return false
        val address = MappingStore.lastAddress(context) ?: return false

        val client = client(context)
        if (client.isConnected || client.connecting) return false

        val device = runCatching {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.getRemoteDevice(address)
        }.getOrNull() ?: return false

        val name = runCatching { device.name }.getOrNull()
        if (!DeviceTable.isSupported(name)) return false

        client.connect(device)
        return true
    }
}
