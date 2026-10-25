package com.example.xgestalkble.utils

import android.content.Context
import com.example.xgestalkble.ble.GloveDataPacket
import java.io.File
import java.io.FileWriter
import java.io.IOException

enum class RecordingModelType {
    RFC,
    TCN,
    LSTM
}

/**
 * Writes SAMVAAD firmware packets to CSV for ML training, with quality gates that
 * keep the dataset stable across recordings and power cycles.
 *
 * CSV columns (Label + 24):
 *   Label, timestamp, packetCounter,
 *   thumb_ax..little_az (15), wrist_ax..wrist_az (3), gyro_x..gyro_z (3),
 *   session                              ← appended last (added for stable data)
 *
 * The 21 feature columns (cols 3-23) match DataNormalizer.normalize() output order
 * and the web companion's format, so CsvDatasetParser reads them directly. The
 * trailing `session` column is appended, so older parsers (which index features by
 * position and only require ≥24 columns) keep working and old files still load.
 *
 * Quality gates applied while recording:
 *   • READY only — frames before the firmware settles are dropped (packet.isReady).
 *   • Settle-discard — the first [SETTLE_FRAMES] READY frames of each recording are
 *     skipped, so the moment right after START (hand still moving into position,
 *     baseline still converging) never poisons the data.
 *   • Full sensor health — frames where any of the 6 IMUs is silent are skipped, so
 *     a dropped sensor mid-recording can't write garbage rows.
 * Each START stamps a new [sessionId]; recording the same gesture across several
 * power cycles (different sessions) is what teaches the model boot-to-boot
 * variation. Skipped frames are counted so the UI can surface data quality.
 */
class DataCollector(private val context: Context) {
    companion object {
        // READY frames to drop at the very start of a recording (~0.3 s at 50 Hz).
        private const val SETTLE_FRAMES = 15
        // All 6 per-sensor health bits (mux channels 0-5) must be set.
        private const val FULL_HEALTH_MASK = 0x3F
    }

    private var isRecording = false
    private var modelType = RecordingModelType.TCN
    private var fileName = "gesture_data"
    private var labelName = "default"
    private var sampleCount = 0
    private var skippedCount = 0
    private var settleRemaining = 0
    private var sessionId = 0L

    /** When true, frames are dropped unless all 6 IMUs report healthy. */
    var requireFullHealth = true

    fun startRecording(type: RecordingModelType, name: String, label: String) {
        modelType = type
        fileName = name.ifBlank { "gesture_data" }
        labelName = label.ifBlank { "default" }
        sampleCount = 0
        skippedCount = 0
        settleRemaining = SETTLE_FRAMES
        sessionId = System.currentTimeMillis()
        isRecording = true
    }

    fun onPacketReceived(packet: GloveDataPacket) {
        if (!isRecording) return
        // Firmware guidance: only record frames where the READY bit is set.
        if (!packet.isReady) return
        // Drop frames with any silent sensor — they'd write garbage feature values.
        if (requireFullHealth && (packet.sensorStatus and FULL_HEALTH_MASK) != FULL_HEALTH_MASK) {
            skippedCount++
            return
        }
        // Discard the unsettled frames right after START (hand still moving in,
        // baseline still converging).
        if (settleRemaining > 0) {
            settleRemaining--
            skippedCount++
            return
        }

        val dir = context.getExternalFilesDir("TelemetryData") ?: return
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "$fileName.csv")
        val isNewFile = !file.exists()

        val fingerNames = arrayOf("thumb", "index", "middle", "ring", "little")

        try {
            FileWriter(file, true).use { writer ->
                if (isNewFile) {
                    writer.write(buildString {
                        append("Label,timestamp,packetCounter")
                        for (name in fingerNames) {
                            append(",${name}_ax,${name}_ay,${name}_az")
                        }
                        append(",wrist_ax,wrist_ay,wrist_az")
                        append(",gyro_x,gyro_y,gyro_z")
                        append(",session")
                        append("\n")
                    })
                }

                writer.write(buildString {
                    append(labelName)
                    append(",${packet.timestamp},${packet.packetCounter}")
                    packet.fingerAccel.forEach { axes -> axes.forEach { append(",$it") } }
                    packet.wristAccel.forEach { append(",$it") }
                    packet.wristGyro.forEach  { append(",$it") }
                    append(",$sessionId")
                    append("\n")
                })
            }
            sampleCount++
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun stopRecording(): String {
        if (!isRecording) return "Not recording"
        isRecording = false
        if (sampleCount == 0) {
            val why = if (skippedCount > 0) " ($skippedCount frames skipped — check sensor health)" else ""
            return "No frames recorded$why"
        }
        val skipNote = if (skippedCount > 0) " (skipped $skippedCount low-quality frames)" else ""
        val result = "Saved $sampleCount samples to TelemetryData/$fileName.csv$skipNote"
        sampleCount = 0
        skippedCount = 0
        return result
    }

    fun getIsRecording(): Boolean = isRecording
    fun getSampleCount(): Int = sampleCount
    /** Frames dropped this recording by the settle / health gates. */
    fun getSkippedCount(): Int = skippedCount
    /** Session stamp written to the `session` column of the current recording. */
    fun getSessionId(): Long = sessionId
}
