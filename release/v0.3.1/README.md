# NusaMesh v0.3.1 (17)

Android release dengan perbaikan pemilihan tujuan GPS dan interaksi routing peta.

- [APK signed](NusaMesh-v0.3.1-release.apk): instalasi langsung di Android.
- [Android App Bundle](NusaMesh-v0.3.1-release.aab): artefak untuk distribusi melalui store.
- [Manifest Android](android-manifest.json): versi, status signing, dan SHA-256 APK/AAB.
- [Catatan verifikasi](verification.json): hasil tes, pemeriksaan isi APK, dan kecocokan sertifikat dengan v0.3.0.
- [Checksum](SHA256SUMS.txt): SHA-256 artefak dan berkas verifikasi.

## Perbaikan

- Popup unit dan waypoint tetap terbuka ketika data GPS diperbarui.
- Klik marker tidak ikut memilih titik kosong di peta.
- Pilihan titik dipertahankan ketika membuka menu Rute.
- Tombol arahkan ke titik baru tetap tersedia ketika rute lama ditampilkan.
- Hasil pencarian lokasi memiliki tombol arahkan ke titik peta.
- Sisa konflik merge chat/lampiran dan pemanggilan tes transport diperbaiki agar aplikasi dapat dibangun.

## Verifikasi dan batas pengujian

Tes release Android serta pemeriksaan signature dan isi APK direkam di verification.json. Uji browser sebelumnya mencakup 10 skenario interaksi peta dan perhitungan rute offline. Belum dilakukan pengujian GPS/BLE/LoRa di HP fisik untuk build ini.

Routing membutuhkan titik awal dan tujuan dalam satu wilayah navigasi bawaan. Build ini tidak menambah cakupan wilayah routing. Tidak ada IPA iOS dalam release ini.

Sertifikat release dipertahankan dari v0.3.0 sehingga APK dapat memperbarui instalasi v0.3.0 yang ditandatangani sertifikat tersebut. Keystore dan password tidak disertakan.
