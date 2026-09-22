package com.itzdfplayer.opengimbal.gimbal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.itzdfplayer.opengimbal.R
import com.itzdfplayer.opengimbal.mapping.GimbalTrigger
import com.itzdfplayer.opengimbal.mapping.MappingStore
import com.itzdfplayer.opengimbal.notifications.ConnectionNotifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal BLE client for the Hohem/Honji gimbal family.
 *
 * Mirrors the connection sequence used by Gimbal Show
 * (`com.honji.device_gimbal.comm.BluetoothService`):
 * connect -> discover services -> requestMtu -> subscribe to FFE2 -> handshake,
 * then decode the streaming status packets.
 */
@SuppressLint("MissingPermission")
class GimbalBleClient(private val context: Context) {

    data class FoundDevice(
        val device: BluetoothDevice,
        val name: String,
        val rssi: Int,
        val model: GimbalModel,
        /** Paired with this phone, so it is reachable even while it is not advertising. */
        val bonded: Boolean = false,
        /** Currently has an active GATT connection to this phone. */
        val connected: Boolean = false,
    ) {
        val address: String get() = device.address

        /** Where this device came from, shown next to it in the list. */
        @get:StringRes
        val sourceLabelRes: Int
            get() = when {
                connected -> R.string.source_connected
                bonded -> R.string.source_paired
                else -> R.string.source_advertising
            }
    }

    // ---- observable state -------------------------------------------------

    var bluetoothReady by mutableStateOf(false)
        private set
    var scanning by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set
    var devices by mutableStateOf<List<FoundDevice>>(emptyList())
        private set

    var connectedName by mutableStateOf<String?>(null)
        private set
    var connectedAddress by mutableStateOf<String?>(null)
        private set
    var connectedModel by mutableStateOf(GimbalModel.NONE)
        private set
    var mtu by mutableStateOf(0)
        private set

    @get:StringRes
    var statusRes by mutableStateOf(R.string.status_idle)
        private set

    var state by mutableStateOf<GimbalState?>(null)
        private set

    var packetCount by mutableStateOf(0)
        private set
    var lastPacketName by mutableStateOf("-")
        private set
    var lastPacketHex by mutableStateOf("")
        private set

    var log by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * Receives `(trigger, value)` for every interpreted event, so the accessibility
     * service can act on it. Value is `1` for one-shot triggers and the signed
     * slider reading for continuous ones (`0` means released).
     *
     * Called on the thread that delivers the GATT notification.
     */
    @Volatile
    var eventListener: ((GimbalTrigger, Int) -> Unit)? = null

    val isConnected: Boolean get() = connectedAddress != null

    // ---- internals --------------------------------------------------------

    private companion object {
        /**
         * How many frames may be waiting to go out.
         *
         * Generous enough that nothing legitimate ever touches it - the handshake and the
         * stick are both far slower than the link - while still bounding what a runaway
         * producer could pile up.
         */
        const val MAX_QUEUED_WRITES = 16
    }

    private val main = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    private val descriptorQueue = ArrayDeque<BluetoothGattDescriptor>()

    private var handshakeAttempts = 0
    private var handshakeDone = false
    private var previous: GimbalState? = null

    /** True once the type-0 device info packet has arrived and data is flowing. */
    private var streaming = false

    /** Remembered so the "disconnected" notification can still name the gimbal. */
    private var lastConnectedName: String? = null

    /** Interprets the trigger nibble, which repeats `15` to signal a hold. */
    private val triggerDecoder = TriggerDecoder()

    private val bluetoothManager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    // ---- scanning ---------------------------------------------------------

    /**
     * A gimbal that is paired and/or already connected stops advertising, so
     * [startScan] alone can never find it. Everything we already know about is
     * added to the list up front.
     */
    private fun addKnownDevices() {
        val manager = bluetoothManager ?: return
        val connectedAddresses = try {
            manager.getConnectedDevices(BluetoothProfile.GATT).map { it.address }.toSet()
        } catch (_: SecurityException) {
            emptySet()
        }

        val bonded = try {
            manager.adapter?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }

        var added = 0
        for (device in bonded) {
            val name = deviceName(device) ?: continue
            if (!DeviceTable.isSupported(name)) continue
            upsert(
                FoundDevice(
                    device = device,
                    name = name,
                    rssi = 0,
                    model = DeviceTable.modelOf(name),
                    bonded = true,
                    connected = connectedAddresses.contains(device.address),
                )
            )
            added++
        }
        if (added > 0) addLog("$added paired gimbal(s) known to the system")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = deviceName(device) ?: result.scanRecord?.deviceName ?: return
            if (name.isEmpty()) return
            if (!DeviceTable.isSupported(name)) return

            upsert(
                FoundDevice(
                    device = device,
                    name = name,
                    rssi = result.rssi,
                    model = DeviceTable.modelOf(name),
                )
            )
        }

        override fun onScanFailed(errorCode: Int) {
            addLog("Scan failed, error=$errorCode")
            scanning = false
        }
    }

    /** Prefers the cached/system name over the possibly truncated advertising name. */
    private fun deviceName(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) {
        null
    }

    private fun upsert(found: FoundDevice) {
        val existing = devices.firstOrNull { it.address == found.address }
        val merged = if (existing == null) {
            found
        } else {
            found.copy(
                rssi = if (found.rssi != 0) found.rssi else existing.rssi,
                bonded = existing.bonded || found.bonded,
                connected = existing.connected || found.connected,
            )
        }
        devices = (devices.filterNot { it.address == found.address } + merged).sortedWith(
            compareByDescending<FoundDevice> { it.connected }
                .thenByDescending { it.bonded }
                .thenByDescending { it.rssi }
        )
    }

    fun refreshAdapterState() {
        bluetoothReady = adapter?.isEnabled == true
    }

    fun startScan(durationMs: Long = 10_000L) {
        refreshAdapterState()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            addLog("Bluetooth is off or unavailable")
            return
        }

        // Below API 31 the BLE scan is gated on location services being on.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationServicesEnabled()) {
            addLog("Location services are OFF - Android < 12 will not return BLE results")
        }

        if (scanning) return
        devices = emptyList()
        // Paired gimbals never advertise, so seed the list from the system first.
        addKnownDevices()

        // If that left us with exactly one gimbal there is nothing to choose, so
        // connect straight away and skip the scan entirely.
        if (AutoConnectPolicy.shouldConnect(devices.size, autoConnectEnabled(), isConnected, connecting)) {
            addLog("Only one gimbal available - connecting automatically")
            connect(devices.first().device)
            return
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .build()
            scanner.startScan(null, settings, scanCallback)
            scanning = true
            statusRes = if (devices.isEmpty()) {
                R.string.status_scanning_for_gimbals
            } else {
                R.string.status_scanning
            }
            addLog("Scan started")
            main.postDelayed({
                stopScan()
                addLog("Scan finished, ${devices.size} device(s)")
                if (devices.isEmpty()) {
                    addLog("Nothing found - pair the gimbal in system Bluetooth settings, then tap \"Refresh paired\"")
                } else {
                    // A lone advertising gimbal is just as unambiguous as a lone paired one.
                    if (AutoConnectPolicy.shouldConnect(devices.size, autoConnectEnabled(), isConnected, connecting)) {
                        addLog("Only one gimbal found - connecting automatically")
                        connect(devices.first().device)
                    }
                }
            }, durationMs)
        } catch (e: SecurityException) {
            addLog("Scan denied: ${e.message}")
        }
    }

    private fun autoConnectEnabled(): Boolean = MappingStore.autoConnect(context)

    private fun locationServicesEnabled(): Boolean = try {
        (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
            ?.isLocationEnabled ?: true
    } catch (_: Exception) {
        true
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    /** Re-reads paired/connected gimbals without starting a BLE scan. */
    fun refreshKnownDevices() {
        refreshAdapterState()
        val before = devices.size
        addKnownDevices()
        if (devices.isEmpty()) {
            addLog("No paired gimbal found - pair it in system Bluetooth settings first")
        } else if (devices.size == before) {
            addLog("Known devices refreshed (${devices.size})")
        }
    }

    // ---- connection -------------------------------------------------------

    fun connect(device: BluetoothDevice) {
        disconnect()
        statusRes = R.string.status_connecting
        connecting = true
        previous = null
        handshakeDone = false
        handshakeAttempts = 0
        packetCount = 0
        try {
            connectedName = try {
                device.name
            } catch (_: SecurityException) {
                null
            }
            connectedAddress = device.address
            connectedModel = DeviceTable.modelOf(connectedName)
            lastConnectedName = connectedName ?: device.address
            addLog("Connecting to ${connectedName ?: device.address}")
            updateNotification()
            @Suppress("DEPRECATION")
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            addLog("Connect denied: ${e.message}")
            connecting = false
        }
        main.postDelayed({
            if (connecting) {
                addLog("Connect timeout")
                disconnect()
            }
        }, 10_000L)
    }

    fun disconnect() {
        // Stop any continuous mapped action that is still running.
        emit(GimbalTrigger.ZOOM_SLIDER, 0)
        main.removeCallbacksAndMessages(null)
        handshakeDone = true
        writeQueue.clear()
        descriptorQueue.clear()
        writing = false
        writeChar = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        triggerDecoder.reset()
        streaming = false
        connecting = false
        connectedName = null
        connectedAddress = null
        connectedModel = GimbalModel.NONE
        mtu = 0
        state = null
        previous = null
        statusRes = R.string.status_disconnected
        updateNotification()
    }

    fun dispose() {
        stopScan()
        disconnect()
    }

    // ---- GATT callback ----------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            gattStatus: Int,
            newState: Int,
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED && gattStatus == 0) {
                connecting = false
                statusRes = R.string.status_discovering
                addLog("GATT connected")
                connectedAddress?.let { MappingStore.setLastAddress(context, it) }
                updateNotification()
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                addLog("GATT disconnected (status=$gattStatus)")
                disconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, gattStatus: Int) {
            if (gattStatus != 0) {
                addLog("Service discovery failed, status=$gattStatus")
                disconnect()
                return
            }
            val service = g.getService(GimbalProtocol.SERVICE_UUID)
            if (service == null) {
                addLog("Service FFE0 not found - not a supported gimbal?")
                disconnect()
                return
            }

            var notify: BluetoothGattCharacteristic? = null
            for (c in service.characteristics) {
                addLog("char ${c.uuid}")
                when (c.uuid) {
                    GimbalProtocol.WRITE_UUID -> writeChar = c
                    GimbalProtocol.NOTIFY_UUID -> notify = c
                }
            }
            if (writeChar == null || notify == null) {
                addLog("FFE1/FFE2 characteristics missing")
                disconnect()
                return
            }
            statusRes = R.string.status_negotiating_mtu
            g.requestMtu(200)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtuValue: Int, gattStatus: Int) {
            mtu = mtuValue
            addLog("MTU = $mtuValue (status=$gattStatus)")
            val service = g.getService(GimbalProtocol.SERVICE_UUID) ?: return

            // Subscribe to every notifiable characteristic of the gimbal service.
            descriptorQueue.clear()
            for (c in service.characteristics) {
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) continue
                g.setCharacteristicNotification(c, true)
                val descriptor = c.getDescriptor(GimbalProtocol.CCCD_UUID) ?: continue
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                descriptorQueue.addLast(descriptor)
            }
            addLog("Subscribing to ${descriptorQueue.size} characteristic(s)")
            writeNextDescriptor()

            statusRes = R.string.status_handshaking
            main.postDelayed(handshakeRunnable, 300L)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            gattStatus: Int,
        ) {
            writeNextDescriptor()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleNotification(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            gattStatus: Int,
        ) {
            writing = false
            writeNext()
        }
    }

    // ---- handshake + writes ----------------------------------------------

    private val handshakeRunnable = object : Runnable {
        override fun run() {
            if (handshakeDone || writeChar == null) return
            if (handshakeAttempts >= GimbalProtocol.HANDSHAKE_MAX_ATTEMPTS) {
                handshakeDone = true
                addLog("Handshake gave up after ${GimbalProtocol.HANDSHAKE_MAX_ATTEMPTS} attempts")
                statusRes = R.string.status_no_handshake
                updateNotification()
                return
            }
            val frame = if (handshakeAttempts % 2 == 0) {
                GimbalProtocol.HANDSHAKE_WRAPPED
            } else {
                GimbalProtocol.HANDSHAKE_RAW
            }
            handshakeAttempts++
            write(frame)
            main.postDelayed(this, GimbalProtocol.HANDSHAKE_INTERVAL_MS)
        }
    }

    private fun write(data: ByteArray) {
        // A control loop can produce commands faster than a BLE link can carry them, and an
        // unbounded queue is worse than a lossy one: every frame waiting in it was right
        // when it was made, and the ones that reach the gimbal last are the most stale. For
        // a loop that is chasing something, only the newest command is worth sending, so the
        // oldest is the one to drop.
        var dropped = 0
        while (writeQueue.size >= MAX_QUEUED_WRITES) {
            writeQueue.removeFirst()
            dropped++
        }
        if (dropped > 0) addLog("Write queue full, dropped $dropped stale frame(s)")

        writeQueue.addLast(data)
        writeNext()
    }

    /**
     * Sends a raw frame, queued behind anything already in flight. Returns false when
     * there is nowhere to send it.
     */
    fun send(data: ByteArray): Boolean {
        if (!isConnected || writeChar == null) return false
        write(data)
        return true
    }

    private fun writeNext() {
        if (writing) return
        val g = gatt ?: return
        val characteristic = writeChar ?: return
        val data = writeQueue.removeFirstOrNull() ?: return
        writing = true
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = data
            if (!g.writeCharacteristic(characteristic)) {
                writing = false
            }
        }
    }

    private fun writeNextDescriptor() {
        val g = gatt ?: return
        val descriptor = descriptorQueue.removeFirstOrNull() ?: return
        @Suppress("DEPRECATION")
        if (!g.writeDescriptor(descriptor)) {
            addLog("Descriptor write rejected")
        }
    }

    // ---- notification handling -------------------------------------------

    private fun handleNotification(value: ByteArray) {
        lastPacketHex = GimbalProtocol.toHex(value)
        val packet = GimbalProtocol.parse(value) ?: return

        packetCount++
        lastPacketName = packet.typeName

        if (packet.type == GimbalPacket.TYPE_DEVICE_INFO && !handshakeDone) {
            handshakeDone = true
            main.removeCallbacks(handshakeRunnable)
            streaming = true
            statusRes = R.string.status_streaming
            addLog("Handshake complete (device info received)")
            updateNotification()
        }

        val newState = packet.state ?: return
        val now = SystemClock.elapsedRealtime()
        // Every packet, not just changes: a long press repeats 15 rather than changing.
        reportTrigger(newState.triggerButton, now)
        reportTransitions(previous, newState)
        previous = newState
        state = newState
    }

    /**
     * Decodes the trigger nibble and turns it into mappable events.
     *
     * The gimbal opens every press with `15` and then reports the click count, so
     * raw transitions are logged here (including a repeated `15`) to make the
     * sequence visible in the event log.
     */
    private fun reportTrigger(rawTrigger: Int, now: Long) {
        val previousRaw = triggerDecoder.raw
        val transitions = triggerDecoder.onState(rawTrigger, now)

        if (previousRaw != rawTrigger) {
            addLog("TRIGGER raw=${triggerName(rawTrigger)} ($previousRaw -> $rawTrigger)")
        }

        for (transition in transitions) {
            when (transition.event) {
                TriggerEvent.PRESS_DOWN -> Unit
                TriggerEvent.SINGLE_CLICK -> {
                    addLog("TRIGGER single click (held ${transition.holdMs} ms)")
                    emit(GimbalTrigger.TRIGGER_SINGLE)
                }
                TriggerEvent.DOUBLE_CLICK -> {
                    addLog("TRIGGER double click (held ${transition.holdMs} ms)")
                    emit(GimbalTrigger.TRIGGER_DOUBLE)
                }
                TriggerEvent.LONG_PRESS -> {
                    addLog("TRIGGER long press (held ${transition.holdMs} ms)")
                    emit(GimbalTrigger.TRIGGER_LONG)
                }
            }
        }
    }

    private fun reportTransitions(old: GimbalState?, new: GimbalState) {
        if (old == null) {
            addLog("First state packet received")
            return
        }
        if (old.triggerButton != new.triggerButton) {
            // Logged by reportTrigger(), which also handles repeated values.
        }
        if (old.captureButton != new.captureButton) {
            addLog("SHUTTER ${captureName(new.captureButton)} [${old.captureButton}->${new.captureButton}]")
            when (new.captureButton) {
                2 -> emit(GimbalTrigger.SHUTTER_2)
                3 -> emit(GimbalTrigger.SHUTTER_3)
            }
        }
        if (old.modelButton != new.modelButton) {
            addLog("M BUTTON ${modelButtonName(new.modelButton)} [${old.modelButton}->${new.modelButton}]")
            when (new.modelButton) {
                1 -> emit(GimbalTrigger.M_SINGLE)
                2 -> emit(GimbalTrigger.M_DOUBLE)
            }
        }
        if (old.knob != new.knob) {
            addLog("KNOB ${new.knob}")
        }
        if (old.zoomValue != new.zoomValue) {
            if ((old.zoomValue == 0) != (new.zoomValue == 0)) {
                addLog(
                    if (new.zoomValue == 0) {
                        "ZOOM stopped"
                    } else {
                        "ZOOM started dir=${new.zoomValue}"
                    }
                )
            }
            // Continuous: emitted on every change, 0 means released.
            emit(GimbalTrigger.ZOOM_SLIDER, new.zoomValue)
        }
        if (old.mode != new.mode) {
            addLog("MODE ${GimbalMode.of(new.mode)?.label ?: new.mode}")
        }
        if (old.voltage != new.voltage) {
            addLog("BATTERY level ${new.voltage}")
        }
    }

    private fun emit(trigger: GimbalTrigger, value: Int = 1) {
        eventListener?.invoke(trigger, value)
    }

    /** Keeps the status notification in step with the link. */
    private fun updateNotification() {
        val name = connectedName ?: connectedAddress
        when {
            connectedAddress == null -> ConnectionNotifier.showDisconnected(context, lastConnectedName)

            name == null -> Unit

            streaming -> ConnectionNotifier.showConnected(context, name, connectedModel.labelRes)

            else -> ConnectionNotifier.showConnecting(context, name)
        }
    }

    private fun addLog(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        log = (listOf(line) + log).take(80)
    }
}
