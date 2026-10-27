package com.dwisnulabs.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

object FirebaseDeviceManager {
    private const val TAG = "DwisnuFirebase"
    private const val FIREBASE_URL = "https://childlock-parental-default-rtdb.asia-southeast1.firebasedatabase.app"

    val deviceId: String by lazy { resolveDeviceId() }
    val deviceName: String by lazy { resolveDeviceName(deviceId) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false
    private var lastSyncedApp = ""

    private fun resolveDeviceId(): String {
        val model = Build.MODEL ?: "unknown"
        return when {
            model.contains("Note 9 Pro", ignoreCase = true) || model.contains("M2007J20", ignoreCase = true) -> "device_note9pro"
            model.contains("9T", ignoreCase = true) || model.contains("M2010J19", ignoreCase = true) -> "device_redmi9t"
            model.contains("Pad", ignoreCase = true) || model.contains("23073", ignoreCase = true) || model.contains("24075", ignoreCase = true) -> "device_padse"
            model.contains("iQOO", ignoreCase = true) || model.contains("I2401", ignoreCase = true) -> "device_iqooz10"
            else -> "device_" + model.replace("[^a-zA-Z0-9]".toRegex(), "_").lowercase()
        }
    }

    private fun resolveDeviceName(devId: String): String {
        return when (devId) {
            "device_note9pro" -> "👦 Kakak (Redmi Note 9 Pro)"
            "device_redmi9t" -> "👧 Adik 1 (Redmi 9T)"
            "device_padse" -> "📟 Tablet (Redmi Pad SE)"
            "device_iqooz10" -> "📱 Adik 2 (iQOO Z10)"
            else -> Build.MODEL ?: "HP Anak"
        }
    }

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        try {
            val db = FirebaseDatabase.getInstance(FIREBASE_URL)
            val deviceRef = db.getReference("devices").child(deviceId)

            // 1. Initial Status Sync
            syncStatus(context)

            // 2. Command Listener (Zero Polling - Push-Triggered via SSE)
            val cmdRef = deviceRef.child("command")
            cmdRef.addValueEventListener(object : ValueEventListener {
                private var initialLoad = true

                override fun onDataChange(snapshot: DataSnapshot) {
                    val action = snapshot.child("action").getValue(String::class.java) ?: return
                    val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                    // Handle initial load / boot commands without discarding critical state changes
                    if (initialLoad) {
                        initialLoad = false
                        val ageMs = System.currentTimeMillis() - timestamp
                        val isStale = ageMs > 10 * 60 * 1000L // 10 minutes tolerance

                        // Never skip if the device's actual current state doesn't match the command
                        val shouldSkip = when (action.uppercase()) {
                            "LOCK" -> AppState.isLocked // Only skip if already locked
                            "UNLOCK" -> !AppState.isLocked // Only skip if already unlocked
                            "INTERNET_OFF", "NETOFF" -> AppState.isInternetBlocked
                            "INTERNET_ON", "NETON" -> !AppState.isInternetBlocked
                            else -> isStale
                        }

                        if (shouldSkip && isStale) {
                            Log.d(TAG, "Ignoring stale initial command: $action (age: ${ageMs / 1000}s)")
                            return
                        }
                    }

                    Log.d(TAG, "Incoming Firebase Command: $action (timestamp: $timestamp)")
                    executeCommand(context.applicationContext, action)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Firebase command listener error: ${error.message}")
                }
            })

            Log.d(TAG, "FirebaseDeviceManager successfully initialized for $deviceName ($deviceId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init FirebaseDeviceManager: ${e.message}", e)
        }
    }

    fun syncStatus(context: Context) {
        try {
            val db = FirebaseDatabase.getInstance(FIREBASE_URL)
            val deviceRef = db.getReference("devices").child(deviceId)

            val statusMap = hashMapOf<String, Any>(
                "name" to deviceName,
                "model" to (Build.MODEL ?: "Unknown"),
                "battery" to getBatteryPercentage(context),
                "isLocked" to AppState.isLocked,
                "isInternetBlocked" to (AppState.isInternetBlocked || DwisnuLabsKillSwitchService.isRunning),
                "currentApp" to AppState.currentForegroundAppName,
                "currentPackage" to AppState.currentForegroundPackage,
                "lastSeen" to System.currentTimeMillis()
            )

            deviceRef.updateChildren(statusMap)
            Log.d(TAG, "Synced status for $deviceId: Batt=${statusMap["battery"]}%, App=${statusMap["currentApp"]}")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing status to Firebase: ${e.message}")
        }
    }

    fun onForegroundAppChanged(context: Context, appName: String) {
        if (appName != lastSyncedApp) {
            lastSyncedApp = appName
            syncStatus(context)
        }
    }

    private fun executeCommand(context: Context, action: String) {
        mainHandler.post {
            when (action.uppercase()) {
                "LOCK" -> {
                    LockOverlayManager.show(context)
                    syncStatus(context)
                }
                "UNLOCK" -> {
                    LockOverlayManager.hide()
                    AppState.unlockUntilEpochMs = 0L
                    syncStatus(context)
                }
                "UNLOCK15" -> {
                    LockOverlayManager.hide()
                    AppState.unlockUntilEpochMs = System.currentTimeMillis() + 15 * 60 * 1000
                    scheduleAutoRelock(context, 15 * 60 * 1000L)
                    syncStatus(context)
                }
                "UNLOCK30" -> {
                    LockOverlayManager.hide()
                    AppState.unlockUntilEpochMs = System.currentTimeMillis() + 30 * 60 * 1000
                    scheduleAutoRelock(context, 30 * 60 * 1000L)
                    syncStatus(context)
                }
                "INTERNET_OFF", "NETOFF" -> {
                    AppState.isInternetBlocked = true
                    DwisnuLabsKillSwitchService.start(context)
                    syncStatus(context)
                }
                "INTERNET_ON", "NETON" -> {
                    AppState.isInternetBlocked = false
                    DwisnuLabsKillSwitchService.stop(context)
                    syncStatus(context)
                }
                "NET30" -> {
                    AppState.isInternetBlocked = true
                    DwisnuLabsKillSwitchService.start(context)
                    scheduleAutoRestoreInternet(context, 30 * 60 * 1000L)
                    syncStatus(context)
                }
                else -> {
                    Log.w(TAG, "Unknown action received: $action")
                }
            }
        }
    }

    private fun scheduleAutoRelock(context: Context, delayMs: Long) {
        mainHandler.postDelayed({
            if (AppState.unlockUntilEpochMs > 0 && System.currentTimeMillis() >= AppState.unlockUntilEpochMs) {
                LockOverlayManager.show(context)
                syncStatus(context)
            }
        }, delayMs)
    }

    private fun scheduleAutoRestoreInternet(context: Context, delayMs: Long) {
        mainHandler.postDelayed({
            if (AppState.isInternetBlocked || DwisnuLabsKillSwitchService.isRunning) {
                AppState.isInternetBlocked = false
                DwisnuLabsKillSwitchService.stop(context)
                syncStatus(context)
            }
        }, delayMs)
    }

    private fun getBatteryPercentage(context: Context): Int {
        return try {
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 0
        } catch (_: Exception) {
            0
        }
    }
}
