# Quasi duplex dua radio dan perbaikan Codec2

Titik lanjut keseluruhan: [WALKTHROUGH.md](WALKTHROUGH.md); roadmap ACK admission/backpressure ada di [NEXT_STEPS.md](NEXT_STEPS.md), masih rencana.

> Update audio 28 Oktober 2026: [jalur Codec2 bertahap Android](CODEC2_STREAMING.md) sudah ditambahkan. Keterangan voice-note-only di bawah adalah snapshot tahap sebelumnya; lihat dokumen baru untuk status, aktivasi, batas dan validasi.

Keputusan 27 Oktober 2026: kembali ke quasi duplex pada kanal yang sama. Percobaan full duplex dua frekuensi ditunda. Riwayat lama dipertahankan; tidak ada git reset atau penghapusan perubahan modularisasi. `NusaNode.ino` tetap menjadi entry point dan tidak diubah.

## Perilaku yang diimplementasikan

- Satu identitas mesh/hub, satu antrean TX, satu anggaran airtime gabungan. Board kedua tidak memakai NODE_ID yang sama sebagai node mesh mandiri.
- Kedua radio dapat RX dan TX. Saat tidak memancar, kedua radio mendengarkan. Salinan frame yang sama dari dua penerima disaring sebelum masuk handler hub (cache 32 frame, 2 detik).
- Pemilik TX bergantian setiap frame: utama, anggota kedua, utama, dan seterusnya. Satu transfer berfragmen dapat diteruskan oleh radio lain karena msgId/indeks fragmennya tetap sama.
- Sebelum TX: beri jendela RX, minta standby anggota, tunggu ACK dengan token yang cocok, hentikan RX lokal, lalu kirim satu frame. Setelah selesai kembali RX. Tidak ada dua pancaran lokal bersamaan dan tidak ada janji TX/RX RF simultan.
- Takeover berarti penyerahan **paket berikutnya**, bukan memotong frame yang sedang memancar. Ini penjadwal terpusat dengan giliran tetap, belum penyeimbangan berdasarkan kualitas RF atau dua anggota aplikasi penuh.
- Status UART diterima bukan bukti RF berhasil. Counter TX bertambah setelah TX_DONE. Timeout/hasil tidak diketahui dibebankan ke anggaran, tidak dihitung berhasil, dan tidak otomatis mengirim ulang frame yang mungkin sudah terpancar. Penolakan sebelum RF (misalnya CAD sibuk) mempertahankan slot antrean agar radio lain dapat mencoba kemudian, tanpa menganggapnya terkirim.
- Perintah TX memakai token, dengan penolakan duplikat/token lama. Pembatalan juga menutup token yang perintah TX-nya hilang. TX lokal tertahan sampai anggota memastikan pembatalan/standby atau konfigurasi ulang yang cocok.

Layanan BLE/WiFi, routing, dedup aplikasi, dan antrean tetap pada MCU utama. Board kedua memiliki kemampuan radio RX/TX, tetapi **belum** menjalankan salinan penuh layanan NusaNode/BLE/WiFi. Rencana dua anggota aplikasi setara di MULTIRADIO_PLAN.md belum terpenuhi oleh perubahan ini. Susunan terpusat ini juga cocok sebagai dasar satu MCU + dua modul radio kelak.

## Konfigurasi aktif dan wiring

`firmware/NusaNode/config.h`:

```cpp
#define LORA_FULL_DUPLEX      0
#define LORA_QUASI_DUPLEX     1
#define LORA_QUASI_TAKEOVER   1
#define LORA_RX_BACKEND       1
```

Kanal quasi mengikuti LORA_FREQ (default 921 MHz), SF/BW/CR/preamble/sync dan daya dipasok utama ke anggota. Nilai SF runtime dapat mengikuti NVS kecuali varian SF_FIXED.

| LilyGo utama | LilyGo anggota kedua |
|---|---|
| GPIO13 / TX UART2 | GPIO34 / RX UART2 |
| GPIO34 / RX UART2 | GPIO13 / TX UART2 |
| GND | GND |
| USB sendiri | USB sendiri |

Jangan satukan kedua jalur 5V/3V3 USB. GPIO13 tidak boleh dipakai microSD bersamaan. Antena terpasang pada masing-masing board. Tidak perlu menyolder GPIO5/27 karena onboard LoRa masing-masing board memakai SPI internalnya sendiri.

Flash utama dari `NusaNode/NusaNode.ino`, anggota kedua dari `NusaRxCompanion/NusaRxCompanion.ino`. Nama direktori companion dipertahankan, tetapi sketch sekarang dapat RX/TX. **Keduanya wajib firmware wire v3**; v1/v2 tidak kompatibel. Anggota menunggu konfigurasi sebelum mengaktifkan radio.

Set `LORA_QUASI_TAKEOVER=0` untuk susunan historis radio utama TX + anggota RX-only. Set QUASI=0 dan FULL=0 untuk satu radio. FULL tetap 0 pada baseline ini; cabang percobaan lama tidak dihapus agar riwayat/test tidak hilang.

## Codec2 dan antrean media

- Encoder tetap NC2 v2, Codec2 1200 bps: 320 sampel/40 ms menjadi 6 byte; 150 byte/detik, ditambah header NC2 12 byte per voice note. Voice note 10 detik tanpa trim = 1512 byte, bukan satu paket SX1276.
- Decoder tetap menerima NC2 v1/3200 lama. Header yang jumlah frame/sampelnya tidak sesuai, kosong, terpotong, atau punya frame tambahan ditolak. Perhitungan panjang native memakai intermediate 64 bit.
- Penanda `voicePriority` pada flag aplikasi 0x08 dipasang hanya untuk FILE_TRANSFER audio/x-codec2 yang dibuat engine. Flag ikut semua fragmen, decode/encode dan relay TTL. Penjadwal hanya menggunakannya untuk FILE/FRAGMENT, bukan menebak isi terenkripsi.
- Prioritas: ACK/registrasi, teks, Codec2, bulk/lainnya, gossip. Pada firmware prioritas relay biasa tetap kelas tersendiri di bawah bulk; hint audio dikenali juga di dalam header routing terarah.
- Setiap fragmen LoRa audio membawa NUSA_FLAG_VOICE=0x08. Firmware menyisakan 4 slot saat menerima batch bulk, dan 2 slot saat menerima batch audio untuk teks/ACK. Masuk batch tetap semua-atau-tidak, tanpa membuang fragmen gambar yang sudah diantrekan.
- Hint bukan autentikasi atau izin bypass. Rate limit dan anggaran airtime tetap berlaku. Peer/firmware lama masih dapat menerima format payload, tetapi belum tentu mengenal prioritas baru.

**Belum streaming langsung:** Android masih merekam/trim/encode setelah Stop dan penerima memutar setelah berkas utuh. Prioritas baru membuat audio mendahului fragmen gambar yang masih antre, tetapi tidak menghapus paket yang sudah diserahkan ke firmware, tidak menambah bandwidth, dan tidak menjamin mulai terdengar 2-3 detik. Antrean yang sudah kehabisan airtime tetap menunggu.

Tahap streaming berikutnya memerlukan sesi encoder/decoder yang dipertahankan, bundling beberapa frame per chunk, stream ID/urutan/akhir, buffer playback dan kebijakan chunk terlambat. Jangan kirim setiap frame Codec2 6 byte sebagai satu paket LoRa: overhead airtime akan membengkak. Evaluasi durasi chunk dengan airtime aktual sebelum menghubungkan capture langsung ke radio. Tidak ada limiter yang dimatikan untuk mengejar target.

## Validasi dan cara uji hardware

Status hasil build/software final dicatat di bagian hasil di bawah setelah verifikasi. Software host memakai radio/UART stub untuk penjadwal; pengujian Codec2 host memakai pustaka Codec2 asli. Keduanya bukan bukti RF, isolasi antena, kualitas suara percakapan atau latensi end-to-end.

1. Flash kedua sketch wire v3, hubungkan tiga kabel UART/GND, boot utama dan anggota. Pastikan konfigurasi cocok dan rxReady=1.
2. Kirim gambar profil LoRa lalu voice note dari aplikasi yang dibangun dengan hint baru. ACK/teks lebih dahulu, lalu fragmen audio mendahului bulk yang masih antre.
3. Periksa mainTx dan memberTx bertambah ketika keduanya mendapat giliran. Cocokkan TX_DONE dengan log penerima; TX_DONE sendiri belum berarti pesan sampai.
4. Cabut UART anggota saat sibuk. TX utama harus tertahan saat status anggota stale/pembatalan belum diakui. Sambungkan lagi dan periksa pemulihan tanpa TX ganda.
5. Ukur waktu Stop/kirim, penerimaan berkas, dan mulai playback terpisah. Catat SF aktual, ukuran, backlog, retry/hop dan jatah airtime. Jangan melaporkan target 2-3 detik sebelum pengukuran.

Perintah host: `powershell -File firmware/tests/run_duplex_tests.ps1` dan `powershell -File apps/nusamesh/scripts/test-codec2-host.ps1`. Jalankan sinkronisasi `firmware/sync-radio-modules.ps1` setelah mengubah modul kanonis. Skrip itu mempertahankan semua file INO dan identitas konfigurasi varian SF.

## Hasil verifikasi

- Sembilan suite host firmware lulus: wire/framing, full UART historis, full SPI historis, quasi UART RX-only historis, quasi SPI RX-only historis, takeover UART, takeover SPI, transport/antrean/fragmentasi, mobility.
- Takeover UART menguji handler companion asli dengan SPI/UART stub: TX bergantian, audio mendahului gambar, tidak overlap lokal, dedup dua RX, token duplikat/lama, status hilang, pembatalan perintah TX yang hilang, CAD sibuk dan reboot main saat anggota masih TX.
- Takeover SPI menguji satu paket 700 byte (3 frame) berpindah pemancar tanpa perubahan msgId/urutan/isi; kembali RX dan menahan TX saat standby lokal gagal. Backend SPI diuji host, belum build/upload perangkat khusus backend SPI pada tahap ini.
- Codec2 host memakai sumber pustaka asli: 1200 encode/decode untuk 1/319/320/321/8000/80000 sampel, kapasitas output, truncation/header rusak dan decode 3200 lama. Lulus; bukan penilaian kualitas suara atau pengukuran latensi RF.
- Arduino CLI / ESP32 3.3.12 / RadioLib 7.8.1: main quasi takeover **701562 byte flash / 73148 byte RAM**, anggota wire v3 **314031 / 23752**. Build keduanya berhasil.
- Android `testDebugUnitTest` dan `assembleDebug`: 151 tes, 0 gagal; build akhir berhasil dan APK debug diperbarui. Empat ABI native ikut dibangun pada build Android.
- SHA256 NusaNode.ino dan keenam varian INO tetap `C6B51D798F196B6073BD45F91679E50DFD53C4D297E0225974E4EC5C75AEC5CC`. Modul semua varian serta header wire companion cocok dengan sumber kanonis; identitas varian dipertahankan.
- Belum ada flash/upload, tes perangkat Android, pengukuran RF atau bukti latensi 2-3 detik. Cabang half memiliki warning counter telemetri tidak dipakai; tidak menggagalkan pengujian.

Output lokal (diabaikan Git): `firmware/build/output/quasi-takeover/`, `firmware/build/output/quasi-member-v3/`, dan `apps/nusamesh/composeApp/build/outputs/apk/debug/composeApp-debug.apk`. APK debug untuk pengujian, bukan release produksi. Perubahan sumber belum di-commit.
