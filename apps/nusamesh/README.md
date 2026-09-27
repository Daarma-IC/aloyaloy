# NusaMesh Mobile

Aplikasi Kotlin Multiplatform untuk Android dan iOS. UI Compose dibagi bersama,
sedangkan akses BLE memakai API native masing-masing platform.

## Struktur singkat

```text
apps/nusamesh/
├── composeApp/
│   └── src/
│       ├── commonMain/   # UI, domain, protocol, repository, controller
│       ├── androidMain/  # Android BLE GATT + Activity
│       ├── iosMain/      # iOS CoreBluetooth + UIViewController
│       └── commonTest/   # tes format paket firmware
├── iosApp/               # entry point Swift dan project.yml XcodeGen
├── docs/                 # arsitektur dan kontrak hardware–software
├── scripts/              # setup serta deploy tanpa Android Studio
└── gradle/                # version catalog dan Gradle Wrapper
```

Mulai membaca dari `NusaMeshApp.kt`, lanjut ke `AppController.kt`,
`MeshRepository.kt`, lalu `MeshTransport.kt`. Detail platform sengaja berada
terakhir supaya alurnya mudah diikuti.

Untuk mencocokkan bagian mockup dengan komponen layar (header, isi, footer),
lihat [`docs/UI_GUIDE.md`](docs/UI_GUIDE.md).

## Review UI lewat browser

Jalankan `powershell -ExecutionPolicy Bypass -File .\scripts\preview-web.ps1` dari
folder ini, lalu buka alamat lokal yang dicetak Gradle (biasanya
`http://localhost:8080/`). Target `wasmJs` memakai composable Home, Chat, Map,
footer, dan aset yang sama dengan aplikasi HP. Lebar preview dibatasi 430 px
di layar desktop; di layar HP mengikuti viewport. Peta Leaflet tetap interaktif.
BLE tidak tersedia di preview browser, jadi daftar perangkat tidak berisi data
buatan. Preview ini membantu review visual dan gesture, tetapi force close Android
tetap harus dilacak lewat runtime Android.

## Deploy Android tanpa Android Studio

Persiapan satu kali di PowerShell:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\setup-android-cli.ps1
```

Aktifkan **Developer options > USB debugging** di ponsel, sambungkan kabel USB,
setujui dialog fingerprint, lalu:

```powershell
.\scripts\deploy-android.ps1
```

APK debug juga tersedia di
`composeApp/build/outputs/apk/debug/composeApp-debug.apk`.

## Deploy iOS

Build iOS wajib dijalankan pada macOS dengan Xcode. Android Studio tidak
dibutuhkan. Setelah memasang XcodeGen (`brew install xcodegen`):

```bash
cd iosApp
xcodegen generate
open NusaMesh.xcodeproj
```

Pilih Apple Development Team dan iPhone tujuan di Xcode, lalu Run. Signing
Apple pertama kali memang perlu dilakukan melalui Xcode.

## Status keamanan

Transport GATT dan format header v2 sudah mengikuti firmware. Payload chat pada
prototype ini masih UTF-8 biasa agar integrasi hardware dapat dites. Sebelum
rilis publik, pasang implementasi Noise di batas `MeshRepository` dan kirim
sebagai tipe `0x12` (`NoiseEncrypted`). Firmware tetap meneruskan ciphertext
tanpa membacanya.
