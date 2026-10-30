package com.dwisnulabs.agent

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject

class DwisnuLabsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DwisnuLabsAccessibility"
        private var lastAlertPkg: String = ""
        private var lastAlertTime: Long = 0L
        private var lastEventTime: Long = 0L
        private var lastProcessedPkg: String = ""

        @Volatile
        var instance: DwisnuLabsAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "DwisnuLabsAccessibilityService connected.")
        AppState.init(applicationContext)
        // Watchdog: Auto-start LockService when Accessibility binds
        DwisnuLabsLockService.start(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    private fun isSystemOrLauncher(pkgName: String): Boolean {
        if (pkgName == packageName || pkgName == "com.dwisnulabs.agent") return true
        if (pkgName == "com.android.systemui" || pkgName.contains("systemui", ignoreCase = true)) return true
        if (pkgName.contains("launcher", ignoreCase = true) || pkgName == "com.miui.home" || pkgName == "com.mi.android.globallauncher") return true
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Watchdog: If LockService is not running, revive it immediately on screen activity
        if (!DwisnuLabsLockService.isRunning) {
            Log.d(TAG, "Watchdog triggered: DwisnuLabsLockService not running. Reviving now...")
            DwisnuLabsLockService.start(applicationContext)
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString() ?: return
            val now = System.currentTimeMillis()

            // Debounce fast duplicate events from same package (prevent event storm)
            if (pkgName == lastProcessedPkg && now - lastEventTime < 250) {
                return
            }
            lastProcessedPkg = pkgName
            lastEventTime = now

            // 1. Enforce Lock when locked
            if (AppState.isLocked) {
                // Already displaying our own lock screen or alert
                if (pkgName == packageName || pkgName == "com.dwisnulabs.agent") {
                    return
                }

                // If on Home Screen or SystemUI (e.g. tablet dock/notification shade),
                // ensure lock overlay is on screen WITHOUT calling GLOBAL_ACTION_HOME (which causes endless loop)
                if (isSystemOrLauncher(pkgName)) {
                    LockOverlayManager.show(applicationContext)
                    return
                }

                // If child is opening an app (YouTube, game, etc.) while locked, kick back to home
                Log.w(TAG, "Device is LOCKED. Intercepting app launch: $pkgName")
                performGlobalAction(GLOBAL_ACTION_HOME)
                LockOverlayManager.show(applicationContext)
                return
            }

            // Ignore common system / self packages when unlocked
            if (isSystemOrLauncher(pkgName)) {
                return
            }

            val appName = getAppLabel(pkgName)
            AppState.currentForegroundPackage = pkgName
            AppState.currentForegroundAppName = appName
            FirebaseDeviceManager.onForegroundAppChanged(applicationContext, appName)

            // Check if app is in blacklist
            if (AppState.blacklistedPackages.contains(pkgName)) {
                Log.w(TAG, "Blocked app detected: $appName ($pkgName)")

                // Redirect child to home screen instantly
                performGlobalAction(GLOBAL_ACTION_HOME)

                // Send instant alert to parent on Telegram (debounced to avoid spamming)
                val now = System.currentTimeMillis()
                if (pkgName != lastAlertPkg || now - lastAlertTime > 30000) {
                    lastAlertPkg = pkgName
                    lastAlertTime = now

                    val alertText = "⚠️ *Peringatan Anak:*\nAnak baru saja mencoba membuka *$appName*!\nSistem telah otomatis memblokir dan mengarahkan ke Home Screen."
                    
                    val keyboard = JSONArray().apply {
                        put(JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "⏳ Izinkan 30 Menit")
                                put("callback_data", "CMD_UNLOCK_30M")
                            })
                            put(JSONObject().apply {
                                put("text", "🔒 Kunci HP Total")
                                put("callback_data", "CMD_LOCK")
                            })
                        })
                    }
                    val markup = JSONObject().put("inline_keyboard", keyboard)
                    TelegramBotClient.sendMessage(TelegramConfig.PARENT_CHAT_ID, alertText, markup)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "DwisnuLabsAccessibilityService interrupted.")
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
