# TECHNICAL SPECIFICATION DESIGN (TSD)
## Project: ChildLockAgent (DwisnuLabs Parental Control System)

| Dokumen | Nilai |
| :--- | :--- |
| **Nama Sistem** | ChildLockAgent / DwisnuLabs Core Service |
| **Versi Dokumen** | 1.7.1 |
| **Status** | Production-Ready (Verified on Android 12 / MIUI 13-14) |
| **Tanggal Pembaruan** | 26 Oktober 2026 |
| **Target OS Perangkat Anak** | Android 8.0 (API Level 26) s/d Android 14 (API Level 34) |
| **Target Controller** | Telegram Messenger Platform (Bot API Engine) |
| **Klasifikasi Dokumen** | Technical & Architectural Design Specification |

---

## 1. Ikhtisar Arsitektur Sistem

### 1.1 Filosofi Desain
ChildLockAgent dibangun dengan filosofi **"Headless Stealth Parental Agent"**. Sistem beroperasi tanpa antarmuka pengguna grafis tradisional di perangkat anak (tanpa icon launcher di home screen atau app drawer) dan dikendalikan sepenuhnya secara nirkabel melalui antarmuka Telegram Bot.

Tujuan utama dari sistem ini adalah memberikan kontrol parental granular yang ramah psikologis anak usia balita (toddler) dan anak-anak, di mana pemutusan akses internet dilakukan secara ilusi (tampak seperti kendala jaringan penyedia/provider yang menyebabkan video terus berputar atau *buffering*) daripada penguncian visual agresif yang dapat memicu tantrum.

### 1.2 Topologi Jaringan Global

```
+-----------------------------------------------------------------------------------+
|                               TOPOLOGI SISTEM                                     |
+-----------------------------------------------------------------------------------+

 [ Smartphone Orang Tua ]
          |
          |  (Koneksi Standar Telegram Client - HTTPS / MTProto)
          v
 [ Telegram Cloud API Server ]
          ^
          |  (HTTP Long-Polling / Polling Berkelanjutan - Port 443)
          v
 [ Perangkat Anak (Redmi Note 9 Pro / Android 12) ]
   +-------------------------------------------------------------------------------+
   | App: com.dwisnulabs.agent (v1.7.0)                                            |
   |                                                                               |
   |  +-------------------------------------------------------------------------+  |
   |  | DwisnuLabsLockService (Foreground Daemon - PID Terlindungi)              |  |
   |  |   * Telegram Polling Client (OkHttp)                                    |  |
   |  |   * State Controller (AppState)                                         |  |
   |  |   * Timer & Auto-Restoration Scheduler                                  |  |
   |  +-------------------------------------------------------------------------+  |
   |          |                                            |                       |
   |          v                                            v                       |
   |  +-------------------------------+    +------------------------------------+  |
   |  | DwisnuLabsKillSwitchService   |    | DwisnuLabsAccessibilityService     |  |
   |  | (Local VPN Firewall Engine)   |    | (Window & Foreground App Watcher)  |  |
   |  |                               |    |                                    |  |
   |  |  * Virtual TUN Interface      |    |  * Intercept Blacklisted Apps      |  |
   |  |  * Blackhole Packet Sink      |    |  * Auto-Close / Redirect           |  |
   |  |  * Split-Tunnel (Bot Whitelist|    +------------------------------------+  |
   |  +-------------------------------+                    |                       |
   |          |                                            v                       |
   |          |                            +------------------------------------+  |
   |          |                            | LockOverlayManager & ScareActivity |  |
   |          |                            | (Visual Camouflage Engine)         |  |
   |          v                            +------------------------------------+  |
   |  +-------------------------------+                                            |
   |  | Android Linux Kernel Network  |                                            |
   |  | (Default Wi-Fi & Cellular)    |                                            |
   |  +-------------------------------+                                            |
   +-------------------------------------------------------------------------------+
```

---

## 2. Struktur Modul & Dekomposisi Komponen

Sistem terbagi ke dalam 7 komponen inti yang saling terisolasi namun terkoordinasi secara asinkron:

| No | Nama Komponen | Tipe Android | Peran & Tanggung Jawab Utama |
| :--- | :--- | :--- | :--- |
| 1 | **DwisnuLabsLockService** | Foreground Service | *Orchestrator Utama*: Mempertahankan proses di memori, menjalankan long-polling Telegram, mengeksekusi perintah kontrol, dan mengelola penjadwalan timer. |
| 2 | **TelegramBotClient** | Engine / Singleton | *Komunikasi Dua Arah*: Menangani pemanggilan Telegram Bot API via HTTP REST, mengurai pesan teks dan interaksi inline keyboard, serta mengirim laporan status. |
| 3 | **DwisnuLabsKillSwitchService** | VpnService | *Firewall Siluman*: Membuat interface jaringan virtual (TUN) lokal untuk menyedot dan membuang seluruh paket data anak (IPv4, IPv6, DNS), dengan mengecualikan paket bot. |
| 4 | **VpnPrepareActivity** | Activity (Translucent) | *Perantara Otorisasi*: Activity transparan satu kali pakai untuk meminta persetujuan VPN Android tanpa menampilkan antarmuka aplikasi. |
| 5 | **DwisnuLabsAccessibilityService** | Accessibility Service | *Pengawas Aktivitas Layar*: Memantau aplikasi yang sedang aktif di layar depan (*foreground package*) dan memblokir aplikasi terlarang (*blacklist*). |
| 6 | **LockOverlayManager & ScareActivity** | Overlay / Fullscreen Activity | *Penghalang Visual*: Menampilkan layar gertakan bertema teknis (misal: memori korup, panas ekstrem, virus) ketika mode kunci total diaktifkan. |
| 7 | **BootReceiver** | BroadcastReceiver | *Penjaga Kelangsungan Hidup*: Membangkitkan kembali seluruh service saat perangkat anak selesai melakukan booting (*cold restart*). |

---

## 3. Spesifikasi Arsitektur Firewall Jaringan (Kill-Switch Engine)

### 3.1 Mengapa VpnService dan Bukan Pemutusan Wi-Fi Fisik?
Pada Android versi modern (Android 10 ke atas / API 29+), pembatasan ketat sistem operasi melarang aplikasi pihak ketiga untuk mengubah status radio Wi-Fi secara terprogram. Lebih penting lagi, mematikan Wi-Fi secara fisik pada perangkat yang tidak memiliki kartu SIM seluler aktif akan menyebabkan perangkat **terputus 100% dari jaringan internet**, sehingga bot tidak akan pernah bisa menerima perintah "Nyalakan Wi-Fi" dari orang tua.

Solusi yang diterapkan adalah **Local Virtual Network Loop (Kill-Switch Firewall)** berbasis `VpnService`:
1. Sistem tidak menghubungkan perangkat ke server VPN remote di internet (tanpa latency dan tanpa biaya server).
2. Sistem membangun adapter virtual lokal (*tun0*) yang bertindak sebagai lubang hitam (*blackhole*).
3. Seluruh aplikasi anak dialihkan ke interface *tun0*, sedangkan paket data milik aplikasi `com.dwisnulabs.agent` dialihkan langsung ke jaringan fisik asli (*split-tunneling*).

### 3.2 Topologi Routing & DNS Sinkhole
Konfigurasi jaringan virtual yang diterapkan pada adapter *tun0*:

| Parameter Jaringan | Alamat Virtual / Mask | Deskripsi Teknis |
| :--- | :--- | :--- |
| **IPv4 Address** | `10.120.0.1 / 24` | Alamat subnet internal tertutup. |
| **IPv4 Route** | `0.0.0.0 / 0` | Mengarahkan seluruh rentang alamat IP dunia ke adapter ini. |
| **IPv4 DNS Server** | `10.120.0.1` | Mengarahkan permintaan nama domain (DNS) ke IP lokal yang tidak merespons. |
| **IPv6 Address** | `fd00:1::1 / 64` | Subnet lokal unik IPv6 untuk mencegah kebocoran pada jaringan seluler 4G/5G. |
| **IPv6 Route** | `:: / 0` | Mengarahkan seluruh trafik IPv6 ke adapter virtual. |
| **IPv6 DNS Server** | `fd00:1::1` | Mencegah kebocoran kueri DNS IPv6 ke server DNS resolver publik. |
| **MTU (Maximum Transmission Unit)** | `1500` | Ukuran standar paket untuk mencegah fragmentasi berlebih. |
| **Exclusion List (Split-Tunnel)** | `com.dwisnulabs.agent` | Satu-satunya paket yang diizinkan memotong (*bypass*) VPN menuju Wi-Fi/LTE fisik. |

### 3.3 Siklus Pemrosesan Paket di Dalam Blackhole
1. Aplikasi YouTube atau Game di perangkat anak memulai koneksi TCP SYN atau kueri DNS UDP.
2. Kernel Android mencocokkan tabel perutean (*routing table*) dan mendapati bahwa UID aplikasi anak terikat pada interface `tun0`.
3. Paket diteruskan ke deskriptor file perangkat virtual (*virtual file descriptor*).
4. Coroutine IO pembaca di dalam service membaca paket dari aliran byte (*stream*) dan langsung mengabaikannya tanpa mengirimkan balasan TCP SYN-ACK atau respon DNS.
5. Sisi aplikasi anak mengalami *socket timeout*, menghentikan pemutaran video, dan menampilkan ikon pemuatan berputar (*spinning buffer*).
6. Di saat bersamaan, thread OkHttp milik `TelegramBotClient` berjalan dengan UID `com.dwisnulabs.agent` yang dikecualikan, sehingga tetap bebas mengakses internet untuk menerima instruksi orang tua.

---

## 4. Alur Kerja Sistem (System Workflows)

### 4.1 Alur Booting dan Polling Telegram

```
[ Boot Selesai (BOOT_COMPLETED) ]
             |
             v
   [ BootReceiver Aktif ]
             |
             v
 [ Start DwisnuLabsLockService ]
             |
             +---> Buat Channel Notifikasi Kamuflase (System Sync Service)
             |
             +---> Panggil startForeground(NOTIFICATION_ID)
             |
             +---> Inisialisasi AppState & SharedPreferences
             |
             v
 [ Mulai Telegram Long-Polling Loop ]
             |
             +---> Kirim Pesan Sapaan: "DwisnuLabs Core Service Aktif!"
             |
             v
 [ HTTP GET /getUpdates (Timeout 20 detik) ] <---+
             |                                    |
             |  (Menunggu Perintah dari Ayah)     | Loop
             v                                    | Berjalan
 [ Terima Pesan / Klik Tombol Inline ]            | Terus
             |                                    | Menerus
             +---> Validasi: ChatID == ParentChatID?
             |         |
             |         +---> Tidak: Abaikan (Keamanan Terjamin)
             |         |
             |         +---> Ya: Eksekusi Perintah
             |                   |
             +-------------------+
```

### 4.2 Alur Pemutusan & Pemulihan Internet

```
[ Ayah Klik Tombol "📶 Putus Internet (Buffer)" di Telegram ]
                        |
                        v
         [ Telegram Client Terima Callback CMD_INTERNET_OFF ]
                        |
                        +---> Simpan State: AppState.isInternetBlocked = true
                        |
                        +---> Panggil DwisnuLabsKillSwitchService.start(context)
                        |           |
                        |           v
                        |   [ Panggil startForeground() Seketika ]
                        |           |
                        |           v
                        |   [ Inisialisasi VPN Tunnel (tun0) ]
                        |           |
                        |           v
                        |   [ Alihkan Seluruh UID Selain com.dwisnulabs.agent ke tun0 ]
                        |
                        +---> Balas Pesan ke Telegram:
                                "📶 Internet Anak Berhasil Diputus!"
                                (Keyboard Otomatis Berubah ke: [ 📶 Sambung Internet ])
                        |
     =================== WAKTU BERJALAN ===================
     (Anak mencoba buka YouTube -> Buffering tanpa henti)
     (HP bot tetap menerima chat Ayah tanpa gangguan)
     ======================================================
                        |
[ Ayah Klik Tombol "📶 Sambung Internet" di Telegram ]
                        |
                        v
         [ Telegram Client Terima Callback CMD_INTERNET_ON ]
                        |
                        +---> Simpan State: AppState.isInternetBlocked = false
                        |
                        +---> Panggil DwisnuLabsKillSwitchService.stop(context)
                        |           |
                        |           v
                        |   [ Tutup Deskriptor tun0 ]
                        |           |
                        |           v
                        |   [ Hentikan Pembacaan Byte & Lepas Foreground ]
                        |           |
                        |           v
                        |   [ Kernel Pulihkan Default Network ke Wi-Fi / LTE ]
                        |
                        +---> Balas Pesan ke Telegram:
                                "📶 Internet Anak Dihubungkan Kembali!"
                                (Keyboard Otomatis Berubah ke: [ 📶 Putus Internet ])
```

---

## 5. Matriks Perintah & Antarmuka Remote Telegram

Sistem menyediakan antarmuka ganda: tombol visual (*Inline Keyboard*) dan perintah teks (*Slash Commands*).

### 5.1 Matriks Perintah Teks

| Perintah Teks | Parameter | Fungsi Utama | Respon Sistem |
| :--- | :--- | :--- | :--- |
| `/menu` | - | Membuka dashboard kendali utama lengkap dengan tombol inline. | Menampilkan 5 baris tombol kendali. |
| `/lock` | - | Mengunci layar seketika dengan tema gertakan aktif. | Membuka jendela penutup layar penuh. |
| `/unlock` | Opsional (`15m`, `30m`) | Membuka kunci layar (permanen atau berbatas waktu). | Menutup jendela layar dan menjadwalkan kunci ulang. |
| `/putus` atau `/internet off` | Opsional (`15m`, `30m`) | Mengaktifkan kill-switch pemutus internet. | Menyalakan adapter VPN blackhole. |
| `/sambung` atau `/internet on` | - | Memulihkan koneksi internet normal seketika. | Mematikan adapter VPN blackhole. |
| `/status` | - | Mengambil telemetri daya baterai, status internet, dan gembok. | Mengirim laporan ringkas status HP. |
| `/apps` | - | Membuka menu kelola aplikasi terlarang (blacklist). | Menampilkan daftar aplikasi populer & status blokirnya. |
| `/theme` | - | Memilih gaya visual gertakan saat layar dikunci. | Menampilkan pilihan tema (Error, Panas, Virus, dsb). |

### 5.2 Antarmuka Tombol Interaktif (Stateful Keyboard)
Tombol interaktif dirancang dinamis (*stateful*). Label tombol baris ke-3 akan berubah secara otomatis tergantung pada status jaringan yang sedang aktif:
- Jika internet anak sedang **Tersambung**, tombol menampilkan: `[ 📶 Putus Internet (Buffer) ]`
- Jika internet anak sedang **Terputus**, tombol menampilkan: `[ 📶 Sambung Internet ]`

---

## 6. Analisis Trade-off Konsumsi Baterai

Pengoperasian aplikasi parental berbasis agen tanpa server perantara (*serverless direct-polling*) memiliki beberapa konsekuensi terhadap konsumsi daya perangkat keras.

### 6.1 Faktor-faktor Penyebab Konsumsi Daya

```
+-----------------------------------------------------------------------------------+
|                     VEKTOR KONSUMSI ENERGI PERANGKAT                              |
+-----------------------------------------------------------------------------------+

 1. Radio Frekuensi / Modem (Wi-Fi & Seluler)
    -----------------------------------------
    Telegram Bot API menggunakan teknik Long-Polling (HTTP GET berdurasi 20 detik).
    * Modem seluler/Wi-Fi dipertahankan dalam status daya menengah/tinggi (DCH/FACH).
    * Radio tidak dapat turun sepenuhnya ke status hemat daya (IDLE sleep).

 2. Siklus Bangun CPU (WakeLock & Doze Mode)
    ----------------------------------------
    Foreground Service berjalan dengan notifikasi prioritas rendah.
    * CPU sesekali dibangunkan untuk memproses paket balasan polling setiap 20 detik.
    * Android Doze Mode (Deep Sleep) tertahan parsial karena ada aktivitas jaringan aktif.

 3. Interupsi I/O pada Mode Pemutusan Internet
    ------------------------------------------
    Saat mode "Putus Internet" aktif, aplikasi anak yang agresif (YouTube/Game) akan
    terus melakukan percobaan koneksi ulang (TCP SYN Retry) berulang-ulang.
    * Setiap paket masuk ke antrian buffer tun0 dan dibaca oleh loop IO.
    * Beban CPU berkorelasi langsung dengan seberapa agresif aplikasi anak mencoba koneksi.

 4. Accessibility Service Event Dispatching
    ---------------------------------------
    Mendengarkan event pergantian jendela (TYPE_WINDOW_STATE_CHANGED).
    * Konsumsi sangat minimal (<0.5%) karena hanya aktif saat ada perpindahan aplikasi.
```

### 6.2 Estimasi Kuantitatif Konsumsi Baterai

| Kondisi Operasional | Estimasi Beban Baterai per Hari (24 Jam) | Dampak Relatif |
| :--- | :--- | :--- |
| **Idle Standby (Internet Normal)** | **~3% s/d 5%** kapasitas baterai total | Sangat aman untuk pemakaian harian. |
| **Mode Putus Aktif (Layar Mati / Tanpa Aplikasi)** | **~4% s/d 6%** kapasitas baterai total | Ringan. |
| **Mode Putus Aktif (Anak Terus Membuka YouTube)** | **~7% s/d 10%** kapasitas baterai total | Sedang (disebabkan oleh frekuensi retry SYN aplikasi anak). |

---

## 7. Rencana Pengembangan & Peningkatan Masa Depan (Roadmap)

Untuk mencapai efisiensi daya maksimal dan kendali yang semakin fleksibel, berikut adalah roadmap arsitektur untuk iterasi berikutnya:

### 7.1 Migrasi ke Arsitektur Push Notification (FCM / Push Relay)
- **Tantangan Saat Ini**: Long-polling mempertahankan koneksi TCP terus-menerus yang menguras daya modem.
- **Solusi Arsitektur Masa Depan**:
  - Membangun micro-service relay ringan (misal di Cloudflare Workers atau server pribadi).
  - Server relay menerima Webhook instan dari Telegram saat Ayah menekan tombol.
  - Relay mengirimkan *High-Priority Silent Push* via **Firebase Cloud Messaging (FCM)** ke perangkat anak.
  - Aplikasi anak **100% tidur lelap (Deep Doze)** dan hanya terbangun selama 2 detik ketika ada instruksi masuk.
  - **Dampak**: Menghilangkan 80% konsumsi baterai saat kondisi siaga (*standby*).

### 7.2 Adaptive Polling (Polling Cerdas Berbasis Layar)
- Jika tetap ingin mempertahankan arsitektur tanpa server pihak ketiga (*zero-backend*):
  - Saat layar HP anak mati (*screen turned off*), frekuensi polling diturunkan secara bertahap (misal: polling setiap 60 detik atau 120 detik).
  - Saat layar anak menyala atau sedang digunakan, polling ditingkatkan kembali ke real-time (timeout 15-20 detik).
  - Menghindari pemborosan baterai di malam hari saat anak sedang tidur.

### 7.3 Active TCP Reset (Sintesis Paket TCP RST)
- **Tantangan Saat Ini**: Paket data dibuang secara diam-diam (*silent drop*). Aplikasi anak akan terus mengulang pengiriman paket (TCP retransmission backoff) selama puluhan detik sebelum menyerah.
- **Solusi Arsitektur Masa Depan**:
  - Di dalam loop pembacaan `tun0`, analisis header IP/TCP dari paket yang masuk.
  - Suntikkan kembali paket balasan sintetis berupa **TCP RST (Reset)** atau **ICMP Port Unreachable**.
  - Aplikasi anak akan langsung menerima sinyal bahwa koneksi ditolak seketika (*fail immediately*), sehingga berhenti mengirim ulang paket dan langsung menampilkan status kendala jaringan. Hal ini menghemat CPU dan mempercepat efek buffering.

### 7.4 Penjadwalan Otomatis di Sisi Perangkat (On-Device Local Rules)
- Memindahkan logika timer dari memori RAM ke persistensi **AlarmManager / WorkManager** berbasis jadwal jam:
  - Aturan jam tidur: Otomatis memutus internet setiap jam 21:00 dan memulihkan kembali jam 06:00 pagi tanpa perlu instruksi manual dari Telegram.
  - Aturan tetap berjalan meskipun sinyal seluler/Wi-Fi di lokasi anak terputus total.

### 7.5 Whitelist Panggilan & Pesan Darurat (Selective Bypass)
- Menambahkan konfigurasi daftar putih paket aplikasi yang tetap diizinkan berselancar saat mode pemutusan aktif:
  - Mengizinkan WhatsApp atau Telegram keluarga tetap terhubung agar anak tetap dapat dihubungi atau membagikan lokasi darurat (*Live Location*), sementara YouTube, Game, dan Browser tetap terputus total.

### 7.6 Perlindungan Proses Tingkat Lanjut (Dual-Process Watchdog)
- Memasangkan proses sekunder ringan (*watchdog*) yang saling memantau dengan core service. Jika salah satu service dibunuh oleh pembersih memori OEM (seperti MIUI Security Cleaner), proses pasangan akan langsung membangkitkannya kembali dalam hitungan milidetik.
