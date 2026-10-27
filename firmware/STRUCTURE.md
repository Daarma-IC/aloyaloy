# Struktur firmware dan titik lanjut

> Firmware terbaru: [FIRMWARE_QOS.md](FIRMWARE_QOS.md). Modul LoRa diperkuat untuk audio, pembagian pemancar dan hasil TX yang tidak pasti; ketujuh INO NusaNode tetap. Dokumen ini mempertahankan riwayat tahap sebelumnya.

> Update P1: [konfirmasi admission dan laporan kapasitas node](ADMISSION_P1.md) sudah tersedia di software; kredit/backpressure dinamis P2 belum diterapkan.

> Update audio 28 Oktober 2026: [jalur Codec2 bertahap Android](CODEC2_STREAMING.md) sudah ditambahkan. Keterangan voice-note-only di bawah adalah snapshot tahap sebelumnya; lihat dokumen baru untuk status, aktivasi, batas dan validasi.

> Update aktif 27 Oktober 2026: [quasi dua radio dengan takeover dan perbaikan Codec2](QUASI_TAKEOVER.md). Bagian implementasi RX-only/target lama di bawah adalah riwayat; baca status terbaru sebelum melanjutkan.


**Mode aktif:** quasi dua radio dengan takeover per frame, `LORA_QUASI_DUPLEX=1`, `LORA_QUASI_TAKEOVER=1`, `LORA_FULL_DUPLEX=0`. Kedua radio dapat RX/TX, berbagi satu antrean dan anggaran airtime; PAUSE harus diakui sebelum salah satu TX. Main dan anggota wajib memakai wire v3. Lihat [QUASI_TAKEOVER.md](QUASI_TAKEOVER.md).

Layanan BLE/WiFi dan routing tetap pada MCU utama. Anggota kedua sekarang mampu memancarkan RF, tetapi rencana dua anggota yang masing-masing menjalankan semua layanan aplikasi belum diimplementasikan. Jangan menggandakan NODE_ID untuk meniru satu hub. Susunan ini memakai satu identitas mesh yang dikelola utama.

Codec2 Android sudah memperoleh jalur capture/encode/kirim/playback bertahap, hint prioritas sepanjang fragmen/relay dan validasi panjang/frame. Mode voice note setelah Stop tetap tersedia. Belum ada jaminan latensi 2-3 detik atau bukti RF; limiter/pacing dan flow control masih hambatan. Lanjut dari [WALKTHROUGH.md](WALKTHROUGH.md) dan [NEXT_STEPS.md](NEXT_STEPS.md). Perhitungan airtime tersedia di [VOICE_LATENCY_AUDIT.md](VOICE_LATENCY_AUDIT.md).

Diperbarui 2026-10-28. Tujuan rework: memisahkan BLE, LoRa dan modul pendukung tanpa mengubah file `.ino` utama, pemanggilan library, `setup()`, `loop()`, callback atau pilihan mode.

**Perkembangan setelah rework:** pilihan half/full-duplex sudah ditambahkan ke `config.h` atas permintaan user. `.ino` utama tetap sama. Backend RX bisa companion LilyGO lewat UART atau radio SPI kedua; lihat [DUPLEX.md](DUPLEX.md). Pernyataan konfigurasi/logika tidak berubah pada bagian verifikasi di bawah merujuk tahap pemisahan folder sebelumnya, bukan fitur duplex yang ditambahkan sesudahnya.

## Peta folder

```text
firmware/
  NusaNode/                      firmware relay utama
    NusaNode.ino                  entry point tetap, tidak diubah
    config.h                      parameter node, mode radio dan pin
    nusa_*.h                      header penghubung untuk include lama
    src/
      ble/nusa_ble.*              koneksi HP lewat NimBLE + rate limit
      lora/nusa_radio.*           SX1276, CAD, antrean, airtime, IRQ
      lora/nusa_fragment.*        fragmentasi dan perakitan paket LoRa
      lora/nusa_receiver.*        anggota radio RX/TX lewat UART / SPI
      wifi/nusa_link.*            alternatif SoftAP/WebSocket
      routing/nusa_mobility.*     direktori user, rute dan handover
      protocol/nusa_frame.h       format frame radio dan dedup
      protocol/nusa_appproto.h    format/control metadata aplikasi
      protocol/nusa_duplex_wire.h framing UART COBS/CRC dan konfigurasi receiver
      ui/nusa_ui.*               OLED dan baterai
  sf-variants/                   struktur src/ sama, SF7-SF12 terkunci
  tests/                         test host transport dan mobility
  dual-radio-test/               eksperimen dua SX1276 pada satu ESP32
  radio-stress/                  dua LilyGO stock: TX beban + RX counter
  NusaRxCompanion/                firmware anggota radio RX/TX terkoordinasi
  sync-radio-modules.ps1          sinkron protokol/implementasi tanpa reset identitas SF
  make-sf-variants.sh             generator salinan firmware per SF
  build/                         hasil validasi lokal, diabaikan Git
```

## Bagaimana modul dipanggil bersama

`NusaNode.ino` tetap menyertakan nama header yang sama. Contoh: `#include "nusa_radio.h"` membuka header penghubung root, lalu `src/lora/nusa_radio.h`. Arduino mengompilasi `.cpp` dalam `src/` secara rekursif; tidak perlu menambahkan include `.cpp` di `.ino` atau membuat salinan implementasi pada root.

Pilihan yang sudah ada tetap berlaku: `USER_LINK_BLE=1` memakai BLE; nilai 0 memakai WiFi/WebSocket. Kode `.ino` tetap memilih alias `UserLink`, menerima paket dari HP, mengantrekannya ke LoRa, lalu mengirim paket radio yang selesai dirakit ke HP. Pemisahan folder ini tidak mengubah protokol atau membuat BLE dan WiFi aktif bersamaan.

```text
HP -- BLE (atau WiFi) --> callback NusaNode.ino
                         --> routing / fragmentasi
                         --> antrean LoRa --> SX1276

SX1276 --> frame / reassembly --> callback NusaNode.ino --> HP
```

Header root hanya untuk kompatibilitas. Edit implementasi di `src/`, bukan menambah logika pada header penghubung. Include internal memakai path relatif antarmodul dan `../../config.h`, agar sketch salinan SF tetap mandiri.

## Varian SF dan pengujian

Generator diperbarui untuk menyalin header root beserta `src/`, bukan hanya `.cpp` di root. Struktur enam varian yang ada juga dipindahkan tanpa mengubah `.ino` atau konfigurasi per unit. Generator tidak dijalankan untuk migrasi ini, sehingga NODE_ID/NODE_NAME lokal tidak direset.

Untuk perubahan implementasi berikutnya, edit `NusaNode/src/`, lalu jalankan generator sesuai [panduan varian](sf-variants/README.md). Generator tetap menyalin konfigurasi dari sumber utama dan mereset identitas varian seperti perilaku sebelumnya; catat identitas perangkat sebelum regenerasi.

Skrip test host diarahkan ke path baru `src/lora/` dan `src/routing/`. Test tersebut tetap membutuhkan compiler C++ host. Build ESP32 menguji kompilasi Arduino dan link antar-modul; tidak membuktikan perilaku radio/BLE di perangkat.

## Status uji beban yang perlu dilanjutkan

User ingin uji kirim beban tinggi memakai dua LilyGO yang tersedia sebelum memasang Cosmic atau menyiapkan node B. [radio-stress/README.md](radio-stress/README.md) memuat sketch dan prosedur lengkap.

- Satu LilyGO menjadi generator TX, satu lagi penerima RX, keduanya memakai radio onboard pada frekuensi sama. Tidak perlu solder atau jumper antar-board.
- Ini uji kapasitas satu arah; bukan pembuktian full-duplex dua endpoint atau jalur chat/BLE NusaNode.
- `run 4096 50 200` menawarkan 4096 paket 200 byte, tiap 50 ms; `run 1000 1 200` menguji overload singkat; interval 0 menawarkan burst sekaligus.
- Counter memisahkan offered, admitted, dropQueue, txDone/error, waktu tunggu antrean dan penerimaan unik. Antrean kosong karena paket dibuang bukan bukti pengiriman tanpa antrean.
- Kedua role stress berhasil build 2026-10-27. Skrip PowerShell pencatat log sudah diperiksa sintaks dan konstruksi objek serial tanpa membuka port.
- Belum upload, belum memancarkan paket uji, belum ada log hasil hardware. COM16/COM17 sempat terdeteksi; identitas TX/RX belum dikonfirmasi user.
- Setelah port ditentukan dan role di-upload, gunakan `radio-stress/run-stress.ps1` untuk mencatat `tx.log`, `rx.log` dan `run.json`. Jangan mengarang hasil dari contoh perintah.

Eksperimen Cosmic tetap terpisah. Foto modul menunjukkan pin 5V; varian listrik/kompatibilitas logika harus dikonfirmasi sebelum wiring ke ESP32. Lihat [walkthrough dual-radio](dual-radio-test/WALKTHROUGH.md).

## Batas rework ini

Rework ini hanya merapikan struktur relay utama dan salinan SF. Sketch diagnostik dual-radio dan radio-stress tidak digabung ke firmware produksi dan tidak diubah pada langkah pemisahan modul. Tidak ada perubahan aplikasi NusaMesh, tidak ada upload perangkat dan tidak ada klaim penghilangan antrean atau keberhasilan full-duplex.

## Verifikasi struktur

- Hash `.ino` utama dan keenam `.ino` varian tetap sama dengan sebelum rework: SHA256 `C6B51D798F196B6073BD45F91679E50DFD53C4D297E0225974E4EC5C75AEC5CC`.
- `config.h` utama dan setiap varian diperiksa sebelum/sesudah pemindahan dan tidak berubah.
- Empat belas berkas implementasi diperbandingkan dengan sumber Git awal: hanya path include yang berubah. Modul seluruh varian identik dengan sumber utama.
- Sebanyak 203 include lokal pada tujuh sketch dapat di-resolve.
- Generator berhasil membuat enam sketch modular SF7-SF12 dalam direktori uji terpisah, dengan `.ino` identik dan SF terkunci sesuai folder.
- Sintaks generator dan kedua runner test shell lolos pemeriksaan Bash. Test C++ host belum dijalankan karena compiler host tidak tersedia pada PATH sesi ini.
- Build NusaNode utama berhasil untuk `esp32:esp32:ttgo-lora32`: 695662 byte program / 71908 byte global RAM, ESP32 core 3.3.12, RadioLib 7.8.1, NimBLE-Arduino 2.5.1 dan U8g2 2.36.19. Ini mode default BLE + LoRa; mode WiFi belum dibuild pada rework ini.
- Build NusaNode_SF7 juga berhasil dengan struktur modular: 695138 byte program / 71908 byte global RAM. Varian SF8-SF12 diperiksa konsistensi sumber/include dan generatornya, tidak dibuild satu per satu.

Seluruh pemeriksaan di atas adalah pemeriksaan struktur/build, bukan uji koneksi BLE atau paket radio pada perangkat.

## Kelanjutan: pilihan duplex terintegrasi

Atas permintaan berikutnya, `config.h` utama dan varian ditambah pilihan `LORA_FULL_DUPLEX`, `LORA_RX_BACKEND` dan `LORA_DUPLEX_SIDE`; default tetap half. Driver radio sekarang dapat menggunakan receiver pada LilyGO kedua lewat UART atau receiver SPI pada MCU yang sama. Perubahan ini memang mengubah konfigurasi/driver, berbeda dari tahap pemisahan folder sebelumnya. `.ino` utama dan seluruh `.ino` varian tetap memiliki hash yang sama.

Lima suite software lolos, termasuk framing UART, kegagalan link, RX selama TX, sinkronisasi SF, backend SPI dan regresi transport/mobility. Build utama half/full-UART, companion, serta validasi driver SPI berhasil. Lihat [hasil dan wiring duplex](DUPLEX.md). Test host kini bisa dijalankan lewat `tests/run_duplex_tests.ps1` dengan compiler lokal. Hardware, WiFi build, serta hasil full-duplex RF belum divalidasi; antrean TX tetap ada.
