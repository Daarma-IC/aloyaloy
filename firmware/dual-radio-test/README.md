# Uji dua SX1276: LilyGO T3 v1.6.1 + breakout SPI

Sketch diagnostik dua node, masing-masing satu ESP32 dan dua radio. Belum terintegrasi dengan BLE, fragmentasi, routing atau relay NusaNode. Flash ke ESP32 LilyGO; SX1276 tidak di-flash sendiri. Sketch mengonfigurasi kedua radio melalui SPI.

## Wiring breakout eksternal (receiver)

Asumsi: modul SX1276 SPI lengkap varian RF yang mendukung 920-923 MHz, dengan logika/catu 3,3 V. Nama Cosmic belum memastikan tipe modul. Jika modul hanya punya TXD/RXD atau breakout adalah adaptor kosong, wiring ini tidak berlaku.

| Pin breakout | GPIO/header LilyGO |
|---|---|
| VCC / 3V3 | 3V3, bukan 5V untuk modul bare 3,3 V |
| GND | GND bersama |
| SCK | GPIO5 |
| MISO | GPIO19 |
| MOSI | GPIO27 |
| NSS / CS | GPIO13 |
| RESET / RST | GPIO14 |
| DIO0 | GPIO34 (input-only, sesuai untuk interrupt RX) |
| DIO1, DIO2, DIO3 dst. | Tidak disambungkan untuk sketch ini |
| ANT | Antena eksternal yang sesuai band modul |

GPIO13/14 juga terkait antarmuka microSD pada board ini: microSD tidak digunakan dalam pengujian. Cocokkan label GPIO dengan revisi board fisik, jangan menghitung posisi header dari tabel ini. Onboard tetap SCK5/MISO19/MOSI27/CS18/RST23/DIO0=26/DIO1=33. Jangan satukan CS, RESET atau DIO0 kedua radio. Pasang antena masing-masing sebelum menyalakan TX. Jangan menggabungkan konektor antena kedua radio dengan kabel T.

Gunakan kabel SPI pendek. Pastikan suplai 3,3 V stabil untuk tambahan RX; kapasitas regulator board belum diukur. Tambahkan decoupling dekat breakout sesuai rekomendasi pembuat modul. Isolasi antena/filter RF perlu diuji; kanal berbeda tidak menjamin RX tetap sensitif saat TX lokal aktif.

Referensi board: https://github.com/Xinyuan-LilyGO/LilyGo-LoRa-Series/blob/master/schematic/T3_V1.6.1.pdf

## Role dan kanal silang

| ESP32 | NUSA_ROLE_A | Onboard TX | Breakout RX |
|---|---|---|---|
| A | 1 | 920,5 MHz | 922,5 MHz |
| B | 0 | 922,5 MHz | 920,5 MHz |

Frekuensi contoh bench test; bukan penetapan kepatuhan regulasi atau jaminan isolasi RF. BW125, SF7, CR4/5, sync 0x12, CRC aktif, TX 2 dBm, paket 160 byte setiap 10 detik. RX tetap aktif selama TX; tidak memakai CAD. Pengujian khusus pasangan A/B; bukan konfigurasi mesh banyak node.

## Build dan upload

Arduino IDE: buka `NusaDualRadio/NusaDualRadio.ino`, install RadioLib, pilih board **TTGO LoRa32-OLED**, serial 115200. Untuk A gunakan `#define NUSA_ROLE_A 1`; untuk B ubah menjadi `0`, kemudian build/upload ulang. Pilih port COM yang benar untuk setiap board.

CLI dari root repo (contoh port, sesuaikan):

```powershell
$dualBuild = Join-Path $PWD 'firmware/dual-radio-test/build'
arduino-cli compile --jobs 4 --fqbn esp32:esp32:ttgo-lora32 --build-path "$dualBuild/work-A" --build-property compiler.cpp.extra_flags=-DNUSA_ROLE_A=1 --output-dir "$dualBuild/A" firmware/dual-radio-test/NusaDualRadio
arduino-cli upload --fqbn esp32:esp32:ttgo-lora32 -p COM5 --input-dir firmware/dual-radio-test/build/A firmware/dual-radio-test/NusaDualRadio
arduino-cli compile --jobs 4 --fqbn esp32:esp32:ttgo-lora32 --build-path "$dualBuild/work-B" --build-property compiler.cpp.extra_flags=-DNUSA_ROLE_A=0 --output-dir "$dualBuild/B" firmware/dual-radio-test/NusaDualRadio
arduino-cli upload --fqbn esp32:esp32:ttgo-lora32 -p COM6 --input-dir firmware/dual-radio-test/build/B firmware/dual-radio-test/NusaDualRadio
```

## Pengukuran

1. Cek `[READY]` dan frekuensi pada masing-masing serial monitor. Kegagalan init mengeluarkan kode error dan tidak menjalankan TX.
2. Tekan `t` di A untuk mematikan TX A: ukur penerimaan A terhadap B dari nomor urut peer. Lakukan sebaliknya sebagai baseline kedua arah.
3. Aktifkan TX kedua board. Catat `rx`, `crc`, `txErr`, dan `rxIrqDuringTx` serta nomor urut yang hilang. Ubah waktu boot/aktivasi salah satu board agar jadwal TX saling bertumpang tindih; `s` meminta kirim jika jeda 10 detik sudah lewat. Tidak ada sinkronisasi jadwal otomatis.
4. `rxIrqDuringTx` menghitung paket peer valid yang IRQ RX-nya teramati saat TX lokal aktif dan IRQ TX_DONE belum teramati. Ini bukti overlap menurut waktu interrupt firmware, bukan pengukuran RF presisi. Nilai nol bisa berarti jadwal tidak overlap, bukan otomatis wiring rusak.
5. Bandingkan kehilangan paket saat TX lokal OFF/ON. CRC nol tidak menjamin tidak ada gangguan: paket yang gagal terdeteksi tidak menghasilkan CRC error. Naiknya counter TX/RX saja bukan bukti RX bekerja saat TX.

Validasi 2026-10-22: role A dan B berhasil build untuk `esp32:esp32:ttgo-lora32`, ESP32 core 3.3.12 / RadioLib 7.8.1. Masing-masing memakai 312375 byte program dan 23928 byte global RAM. Hasil build lokal terpisah di `build/A` dan `build/B`; binary kedua role memiliki hash berbeda. Jalankan perintah build berurutan: hook ekspor Arduino lokal juga menulis ke folder sketch yang sama meskipun build path dipisah.

Belum upload atau diuji di perangkat. Build yang berhasil hanya membuktikan kompatibilitas kompilasi, bukan pinout modul Cosmic atau performa full-duplex RF. Penerima memakai satu FIFO dan flag event; paket beruntun masih bisa hilang.
