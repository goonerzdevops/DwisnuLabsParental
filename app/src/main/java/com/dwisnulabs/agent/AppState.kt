package com.dwisnulabs.agent

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

enum class ScareTheme {
    SYSTEM_ERROR,
    OVERHEAT,
    SECURITY_THREAT,
    FAKE_CRASH
}

object AppState {
    private const val PREFS_NAME = "dwisnulabs_prefs"
    private const val KEY_IS_LOCKED = "is_locked"
    private const val KEY_SCARE_THEME = "scare_theme"
    private const val KEY_UNLOCK_UNTIL = "unlock_until"
    private const val KEY_BLACKLIST = "blacklist_apps"
    private const val KEY_IS_INTERNET_BLOCKED = "is_internet_blocked"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_LAST_CMD_TS = "last_cmd_timestamp"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Auto-migration: Ensure YouTube & YouTube Kids are not blacklisted
            if (prefs.contains(KEY_BLACKLIST)) {
                val current = prefs.getStringSet(KEY_BLACKLIST, null)?.toMutableSet()
                if (current != null && (current.contains("com.google.android.youtube") || current.contains("com.google.android.apps.youtube.kids"))) {
                    current.remove("com.google.android.youtube")
                    current.remove("com.google.android.apps.youtube.kids")
                    prefs.edit().putStringSet(KEY_BLACKLIST, current).apply()
                }
            }
        }
    }

    var isLocked: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_IS_LOCKED, false) else false
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_IS_LOCKED, value).apply()
        }

    var scareTheme: ScareTheme
        get() {
            val name = if (::prefs.isInitialized) prefs.getString(KEY_SCARE_THEME, ScareTheme.SYSTEM_ERROR.name) else ScareTheme.SYSTEM_ERROR.name
            return try { ScareTheme.valueOf(name ?: ScareTheme.SYSTEM_ERROR.name) } catch (e: Exception) { ScareTheme.SYSTEM_ERROR }
        }
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putString(KEY_SCARE_THEME, value.name).apply()
        }

    var unlockUntilEpochMs: Long
        get() = if (::prefs.isInitialized) prefs.getLong(KEY_UNLOCK_UNTIL, 0L) else 0L
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putLong(KEY_UNLOCK_UNTIL, value).apply()
        }

    var blacklistedPackages: MutableSet<String>
        get() {
            return if (::prefs.isInitialized) {
                prefs.getStringSet(KEY_BLACKLIST, mutableSetOf(
                    "com.zhiliaoapp.musically",
                    "com.dts.freefireth",
                    "com.mobile.legends",
                    "com.roblox.client"
                ))?.toMutableSet() ?: mutableSetOf()
            } else mutableSetOf()
        }
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putStringSet(KEY_BLACKLIST, value).apply()
        }

    var isInternetBlocked: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_IS_INTERNET_BLOCKED, false) else false
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_IS_INTERNET_BLOCKED, value).apply()
        }

    var fcmToken: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_FCM_TOKEN, "") ?: "" else ""
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putString(KEY_FCM_TOKEN, value).apply()
        }

    var lastExecutedCmdTimestamp: Long
        get() = if (::prefs.isInitialized) prefs.getLong(KEY_LAST_CMD_TS, 0L) else 0L
        set(value) {
            if (::prefs.isInitialized) prefs.edit().putLong(KEY_LAST_CMD_TS, value).apply()
        }

    var currentForegroundPackage: String = ""
    var currentForegroundAppName: String = "Layar Utama"
}
