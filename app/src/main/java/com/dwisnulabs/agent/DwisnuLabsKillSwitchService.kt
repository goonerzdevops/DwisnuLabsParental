package com.dwisnulabs.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream

class DwisnuLabsKillSwitchService : VpnService() {

    companion object {
        private const val TAG = "DwisnuKillSwitch"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "dwisnulabs_killswitch_channel"
        const val ACTION_START = "com.dwisnulabs.agent.KILLSWITCH_START"
        const val ACTION_STOP = "com.dwisnulabs.agent.KILLSWITCH_STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            AppState.isInternetBlocked = true
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                Log.d(TAG, "VPN not prepared, launching VpnPrepareActivity")
                val intent = Intent(context, VpnPrepareActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(context, DwisnuLabsKillSwitchService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            AppState.isInternetBlocked = false
            val intent = Intent(context, DwisnuLabsKillSwitchService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopKillSwitch()
            stopSelf()
            return START_NOT_STICKY
        }

        startKillSwitch()
        return START_STICKY
    }

    private fun startKillSwitch() {
        // ALWAYS call startForeground immediately to satisfy Android OS requirements
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (vpnInterface != null) {
            Log.d(TAG, "VPN tunnel is already active.")
            return
        }

        try {
            val builder = Builder()
                .setSession("DwisnuLabs Network Driver")
                .setMtu(1500)
                .addAddress("10.120.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.120.0.1")

            // IPv6 blackhole routing
            try {
                builder.addAddress("fd00:1::1", 64)
                builder.addRoute("::", 0)
                builder.addDnsServer("fd00:1::1")
            } catch (e: Exception) {
                Log.w(TAG, "IPv6 route optional: ${e.message}")
            }

            // Exclude our own app so Telegram polling remains 100% active
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to exclude package: ${e.message}")
            }

            val pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "VPN builder.establish() returned null! Triggering prepare...")
                isRunning = false
                val intent = Intent(this, VpnPrepareActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }

            vpnInterface = pfd
            isRunning = true
            Log.d(TAG, "Internet Kill-Switch successfully active!")

            // Discard all outgoing traffic silently
            job = CoroutineScope(Dispatchers.IO).launch {
                val input = FileInputStream(pfd.fileDescriptor)
                val buffer = ByteArray(32768)
                try {
                    while (isActive && isRunning) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                    }
                } catch (_: Exception) {
                } finally {
                    try { input.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN kill-switch: ${e.message}", e)
            isRunning = false
        }
    }

    private fun stopKillSwitch() {
        Log.d(TAG, "Stopping Internet Kill-Switch...")
        isRunning = false
        job?.cancel()
        job = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopKillSwitch()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Layanan Pemeliharaan Jaringan",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Optimasi performa koneksi data sistem"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Layanan Jaringan Sistem")
            .setContentText("Mengoptimalkan stabilitas data.")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}
