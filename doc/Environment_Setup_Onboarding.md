# Dokumentasi Onboarding & Environment Setup: ChildLockAgent

Dokumen ini mencatat seluruh hasil audit, instalasi environment, konfigurasi otomatis, dan struktur project minimalis Android **ChildLockAgent** tanpa memerlukan instalasi Android Studio IDE.

---

## 1. Ringkasan Eksekutif

* **Nama Project**: ChildLockAgent
* **Namespace / Application ID**: `com.dwisnulabs.agent`
* **Target Android SDK**: API 34 (Android 14)
* **Minimum Android SDK**: API 26 (Android 8.0 Oreo)
* **Kompilator & Bahasa**: Kotlin 1.9.23, Java 17, Android Gradle Plugin (AGP) 8.4.0, Gradle 8.6
* **Prinsip Setup**: 100% *Headless & Command-Line First*. Kompilasi dan instalasi APK dilakukan via **Gradle Wrapper** dan **Android SDK CLI / ADB**.

---

## 2. Inventaris Environment & Tooling yang Terpasang

Semua alat telah diunduh, diekstrak, dan dikonfigurasi ke direktori pengguna lokal tanpa memerlukan hak administrator (UAC):

| Komponen | Versi | Jalur Instalasi di Sistem | Keterangan |
| :--- | :---: | :--- | :--- |
| **JDK (Java)** | 17.0.20.1 | `C:\Users\LENOVO\AppData\Local\Programs\Eclipse Adoptium\jdk-17` | Eclipse Temurin OpenJDK 64-bit |
| **Android SDK Root** | API 34 | `C:\Users\LENOVO\AppData\Local\Android\Sdk` | Direktori root SDK (`ANDROID_HOME`) |
| **Android Platform-Tools** | 37.0.1 | `C:\Users\LENOVO\AppData\Local\Android\Sdk\platform-tools` | Berisi `adb.exe`, `fastboot.exe`, dll. |
| **Command-line Tools** | 12.0 | `C:\Users\LENOVO\AppData\Local\Android\Sdk\cmdline-tools\latest` | Berisi `sdkmanager.bat`, `avdmanager.bat` |
| **SDK Platform** | API 34 | `C:\Users\LENOVO\AppData\Local\Android\Sdk\platforms\android-34` | Library `android.jar` untuk API 34 |
| **SDK Build-Tools** | 34.0.0 | `C:\Users\LENOVO\AppData\Local\Android\Sdk\build-tools\34.0.0` | Berisi `aapt2.exe`, `d8.bat`, `zipalign.exe` |
| **Google Android CLI** | 1.1.16717390 | `C:\Users\LENOVO\AppData\AndroidCLI\android.exe` | CLI resmi Google untuk Android tooling |
| **Gradle Runtime** | 8.6 | `~/.gradle/wrapper/dists/gradle-8.6-bin/` | Dikelola otomatis oleh Gradle Wrapper |

---

## 3. Konfigurasi Environment Variables

Variabel berikut telah tersimpan secara permanen pada **Windows User Environment Registry** (`HKCU\Environment`) dan disiarkan (`WM_SETTINGCHANGE`):

1. **`JAVA_HOME`**
   ```text
   C:\Users\LENOVO\AppData\Local\Programs\Eclipse Adoptium\jdk-17
   ```
2. **`ANDROID_HOME` & `ANDROID_SDK_ROOT`**
   ```text
   C:\Users\LENOVO\AppData\Local\Android\Sdk
   ```
3. **User `Path`** telah ditambahkan direktori:
   * `%JAVA_HOME%\bin`
   * `%ANDROID_HOME%\platform-tools`
   * `%ANDROID_HOME%\cmdline-tools\latest\bin`
   * `%USERPROFILE%\AppData\AndroidCLI`

---

## 4. Struktur Direktori Project

```text
Parental/
├── .gitignore                           # Rule ignore untuk build, cache gradle, dan local.properties
├── gradle.properties                    # AndroidX aktif & JVM heap tuning
├── local.properties                     # Pointer eksplisit sdk.dir untuk Gradle
├── gradlew & gradlew.bat                # Wrapper script dengan auto-fallback JAVA_HOME
├── settings.gradle.kts                  # Definisi rootProject.name dan repositori Google/MavenCentral
├── build.gradle.kts                     # Deklarasi plugin AGP 8.4.0 & Kotlin 1.9.23
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties    # Distribusi Gradle 8.6-bin
├── doc/
│   ├── Analisis.txt                     # Dokumen analisis awal
│   └── Environment_Setup_Onboarding.md  # Dokumen ini
└── app/
    ├── build.gradle.kts                 # Konfigurasi target compileSdk, namespace, dan dependency
    └── src/
        └── main/
            ├── AndroidManifest.xml      # Manifest aplikasi & deklarasi service LockService
            └── java/
                └── com/dwilabs/agent/
                    └── LockService.kt   # Service background agent
```

---

## 5. Konfigurasi Khusus (Resilience & Fail-safe)

Untuk memastikan kelancaran build di berbagai terminal dan sesi yang berbeda:

1. **Auto-Fallback di `gradlew.bat`**:
   Script `gradlew.bat` telah dilengkapi logika pengecekan cadangan. Jika variabel sesi `JAVA_HOME` belum terdefinisi saat terminal baru dibuka, batch script akan otomatis membaca nilai `JAVA_HOME` langsung dari Registry `HKCU\Environment` atau folder lokal `%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-17` sebelum mengeksekusi kompilasi.
2. **Explicit `org.gradle.java.home`**:
   Ditambahkan pada `gradle.properties` untuk mengunci JVM Gradle Daemon ke Temurin 17.
3. **Explicit `sdk.dir` di `local.properties`**:
   Menghindari masalah resolusi SDK jika variabel shell tidak terwariskan dengan sempurna pada subprocess.

---

## 6. Verifikasi Build & Output APK

Pengujian kompilasi lokal telah dieksekusi dengan hasil:
```text
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:packageDebug UP-TO-DATE
> Task :app:assembleDebug UP-TO-DATE

BUILD SUCCESSFUL in 12s
35 actionable tasks: 1 executed, 34 up-to-date
```

* **File APK Terbentuk**:
  `app/build/outputs/apk/debug/app-debug.apk`
* **Ukuran File**: ~2.04 MB (2,035,764 bytes)
* **Integritas**: Siap dideploy dan dipasang di perangkat fisik.

---

## 7. Panduan Operasional Harian (Daily Runbook)

### A. Melakukan Kompilasi APK
Buka terminal di root workspace (`c:\D\Project\Mix\Parental`), lalu jalankan:

```powershell
# Build debug APK
.\gradlew.bat assembleDebug

# Bersihkan build cache jika ada perubahan dependensi besar
.\gradlew.bat clean assembleDebug
```

### B. Menghubungkan HP Target (Xiaomi / Redmi / Android Lain)
1. Aktifkan **Developer Options**:
   * Buka *Settings* -> *About Phone* -> Ketuk *MIUI version / OS version* sebanyak 7 kali hingga muncul notifikasi "You are now a developer!".
2. Masuk ke *Additional Settings* -> *Developer Options*, lalu aktifkan:
   * **USB Debugging** (Aktifkan)
   * **Install via USB** (Wajib aktifkan di Xiaomi/MIUI untuk bypass blokir ADB)
   * **USB Debugging (Security settings)** (Disarankan aktifkan jika butuh simulasi klik/input)
3. Sambungkan kabel data USB ke komputer.
4. Cek apakah HP terdeteksi:
   ```powershell
   adb devices
   ```
   *Jika statusnya `unauthorized`, cek layar HP dan centang "Always allow from this computer", lalu tekan **OK**.*

### C. Instalasi APK ke HP
Pilih salah satu metode instalasi:

```powershell
# Metode 1: Langsung via Gradle (Otomatis build + install)
.\gradlew.bat installDebug

# Metode 2: Manual via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### D. Memantau Log Aplikasi (Real-time Debugging)
Untuk melihat output log dari `LockService` atau error aplikasi:

```powershell
# Filter log khusus tag ChildLockAgent
adb logcat -s ChildLockAgent

# Menjalankan Service secara manual via ADB
adb shell am start-foreground-service -n com.dwilabs.agent/.LockService
```

---

## 8. Catatan Pengembangan Fitur Selanjutnya
Fondasi sistem sudah tuntas. Pengembangan selanjutnya dapat berfokus pada:
1. Implementasi **DevicePolicyManager** (Device Admin API) untuk proteksi uninstall / kunci layar.
2. Implementasi **AccessibilityService** untuk mendeteksi aplikasi foreground yang dibuka anak dan menampilkan lock overlay secara instan.
3. Sinkronisasi status / konfigurasi waktu pemakaian perangkat.
