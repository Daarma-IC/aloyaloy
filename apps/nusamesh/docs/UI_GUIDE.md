# Cara membaca UI NusaMesh

Tiga berkas SVG di `../../asetku/` adalah acuan visual: Dashboard Perangkat, Halaman Chat, dan Halaman Peta. SVG itu gambar desain, sedangkan layar aplikasi dibangun dari komponen Compose supaya ukuran dan interaksinya bisa menyesuaikan HP.

## Peta layar ke kode

| Bagian yang terlihat | Komponen Kotlin | Berkas |
|---|---|---|
| Kerangka layar dan pilihan tab aktif | `NusaMeshApp` | `composeApp/src/commonMain/kotlin/id/nusamesh/app/NusaMeshApp.kt` |
| Footer navigasi Message / Home / Map | `AppBottomBar`, `NavItem` | `composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/Navigation.kt` |
| Header Home: Welcome Back dan avatar | `HomeHeader` | `composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/HomeScreen.kt` |
| Konten Home: kualitas modul dan tombol BLE | `ModuleQualityCard`, `Metric` | `composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/HomeScreen.kt` |
| Daftar perangkat yang benar-benar terdeteksi | `DevicesHeader`, `DeviceCard` | `composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/HomeScreen.kt` |
| Header Chat dan tombol tambah pesan | `ChatHeader` | `composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/ChatScreen.kt` |
| Filter dan daftar pesan Chat | `ChatFilters`, `ChatRow` | `composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/ChatScreen.kt` |
| Peta utama | `LeafletMap` | `composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/LeafletMap.kt` dan implementasi platform di `androidMain` / `iosMain` |
| Panel bawah Map dan handle geser | `MapScreen` | `composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/MapScreen.kt` |
| Warna dan tema | `NusaTheme` | `composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/NusaTheme.kt` |
| Ikon dari path SVG desain | `nav_*.svg`, `bluetooth.svg`, `device_phone.svg` | `composeApp/src/commonMain/composeResources/drawable/` |

`Header` berarti bagian atas layar, `Content` isi utama, dan `Footer` navigasi yang tetap di bawah. `NusaMeshApp` menaruh footer dan memberi ruang aman sistem ke masing-masing layar. `HomeScreen` memakai `LazyColumn`, sehingga kontennya bisa di-scroll di HP pendek. Panel Map memakai fraksi tinggi layar; tarik garis kecil di atas panel untuk mengubah tinggi panel, sementara peta Leaflet tetap bisa digeser di area yang tidak tertutup panel.

## Aliran data

`AndroidMeshTransport`/`IosMeshTransport` menerima data BLE, `MeshRepository` mengubahnya menjadi aliran data, `AppController` membentuk `AppUiState`, lalu `NusaMeshApp` menyerahkan state itu ke layar. Karena itu daftar perangkat dan chat tidak diisi contoh palsu. Map memakai tile OpenStreetMap melalui Leaflet, tetapi marker unit baru bisa tampil setelah firmware menyediakan koordinat yang dipetakan ke state aplikasi.

## Kendala yang masih perlu diuji

Build APK hanya memastikan kode berhasil dikompilasi. Force close saat aplikasi dibuka perlu stack trace dari Android runtime. Pengujian bisa memakai Android Emulator di PC tanpa Developer mode pada HP. SDK lokal saat ini memiliki build-tools dan adb, tetapi belum memiliki paket emulator maupun system image. APK `NusaMesh-candidate.apk` berisi perbaikan ukuran SVG dan handle Map; status runtime-nya masih belum terverifikasi.
