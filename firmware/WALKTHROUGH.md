# Walkthrough NusaNode dan Codec2 — titik lanjut

Diperbarui 28 Oktober 2026. Mulai dari dokumen ini saat melanjutkan rework. [NEXT_STEPS.md](NEXT_STEPS.md) berisi pekerjaan berikutnya; [walkthrough eksperimen awal](dual-radio-test/WALKTHROUGH.md) disimpan sebagai riwayat hardware/diagnostik.

## Baseline yang dipertahankan

- Satu hub logis dengan dua radio SX1276; sementara memakai dua LilyGo T3 v1.6.1. Target hardware berikutnya satu MCU + dua modul LoRa.
- Quasi duplex pada kanal yang sama. Kedua radio dapat RX/TX, tetapi hanya satu frame lokal dipancarkan pada satu waktu. Setelah frame selesai dan kesempatan RX, paket berikutnya dapat memakai radio lain.
- Pembagian TX saat ini **bergantian per frame**, belum memilih radio berdasarkan beban, RSSI atau kondisi antena. Tidak menjanjikan bandwidth dua kali lipat, bebas interferensi atau tanpa antrean.
- BLE/WiFi, routing, antrean dan identitas mesh dikelola board utama. Anggota kedua adalah coprocessor radio RX/TX, belum NusaNode penuh dengan layanan BLE/WiFi sendiri.
- Entry point tetap `NusaNode/NusaNode.ino`. Pemanggilan library, setup, loop dan callback pada INO utama tidak diubah. Implementasi modular berada di `NusaNode/src/`, header root menjadi penghubung include lama.
- Full duplex/frekuensi silang tetap eksperimen nonaktif. Jangan menggandakan NODE_ID dua node mandiri untuk membentuk satu hub.

## Yang sudah dikerjakan

| Bagian | Perubahan yang tersedia | Panduan |
|---|---|---|
| Struktur firmware | Pisah modul BLE, LoRa, WiFi, routing, protokol dan UI; sinkronisasi varian SF tanpa mengubah INO/identitas yang disimpan | [STRUCTURE.md](STRUCTURE.md) |
| Quasi dua radio | TX bergantian per frame, PAUSE/RESUME terkonfirmasi, satu antrean dan anggaran airtime | [QUASI_TAKEOVER.md](QUASI_TAKEOVER.md) |
| UART anggota | Wire v3, konfigurasi PHY/daya, token TX, status selesai/gagal/tidak diketahui/ditolak, cancel dan recovery | [NusaRxCompanion](NusaRxCompanion/README.md) |
| Antrean radio | Audio di atas gambar/bulk, slot interaktif dicadangkan, batch fragmen masuk semua-atau-tidak | [driver radio](NusaNode/src/lora/nusa_radio.cpp) |
| Deduplikasi dua RX | Salinan frame dari dua penerima disaring sebelum handler hub; identitas/indeks fragmen tetap saat berganti TX | [QUASI_TAKEOVER.md](QUASI_TAKEOVER.md) |
| Codec2 native | Validasi NC2, kompatibilitas 3200 lama, sesi encoder/decoder persisten; tidak membuat ulang codec per chunk | [bridge C](../apps/nusamesh/composeApp/src/nativeInterop/codec2/codec2_bridge.c) |
| Audio Android | Encode sambil merekam, bundle pertama 640 ms lalu hingga 1,44 detik, playback bertahap dan arsip akhir tanpa kirim ulang berkas | [CODEC2_STREAMING.md](CODEC2_STREAMING.md) |
| Protokol suara | NVS1 dengan ID sesi, sequence, offset/jumlah sampel, END/CANCEL; reassembly terbatas, penanganan duplikat/lubang/timeout | [VoiceStream.kt](../apps/nusamesh/composeApp/src/commonMain/kotlin/id/nusamesh/app/media/VoiceStream.kt) |
| Keamanan/kompatibilitas | Enkripsi channel per chunk, kunci sesi dipertahankan, prioritas ikut fragmen/relay; mode voice note lama tetap tersedia | [CODEC2_STREAMING.md](CODEC2_STREAMING.md) |
| Pengamatan | Event capture/encode/chunk/playback/buffering/completion, CSV arsip/checksum dan ACK setelah arsip lengkap | [log dan prosedur uji](CODEC2_STREAMING.md#log-dan-prosedur-pengukuran) |

Codec2 tetap 1200 bps, 320 sampel/40 ms → 6 byte, sekitar 150 byte codec/detik. Mode ini dipilih untuk hemat data LoRa; bukan klaim kualitas suara tertinggi seluruh mode Codec2. Batas rekaman tetap 10 detik. Android memiliki capture/playback bertahap baru; jalur/perangkat iOS belum diverifikasi.

## Konfigurasi dan wiring aktif

```cpp
// firmware/NusaNode/config.h
#define LORA_FULL_DUPLEX      0
#define LORA_QUASI_DUPLEX     1
#define LORA_QUASI_TAKEOVER   1
#define LORA_RX_BACKEND       1
```

| LilyGo utama | LilyGo anggota |
|---|---|
| GPIO13 TX UART2 | GPIO34 RX UART2 |
| GPIO34 RX UART2 | GPIO13 TX UART2 |
| GND | GND |
| USB sendiri | USB sendiri |

Gunakan UART 115200, jangan satukan rail 5V/3V3 USB. GPIO13 tidak dipakai microSD bersamaan. Radio onboard masing-masing board tetap memakai koneksi SPI internal; tidak perlu menyolder GPIO5/27.

Flash utama dari `NusaNode/NusaNode.ino`; anggota dari `NusaRxCompanion/NusaRxCompanion.ino`. **Keduanya wajib wire v3**, jangan campur v1/v2. Set identitas unik per hub pada config utama; anggota tidak mempunyai NODE_ID mesh mandiri. Default frekuensi quasi 921 MHz, SF dapat mengikuti NVS kecuali varian SF_FIXED; periksa PHY aktual saat uji.

Untuk satu radio, QUASI=0 dan FULL=0. Untuk susunan historis TX utama + RX anggota, TAKEOVER=0. Rencana Cosmic masih memerlukan kepastian varian catu/level sinyal; instruksi daya lama bukan wiring aktif untuk dua LilyGo ini.

## Batas yang masih menghambat audio

| Lapisan | Kondisi kode sekarang | Dampak |
|---|---|---|
| Firmware BLE | Token bucket 0,3 paket/detik per HP, burst 3 | Setelah burst habis, sekitar satu token/3,33 detik; paket tanpa token ditolak |
| NodeQueue aplikasi | Pacing 3500 ms, burst 2, bagian airtime 4% | Chunk yang terbentuk tiap 1,44 detik dapat menumpuk |
| Radio | Anggaran airtime bersama dan RX window minimal 500 ms | Token aplikasi/antrean kosong tidak berarti radio langsung boleh TX |
| Dua radio | Satu kanal, satu penjadwal, satu anggaran hub | Kapasitas udara tidak otomatis bertambah |
| Flow control HP–node | Belum ada kontrak ACK admission/credit yang lengkap | BLE accepted belum membuktikan paket masuk antrean radio atau sampai ke penerima |

Token 0,3 tersebut menghitung paket aplikasi yang diajukan ke backbone, bukan setiap frame Codec2 6 byte. Satu paket aplikasi dapat dipecah lagi menjadi beberapa frame RF. Perubahan token radio anggota tidak otomatis menambah jatah HP/hub.

Nilai firmware `DUTY_RATIO=0.05` masih ditandai TESTING pada config. Itu bukan bukti konfigurasi operasional sudah disahkan. Jangan mematikan limiter atau sekadar menaikkan rate untuk mengklaim latensi tercapai. Audit dan contoh airtime tersedia di [VOICE_LATENCY_AUDIT.md](VOICE_LATENCY_AUDIT.md) serta [CODEC2_STREAMING.md](CODEC2_STREAMING.md#airtime-dan-batas-kinerja).

## Bukti pengujian yang tersedia

Hasil sesi implementasi sebelumnya; perubahan dokumentasi ini tidak menjalankan ulang build/test aplikasi atau firmware.

- **9 suite host firmware lulus**, memakai SPI/UART stub: termasuk takeover UART/SPI, giliran TX, prioritas, dedup, token lama/duplikat, status hilang, cancel, CAD sibuk, reboot, atomic admission, fragmentasi dan mobility. Rincian ada di QUASI_TAKEOVER.md.
- Build ESP32 utama quasi takeover: **701562 byte flash / 73148 byte RAM**. Anggota wire v3: **314031 / 23752**. Ini keberhasilan compile, bukan bukti RF.
- **160 unit test Android/Kotlin lulus**, 0 gagal/error/skipped. Integrasi FakeRadio membuktikan chunk tersedia sebelum END, satu arsip akhir dan satu ACK, serta cancel tidak diakui sebagai arsip sukses.
- Host native menggunakan pustaka Codec2 asli: encode bertahap menghasilkan bitstream identik dengan encode utuh untuk PCM uji yang sama; decode, batas buffer dan format lama lulus.
- APK debug berhasil dibangun; empat ABI native mengekspor enam entry point JNI. SHA256 APK sesi ini: `54857d992b22588bd214ecffbb65cdea3357c620c265bd2daf8a473ccf8c3785`.
- SHA256 INO utama dan keenam varian tetap `C6B51D798F196B6073BD45F91679E50DFD53C4D297E0225974E4EC5C75AEC5CC`.
- **Belum ada** upload/flash, instalasi/uji mikrofon-speaker HP, bukti RF dua radio, pengukuran latensi 2–3 detik, atau build/perangkat iOS pada tahap ini. Codec2 sudah ditingkatkan; performa ujung-ke-ujung belum maksimal/terbukti.

Output lokal diabaikan Git: `firmware/build/output/quasi-takeover/`, `firmware/build/output/quasi-member-v3/`, APK di `apps/nusamesh/composeApp/build/outputs/apk/debug/`, manifest lokal `apps/nusamesh/composeApp/build/codec2-validation/validation.json`. Pada clone baru, bangun ulang; jangan mengandalkan folder output tersebut tersedia.

## Cara mulai sesi berikutnya

1. Baca dokumen ini, lalu [NEXT_STEPS.md](NEXT_STEPS.md).
2. Periksa `git status`, perubahan pengguna, config/identitas/PHY aktual dan versi kedua board. Jangan reset seluruh working tree untuk kembali ke quasi.
3. Kerjakan tahap **P1: observasi dan ACK admission** terlebih dahulu. Tahap ini belum diimplementasikan; kontrak dan kriteria selesai ada di NEXT_STEPS.md.
4. Pertahankan INO utama, wire v3 radio anggota, batas buffer, atomic enqueue, dedup dan anggaran airtime bersama. Ubah protokol dengan fallback/capability yang jelas.
5. Jalankan hanya pengujian yang relevan dengan perubahan. Simpan bukti software terpisah dari bukti perangkat/RF.

Perintah yang dipakai pada sesi implementasi:

```powershell
powershell -File firmware/tests/run_duplex_tests.ps1
powershell -File apps/nusamesh/scripts/test-codec2-host.ps1
cd apps/nusamesh
.\gradlew.bat :composeApp:testDebugUnitTest :composeApp:assembleDebug --offline --no-daemon --no-parallel --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

Host test membutuhkan compiler yang tersedia (lihat parameter `-Compiler` pada skrip; sesi ini memakai Zig lokal). Android memakai JDK 17, SDK/NDK dan dependency yang tersedia. ESP32 memakai Arduino CLI, core ESP32 dan library sesuai catatan build; tidak ada janji build offline pada clone kosong. Setelah mengubah modul firmware kanonis, jalankan `firmware/sync-radio-modules.ps1` untuk menyinkronkan varian tanpa mengganti INO/identitas.

## Catatan untuk commit/push GitHub

Snapshot saat dokumentasi dibuat masih **belum di-stage/commit**. File CPP lama pada root NusaNode/SF terlihat deleted karena implementasi dipindah ke `src/`; header root penghubung tetap ada. Folder `src/`, firmware anggota, skrip pengujian dan dokumen baru masih untracked. Masukkan pemindahan dan file baru bersama agar clone berikutnya bisa dibangun.

Kelompok perubahan yang perlu ikut review: `firmware/`, sumber aplikasi `apps/nusamesh/composeApp/src/`, serta `apps/nusamesh/scripts/test-codec2-host.ps1` dan `codec2_bridge_test.c`. Folder build, compiler unduhan, cache, APK dan BIN lokal sudah diabaikan Git; deliverable rilis ditangani terpisah bila nanti dibuat.

Sebelum commit, gunakan `git status --short`, tinjau perubahan, lalu periksa `git diff --cached --stat` dan `git diff --cached --check` setelah staging. Commit/push dilakukan pengguna. Tidak ada commit/push otomatis pada pekerjaan dokumentasi ini.
