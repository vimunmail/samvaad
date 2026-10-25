package com.example.xgestalkble.ui.main

import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.xgestalkble.ble.BleConnectionState
import com.example.xgestalkble.ble.BleManager
import com.example.xgestalkble.ble.GloveDataPacket
import com.example.xgestalkble.utils.DataCollector
import com.example.xgestalkble.utils.DataNormalizer
import com.example.xgestalkble.utils.RecordingModelType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainScreenViewModel(context: Context) : ViewModel() {
    private val bleManager = BleManager.getInstance(context)
    private val dataCollector = DataCollector(context)

    // BLE state flows
    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val scanResults: StateFlow<List<ScanResult>> = bleManager.scanResults
    val dataPacket: StateFlow<GloveDataPacket?> = bleManager.dataPacket
    val packetRate: StateFlow<Int> = bleManager.packetRate
    // Glove battery charge 0..100 % (-1 when unknown / firmware without battery byte).
    val batteryPercent: StateFlow<Int> = bleManager.batteryPercent
    val logs: StateFlow<List<String>> = bleManager.logs
    val connectedDeviceName: StateFlow<String?> = bleManager.connectedDeviceName
    val connectedDeviceAddress: StateFlow<String?> = bleManager.connectedDeviceAddress

    // Data collection flows
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingStatus = MutableStateFlow("Ready for recording")
    val recordingStatus: StateFlow<String> = _recordingStatus.asStateFlow()

    private val _recordedCount = MutableStateFlow(0)
    val recordedCount: StateFlow<Int> = _recordedCount.asStateFlow()

    // Frames dropped by the settle / sensor-health gates during the current
    // recording — surfaced so the user can see data quality at a glance.
    private val _skippedCount = MutableStateFlow(0)
    val skippedCount: StateFlow<Int> = _skippedCount.asStateFlow()

    init {
        // Collect BLE packets and feed them to the data collector if recording is active
        viewModelScope.launch {
            bleManager.dataPacket.collect { packet ->
                if (packet != null && dataCollector.getIsRecording()) {
                    dataCollector.onPacketReceived(packet)
                    _recordedCount.value = dataCollector.getSampleCount()
                    _skippedCount.value = dataCollector.getSkippedCount()
                }
            }
        }

        // Reset normalizer gyro buffer on new connections
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state == BleConnectionState.CONNECTED) {
                    DataNormalizer.resetBuffer()
                }
            }
        }
    }

    // BLE controls
    fun startScanning() {
        bleManager.startScan()
    }

    fun stopScanning() {
        bleManager.stopScan()
    }

    fun connectDevice(result: ScanResult) {
        bleManager.connect(result.device)
    }

    fun disconnectDevice() {
        bleManager.disconnect()
    }

    fun clearLogs() {
        bleManager.clearLogs()
    }

    /**
     * Asks the glove to run its finger recalibration routine. The firmware
     * blocks (and stops streaming) for ~12 s while it records the open/fist
     * accel range, so the UI runs its own timed coach mirroring that sequence.
     * Returns true if the command was dispatched over BLE.
     */
    fun recalibrateGlove(): Boolean {
        val ok = bleManager.recalibrateFingers()
        logInfo(if (ok) "Recalibration started — follow the on-screen steps."
                else "Recalibration failed to start (glove not connected?).")
        return ok
    }

    fun logInfo(message: String) {
        bleManager.log(message)
    }

    // Data collection controls
    fun startRecording(type: RecordingModelType, fileName: String, labelName: String) {
        _recordedCount.value = 0
        _skippedCount.value = 0
        dataCollector.startRecording(type, fileName, labelName)
        _isRecording.value = true
        _recordingStatus.value = "Recording active..."
        logInfo("Started recording gesture dataset [Format: $type, File: $fileName, Label: $labelName]")
    }

    fun stopRecording() {
        val statusMessage = dataCollector.stopRecording()
        _isRecording.value = false
        _recordingStatus.value = statusMessage
        logInfo("Stopped recording. Result: $statusMessage")
    }
}
