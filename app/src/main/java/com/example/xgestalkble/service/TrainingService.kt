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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.xgestalkble.MainActivity
import com.example.xgestalkble.ml.TrainingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that keeps the app process alive while [TrainingManager]
 * trains a model, and mirrors its progress into an ongoing notification. When
 * the run finishes it posts a completion/failure notification and stops itself.
 *
 * Training itself runs in TrainingManager's own scope — this service only
 * guarantees the process survives the app being closed and shows progress.
 */
class TrainingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildProgressNotification(
            TrainingManager.statusText.value,
            (TrainingManager.progress.value * 100).roundToInt()
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Training is pure CPU work with no wake-holding I/O; keep the CPU on
        // so a run finishes even if the screen turns off mid-training.
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xgestalk:training").apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }

        if (watchJob == null) {
            watchJob = serviceScope.launch { watchTraining() }
        }
        return START_NOT_STICKY
    }

    private suspend fun watchTraining() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        combine(
            TrainingManager.isTraining,
            TrainingManager.progress,
            TrainingManager.statusText
        ) { training, progress, status -> Triple(training, progress, status) }
            .conflate()
            .collect { (training, progress, status) ->
                if (training) {
                    nm.notify(NOTIFICATION_ID, buildProgressNotification(status, (progress * 100).roundToInt()))
                    // Throttle notification churn; conflate() drops stale frames.
                    delay(500)
                } else {
                    TrainingManager.lastResult.value?.let { result ->
                        nm.notify(RESULT_NOTIFICATION_ID, buildResultNotification(result))
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildProgressNotification(status: String, percent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(TrainingManager.notificationTitle)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    private fun buildResultNotification(result: TrainingManager.Result): Notification =
        NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(
                if (result.success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(if (result.success) "Model training complete" else "Model training failed")
            .setContentText(result.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent())
            .build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS, "Model training progress", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Live progress while a gesture model trains" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT, "Model training results", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Shown when a gesture model finishes training" }
        )
    }

    companion object {
        private const val CHANNEL_PROGRESS = "model_training_progress"
        private const val CHANNEL_RESULT = "model_training_result"
        private const val NOTIFICATION_ID = 2001
        private const val RESULT_NOTIFICATION_ID = 2002
        // Generous ceiling — on-device runs take ~1-5 min; the lock is released
        // in onDestroy well before this.
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60L * 1000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, TrainingService::class.java)
                )
            } catch (t: Throwable) {
                // Training still runs in TrainingManager; only the keep-alive
                // notification is missing (e.g. background-start restriction).
                android.util.Log.w("TrainingService", "Could not start foreground service: ${t.message}")
            }
        }
    }
}
