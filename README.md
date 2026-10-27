# 🛡️ DwisnuLabs Parental (ChildLock Central)

**Sistem Pengendali & Pengawasan Gadget Anak Multi-Device Berbasis Cloud Push & Telegram Bot.**

Aplikasi agen kontrol orang tua cerdas untuk Android yang dikendalikan secara terpusat melalui **Telegram Bot**, menggunakan arsitektur *100% Free Serverless Webhook* (**Cloudflare Worker**) dan *Push-Triggered SSE* (**Google Firebase Realtime Database**) tanpa menguras baterai (*zero-polling*).

---

## 📱 Arsitektur Sistem

```
[ Smartphone Orang Tua (Telegram Bot) ]
                   │
                   ▼ (HTTPS Webhook)
      [ Cloudflare Worker Relay ]
                   │
                   ▼ (REST PUT / JSON)
  [ Google Firebase Realtime Database ]
                   │
                   ▼ (Push SSE Event / Real-time Listener)
┌──────────────────┼──────────────────┬──────────────────┐
│                  │                  │                  │
▼                  ▼                  ▼                  ▼
[ HP Kakak ]       [ HP Adik 1 ]      [ Tablet Anak ]    [ HP Adik 2 ]
(Redmi Note 9 Pro) (Redmi 9T)         (Redmi Pad SE)     (iQOO Z10)
```

---

## ✨ Fitur Utama

- **🎮 Remote Control via Telegram Bot**:
  - Gembok Layar Instan (`LOCK`) & Buka Kunci (`UNLOCK`).
  - Timer Kunci Otomatis (`Buka 15 Menit` / `Buka 30 Menit`).
  - Kill-Switch Internet / Putus Akses Jaringan Lokal (`NETOFF` / `NET30`).
  - Broadcast Aksi ke Seluruh HP Anak Serentak (`Kunci Semua HP`, `Putus Internet Semua`).
- **🎭 Layar Kamuflase (Gertakan Cerdas)**:
  - Pilihan tema layar gembok: *Critical System Error*, *CPU & Battery Overheat 88°C*, *Security / Trojan Threat*, dan *System UI Stopped*.
  - Dilengkapi *Secret Emergency Tap* untuk membuka kunci darurat oleh orang tua.
- **🚫 Pemblokiran Aplikasi (Blacklist Intercept)**:
  - Menggunakan `AccessibilityService` untuk mendeteksi aplikasi terlarang (YouTube, TikTok, game, dll.) dan seketika mengarahkan kembali ke Home Screen.
- **📊 Real-time Device Monitoring**:
  - Deteksi persentase baterai, aplikasi yang sedang dibuka di latar depan (*foreground app*), dan status konektivitas (`🟢 Online` / `🔴 Offline`).
- **⚡ Super Hemat Baterai (Zero-Polling)**:
  - Tidak menggunakan *background polling loop* yang memboroskan baterai; mengandalkan event-driven listener dari Firebase.

---

## 📂 Struktur Proyek

```text
Parental/
├── app/                       # Android Agent Application (Kotlin)
│   ├── src/main/java/com/dwisnulabs/agent/
│   │   ├── FirebaseDeviceManager.kt       # Sinkronisasi status & listener perintah
│   │   ├── LockOverlayManager.kt          # Manajer overlay layar gembok
│   │   ├── ScareActivity.kt               # Tampilan kamuflase fullscreen
│   │   ├── DwisnuLabsLockService.kt       # Foreground service & keep-alive heartbeat
│   │   ├── DwisnuLabsAccessibilityService.kt # Pencegat aplikasi & navigasi
│   │   ├── DwisnuLabsKillSwitchService.kt # Layanan pemutus internet lokal (VPN)
│   │   ├── TelegramBotClient.kt           # Klien komunikasi Telegram internal
│   │   └── AppState.kt                    # Manajemen state lokal terenkripsi
│   └── build.gradle.kts
├── backend/
│   └── cloudflare-worker/     # Serverless Telegram Bot Webhook
│       ├── worker.js          # Script Cloudflare Worker & integrasi Firebase
│       └── README.md          # Panduan deployment Cloudflare
├── doc/                       # Dokumentasi arsitektur, BRD, dan analisis
├── build.gradle.kts
└── settings.gradle.kts
```

---

## ⚙️ Panduan Pengaturan Khusus Xiaomi (MIUI / HyperOS)

Agar agen di perangkat Xiaomi / Redmi tidak dihentikan oleh penghemat daya bawaan:

1. **Mulai Otomatis (Autostart)**:
   - Masuk ke **Setelan** > **Aplikasi** > **Kelola Aplikasi** > **ChildLock Agent** > Aktifkan toggle **Mulai otomatis**.
2. **Penghemat Baterai**:
   - Di halaman info aplikasi, pilih **Penghemat Baterai** > Ubah ke **Tidak ada pembatasan (No restrictions)**.
3. **Perizinan Khusus**:
   - Pilih **Perizinan Lainnya** > Pastikan **Tampilkan jendela pop-up saat berjalan di latar belakang** dan **Tampilkan di Layar Kunci** diset ke **Izinkan**.
4. **Kunci di Recent Apps**:
   - Buka layar Recent Apps, tekan lama aplikasi **ChildLock Agent**, lalu ketuk ikon **Gembok**.

---

## 🚀 Kompilasi & Build

Pastikan JDK 17 dan Android SDK telah terkonfigurasi:

```bash
# Debug APK Build
./gradlew assembleDebug

# Output APK:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔒 Lisensi & Keamanan
Proyek ini dikembangkan secara tertutup untuk kebutuhan parental control keluarga. Seluruh kredensial token dan konfigurasi sensitif dilindungi menggunakan variable environment.
