package com.dwisnulabs.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object TelegramBotClient {
    private const val TAG = "DwisnuLabsTelegram"
    private const val BASE_URL = "https://api.telegram.org/bot"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var pollingJob: Job? = null
    private var lastUpdateId = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    val isPolling: Boolean
        get() = pollingJob?.isActive == true

    fun startPolling(context: Context, scope: CoroutineScope) {
        if (pollingJob?.isActive == true) return

        if (!TelegramConfig.isConfigured()) {
            Log.w(TAG, "TelegramConfig is not configured. Polling not started.")
            return
        }

        pollingJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Telegram Long Polling started.")
            var greetingSent = false

            while (isActive) {
                try {
                    // Send greeting as soon as internet is available (with auto-retry)
                    if (!greetingSent) {
                        try {
                            val sent = sendMessageSync(
                                TelegramConfig.PARENT_CHAT_ID,
                                "✅ <b>DwisnuLabs Core Service Aktif!</b>\nHP Anak terhubung dan siap dikendalikan.\nKetik /menu untuk membuka remote control.",
                                getHomeKeyboard()
                            )
                            if (sent) {
                                greetingSent = true
                                Log.d(TAG, "Initial greeting successfully delivered to Telegram.")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Waiting for network connectivity to send greeting: ${e.message}")
                        }
                    }

                    pollUpdates(context)
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    Log.e(TAG, "Error in polling loop: ${e.message}")
                    delay(3000)
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun pollUpdates(context: Context) {
        val url = "$BASE_URL${TelegramConfig.BOT_TOKEN}/getUpdates?offset=${lastUpdateId + 1}&timeout=20"
        val request = Request.Builder().url(url).build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "getUpdates response not successful: code ${response.code}")
                delay(3000)
                return
            }

            val bodyString = response.body?.string() ?: return
            val json = JSONObject(bodyString)
            if (!json.optBoolean("ok", false)) return

            val result = json.optJSONArray("result") ?: return
            for (i in 0 until result.length()) {
                val update = result.getJSONObject(i)
                val updateId = update.getLong("update_id")
                if (updateId > lastUpdateId) {
                    lastUpdateId = updateId
                }

                // Handle regular message
                if (update.has("message")) {
                    handleIncomingMessage(context, update.getJSONObject("message"))
                }

                // Handle inline button callback query
                if (update.has("callback_query")) {
                    handleCallbackQuery(context, update.getJSONObject("callback_query"))
                }
            }
        } catch (e: IOException) {
            delay(3000)
        }
    }

    private suspend fun handleIncomingMessage(context: Context, message: JSONObject) {
        val chat = message.getJSONObject("chat")
        val chatId = chat.getLong("id")

        // Strict whitelist: Only parent can control!
        if (chatId != TelegramConfig.PARENT_CHAT_ID) {
            Log.w(TAG, "Unauthorized message from Chat ID: $chatId")
            return
        }

        val text = message.optString("text", "").trim()
        Log.d(TAG, "Command received: $text")

        when {
            text.equals("/start", ignoreCase = true) || text.equals("/menu", ignoreCase = true) -> {
                sendMessage(chatId, "🎮 <b>Pusat Kendali HP Anak (ChildLockAgent)</b>\nPilih perintah di bawah ini:", getHomeKeyboard())
            }
            text.equals("/status", ignoreCase = true) -> {
                sendStatusReport(context, chatId)
            }
            text.startsWith("/lock", ignoreCase = true) -> {
                LockOverlayManager.show(context)
                sendMessage(chatId, "🔒 <b>Layar HP Anak Berhasil Dikunci!</b>\nTampilan kamuflase (${AppState.scareTheme.name}) sedang aktif di layar.", getHomeKeyboard())
            }
            text.startsWith("/unlock", ignoreCase = true) -> {
                handleUnlockCommand(chatId, text)
            }
            text.startsWith("/wifi off", ignoreCase = true) || text.startsWith("/internet off", ignoreCase = true) || text.equals("/putus", ignoreCase = true) -> {
                AppState.isInternetBlocked = true
                val parts = text.split(" ")
                if (parts.size > 1 && parts[1].contains("m", ignoreCase = true)) {
                    val minutes = parts[1].lowercase().removeSuffix("m").toLongOrNull() ?: 30L
                    DwisnuLabsKillSwitchService.start(context)
                    scheduleAutoRestoreInternet(context, minutes * 60 * 1000L)
                    sendMessage(chatId, "📶 <b>Internet Anak Diputus selama $minutes Menit!</b>\nSemua video YouTube / Game anak akan loading berputar terus (buffering).\n<i>Setelah $minutes menit internet otomatis pulih. Bot tetap online setiap saat.</i>", getHomeKeyboard())
                } else {
                    DwisnuLabsKillSwitchService.start(context)
                    sendMessage(chatId, "📶 <b>Internet Anak Berhasil Diputus!</b>\nSemua video YouTube / Game anak akan buffering tanpa batas (seperti kendala sinyal provider).\n<i>Bot tetap siaga menerima perintah Ayah kapan saja.</i>", getHomeKeyboard())
                }
            }
            text.startsWith("/wifi on", ignoreCase = true) || text.startsWith("/internet on", ignoreCase = true) || text.equals("/sambung", ignoreCase = true) -> {
                AppState.isInternetBlocked = false
                autoRestoreInternetRunnable?.let { mainHandler.removeCallbacks(it) }
                DwisnuLabsKillSwitchService.stop(context)
                sendMessage(chatId, "📶 <b>Internet Anak Dihubungkan Kembali!</b>\nSemua aplikasi dan streaming video anak telah pulih normal.", getHomeKeyboard())
            }
            text.equals("/apps", ignoreCase = true) -> {
                sendAppPicker(chatId)
            }
            text.equals("/theme", ignoreCase = true) -> {
                sendMessage(chatId, "🎭 <b>Pilih Tema Kamuflase / Gertakan:</b>", getThemesKeyboard())
            }
            text.startsWith("/block ", ignoreCase = true) -> {
                val query = text.substringAfter("/block ").trim()
                blockAppByName(chatId, query)
            }
            text.startsWith("/unblock ", ignoreCase = true) -> {
                val query = text.substringAfter("/unblock ").trim()
                unblockAppByName(chatId, query)
            }
            else -> {
                sendMessage(chatId, "❓ Perintah tidak dikenali. Ketik /menu untuk melihat tombol kendali.", getHomeKeyboard())
            }
        }
    }

    private suspend fun handleCallbackQuery(context: Context, callbackQuery: JSONObject) {
        val queryId = callbackQuery.getString("id")
        val from = callbackQuery.getJSONObject("from")
        val fromId = from.getLong("id")
        val data = callbackQuery.optString("data", "")

        if (fromId != TelegramConfig.PARENT_CHAT_ID) return

        answerCallback(queryId, "Memproses...")

        when (data) {
            "CMD_LOCK" -> {
                LockOverlayManager.show(context)
                sendMessage(fromId, "🔒 <b>Layar HP Anak Dikunci Sekarang!</b>\nTampilan kamuflase (${AppState.scareTheme.name}) aktif.", getHomeKeyboard())
            }
            "CMD_UNLOCK" -> {
                LockOverlayManager.hide()
                AppState.unlockUntilEpochMs = 0L
                sendMessage(fromId, "🔓 <b>Layar HP Anak Telah Dibuka.</b>", getHomeKeyboard())
            }
            "CMD_UNLOCK_15M" -> {
                LockOverlayManager.hide()
                AppState.unlockUntilEpochMs = System.currentTimeMillis() + (15 * 60 * 1000)
                scheduleAutoRelock(context, 15 * 60 * 1000L)
                sendMessage(fromId, "⏳ <b>Layar dibuka selama 15 menit.</b> Akan terkunci kembali otomatis.", getHomeKeyboard())
            }
            "CMD_UNLOCK_30M" -> {
                LockOverlayManager.hide()
                AppState.unlockUntilEpochMs = System.currentTimeMillis() + (30 * 60 * 1000)
                scheduleAutoRelock(context, 30 * 60 * 1000L)
                sendMessage(fromId, "⏳ <b>Layar dibuka selama 30 menit.</b> Akan terkunci kembali otomatis.", getHomeKeyboard())
            }
            "CMD_STATUS" -> {
                sendStatusReport(context, fromId)
            }
            "CMD_INTERNET_ON", "CMD_WIFI_ON" -> {
                AppState.isInternetBlocked = false
                autoRestoreInternetRunnable?.let { mainHandler.removeCallbacks(it) }
                DwisnuLabsKillSwitchService.stop(context)
                sendMessage(fromId, "📶 <b>Internet Anak Dihubungkan Kembali!</b>\nKoneksi data semua aplikasi telah kembali lancar.", getHomeKeyboard())
            }
            "CMD_INTERNET_OFF", "CMD_WIFI_OFF" -> {
                AppState.isInternetBlocked = true
                DwisnuLabsKillSwitchService.start(context)
                sendMessage(fromId, "📶 <b>Internet Anak Berhasil Diputus!</b>\nSemua video YouTube / Game anak akan buffering berputar terus tanpa henti (tampak seperti kendala provider).\n<i>Bot tetap siaga menerima perintah Ayah kapan saja.</i>", getHomeKeyboard())
            }
            "CMD_INTERNET_30M" -> {
                AppState.isInternetBlocked = true
                DwisnuLabsKillSwitchService.start(context)
                scheduleAutoRestoreInternet(context, 30 * 60 * 1000L)
                sendMessage(fromId, "⏳ <b>Internet anak diputus selama 30 menit.</b>\nVideo akan buffering selama 30 menit lalu otomatis pulih kembali.", getHomeKeyboard())
            }
            "CMD_APPS" -> {
                sendAppPicker(fromId)
            }
            "CMD_THEMES" -> {
                sendMessage(fromId, "🎭 <b>Pilih Gaya Gertakan / Kamuflase:</b>", getThemesKeyboard())
            }
            "SET_THEME_ERROR" -> {
                AppState.scareTheme = ScareTheme.SYSTEM_ERROR
                sendMessage(fromId, "✅ Tema diubah ke: <b>Sistem Rusak / Storage Corrupted</b>", getHomeKeyboard())
            }
            "SET_THEME_OVERHEAT" -> {
                AppState.scareTheme = ScareTheme.OVERHEAT
                sendMessage(fromId, "✅ Tema diubah ke: <b>CPU Overheat 88°C</b>", getHomeKeyboard())
            }
            "SET_THEME_THREAT" -> {
                AppState.scareTheme = ScareTheme.SECURITY_THREAT
                sendMessage(fromId, "✅ Tema diubah ke: <b>Security Threat / Malware</b>", getHomeKeyboard())
            }
            "SET_THEME_CRASH" -> {
                AppState.scareTheme = ScareTheme.FAKE_CRASH
                sendMessage(fromId, "✅ Tema diubah ke: <b>Fake Crash (App Terhenti)</b>", getHomeKeyboard())
            }
            "CMD_MENU" -> {
                sendMessage(fromId, "🎮 <b>Menu Utama Kendali:</b>", getHomeKeyboard())
            }
            else -> {
                if (data.startsWith("TOGGLE_APP_")) {
                    val pkg = data.removePrefix("TOGGLE_APP_")
                    toggleAppBlock(fromId, pkg)
                }
            }
        }
    }

    private fun handleUnlockCommand(chatId: Long, text: String) {
        LockOverlayManager.hide()
        val parts = text.split(" ")
        if (parts.size > 1) {
            val durationStr = parts[1].lowercase()
            val minutes = durationStr.removeSuffix("m").toLongOrNull() ?: 15L
            AppState.unlockUntilEpochMs = System.currentTimeMillis() + (minutes * 60 * 1000)
            sendMessage(chatId, "🔓 <b>Layar dibuka selama $minutes menit.</b>", getHomeKeyboard())
        } else {
            AppState.unlockUntilEpochMs = 0L
            sendMessage(chatId, "🔓 <b>Layar dibuka penuh tanpa batas waktu.</b>", getHomeKeyboard())
        }
    }

    private var autoRestoreInternetRunnable: Runnable? = null

    private fun scheduleAutoRestoreInternet(context: Context, delayMs: Long) {
        autoRestoreInternetRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (AppState.isInternetBlocked || DwisnuLabsKillSwitchService.isRunning) {
                AppState.isInternetBlocked = false
                DwisnuLabsKillSwitchService.stop(context)
                sendMessage(TelegramConfig.PARENT_CHAT_ID, "⏰ <b>Waktu Pemutusan Internet Habis!</b>\nInternet anak telah otomatis dipulihkan kembali ke status normal.", getHomeKeyboard())
            }
        }
        autoRestoreInternetRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun scheduleAutoRelock(context: Context, delayMs: Long) {
        mainHandler.postDelayed({
            if (AppState.unlockUntilEpochMs > 0 && System.currentTimeMillis() >= AppState.unlockUntilEpochMs) {
                LockOverlayManager.show(context)
                sendMessage(TelegramConfig.PARENT_CHAT_ID, "⏰ <b>Waktu Bermain Habis!</b>\nLayar HP anak terkunci kembali otomatis.", getHomeKeyboard())
            }
        }, delayMs)
    }

    private fun sendStatusReport(context: Context, chatId: Long) {
        val batteryPct = getBatteryPercentage(context)
        val isLocked = LockOverlayManager.isShowing() || AppState.isLocked
        val lockStatus = if (isLocked) "🔒 Terkunci (${AppState.scareTheme.name})" else "🔓 Terbuka"
        val wifiStatus = if (isWifiEnabled(context)) "📶 Aktif" else "📵 Mati"
        val currentApp = AppState.currentForegroundAppName

        val isCut = AppState.isInternetBlocked
        val internetStatus = if (isCut) "❌ Diputus (Buffering Mode)" else "✅ Normal (Aktif)"
        val report = """
            📱 <b>STATUS HP ANAK (${FirebaseDeviceManager.deviceName})</b>
            ━━━━━━━━━━━━━━━━━━━
            🔋 Baterai       : $batteryPct%
            🔒 Status Kunci  : $lockStatus
            📱 Aplikasi Aktif : $currentApp
            📶 Internet Anak : $internetStatus
            📡 Sinyal Wi-Fi  : $wifiStatus
            🎭 Tema Gertakan : ${AppState.scareTheme.name}
            ━━━━━━━━━━━━━━━━━━━
        """.trimIndent()

        sendMessage(chatId, report, getHomeKeyboard())
    }

    private fun sendAppPicker(chatId: Long) {
        val blacklist = AppState.blacklistedPackages
        val keyboard = JSONArray()

        val sampleApps = listOf(
            "YouTube" to "com.google.android.youtube",
            "TikTok" to "com.zhiliaoapp.musically",
            "Roblox" to "com.roblox.client",
            "Mobile Legends" to "com.mobile.legends",
            "Free Fire" to "com.dts.freefireth"
        )

        for ((name, pkg) in sampleApps) {
            val isBlocked = blacklist.contains(pkg)
            val icon = if (isBlocked) "🔴 Blokir" else "🟢 Boleh"
            val row = JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "$name ($icon)")
                    put("callback_data", "TOGGLE_APP_$pkg")
                })
            }
            keyboard.put(row)
        }

        keyboard.put(JSONArray().apply {
            put(JSONObject().apply {
                put("text", "⬅️ Kembali ke Menu Utama")
                put("callback_data", "CMD_MENU")
            })
        })

        val replyMarkup = JSONObject().put("inline_keyboard", keyboard)
        sendMessage(chatId, "📱 <b>Kelola Aplikasi yang Dilarang:</b>\nKetuk nama aplikasi untuk mengubah status blokir:", replyMarkup)
    }

    private fun toggleAppBlock(chatId: Long, pkg: String) {
        val list = AppState.blacklistedPackages
        val nowBlocked = if (list.contains(pkg)) {
            list.remove(pkg)
            false
        } else {
            list.add(pkg)
            true
        }
        AppState.blacklistedPackages = list

        val statusText = if (nowBlocked) "🔴 DIBLOKIR" else "🟢 DIIZINKAN"
        sendMessage(chatId, "Aplikasi <code>$pkg</code> sekarang: <b>$statusText</b>")
        sendAppPicker(chatId)
    }

    private fun blockAppByName(chatId: Long, query: String) {
        val mappedPkg = when (query.lowercase()) {
            "youtube" -> "com.google.android.youtube"
            "tiktok" -> "com.zhiliaoapp.musically"
            "roblox" -> "com.roblox.client"
            "ml", "mobile legends" -> "com.mobile.legends"
            "ff", "free fire" -> "com.dts.freefireth"
            else -> query
        }
        val set = AppState.blacklistedPackages
        set.add(mappedPkg)
        AppState.blacklistedPackages = set
        sendMessage(chatId, "🔴 Berhasil memblokir: <b>$mappedPkg</b>", getHomeKeyboard())
    }

    private fun unblockAppByName(chatId: Long, query: String) {
        val mappedPkg = when (query.lowercase()) {
            "youtube" -> "com.google.android.youtube"
            "tiktok" -> "com.zhiliaoapp.musically"
            "roblox" -> "com.roblox.client"
            "ml", "mobile legends" -> "com.mobile.legends"
            "ff", "free fire" -> "com.dts.freefireth"
            else -> query
        }
        val set = AppState.blacklistedPackages
        set.remove(mappedPkg)
        AppState.blacklistedPackages = set
        sendMessage(chatId, "🟢 Berhasil membuka blokir: <b>$mappedPkg</b>", getHomeKeyboard())
    }

    @Suppress("DEPRECATION")
    private fun setWifiState(context: Context, enable: Boolean): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val current = wifiManager.isWifiEnabled
            Log.d(TAG, "Current isWifiEnabled before change: $current. Requesting enable=$enable")
            val result = wifiManager.setWifiEnabled(enable)
            Log.d(TAG, "wifiManager.setWifiEnabled($enable) returned: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Wi-Fi: ${e.message}")
            false
        }
    }

    private fun isWifiEnabled(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled
    }

    private fun getBatteryPercentage(context: Context): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 0
    }

    private fun getHomeKeyboard(): JSONObject {
        val isCut = AppState.isInternetBlocked
        val internetText = if (isCut) "📶 Sambung Internet" else "📶 Putus Internet (Buffer)"
        val internetCallback = if (isCut) "CMD_INTERNET_ON" else "CMD_INTERNET_OFF"

        val keyboard = JSONArray().apply {
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "🔒 Kunci Sekarang"); put("callback_data", "CMD_LOCK") })
                put(JSONObject().apply { put("text", "🔓 Buka Kunci"); put("callback_data", "CMD_UNLOCK") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "⏳ Buka 15 Menit"); put("callback_data", "CMD_UNLOCK_15M") })
                put(JSONObject().apply { put("text", "⏳ Buka 30 Menit"); put("callback_data", "CMD_UNLOCK_30M") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", internetText); put("callback_data", internetCallback) })
                put(JSONObject().apply { put("text", "⏳ Putus 30 Menit"); put("callback_data", "CMD_INTERNET_30M") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "📱 Kelola Aplikasi"); put("callback_data", "CMD_APPS") })
                put(JSONObject().apply { put("text", "📊 Status HP"); put("callback_data", "CMD_STATUS") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "🎭 Ganti Tema Gertakan"); put("callback_data", "CMD_THEMES") })
            })
        }
        return JSONObject().put("inline_keyboard", keyboard)
    }

    private fun getThemesKeyboard(): JSONObject {
        val keyboard = JSONArray().apply {
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "⚠️ Sistem Rusak / Error"); put("callback_data", "SET_THEME_ERROR") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "🔥 CPU Overheat 88°C"); put("callback_data", "SET_THEME_OVERHEAT") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "🛡️ Virus / Hack Alert"); put("callback_data", "SET_THEME_THREAT") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "💥 Fake Crash App"); put("callback_data", "SET_THEME_CRASH") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "⬅️ Menu Utama"); put("callback_data", "CMD_MENU") })
            })
        }
        return JSONObject().put("inline_keyboard", keyboard)
    }

    fun sendMessageSync(chatId: Long, text: String, replyMarkup: JSONObject? = null): Boolean {
        return try {
            val url = "$BASE_URL${TelegramConfig.BOT_TOKEN}/sendMessage"
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "HTML")
                if (replyMarkup != null) {
                    put("reply_markup", replyMarkup)
                }
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            val response = httpClient.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                val err = response.body?.string()
                Log.e(TAG, "sendMessage error ${response.code}: $err")
            }
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message: ${e.message}")
            false
        }
    }

    fun sendMessage(chatId: Long, text: String, replyMarkup: JSONObject? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            sendMessageSync(chatId, text, replyMarkup)
        }
    }

    private fun answerCallback(callbackId: String, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "$BASE_URL${TelegramConfig.BOT_TOKEN}/answerCallbackQuery"
                val json = JSONObject().apply {
                    put("callback_query_id", callbackId)
                    put("text", text)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                httpClient.newCall(Request.Builder().url(url).post(body).build()).execute().close()
            } catch (_: Exception) {}
        }
    }
}
