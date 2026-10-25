package com.example.xgestalkble.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.example.xgestalkble.service.GloveConnectionService
import com.example.xgestalkble.utils.DataNormalizer

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

// SAMVAAD v3.3 firmware FeaturePacket — 35 bytes, type 0xA1, struct "<BBII18b3hB"
//   uint8  type (0xA1)
//   uint8  sensorStatus     (bits0-5 per-sensor health, bit7 0x80 = READY)
//   uint32 timestamp
//   uint32 packetCounter
//   int8   fingerAccel[5][3]  quantized accel levels -2..+2 (Thumb,Index,Middle,Ring,Little)
//   int8   wristAccel[3]      quantized accel levels -2..+2
//   int16  wristGyro[3]       integer deg/s
//   uint8  battery            0..100 % (TELEMETRY, NOT a model feature)
// v3.1/v3.2 (34-byte, no battery) packets are still accepted; battery reports -1.
data class GloveDataPacket(
    val timestamp: Long,
    val packetCounter: Long,
    // Per-sensor health bitmask (bits0-5) + READY bit (0x80)
    val sensorStatus: Int,
    // READY: only record / predict on frames where this is true
    val isReady: Boolean,
    // Per-finger quantized accel levels [-2..+2]; index 0=thumb..4=little, axis 0=x,1=y,2=z
    val fingerAccel: Array<IntArray>,   // [5][3]
    // Wrist quantized accel levels [-2..+2]
    val wristAccel: IntArray,           // [3]
    // Wrist gyro integer deg/s
    val wristGyro: IntArray,            // [3]
    // 21-element ML feature vector (matches CSV column order)
    val normalizedFeatures: FloatArray,
    // Battery charge 0..100 %, or -1 if the firmware didn't send it. Telemetry only.
    val battery: Int = -1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GloveDataPacket
        return timestamp == other.timestamp &&
               packetCounter == other.packetCounter &&
               sensorStatus == other.sensorStatus &&
               isReady == other.isReady &&
               fingerAccel.contentDeepEquals(other.fingerAccel) &&
               wristAccel.contentEquals(other.wristAccel) &&
               wristGyro.contentEquals(other.wristGyro) &&
               normalizedFeatures.contentEquals(other.normalizedFeatures) &&
               battery == other.battery
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + packetCounter.hashCode()
        result = 31 * result + sensorStatus
        result = 31 * result + isReady.hashCode()
        result = 31 * result + fingerAccel.contentDeepHashCode()
        result = 31 * result + wristAccel.contentHashCode()
        result = 31 * result + wristGyro.contentHashCode()
        result = 31 * result + normalizedFeatures.contentHashCode()
        result = 31 * result + battery
        return result
    }
}

class BleManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null

    // Service & Characteristic UUIDs
    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHARACTERISTIC_UUID = UUID.fromString("abcdefab-1234-5678-1234-abcdefabcdef")
    // Write-only command characteristic (firmware CMD_CHAR_UUID). A single byte
    // is interpreted directly as the command code (see CMD_* constants).
    private val COMMAND_UUID = UUID.fromString("abcdefab-1234-5678-1234-abcdefabcd01")
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    // State Flows
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private val _dataPacket = MutableStateFlow<GloveDataPacket?>(null)
    val dataPacket: StateFlow<GloveDataPacket?> = _dataPacket.asStateFlow()

    private val _packetRate = MutableStateFlow(0)
    val packetRate: StateFlow<Int> = _packetRate.asStateFlow()

    // Latest battery charge % from the glove (0..100), or -1 if unknown / firmware
    // without the battery byte. Updated from every FEATURE packet.
    private val _batteryPercent = MutableStateFlow(-1)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

    // Internal calculation variables
    private var packetCount = 0
    private var lastPacketTime = 0L
    private var logThrottleCount = 0
    private var autoPairEnabled = true

    // Auto-connect: when the link drops unexpectedly we re-scan and re-pair.
    // A user-initiated disconnect (tapping "Disconnect") suppresses this.
    private var userInitiatedDisconnect = false
    private var reconnectRunnable: Runnable? = null

    // SECURITY — trust-on-first-use device pinning. Advertised BLE names are
    // trivially spoofable: anyone nearby can broadcast "Gestalk_Glove" and get
    // auto-connected, then feed fake gesture packets that this app would speak
    // aloud. After the first successful connection we remember the glove's MAC
    // and auto-pair only reconnects to that address. Manually picking a device
    // in the scan dialog still works and re-pins trust to the chosen glove.
    private val trustPrefs = appContext.getSharedPreferences("BleTrust", Context.MODE_PRIVATE)
    private var trustedAddress: String? = trustPrefs.getString("trusted_address", null)

    init {
        log("BLE Manager Initialized. Bluetooth status: ${if (bluetoothAdapter != null) "Available" else "NOT Available"}")
    }

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val fullMessage = "[$timestamp] $message"
        android.util.Log.d("BleManager", message)
        _logs.value = (_logs.value + fullMessage).takeLast(150)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // Scanning Methods
    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth scanner not available (is Bluetooth enabled?)")
            return
        }

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            log("Scan permission missing")
            return
        }

        _scanResults.value = emptyList()
        _connectionState.value = BleConnectionState.SCANNING
        log("Scanning started...")
        autoPairEnabled = true

        // Warn if Location Services (GPS) are disabled on Android
        try {
            val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            if (!isGpsEnabled && !isNetworkEnabled) {
                log("[WARNING] GPS/Location Services are OFF. Android requires Location Services enabled to discover BLE devices!")
            }
        } catch (e: Exception) {
            log("Failed to check Location Services status: ${e.message}")
        }

        val filter = ScanFilter.Builder()
            // Filter by service UUID or name if desired
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Auto-stop scan after 15 seconds to save power
        scanRunnable?.let { handler.removeCallbacks(it) }
        scanRunnable = Runnable {
            log("Scan auto-stopped after timeout.")
            stopScan()
        }
        handler.postDelayed(scanRunnable!!, 15000)

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null && _connectionState.value == BleConnectionState.SCANNING) {
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                scanner.stopScan(scanCallback)
            }
            log("Scanning stopped.")
        }
        scanRunnable?.let { handler.removeCallbacks(it) }
        if (_connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { addScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed with error code: $errorCode")
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    private fun addScanResult(result: ScanResult) {
        // Resolve the advertised device name (guarded by permission on Android 12+).
        val deviceName = try {
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                result.device.name
            } else {
                null
            }
        } catch (e: SecurityException) {
            null
        }

        // Only surface the Samvaad glove in the connect list — ignore every other
        // BLE device so the user isn't confused by unrelated nearby devices.
        val isGlove = deviceName != null &&
            (deviceName.contains("Gestalk_Glove", ignoreCase = true) || deviceName.contains("Glove", ignoreCase = true))
        if (!isGlove) {
            return
        }

        val currentList = _scanResults.value.toMutableList()
        val index = currentList.indexOfFirst { it.device.address == result.device.address }
        if (index >= 0) {
            currentList[index] = result // Update result with newer RSSI
        } else {
            currentList.add(result)
        }
        _scanResults.value = currentList.sortedByDescending { it.rssi }

        // Auto-pairing system: automatically connect to Samvaad Glove — but only
        // to the trusted (previously connected) glove once one is known.
        if (autoPairEnabled &&
            _connectionState.value == BleConnectionState.SCANNING) {
            val trusted = trustedAddress
            if (trusted == null || result.device.address == trusted) {
                log("[AUTO-PAIR] Found glove device: ${gloveDisplayName(deviceName)} (${result.device.address}). Connecting automatically...")
                connect(result.device)
            } else {
                log("[AUTO-PAIR] Ignoring unknown glove ${gloveDisplayName(deviceName)} (${result.device.address}) — not the trusted glove. Select it in the scan list to switch.")
            }
        }
    }

    // Connection Methods
    fun connect(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            log("Connection permission missing")
            return
        }

        stopScan()
        userInitiatedDisconnect = false
        cancelReconnect()
        closeGatt()

        _connectionState.value = BleConnectionState.CONNECTING
        _connectedDeviceName.value = gloveDisplayName(device.name)
        _connectedDeviceAddress.value = device.address
        log("Connecting to ${_connectedDeviceName.value} (${device.address})...")

        bluetoothGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // User-initiated disconnect: stops auto-reconnect until the user reconnects.
    fun disconnect() {
        userInitiatedDisconnect = true
        autoPairEnabled = false
        cancelReconnect()
        closeGatt()
        handleDisconnect()
        // The user ended the session — drop the keep-alive foreground service.
        GloveConnectionService.stop(appContext)
    }

    private fun closeGatt() {
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
        commandCharacteristic = null
    }

    /**
     * Writes a single command byte to the glove's command characteristic.
     * The firmware uses the raw byte value directly as the command code, so pass
     * one of the CMD_* constants. Returns true if the write was dispatched.
     */
    fun sendCommand(cmd: Byte): Boolean {
        val gatt = bluetoothGatt
        val ch = commandCharacteristic
        if (gatt == null || ch == null) {
            log("Cannot send command 0x${(cmd.toInt() and 0xFF).toString(16)}: not connected / no command channel.")
            return false
        }
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            log("Cannot send command: BLUETOOTH_CONNECT permission missing.")
            return false
        }

        val payload = byteArrayOf(cmd)
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = payload
                gatt.writeCharacteristic(ch)
            }
        }
        log(if (ok) "Sent command 0x${(cmd.toInt() and 0xFF).toString(16)} to glove."
            else "Failed to dispatch command 0x${(cmd.toInt() and 0xFF).toString(16)}.")
        return ok
    }

    /** Triggers the firmware finger recalibration routine (~12 s). */
    fun recalibrateFingers(): Boolean = sendCommand(CMD_RECAL_FINGERS)

    private fun handleDisconnect() {
        _connectionState.value = BleConnectionState.DISCONNECTED
        _connectedDeviceName.value = null
        _connectedDeviceAddress.value = null
        _packetRate.value = 0
        _batteryPercent.value = -1
        log("Disconnected and resources cleaned up.")
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    // Called when the GATT link drops without the user asking.
    private fun handleUnexpectedDisconnect() {
        closeGatt()
        handleDisconnect()
        // Stop the keep-alive foreground service so the notification is dismissed
        GloveConnectionService.stop(appContext)
        log("[DISCONNECT] Glove link lost. Stopped keep-alive service.")
    }

    // GATT Callbacks
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                log("GATT State change permission error")
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("Connected. Requesting MTU 247 for high throughput...")
                    gatt.requestMtu(247)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("GATT disconnected.")
                    handleUnexpectedDisconnect()
                }
            } else {
                log("GATT connection error. Status: $status. Disconnecting...")
                handleUnexpectedDisconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return
            }

            log("MTU negotiated to $mtu. Discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Services discovered. Finding Samvaad service...")
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    // Cache the write-only command characteristic (recalibrate, hard-zero, etc.)
                    commandCharacteristic = service.getCharacteristic(COMMAND_UUID)
                    if (commandCharacteristic == null) {
                        log("Note: command characteristic not found — recalibrate/hard-zero won't work.")
                    }
                    val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        log("Samvaad service and characteristic found! Enabling notifications...")
                        enableNotifications(gatt, characteristic)
                    } else {
                        log("Error: Samvaad Characteristic not found.")
                        disconnect()
                    }
                } else {
                    log("Error: Samvaad Service not found.")
                    disconnect()
                }
            } else {
                log("Service discovery failed with status: $status")
                disconnect()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            parsePacket(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parsePacket(value)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return
        }

        gatt.setCharacteristicNotification(characteristic, true)

        val clientConfigUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val descriptor = characteristic.getDescriptor(clientConfigUuid)
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            _connectionState.value = BleConnectionState.CONNECTED
            log("Notifications enabled! Receiving real-time streams.")
            // Trust-on-first-use: remember this glove so auto-pair only ever
            // reconnects to it (see trustedAddress above).
            _connectedDeviceAddress.value?.let { addr ->
                if (trustedAddress != addr) {
                    trustedAddress = addr
                    trustPrefs.edit().putString("trusted_address", addr).apply()
                    log("[TRUST] Remembered $addr as the trusted glove.")
                }
            }
            // Keep the link (and this process) alive if the app is closed.
            GloveConnectionService.start(appContext)
        } else {
            log("Error: CCCD descriptor not found on characteristic.")
            disconnect()
        }
    }

    // SAMVAAD v3.1 FeaturePacket layout (34 bytes, little-endian, type 0xA1):
    //   uint8 type, uint8 sensorStatus,
    //   uint32 timestamp, uint32 packetCounter,
    //   int8 fingerAccel[5][3], int8 wristAccel[3], int16 wristGyro[3]
    // RAW-mode packets (type 0xB2, "<BBII36h" = 82 bytes) are detected and skipped.
    private fun parsePacket(data: ByteArray) {
        if (data.isNotEmpty() && (data[0].toInt() and 0xFF) == 0xB2) {
            logThrottleCount++
            if (logThrottleCount >= 25) {
                logThrottleCount = 0
                log("Glove is in RAW mode (type 0xB2) — switch it to FEATURE mode ('m' on serial) for recording/prediction.")
            }
            return
        }
        if (data.size < 34) {
            log("Packet ignored: size ${data.size} bytes (expected 34/35 for feature firmware)")
            return
        }

        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            val type = buffer.get().toInt() and 0xFF
            if (type != 0xA1) {
                log("Packet ignored: unknown type 0x${type.toString(16).uppercase()}")
                return
            }
            val sensorStatus = buffer.get().toInt() and 0xFF
            val isReady = (sensorStatus and 0x80) != 0

            val timestamp     = buffer.int.toLong() and 0xFFFFFFFFL
            val packetCounter = buffer.int.toLong() and 0xFFFFFFFFL

            // 15 finger accel levels: [thumb,index,middle,ring,little] x [x,y,z]
            val fingerAccel = Array(5) { IntArray(3) { buffer.get().toInt() } }
            // 3 wrist accel levels
            val wristAccel = IntArray(3) { buffer.get().toInt() }
            // 3 wrist gyro integer dps
            val wristGyro = IntArray(3) { buffer.short.toInt() }

            // v3.3+: one trailing battery byte (0..100 %). Older firmware omits it.
            val battery = if (buffer.remaining() >= 1) (buffer.get().toInt() and 0xFF) else -1
            if (battery in 0..100) _batteryPercent.value = battery

            val normalizedFeatures = DataNormalizer.normalize(fingerAccel, wristAccel, wristGyro)

            _dataPacket.value = GloveDataPacket(
                timestamp = timestamp,
                packetCounter = packetCounter,
                sensorStatus = sensorStatus,
                isReady = isReady,
                fingerAccel = fingerAccel,
                wristAccel = wristAccel,
                wristGyro = wristGyro,
                normalizedFeatures = normalizedFeatures,
                battery = battery
            )

            // Log every 25 frames (~0.5 s at 50 Hz)
            logThrottleCount++
            if (logThrottleCount >= 25) {
                logThrottleCount = 0
                log(String.format(
                    Locale.US,
                    "v3.3 -> %s health=0x%02X thumb=[%d,%d,%d] wristAcc=[%d,%d,%d] gyro=[%d,%d,%d] batt=%s",
                    if (isReady) "READY" else "settling", sensorStatus,
                    fingerAccel[0][0], fingerAccel[0][1], fingerAccel[0][2],
                    wristAccel[0], wristAccel[1], wristAccel[2],
                    wristGyro[0], wristGyro[1], wristGyro[2],
                    if (battery >= 0) "$battery%" else "n/a"
                ))
            }

            packetCount++
            val now = System.currentTimeMillis()
            if (lastPacketTime == 0L) {
                lastPacketTime = now
            } else if (now - lastPacketTime >= 1000) {
                _packetRate.value = packetCount
                packetCount = 0
                lastPacketTime = now
            }
        } catch (e: Exception) {
            log("Parsing error: ${e.message}")
        }
    }

    companion object {
        // Firmware command codes (sent as a single raw byte to COMMAND_UUID).
        const val CMD_RECAL_FINGERS: Byte = 0x01   // recalibrate fingers (~12 s)
        const val CMD_REZERO_IMUS: Byte = 0x02     // hard-zero (hold flat & still)
        const val CMD_FACTORY_RESET: Byte = 0x03   // wipe NVS + reboot

        @Volatile
        private var INSTANCE: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleManager(context).also { INSTANCE = it }
            }
        }
    }
}
