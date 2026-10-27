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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "DwisnuLabsAccessibilityService connected.")
        AppState.init(applicationContext)
        // Watchdog: Auto-start LockService & Telegram polling when Accessibility binds
        DwisnuLabsLockService.start(applicationContext)
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

            // 1. Enforce Lock: If locked, immediately restore lock overlay and block access
            if (AppState.isLocked) {
                if (pkgName != packageName && pkgName != "com.dwisnulabs.agent") {
                    Log.w(TAG, "Device is LOCKED. Intercepting window switch to: $pkgName")
                    LockOverlayManager.show(applicationContext)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }
            }

            // Ignore common system / self packages
            if (pkgName == packageName || pkgName == "com.android.systemui" || pkgName.contains("launcher")) {
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
