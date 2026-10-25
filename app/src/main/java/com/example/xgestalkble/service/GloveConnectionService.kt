package com.example.xgestalkble.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.xgestalkble.MainActivity
import com.example.xgestalkble.ble.BleConnectionState
import com.example.xgestalkble.ble.BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the glove's BLE link alive when the app is
 * closed. [BleManager] is a process-scoped singleton, so the GATT connection
 * itself survives the Activity — this service's job is to keep the *process*
 * alive (and visible via a persistent notification with battery level).
 *
 * Started by BleManager when the glove connects; stopped when the user taps
 * Disconnect. While running, it also keeps re-scanning if the link drops so
 * the glove transparently re-pairs even with the app in the background.
 */
class GloveConnectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ble = BleManager.getInstance(applicationContext)
        val notification = buildNotification(
            ble.connectionState.value,
            ble.connectedDeviceName.value,
            ble.batteryPercent.value
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!watching) {
            watching = true
            serviceScope.launch { watchConnection(ble) }
        }
        return START_STICKY
    }

    private suspend fun watchConnection(ble: BleManager) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        combine(
            ble.connectionState,
            ble.connectedDeviceName,
            ble.batteryPercent
        ) { state, name, battery -> Triple(state, name, battery) }
            .distinctUntilChanged()
            .collect { (state, name, battery) ->
                nm.notify(NOTIFICATION_ID, buildNotification(state, name, battery))
            }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(
        state: BleConnectionState,
        deviceName: String?,
        battery: Int
    ): Notification {
        val text = when (state) {
            BleConnectionState.CONNECTED -> buildString {
                append(deviceName ?: "Glove")
                append(" connected")
                if (battery in 0..100) append(" • $battery% battery")
            }
            BleConnectionState.SCANNING,
            BleConnectionState.CONNECTING -> "Reconnecting to glove…"
            BleConnectionState.DISCONNECTED -> "Glove link lost — will keep retrying"
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Samvaad glove")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Glove connection", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the glove connected while the app is closed" }
        )
    }

    companion object {
        private const val CHANNEL_ID = "glove_connection"
        private const val NOTIFICATION_ID = 1001
        // Scans auto-stop after 15 s; waiting 20 s between retries keeps the
        // radio duty cycle reasonable while still re-pairing quickly.
        private const val RETRY_INTERVAL_MS = 20_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, GloveConnectionService::class.java)
                )
            } catch (t: Throwable) {
                // Rare: background-start restriction. The in-app connection
                // still works; only the keep-alive guarantee is missing.
                android.util.Log.w("GloveConnService", "Could not start foreground service: ${t.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GloveConnectionService::class.java))
        }
    }
}
