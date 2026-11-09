package com.dwisnulabs.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
    private var batteryReceiver: BroadcastReceiver? = null

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

        // Register dynamic battery guard monitor (<= 15% auto-lock, >= 35% auto-unlock)
        registerBatteryMonitor()

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

        try {
            batteryReceiver?.let { unregisterReceiver(it) }
            batteryReceiver = null
        } catch (_: Exception) {}

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

    private fun registerBatteryMonitor() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED || context == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val pct = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                if (pct <= 0) return

                // 1. Lock trigger: pct <= 15% and NOT charging
                if (pct in 1..15 && !isCharging) {
                    if (!AppState.isBatteryLocked) {
                        Log.w(TAG, "Battery guard triggered: $pct% (not charging). Locking device with BATTERY_LOW scare screen.")
                        AppState.isBatteryLocked = true
                        AppState.scareTheme = ScareTheme.BATTERY_LOW
                        AppState.isLocked = true
                        LockOverlayManager.show(context)
                        DwisnuLabsDeviceAdminReceiver.lockScreenNow(context)
                        FirebaseDeviceManager.syncStatus(context)

                        val alertText = "🔋⚠️ *Peringatan Baterai:*\nBaterai *${FirebaseDeviceManager.deviceName}* tersisa *$pct%* dan kepanasan!\nLayar telah otomatis dikunci dengan peringatan visual sampai dicas minimal 35%."
                        TelegramBotClient.sendMessage(TelegramConfig.PARENT_CHAT_ID, alertText)
                    }
                }

                // 2. Recovery trigger: was battery-locked, now charging, and pct >= 35%
                if (AppState.isBatteryLocked && isCharging && pct >= 35) {
                    Log.i(TAG, "Battery guard recovery: $pct% (charging >= 35%). Unlocking device.")
                    AppState.isBatteryLocked = false
                    AppState.isLocked = false
                    LockOverlayManager.hide()
                    FirebaseDeviceManager.syncStatus(context)

                    val alertText = "✅ *Baterai Aman:*\nBaterai *${FirebaseDeviceManager.deviceName}* telah terisi *$pct%* saat dicas (mencapai target 35%). Proteksi layar kunci telah otomatis dibuka kembali."
                    TelegramBotClient.sendMessage(TelegramConfig.PARENT_CHAT_ID, alertText)
                }
            }
        }
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(batteryReceiver, filter)
            Log.d(TAG, "Battery guard monitor successfully registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering battery monitor: ${e.message}")
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
