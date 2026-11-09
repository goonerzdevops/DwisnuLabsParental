package com.dwisnulabs.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class ScareActivity : Activity() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ScareActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }

        fun close(activity: Activity?) {
            activity?.finish()
        }

        var instance: ScareActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "DwisnuLabs:ScareWakeLock"
            )
            wakeLock.acquire(3000)
        } catch (_: Exception) {}

        setContentView(createScareView())
    }

    override fun onResume() {
        super.onResume()
        if (!AppState.isLocked) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Block back button when locked
        if (!AppState.isLocked) {
            super.onBackPressed()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // If child presses Home, immediately re-launch if still locked
        if (AppState.isLocked) {
            start(this)
        }
    }

    private fun createScareView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            isClickable = true
            isFocusable = true
        }

        val theme = AppState.scareTheme

        val titleView = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val descView = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setLineSpacing(1.2f, 1.2f)
            setPadding(0, 0, 0, 48)
        }

        val instructionView = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }

        when (theme) {
            ScareTheme.SYSTEM_ERROR -> {
                titleView.text = "⚠️ [CRITICAL SYSTEM ERROR 0x8849F]"
                titleView.setTextColor(Color.parseColor("#FF5252"))
                descView.text = "KERNEL_SECURITY_CHECK_FAILURE\nInternal storage block corrupted due to excessive load.\n\nSistem dinonaktifkan sementara untuk mencegah kerusakan permanen pada memori internal dan chipset."
                descView.setTextColor(Color.parseColor("#FFD54F"))
                instructionView.text = "DIANJURKAN: Diamkan perangkat selama 45–60 menit dalam kondisi dingin dan jangan dinyalakan!"
                instructionView.setTextColor(Color.WHITE)
            }
            ScareTheme.OVERHEAT -> {
                titleView.text = "🔥 [HARDWARE TEMPERATURE: 88°C]"
                titleView.setTextColor(Color.parseColor("#FF1744"))
                descView.text = "CRITICAL CPU & BATTERY OVERHEAT DETECTED.\nSuhu prosesor melampaui batas toleransi aman.\n\nRisiko degradasi baterai dan kerusakan hardware permanen."
                descView.setTextColor(Color.parseColor("#FF9100"))
                instructionView.text = "PERINGATAN: Perangkat dimatikan otomatis untuk pendinginan darurat. Jangan digunakan!"
                instructionView.setTextColor(Color.WHITE)
            }
            ScareTheme.SECURITY_THREAT -> {
                titleView.text = "🛡️ [ANDROID SECURITY PROTOCOL]"
                titleView.setTextColor(Color.parseColor("#FF3D00"))
                descView.text = "SUSPICIOUS EXPLOIT / TROJAN THREAT DETECTED.\nAktivitas jaringan berbahaya terdeteksi di perangkat ini.\n\nSistem telah mengisolasi akses aplikasi dan input untuk mencegah kebocoran data."
                descView.setTextColor(Color.parseColor("#80D8FF"))
                instructionView.text = "Sistem dalam mode karantina keamanan. Silakan tutup dan letakkan perangkat."
                instructionView.setTextColor(Color.WHITE)
            }
            ScareTheme.FAKE_CRASH -> {
                titleView.text = "❌ [SYSTEM UI HAS STOPPED]"
                titleView.setTextColor(Color.WHITE)
                descView.text = "Proses com.android.systemui tidak merespons.\nTerjadi kegagalan render tampilan antarmuka (Error 503)."
                descView.setTextColor(Color.LTGRAY)
                instructionView.text = "Silakan diamkan perangkat beberapa saat."
                instructionView.setTextColor(Color.GRAY)
            }
            ScareTheme.BATTERY_LOW -> {
                titleView.text = "🔋 [BATTERY CRITICAL: 15% & OVERHEAT]"
                titleView.setTextColor(Color.parseColor("#FF1744"))
                descView.text = "BATRE MAU HABIS dan KEPANASAN!\nSuhu baterai tinggi dan daya berada di batas kritis.\n\nMOHON CAS DEVICE INI AGAR TIDAK RUSAK."
                descView.setTextColor(Color.parseColor("#FF9100"))
                instructionView.text = "🔌 Hubungkan kabel pengisi daya (charger) sekarang juga!\nPerangkat baru dapat digunakan kembali setelah terisi minimal 35%."
                instructionView.setTextColor(Color.WHITE)
            }
        }

        // Secret Emergency Unlock: Tap title 5 times rapidly
        var tapCount = 0
        var lastTapTime = 0L
        titleView.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 800) {
                tapCount++
                if (tapCount >= 5) {
                    LockOverlayManager.hide()
                    finish()
                }
            } else {
                tapCount = 1
            }
            lastTapTime = now
        }

        if (theme == ScareTheme.BATTERY_LOW) {
            try {
                val imgView = android.widget.ImageView(this).apply {
                    val sizeDp = 240
                    val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = 24
                    }
                    setImageResource(R.drawable.img_battery_overheat)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                root.addView(imgView)
            } catch (e: Exception) {
                android.util.Log.w("ScareActivity", "Could not load battery overheat image: ${e.message}")
            }
        }

        root.addView(titleView)
        root.addView(descView)
        root.addView(instructionView)

        return root
    }
}
