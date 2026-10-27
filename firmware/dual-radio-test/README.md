# Uji dua SX1276: LilyGO T3 v1.6.1 + breakout SPI

Untuk riwayat pekerjaan, status validasi, pinout lengkap dan urutan melanjutkan rework, baca [WALKTHROUGH.md](WALKTHROUGH.md). Diperbarui 2026-10-27.

Untuk uji beban dengan **dua LilyGO stock tanpa Cosmic/solder**, lihat [radio-stress](../radio-stress/README.md). Mode itu mengukur antrean/drop dan penerimaan satu arah, belum full-duplex.

Sketch diagnostik dua node, masing-masing satu ESP32 dan dua radio. Belum terintegrasi dengan BLE, fragmentasi, routing atau relay NusaNode. Flash ke ESP32 LilyGO; SX1276 tidak di-flash sendiri. Sketch mengonfigurasi kedua radio melalui SPI.

**Pilihan terintegrasi terbaru:** NusaNode utama sekarang punya switch half/full-duplex dan RX companion dua LilyGO melalui UART. Lihat [DUPLEX.md](../DUPLEX.md). Sketch diagnostik di folder ini tetap terpisah dan belum menjadi bukti hardware fitur tersebut.

## Wiring breakout eksternal (receiver)

**Revisi header-only 2026-10-27:** breakout sekarang memakai SPI kedua (HSPI) melalui pin header pada gambar user. Radio onboard tetap memakai VSPI internal GPIO5/19/27. Sketch sudah diubah; wiring dan binary versi bus bersama sebelumnya tidak dipakai lagi. OLED dan microSD tidak digunakan dalam konfigurasi ini.

**Koreksi modul Cosmic berdasarkan foto user:** header board bertuliskan **5V** dan **GND**, bukan VCC/3V3. Instruksi sebelumnya untuk menghubungkan 3V3 LilyGO ke pin VCC breakout tidak berlaku untuk board pada foto. Jangan menganggap tegangan input daya sama dengan level logika SPI.

[Dokumentasi resmi Cosmic LoRa Breakout](https://github.com/cosmic-id/cosmic-lora-breakout#compatability) membedakan varian 3.3V, 5V dan Dual: ESP32 didukung pada varian 3.3V/Dual, sedangkan varian 5V ditandai tidak kompatibel. Foto memperlihatkan tulisan `5V EN`, tetapi fungsi jumper/pad itu dan varian aktual belum terkonfirmasi. Jangan mengubah jumper atau menyambungkan pin sinyal langsung ke ESP32 sebelum varian/level logikanya dipastikan dari model, foto belakang atau petunjuk pembuat.

Tabel sinyal di bawah adalah alokasi GPIO sketch HSPI. Untuk board pada foto, sambungan daya dan kebutuhan antarmuka level logika masih harus ditentukan. Jika ternyata varian 5V-only, gunakan antarmuka SPI/reset/DIO dengan penerjemah level dan arah sinyal yang sesuai atau modul yang kompatibel 3,3 V; jangan langsung menghubungkan MISO/DIO 5 V ke ESP32. Dukungan band RF 920-923 MHz juga tetap perlu dikonfirmasi.

| Pin breakout | Sinyal LilyGO yang dipakai sketch sekarang |
|---|---|
| **5V** (sesuai foto Cosmic) | **Belum ditetapkan:** konfirmasi varian dan petunjuk daya; bukan pin 3V3 |
| GND | GND bersama |
| SCK | GPIO14 |
| MISO | GPIO39 / VN (input-only, sesuai untuk MISO) |
| MOSI | GPIO13 |
| NSS / CS | GPIO21 |
| RESET / RST | GPIO22 |
| DIO0 | GPIO34 (input-only, sesuai untuk interrupt RX) |
| DIO1, DIO2, DIO3 dst. | Tidak disambungkan untuk sketch ini |
| ANT | Antena eksternal yang sesuai band modul |

GPIO13/14 terkait antarmuka microSD dan GPIO21/22 biasanya OLED SDA/SCL: keluarkan kartu microSD dan jangan jalankan OLED/I2C pada pin tersebut. Sketch ini tidak menginisialisasi OLED maupun SD. Cocokkan label GPIO dengan revisi board fisik, jangan menghitung posisi header. GPIO39 adalah pin 39/VN pada gambar, bukan GPIO35. Onboard tetap SCK5/MISO19/MOSI27/CS18/RST23/DIO0=26/DIO1=33 tanpa kabel tambahan. Semua sinyal SPI breakout memakai bus terpisah; jangan jumper ke pad radio onboard. Pasang antena masing-masing sebelum TX, jangan menggabungkan konektor antena dengan kabel T.

ESP32 dapat merutekan SPI melalui GPIO matrix; GPIO34-39 hanya input, sehingga dipakai sebagai DIO0 dan MISO, bukan SCK/MOSI/CS/RESET. Referensi: [Espressif GPIO](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/peripherals/gpio.html).

Gunakan kabel SPI pendek. Setelah varian terkonfirmasi, gunakan suplai sesuai board dan decoupling sesuai pembuat modul; kapasitas suplai LilyGO untuk setup ini belum diukur. Isolasi antena/filter RF perlu diuji; kanal berbeda tidak menjamin RX tetap sensitif saat TX lokal aktif.

## Apakah Cosmic perlu firmware sendiri?

Breakout SPI SX1276/RFM95 tidak memerlukan upload firmware terpisah. Sketch di ESP32 menggunakan RadioLib untuk mengatur register kedua radio, frekuensi, modulasi, TX dan RX melalui SPI. Install RadioLib di lingkungan Arduino komputer dan upload `NusaDualRadio.ino` ke LilyGO masing-masing. Ini berbeda dari development board Cosmic yang juga memiliki MCU. Konfigurasi frekuensi tidak mengatasi ketidakcocokan tegangan/level logika hardware.

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

Riwayat 2026-10-22: role A dan B versi bus bersama berhasil build (312375 byte program, 23928 byte global RAM). Angka ini untuk versi lama; jangan gunakan binary versi itu untuk wiring HSPI baru. Hasil build lokal terbaru ditempatkan terpisah di `build/A` dan `build/B`. Jalankan perintah build berurutan: hook ekspor Arduino lokal juga menulis ke folder sketch yang sama meskipun build path dipisah.

Validasi revisi HSPI 2026-10-27: role A dan B berhasil build untuk `esp32:esp32:ttgo-lora32`, ESP32 core 3.3.12 / RadioLib 7.8.1. Masing-masing menggunakan **312515 byte program / 23960 byte global RAM**. Folder `build/A` dan `build/B` sudah berisi hasil revisi ini. Belum upload ke perangkat.

Belum upload atau diuji di perangkat. Build yang berhasil hanya membuktikan kompatibilitas kompilasi, bukan pinout modul Cosmic atau performa full-duplex RF. Penerima memakai satu FIFO dan flag event; paket beruntun masih bisa hilang.
