# 🛠️ Panduan Onboarding & Setup Environment Lengkap: DwisnuLabs Parental

Dokumen ini adalah panduan teknis langkah-demi-langkah bagi kontributor atau developer (pemula hingga mahir) untuk mendirikan (*setup*), mengompilasi, dan meluncurkan seluruh ekosistem **DwisnuLabs Parental** dari nol secara mandiri.

---

## 📋 Daftar Isi
1. [Arsitektur & Komponen Sistem](#1-arsitektur--komponen-sistem)
2. [Prasyarat Perangkat Lunak (Prerequisites)](#2-prasyarat-perangkat-lunak-prerequisites)
3. [Langkah 1: Setup Bot Telegram (@BotFather)](#langkah-1-setup-bot-telegram-botfather)
4. [Langkah 2: Setup Google Firebase Realtime Database](#langkah-2-setup-google-firebase-realtime-database)
5. [Langkah 3: Setup & Deploy Cloudflare Worker (100% Full CLI)](#langkah-3-setup--deploy-cloudflare-worker-100-full-cli)
6. [Langkah 4: Konfigurasi & Kompilasi APK Android](#langkah-4-konfigurasi--kompilasi-apk-android)
7. [Langkah 5: Instalasi & Konfigurasi Izin Khusus Vendor (Xiaomi/HyperOS)](#langkah-5-instalasi--konfigurasi-izin-khusus-vendor-xiaomihyperos)
8. [Troubleshooting & Solusi Kendala Populer](#8-troubleshooting--solusi-kendala-populer)

---

## 1. Arsitektur & Komponen Sistem

Sistem ini terdiri dari 3 pilar yang saling terhubung:
1. **Telegram Bot (User Interface Orang Tua)**: Berisi menu keyboard interaktif untuk mengirim perintah kunci, pemutus internet, dan memantau status HP anak tanpa perlu memasang aplikasi tambahan di HP orang tua.
2. **Cloudflare Worker (Serverless Relay Hub)**: Menerima webhook Telegram 24/7 (0ms cold-start, gratis 100.000 req/hari), memvalidasi ID pengirim, dan menyinkronkan instruksi ke Firebase.
3. **Android Stealth Agent (HP/Tablet Anak)**: Background Service & Accessibility Service di perangkat anak yang terhubung ke Firebase via real-time WebSocket (<100ms response).

---

## 2. Prasyarat Perangkat Lunak (Prerequisites)

Sebelum memulai, pastikan komputer Anda telah terpasang:
* **JDK (Java Development Kit) 17**: [Eclipse Temurin OpenJDK 17](https://adoptium.net/temurin/releases/?version=17) atau Oracle JDK 17.
* **Android SDK (API 34) & Platform Tools**: Termasuk `adb.exe` untuk debug dan instalasi perangkat.
* **Node.js (v18+) & npm**: Untuk manajemen Cloudflare Wrangler CLI.
* **Akun Telegram**: Untuk membuat bot kontrol.
* **Akun Google**: Untuk mengakses Firebase Console (Gratis).
* **Akun Cloudflare**: Untuk serverless worker (Gratis).

---

## Langkah 1: Setup Bot Telegram (@BotFather)

Pembuatan bot Telegram dilakukan melalui aplikasi Telegram:

1. Buka aplikasi Telegram di HP atau laptop Anda.
2. Cari akun resmi **`@BotFather`** (bertanda centang biru).
3. Ketik perintah:
   ```text
   /newbot
   ```
4. Ikuti instruksi BotFather:
   * Masukkan nama bot Anda (misal: `MyParentalControlBot`).
   * Masukkan username unik berakhiran `bot` (misal: `my_parental_hub_bot`).
5. BotFather akan memberikan **HTTP API Token** seperti ini:
   ```text
   1234567890:ABCdefGHIjklMNOpqrSTUvwxyz
   ```
   *Simpan token ini sebagai `TELEGRAM_BOT_TOKEN`.*
6. Cari bot **`@userinfobot`** di Telegram, tekan `/start`. Bot akan menampilkan ID numerik Telegram Anda:
   ```text
   Id: 1234567890
   ```
   *Simpan angka ini sebagai `PARENT_CHAT_ID` (Hanya akun ini yang berhak mengontrol bot).*

---

## Langkah 2: Setup Google Firebase Realtime Database

Firebase bertindak sebagai bus komunikasi data real-time dan antrean perintah (*command queue*).

1. Buka [Firebase Console](https://console.firebase.google.com/) dan klik **"Add project"**.
2. Beri nama project (misal: `parental-hub`), nonaktifkan Google Analytics (opsional), lalu klik **Create project**.
3. Di panel navigasi kiri: **Build** > **Realtime Database** > Klik **Create Database**.
   * Pilih lokasi database (disarankan **Singapore (`asia-southeast1`)** untuk latensi tercepat di Asia Tenggara).
   * Pilih opsi keamanan awal: **Start in test mode** (klik **Enable**).
4. Masuk ke tab **Rules**, lalu sesuaikan aturannya:
   ```json
   {
     "rules": {
       ".read": true,
       ".write": true
     }
   }
   ```
   Klik **Publish**. Salin URL Database Anda (contoh: `https://parental-hub-default-rtdb.asia-southeast1.firebasedatabase.app`).
5. **Daftarkan Aplikasi Android**:
   * Kembali ke Project Overview, klik ikon **Android**.
   * Masukkan Android package name: `com.dwisnulabs.agent`
   * Klik **Register app**.
   * Unduh file **`google-services.json`**.
   * Pindahkan file `google-services.json` yang diunduh ke folder `app/` di direktori project lokal Anda (`app/google-services.json`).
   * Buka file `app/google-services.json` tersebut dan pastikan di dalam blok `"project_info"` terdapat baris:
     ```json
     "firebase_url": "https://<YOUR-PROJECT-ID>-default-rtdb.asia-southeast1.firebasedatabase.app",
     ```

---

## Langkah 3: Setup & Deploy Cloudflare Worker (100% Full CLI)

Anda **tidak perlu** mengotak-atik web dashboard Cloudflare. Seluruh proses deploy dan konfigurasi secret dapat dilakukan 100% dari terminal menggunakan **Wrangler CLI**.

1. Buka terminal (CMD / PowerShell / Bash), lalu masuk ke direktori worker:
   ```bash
   cd backend/cloudflare-worker
   ```
2. Login ke akun Cloudflare Anda (otomatis membuka browser untuk klik "Allow"):
   ```bash
   npx wrangler login
   ```
3. Deploy worker ke Cloudflare edge:
   ```bash
   npx wrangler deploy
   ```
   *Terminal akan menghasilkan URL worker Anda, misalnya:*
   `https://dwisnulabs-parental-bot.<username>.workers.dev`

4. **Simpan Kredensial Rahasia via Cloudflare Secrets**:
   Jalankan perintah ini di terminal (masukkan nilai masing-masing saat diminta):
   ```bash
   npx wrangler secret put TELEGRAM_BOT_TOKEN
   # Masukkan token dari BotFather: 1234567890:ABCdef...

   npx wrangler secret put PARENT_CHAT_ID
   # Masukkan Chat ID Telegram Anda: 1234567890

   npx wrangler secret put FIREBASE_DB_URL
   # Masukkan URL Firebase: https://<PROJECT-ID>-default-rtdb.asia-southeast1.firebasedatabase.app
   ```

5. **Hubungkan Webhook Telegram ke Cloudflare Worker**:
   Jalankan perintah curl berikut dari terminal Anda (ganti `<BOT_TOKEN>` dan `<URL_WORKER>`):
   ```bash
   curl -F "url=https://dwisnulabs-parental-bot.<username>.workers.dev" https://api.telegram.org/bot<BOT_TOKEN>/setWebhook
   ```
   Jika sukses, Anda akan menerima respon:
   ```json
   {"ok":true,"result":true,"description":"Webhook was set"}
   ```

Sekarang coba buka bot Telegram Anda dan kirim pesan `/start` atau `/menu`. Bot Anda sudah aktif 24/7 di serverless Cloudflare!

---

## Langkah 4: Konfigurasi & Kompilasi APK Android

1. Buka folder root project di terminal:
   ```bash
   cd c:\D\Project\Mix\Parental
   ```
2. Pastikan file `app/google-services.json` telah berada di tempatnya (jika belum ada, gunakan template `app/google-services.json.example` sebagai referensi).
3. Bersihkan cache build lama dan kompilasi APK baru menggunakan Gradle Wrapper:
   ```bash
   # Di Windows (PowerShell / Command Prompt):
   .\gradlew.bat assembleDebug

   # Di macOS / Linux:
   ./gradlew assembleDebug
   ```
4. File output APK siap pasang berada di:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Langkah 5: Instalasi & Konfigurasi Izin Khusus Vendor (Xiaomi/HyperOS)

1. Aktifkan **Opsi Pengembang (Developer Options)** dan **USB Debugging** di HP/Tablet anak:
   * Masuk ke *Settings* > *About phone* > Ketuk *OS Version / MIUI Version* sebanyak 7 kali.
   * Masuk ke *Additional settings* > *Developer options* > Aktifkan:
     * **USB debugging**
     * **Install via USB**
     * **USB debugging (Security settings)**
2. Hubungkan HP/tablet ke laptop menggunakan kabel USB.
3. Pasang APK hasil kompilasi:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. **Berikan Seluruh Izin Khusus dalam 1 Baris Perintah ADB**:
   Agar sistem OS Android 10–14 maupun HyperOS tidak mematikan service secara sepihak, jalankan perintah batch berikut:
   ```bash
   # Berikan izin Overlay, AppOps Xiaomi, Notifikasi, dan Abaikan Baterai:
   adb shell cmd appops set com.dwisnulabs.agent SYSTEM_ALERT_WINDOW allow
   adb shell cmd appops set com.dwisnulabs.agent 10008 allow
   adb shell cmd appops set com.dwisnulabs.agent 10020 allow
   adb shell cmd appops set com.dwisnulabs.agent 10021 allow
   adb shell cmd appops set com.dwisnulabs.agent USE_FULL_SCREEN_INTENT allow
   adb shell pm grant com.dwisnulabs.agent android.permission.POST_NOTIFICATIONS
   adb shell dumpsys deviceidle whitelist +com.dwisnulabs.agent

   # Aktifkan Layanan Aksesibilitas Langsung via ADB:
   adb shell settings put secure enabled_accessibility_services com.dwisnulabs.agent/com.dwisnulabs.agent.DwisnuLabsAccessibilityService
   adb shell settings put secure accessibility_enabled 1
   ```
5. Jalankan layanan latar belakang untuk pertama kali:
   ```bash
   adb shell am start-foreground-service -n com.dwisnulabs.agent/.DwisnuLabsLockService
   ```

Selesai! Buka bot Telegram Anda, kirim perintah `/menu`. Perangkat anak akan langsung muncul di daftar kendali dan siap dikunci atau dipantau!

---

## 8. Troubleshooting & Solusi Kendala Populer

### Q1: Muncul `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` saat install via ADB?
* **Penyebab**: Di Xiaomi/HyperOS, saat install via USB muncul dialog konfirmasi di layar anak dengan hitung mundur 10 detik. Jika tidak ditekan *"Install"*, instalasi dibatalkan otomatis.
* **Solusi**: Pastikan layar HP anak dalam kondisi menyala dan tidak tertutup gembok, lalu tekan tombol **"Install"** saat dialog konfirmasi muncul.

### Q2: Layar terkunci tapi YouTube malah mengecil ke pojok kanan bawah (Picture-in-Picture)?
* **Solusi**: Di arsitektur terbaru (v2.0.0), sistem menggunakan `TYPE_ACCESSIBILITY_OVERLAY` (layer prioritas 2032) yang merender gembok di atas seluruh jendela termasuk PiP, serta fungsi `pauseMediaPlayback()` yang seketika memutus audio dan menjeda pemutaran video secara otomatis.

### Q3: Kenapa jam di tablet anak tidak sama dengan waktu server sehingga statusnya selalu tertulis offline?
* **Solusi**: Jalankan sinkronisasi waktu jaringan via ADB:
  ```bash
  adb shell settings put global auto_time 1
  adb shell settings put global auto_time_zone 1
  ```
  Pada kode `FirebaseDeviceManager.kt`, status `lastSeen` telah diperbarui menggunakan `com.google.firebase.database.ServerValue.TIMESTAMP` sehingga otomatis kebal terhadap kesalahan jam lokal pada perangkat anak.
