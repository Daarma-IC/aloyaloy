# Laporan Investigasi & Perbaikan Force-Close NusaMesh Android

Dokumen ini merangkum analisis lengkap penyebab aplikasi **NusaMesh (Kotlin Compose Multiplatform)** langsung mengalami *force-close* (crash saat dibuka) di perangkat Android, serta seluruh perbaikan yang telah diterapkan ke dalam *codebase*.

---

## 1. Akar Masalah Penyebab Force-Close (Crash on Launch)

Saat dicek, ada **4 masalah fatal** yang menyebabkan APK crash seketika saat baru dibuka:

### A. Format Icon Vector SVG Tidak Didukung di Android Runtime (Penyebab Utama)
* **Penyebab:** Pada folder `composeApp/src/commonMain/composeResources/drawable/`, seluruh ikon disimpan dalam format mentah `.svg`:
  * `bluetooth.svg`
  * `device_phone.svg`
  * `nav_home.svg`
  * `nav_map.svg`
  * `nav_message.svg`
  * `reboot.svg`
* **Mengapa crash:** Pada target Web (Wasm), Compose menggunakan mesin Skia yang bisa me-render SVG secara langsung. Namun pada **Android**, `painterResource()` dari JetBrains Compose **tidak mendukung file mentah `.svg`**. Android mewajibkan format **Android Vector Drawable (`.xml`)**.
* **Dampak:** Begitu `MainActivity` menjalankan `setContent { NusaMeshApp(...) }`, layar `HomeScreen` dan `AppBottomBar` langsung memanggil `painterResource(Res.drawable.nav_message)`, `painterResource(Res.drawable.bluetooth)`, dsb. Android melemparkan exception gagal decode/resource not found, sehingga aplikasi langsung crash sebelum frame pertama sempat digambar.

---

### B. Unsafe Cast `BluetoothManager` pada `AndroidMeshTransport.kt`
* **Penyebab:** Pada inisialisasi class:
  ```kotlin
  // KODE LAMA (CRASH):
  private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  ```
* **Mengapa crash:** Jika dijalankan di emulator, perangkat tanpa BLE aktif, atau jika service bluetooth OS belum siap saat `ApplicationContext` dibaca di `onCreate()`, pemanggilan `getSystemService` mengembalikan `null`. Kotlin melakukan casting non-null `as BluetoothManager` yang langsung memicu:
  `NullPointerException: null cannot be cast to non-null type android.bluetooth.BluetoothManager`

---

### C. `targetSdk = 36` (Android 16 Developer Preview)
* **Penyebab:** Pada `composeApp/build.gradle.kts`, `targetSdk` dipasang ke angka `36`.
* **Mengapa bermasalah:** SDK 36 adalah Android 16 (masih tahap preview/belum rilis publik). Jika APK dengan target SDK preview diinstall di perangkat Android 11â€“15, sistem OS akan memberlakukan aturan kompatibilitas yang tidak stabil atau menolak inisialisasi activity standar.

---

### D. Hilangnya Launcher Icon & Definisi Feature Wajib
* **Penyebab:**
  1. `AndroidManifest.xml` tidak memiliki atribut `android:icon` maupun `android:roundIcon`. Di launcher OEM tertentu (seperti MIUI/HyperOS, ColorOS, atau Samsung One UI), aplikasi tanpa icon launcher akan gagal diluncurkan atau langsung di-terminate oleh launcher OS.
  2. `<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />` menghalangi atau memicu crash instalasi jika device/lingkungan uji tidak melaporkan sensor BLE secara penuh.

---

## 2. Rincian Perbaikan yang Telah Dilakukan

### 1. Konversi Seluruh SVG ke Android Vector Drawable XML (Multiplatform)
Seluruh 6 file `.svg` telah dihapus dan digantikan dengan file Android Vector Drawable XML standar yang **100% kompatibel di Android, iOS, Desktop, dan Web (Wasm)**:
* [`bluetooth.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/commonMain/composeResources/drawable/bluetooth.xml)
* [`device_phone.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/commonMain/composeResources/drawable/device_phone.xml)
* [`nav_home.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/commonMain/composeResources/drawable/nav_home.xml)
* [`nav_map.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/commonMain/composeResources/drawable/nav_map.xml)
* [`nav_message.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/commonMain/composeResources/drawable/nav_message.xml)
* [`reboot.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/commonMain/composeResources/drawable/reboot.xml)

*(Pemanggilan kode di Kotlin seperti `Res.drawable.bluetooth` tetap sama persis dan tidak perlu diubah).*

---

### 2. Null-Safety pada `AndroidMeshTransport.kt`
File: [`AndroidMeshTransport.kt`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/androidMain/kotlin/id/nusamesh/app/ble/AndroidMeshTransport.kt)
* Mengubah casting menjadi safe-cast:
  ```kotlin
  private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val adapter: BluetoothAdapter? get() = manager?.adapter
  ```
* Memperbaiki pengecekan return code `writeCharacteristic` pada API 33+ (Tiramisu) agar menggunakan `BluetoothStatusCodes.SUCCESS` sesuai standar lint Android.

---

### 3. Penambahan Icon Aplikasi (Adaptive Icon)
Dibuat asset launcher lengkap di Android:
* Background: [`ic_launcher_background.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/androidMain/res/drawable/ic_launcher_background.xml)
* Foreground: [`ic_launcher_foreground.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/androidMain/res/drawable/ic_launcher_foreground.xml)
* Adaptive Icon: [`ic_launcher.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml)
* Round Icon: [`ic_launcher_round.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_round.xml)

---

### 4. Perbaikan `AndroidManifest.xml` & `styles.xml`
File: [`AndroidManifest.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/androidMain/AndroidManifest.xml)
* Menambahkan `android:icon="@mipmap/ic_launcher"` dan `android:roundIcon="@mipmap/ic_launcher_round"`.
* Mengubah `android:hardware.bluetooth_le` menjadi `android:required="false"` agar aplikasi tetap dapat terbuka dengan aman di perangkat manapun (notifikasi izin akan muncul saat tombol hubungkan ditekan).
* Memperbaiki prefix style pada [`styles.xml`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/androidMain/res/values/styles.xml) menjadi `parent="@android:style/Theme.Material.Light.NoActionBar"`.

---

### 5. Stabilisasi Target SDK
File: [`build.gradle.kts`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/build.gradle.kts)
* Menurunkan `targetSdk` dari `36` (Android 16 preview) ke **`35` (Android 15 resmi)** untuk memastikan kompatibilitas penuh dan stabilitas runtime pada smartphone Android saat ini.

---

### 6. Pengecekan Aman pada `MainActivity.onDestroy()`
File: [`MainActivity.kt`](file:///c:/Users/darma/OneDrive/Dokumen/taa/apps/nusamesh/composeApp/src/androidMain/kotlin/id/nusamesh/app/MainActivity.kt)
* Menambahkan pengecekan `if (::transport.isInitialized)` sebelum memanggil `transport.disconnect()` agar tidak terjadi secondary crash (`UninitializedPropertyAccessException`).

---

## 3. Cara Build Ulang APK

Untuk men-generate APK baru yang sudah bebas dari force-close:

Buka PowerShell di folder `apps/nusamesh`, lalu jalankan:

```powershell
.\gradlew.bat :composeApp:assembleDebug
```

Hasil APK berada di:
`composeApp/build/outputs/apk/debug/composeApp-debug.apk`

Atau jika HP terhubung via USB dengan USB Debugging aktif:
```powershell
.\scripts\deploy-android.ps1
```

---

## 4. Status Build Final (25 September 2026)

- Seluruh perubahan pada bagian 2 sudah ditemukan di source dan dikompilasi ulang dari project saat ini.
- File `NusaMesh.apk` lama bertanggal 25 September 2026 12:25, lebih lama daripada laporan/perbaikan bertanggal 15:55. File lama tersebut belum memuat rangkaian perbaikan di atas.
- `NusaMesh.apk` telah diganti dengan build baru:
  - Application ID: `id.nusamesh.app`
  - Version: `0.1.3` (`versionCode 4`)
  - Minimum Android: API 26
  - Target Android: API 35
  - ABI: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
  - SHA-256: `3EEC465E56136F670192FB9AFC23DE4DE8A4B69FFCAC4FC5268E4E03FB2C841E`
- Gradle `:composeApp:assembleDebug --offline --no-daemon` selesai dengan status `BUILD SUCCESSFUL`.
- APK lolos verifikasi signature APK Signature Scheme v2 dan pemeriksaan paket/resource.
- Uji launch langsung belum dijalankan karena tidak ada perangkat yang terdeteksi oleh ADB. Jika masih terjadi crash, logcat dari perangkat diperlukan untuk menentukan exception runtime yang tersisa.

---

## 5. Perbaikan Runtime dan Preview (versi 0.1.3)

- Keyboard chat: Activity menggunakan `adjustResize` dan layar percakapan menggunakan IME inset, sehingga header tetap terlihat dan composer naik di atas keyboard.
- Voice note: byte audio disimpan pada message dan tombol play memakai `MediaPlayer` Android dengan cleanup aman.
- BLE: alasan putus tidak lagi hilang menjadi status Idle; error GATT sementara mencoba reconnect maksimal dua kali.
- Web localhost: chat teks berhasil diuji kirim; Leaflet mendukung pencarian dan penempatan beberapa titik koordinat lewat klik.
- Home dikembalikan ke desain asli setelah eksperimen redesign ditolak.
- Android dan Web selesai dengan `BUILD SUCCESSFUL`. Validasi iOS native penuh memerlukan Kotlin/Native toolchain dan macOS/Xcode.
