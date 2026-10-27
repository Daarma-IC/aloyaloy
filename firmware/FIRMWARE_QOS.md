# Penguatan firmware audio dan dual LoRa — 28 Oktober 2026

Tahap ini mendahulukan firmware, sebelum kredit/backpressure HP pada P2. Entry point tetap `NusaNode.ino`; ketujuh INO utama/varian dan identitas config tidak berubah. Modul kanonis disalin ke SF7–SF12 melalui `sync-radio-modules.ps1`. Sketch companion ikut diperbaiki.

Codec2 encode/decode berada di HP. Perubahan ini melindungi pengangkutan chunk di MCU: prioritas, ruang antrean, urutan fragmen, kesempatan RX, pemilihan pemancar dan hasil TX. Bitrate, bundling Android, pacing HP dan budget airtime tidak dinaikkan.

## Antrean yang berlaku

| Kelas masuk | Slot yang harus tersisa setelah admission |
|---|---:|
| ACK | 0 |
| Teks | 1 untuk ACK |
| Audio | 2 untuk teks/ACK |
| Bulk/gambar, relay biasa, gossip | 6 untuk audio/teks/ACK |

Aturan sama berlaku pada enqueue frame mentah dan batch aplikasi. Hint voice `0x08` tetap dipertahankan di fragmen/relay. Dari antrean kosong 24 slot, bulk maksimal mengisi 18; enam sisanya cukup untuk satu paket audio aplikasi 512 byte yang menjadi tiga frame RF, dengan ruang interaktif tersisa. Audio masih dapat ditolak bila kelas lain sudah menghabiskan kapasitas. `queueFree` P1 menunjukkan ruang fisik, bukan janji seluruhnya boleh dipakai bulk.

Batch tetap atomik: semua fragmen diterima atau semuanya ditolak. Fragmen yang sudah diterima tidak digusur. Nomor urut enqueue menjaga FIFO ketika timestamp sama dan slot awal dipakai ulang. Aging tidak menaikkan gambar menjadi audio atau audio menjadi teks.

ACK dan teks didahulukan. Sesudah tiga percobaan frame audio yang dibebankan airtimenya, satu frame bulk menunggu boleh mendapat giliran bila budget cukup. Sesudah itu audio lanjut. Bulk yang belum terjangkau budget tidak menghalangi audio yang lebih kecil. Ini fairness per frame, bukan per HP; trafik ACK/teks terus-menerus masih dapat menunda kelas di bawahnya.

## Koordinasi kedua radio

Tetap satu hub, satu antrean/budget, satu kanal dan maksimal satu TX lokal. Main mengelola BLE/WiFi/routing; companion merupakan anggota radio RX/TX, bukan aplikasi NusaNode penuh kedua. Saat sehat, pemancar bergantian per frame. Frame bisa dialihkan setelah kegagalan yang diketahui terjadi sebelum TX; pancaran aktif tidak dipotong.

| Perangkat | Sketch | Peran |
|---|---|---|
| LilyGo utama | `NusaNode/NusaNode.ino` | Akses HP, routing, antrean, satu identitas hub dan radio RX/TX onboard |
| LilyGo kedua | `NusaRxCompanion/NusaRxCompanion.ino` | Radio RX/TX terkoordinasi UART; menerima config PHY dari main, tanpa NODE_ID mesh mandiri |

Ini bukan pembagian tetap satu board TX dan satu board RX: kedua radio dapat menjalankan kedua peran secara bergantian. Saat salah satu memancar, RX lokal dijeda. Dua radio tidak otomatis menghilangkan antrean/interferensi atau menggandakan kapasitas kanal. Identitas node tidak memisahkan sinyal RF. Migrasi ke satu MCU + dua modul tetap target hardware berikutnya; backend SPI yang ada belum menjadi bukti wiring/catu Cosmic tertentu tervalidasi.

- CAD dilakukan oleh radio yang akan memancar. CAD main yang rusak tidak mencegah companion sehat memakai CAD-nya sendiri.
- Preamble sibuk menghasilkan backoff kanal bersama. Error CAD tidak dianggap kanal kosong; kegagalan memberi cooldown radio 0,5–8 detik dan membuka kesempatan pemancar lain. Sukses TX menghapus cooldown radio itu.
- Jitter berlangsung ketika RX masih aktif. Waktu mendengarkan terkonfirmasi saat idle ikut memenuhi jendela RX berikutnya; tidak mengulang tunggu 500 ms hanya karena antrean sempat kosong. Minimum jendela tetap menyesuaikan airtime frame penuh pada SF aktif.
- Sebelum TX, main tetap menunggu ACK PAUSE bertoken tepat dan menghentikan RX lokal. UART stale atau pembatalan belum terkonfirmasi menahan TX; kehilangan anggota tidak otomatis mengizinkan main memancar sendiri.
- Anggota menolak LISTEN bertoken lama dan perubahan keadaan pada token sama. Token `65535 → 1` tetap sah. RESUME tertunda tidak boleh membuka RX di tengah giliran main.
- Backoff, jitter dan cooldown aman melewati rollover `millis()`. State penjadwal direset pada `begin()`.

## Penolakan dan hasil tidak pasti

| Kondisi | Perlakuan terhadap frame dan airtime |
|---|---|
| CAD sibuk/error sebelum start TX | Frame tetap antre; belum dibebankan airtime TX, boleh mencoba radio lain setelah backoff/cooldown |
| Perintah UART belum dapat dikirim utuh | Frame tetap antre; bukan RF sukses |
| `startTransmit()` error | Mungkin gagal setelah register mode TX ditulis: UNKNOWN, airtime dibebankan, frame tidak otomatis diulang |
| TX_DONE dan finish berhasil | Counter TX sukses bertambah, airtime dibebankan; belum membuktikan penerima akhir menerima |
| Finish gagal, timeout atau status TX tidak diketahui | Bukan sukses; airtime dibebankan, tanpa replay otomatis |
| Standby tidak terkonfirmasi | Tahan operasi berikutnya; jangan membuka RX/TX dengan asumsi radio sudah berhenti |

Untuk start/finish/timeout lokal, standby harus berhasil sebelum RX dibuka lagi. Backend SPI anggota juga tidak membuka RX setelah kehilangan status konfigurasi akibat standby gagal. Pada UART, status terminal atau konfigurasi ulang cocok diperlukan untuk keluar dari pembatalan.

Wire UART tetap **v3**, ukuran pesan sama; `txError` yang sudah tersedia kini dibaca main. Flash main dan companion dari tahap yang sama agar perbaikan gate dan klasifikasi UNKNOWN tersedia pada keduanya. Config/wiring tetap seperti [WALKTHROUGH.md](WALKTHROUGH.md#konfigurasi-dan-wiring-aktif).

## Validasi software

`powershell -NoProfile -ExecutionPolicy Bypass -File firmware/tests/run_duplex_tests.ps1`: **11 suite lulus**. Mencakup framing, legacy half, quasi UART/SPI, eksperimen full UART/SPI, takeover UART/SPI, P1 admission dan mobility.

Kasus baru: cadangan raw/batch, FIFO timestamp sama, fairness, aging, CAD error, cooldown/backoff melewati rollover, RX saat jitter, CAD pemancar terpilih, handover setelah CAD main gagal, start error kedua radio menjadi UNKNOWN tanpa replay, serta token RESUME basi/berlawanan/rollover. Handler companion asli diuji dengan stub SPI/UART; konstanta CAD stub mengikuti RadioLib 7.8.1, termasuk CHANNEL_FREE negatif.

| Build Arduino | Flash program | RAM global | Output lokal |
|---|---:|---:|---|
| Main BLE, quasi UART | 707430 byte | 76404 byte | `build/output/firmware-qos-main/` |
| Companion v3 | 314031 byte | 23752 byte | `build/output/firmware-qos-member/` |
| Main WiFi, profil terpisah | 1028478 byte | 88712 byte | `build/output/firmware-qos-wifi/` |

Ketiganya berhasil dibangun memakai ESP32 3.3.12, RadioLib 7.8.1 dan FQBN `esp32:esp32:ttgo-lora32`. Config utama tetap BLE; WiFi memakai salinan profil `build/profiles/AdmissionWifi/`. Backend SPI takeover diuji host, belum build/upload khusus perangkat pada tahap ini. Semua SF menerima modul identik, bukan tujuh hasil build terpisah.

Hash ketujuh INO NusaNode tetap `C6B51D798F196B6073BD45F91679E50DFD53C4D297E0225974E4EC5C75AEC5CC`. Manifest checksum artefak/validasi lokal ada di `build/output/firmware-qos-main/validation.json` dan tidak ikut Git. Android tidak diubah atau dibangun ulang pada tahap firmware ini.

## Uji perangkat dan titik lanjut

Belum ada upload, pengukuran RF atau audio HP pada tahap ini. Software lulus tidak membuktikan bebas interferensi, latensi 2–3 detik, nol antrean atau bandwidth dua kali lipat. Codec2 tetap dibatasi kapasitas PHY, overhead, pacing HP dan duty bersama.

1. Flash main dan companion terbaru, UART silang TX13–RX34, GND bersama, USB masing-masing. Pastikan board/COM sebelum upload.
2. Dengan HP dan node lawan, kirim gambar lalu audio sambil mengirim teks. Cocokkan admission P1, msgId/fragmen, arsip/checksum dan delivery ACK penerima.
3. Pantau serial `[DUPLEX]`: `mainTx`, `memberTx`, `cadBusy`, `cadErr`, `fallback`, `txErr`, `q`, `rxReady`. `fallback` menghitung keputusan melewati preferensi/pindah dari CAD main gagal, bukan bukti pengiriman berhasil; CAD anggota gagal terlihat melalui `cadErr` dan status anggota.
4. Putus/reboot companion ketika idle dan TX. Harus tertahan saat status tidak pasti, tanpa TX ganda, lalu pulih setelah konfigurasi/pembatalan terkonfirmasi. Log UART bukan pengukuran overlap RF presisi.
5. Catat waktu antre/chunk/TX/penerimaan/playback, kegagalan reassembly, kehilangan/duplikat dan buffering. Sesudah bukti perangkat, lanjut P2 kredit/backpressure dan P3 bundling adaptif pada [NEXT_STEPS.md](NEXT_STEPS.md).

Dua LilyGo sekarang membentuk satu hub; uji end-to-end LoRa memerlukan setidaknya satu node lawan. Tidak ada flash atau push Git otomatis.

Checklist kerja lengkap, skenario minimum dan template pencatatan perangkat ada di [NEXT_STEPS.md](NEXT_STEPS.md#urutan-kerja-berikutnya--mulai-dari-sini). Hasil 11 suite/build di atas berasal dari tahap implementasi firmware; sesi pembaruan MD tidak menjalankan ulang build atau pengujian.
