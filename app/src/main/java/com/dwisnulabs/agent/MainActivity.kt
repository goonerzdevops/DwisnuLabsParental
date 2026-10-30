package com.dwisnulabs.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusContainer: LinearLayout
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(applicationContext)

        // Auto start core service on activity open
        DwisnuLabsLockService.start(applicationContext)
        FirebaseDeviceManager.init(applicationContext)
        FirebaseDeviceManager.syncStatus(applicationContext)

        setContentView(buildView())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "🛡️ DwisnuLabs Central Agent"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val subtitle = TextView(this).apply {
            text = "Panel Diagnostik & Status Perangkat Anak"
            textSize = 13f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        root.addView(title)
        root.addView(subtitle)

        statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(32, 32, 32, 32)
        }
        root.addView(statusContainer)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 32)
        }
        root.addView(spacer)

        // Tombol Test Kunci
        val btnTestLock = Button(this).apply {
            text = "🔒 Test Kunci Layar (5 Detik)"
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Layar akan dikunci selama 5 detik...", Toast.LENGTH_SHORT).show()
                LockOverlayManager.show(applicationContext)
                mainHandler.postDelayed({
                    LockOverlayManager.hide(applicationContext)
                    Toast.makeText(this@MainActivity, "Test selesai! Kunci dibuka kembali.", Toast.LENGTH_SHORT).show()
                }, 5000)
            }
        }
        root.addView(btnTestLock)

        // Tombol Sync Sekarang
        val btnSync = Button(this).apply {
            text = "🔄 Sinkronkan Status ke Firebase"
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                FirebaseDeviceManager.syncStatus(applicationContext)
                Toast.makeText(this@MainActivity, "Status berhasil disinkronkan ke Cloud!", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
        root.addView(btnSync)

        // Tombol Buka Pengaturan Izin Khusus Xiaomi (Autostart & Pop-up)
        val btnXiaomi = Button(this).apply {
            text = "⚙️ Buka Info Aplikasi (Izin Xiaomi / HyperOS)"
            setBackgroundColor(Color.parseColor("#424242"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
        root.addView(btnXiaomi)

        scroll.addView(root)
        return scroll
    }

    private fun refreshStatus() {
        statusContainer.removeAllViews()

        val devId = FirebaseDeviceManager.deviceId
        val devName = FirebaseDeviceManager.deviceName

        addInfoRow("ID Perangkat", devId, Color.parseColor("#80D8FF"))
        addInfoRow("Label Target", devName, Color.parseColor("#FFD54F"))
        addInfoRow("Model Perangkat", Build.MODEL ?: "Unknown", Color.WHITE)
        addInfoRow("Board / Codename", Build.DEVICE ?: "Unknown", Color.LTGRAY)
        addInfoRow("Versi Android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", Color.WHITE)

        val hasOverlay = Settings.canDrawOverlays(this)
        addPermissionRow("1. Tampilkan di Atas Aplikasi Lain", hasOverlay) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        val hasAccessibility = isAccessibilityEnabled()
        addPermissionRow("2. Layanan Aksesibilitas", hasAccessibility) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val hasBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
        addPermissionRow("3. Bebas Penghemat Baterai", hasBattery) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun addInfoRow(label: String, value: String, valueColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val lblView = TextView(this).apply {
            text = "$label: "
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 13f
        }
        val valView = TextView(this).apply {
            text = value
            setTextColor(valueColor)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(lblView)
        row.addView(valView)
        statusContainer.addView(row)
    }

    private fun addPermissionRow(title: String, isGranted: Boolean, onFixClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }

        val titleView = TextView(this).apply {
            text = "$title: ${if (isGranted) "✅ Aktif" else "❌ Belum"}"
            setTextColor(if (isGranted) Color.parseColor("#81C784") else Color.parseColor("#E57373"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(titleView)

        if (!isGranted) {
            val btnFix = Button(this).apply {
                text = "Izinkan"
                textSize = 11f
                setOnClickListener { onFixClick() }
            }
            row.addView(btnFix)
        }

        statusContainer.addView(row)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val prefString = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return prefString.contains(packageName)
    }
}

