# Walkthrough dua radio SX1276 ? riwayat eksperimen

Diperbarui 28 Oktober 2026. **Titik lanjut aktif ada di [firmware/WALKTHROUGH.md](../WALKTHROUGH.md)**; urutan perbaikan selanjutnya ada di [NEXT_STEPS.md](../NEXT_STEPS.md).

Baseline aktif: `LORA_QUASI_DUPLEX=1`, `LORA_QUASI_TAKEOVER=1`, `LORA_FULL_DUPLEX=0`, UART wire v3. Kedua radio dapat RX/TX bergantian per frame, satu antrean/budget hub. Layanan aplikasi tetap pada MCU utama. Detail wiring/build ada di [QUASI_TAKEOVER.md](../QUASI_TAKEOVER.md); Android sudah memiliki audio bertahap pada [CODEC2_STREAMING.md](../CODEC2_STREAMING.md). Tahap berikutnya adalah ACK admission dan backpressure HP?firmware, belum diimplementasikan.

Dokumen ini mempertahankan eksperimen Cosmic, frekuensi silang dan uji beban awal sebagai riwayat. `NusaDualRadio.ino` di folder ini adalah sketch diagnostik, **bukan sumber konfigurasi baseline NusaNode**. Config aktif adalah [NusaNode/config.h](../NusaNode/config.h). Keterangan RX-only, wire v2, atau TX/RX serentak di bagian historis bukan perilaku aktif sekarang. INO utama tetap sama; belum ada bukti RF/hardware pada catatan implementasi ini.

**Temuan terbaru modul Cosmic:** foto user memperlihatkan pin daya **5V**, GND dan tulisan **5V EN**. Tidak ada header VCC/3V3 pada foto itu. Tabel daya lama untuk modul bare 3,3 V dibatalkan. [Dokumentasi resmi Cosmic](https://github.com/cosmic-id/cosmic-lora-breakout#compatability) mendukung ESP32 pada varian 3.3V dan Dual; varian 5V ditandai tidak kompatibel langsung. Identitas varian dan fungsi jumper 5V EN belum dipastikan. Alokasi GPIO HSPI sudah ada, tetapi **jangan menganggap wiring listrik langsung ke ESP32 sudah disetujui** sebelum level sinyal dan catu terkonfirmasi. Tidak perlu firmware terpisah untuk breakout SPI SX1276; firmware berada di ESP32.

**Revisi pinout 2026-10-27:** GPIO5/27 tidak keluar pada header gambar user. Wiring lama bus bersama dibatalkan. Sketch sekarang memakai bus kedua HSPI untuk breakout: **SCK14/MISO39/MOSI13/CS21/RST22/DIO0=34**. Radio onboard tetap pada VSPI internal. OLED dan microSD tidak digunakan. Keberhasilan build bukan bukti wiring fisik atau performa RF.

## 1. Riwayat tujuan dan keputusan eksperimen awal

User ingin menambahkan breakout LoRa SX1276 Cosmic ke LilyGO yang sudah memiliki SX1276 onboard, supaya satu node dapat mengirim dan menerima bersamaan.

Rancangan uji awal (historis):

- Satu node = satu ESP32 LilyGO T3 v1.6.1 + dua SX1276.
- Radio onboard digunakan untuk TX; breakout eksternal untuk RX.
- Radio onboard memakai VSPI; breakout memakai HSPI terpisah melalui pin header, dengan CS, reset dan DIO0 sendiri.
- Node A dan B memakai frekuensi silang. Dibutuhkan dua LilyGO dan dua breakout untuk menguji pasangan lengkap.
- Upload dilakukan ke ESP32 masing-masing LilyGO. SX1276 dikonfigurasi lewat SPI, tidak di-flash sendiri.
- Masing-masing SX1276 tetap half-duplex. Target sistem dua radio adalah TX dan RX bersamaan pada kanal berbeda; keberhasilan RF-nya belum dibuktikan.

## 2. Yang sudah dikerjakan

1. Membaca struktur `firmware/`, khususnya `NusaNode/`, `sf-variants/` dan `tests/`.
2. Meninjau driver radio, pin konfigurasi, antrean prioritas/airtime, fragmentasi, BLE serta interface mobility/routing NusaNode.
3. Menemukan driver utama masih menggunakan satu objek SX1276, satu flag interrupt dan state bergantian RX/TX. Konfigurasi frekuensi utamanya 921 MHz.
4. Membuat sketch diagnostik terpisah di `firmware/dual-radio-test/`. Driver utama NusaNode belum diubah untuk dua radio.
5. Membuat role A/B dari satu sketch, memisahkan interrupt RX/TX, mempertahankan RX breakout selama TX onboard, serta menambah timeout TX dan counter diagnostik.
6. Membuat wiring dan panduan upload; build kedua role berhasil pada 2026-10-22.
7. Menyusun dokumen ini pada 2026-10-27 untuk memudahkan kelanjutan pekerjaan.
8. Mengoreksi kesalahan akses fisik GPIO5/27 dari gambar user, lalu mengubah sketch dan wiring ke HSPI terpisah melalui pin header. Tidak mengubah firmware utama NusaNode.

`sf-variants/` merupakan hasil generator dari NusaNode utama. Jangan menjadikannya sumber implementasi dua radio atau mengedit semua salinannya secara terpisah.

## 3. Lokasi berkas

| Lokasi relatif dari root repo | Kegunaan |
|---|---|
| `firmware/NusaNode/` | Firmware relay utama; masih satu radio |
| `firmware/NusaNode/src/lora/nusa_radio.cpp` | Driver utama yang nantinya perlu mendukung dua radio |
| `firmware/NusaNode/config.h` | Pin dan parameter firmware utama |
| `firmware/sf-variants/` | Varian SF7-SF12 hasil generator |
| `firmware/tests/` | Pengujian transport/fragmentasi dan mobility yang sudah ada |
| `firmware/dual-radio-test/NusaDualRadio/NusaDualRadio.ino` | Sketch uji hardware dua radio saat ini |
| `firmware/dual-radio-test/README.md` | Petunjuk singkat wiring, build dan uji |
| `firmware/dual-radio-test/build/A/` | Hasil build lokal role A; diabaikan Git |
| `firmware/dual-radio-test/build/B/` | Hasil build lokal role B; diabaikan Git |

Sketch uji belum menjalankan BLE, payload aplikasi NusaMesh, fragmentasi, routing atau relay NusaNode. Meng-upload sketch ini mengganti program yang sedang berjalan di LilyGO dengan program diagnostik.

## 4. Pinout wiring

### Breakout Cosmic ke LilyGO

Tabel berikut adalah **pemetaan baru yang sudah diterapkan pada sketch**. Seluruh pin breakout tersedia pada header gambar user. Tidak memerlukan solder ke pad radio onboard. Pin onboard tetap pada bus aslinya.

Alokasi GPIO **sama untuk A dan B**. Tabel ini menunjukkan tujuan sinyal sketch, bukan bukti kompatibilitas listrik varian Cosmic. Foto terbaru memakai header daya 5V; level SPI/DIO dan varian 3.3V/5V/Dual masih perlu dikonfirmasi. Dukungan band frekuensi modul juga belum terverifikasi.

| Pin breakout SX1276 | Sambungkan ke LilyGO | Catatan |
|---|---|---|
| **5V** pada foto Cosmic | **Belum ditentukan** | Konfirmasi varian/petunjuk daya; jangan pakai instruksi 3V3 lama |
| GND | **GND** | Ground bersama |
| SCK / CLK | **GPIO14** | HSPI clock; microSD tidak dipakai |
| MISO | **GPIO39 / VN** | Input-only; pin 39 pada gambar, bukan 35 |
| MOSI | **GPIO13** | HSPI data out; microSD tidak dipakai |
| NSS / CS | **GPIO21** | Memakai pin OLED SDA; OLED tidak dipakai |
| RST / RESET | **GPIO22** | Memakai pin OLED SCL; OLED tidak dipakai |
| DIO0 | **GPIO34** | Input-only, digunakan untuk interrupt RX |
| DIO1-DIO5 | **Tidak disambungkan** | Sketch ini memakai continuous RX tanpa DIO1 |
| ANT | **Antena breakout sendiri** | Sesuaikan band antena dengan modul |

### Koneksi radio onboard yang dipakai sketch

Koneksi ini sudah ada di PCB LilyGO, tidak perlu dikabel ulang.

| Sinyal radio onboard | GPIO |
|---|---|
| SCK | 5 |
| MISO | 19 |
| MOSI | 27 |
| CS | 18 |
| RESET | 23 |
| DIO0 | 26 |
| DIO1 | 33 |

Catatan perakitan:

- Ikuti **label GPIO**, bukan nomor urutan kaki header. Cocokkan dengan revisi board fisik.
- GPIO13/14 terkait antarmuka microSD; keluarkan kartu dan jangan aktifkan SD.
- GPIO21/22 biasanya dipakai OLED SDA/SCL; jangan jalankan OLED/I2C pada konfigurasi ini. Sketch tidak menginisialisasi layar. Jika nanti layar perlu digunakan, rancang ulang alokasi pin sebelum mengaktifkannya.
- Jangan satukan CS, reset atau DIO0 kedua radio.
- Gunakan kabel SPI pendek; suplai daya harus sesuai varian Cosmic. Kapasitas suplai LilyGO untuk setup aktual belum diukur.
- Pasang antena masing-masing sebelum TX. Jangan menyatukan port antena dengan kabel T.
- Beri jarak antar-antena; kebutuhan filter dan isolasi RF ditentukan lewat pengujian.
- Foto Cosmic sudah memperlihatkan pin SPI, RESET, DIO0-DIO5, 5V dan GND. Varian listrik dan fungsi 5V EN belum dikonfirmasi. Jangan jumper 5V EN atau menghubungkan sinyal sebelum jelas apakah board kompatibel logika ESP32 3,3 V. Varian 5V-only memerlukan antarmuka level yang sesuai atau penggantian modul kompatibel.

Referensi skematik board yang dirujuk: [LilyGO T3 v1.6.1](https://github.com/Xinyuan-LilyGO/LilyGo-LoRa-Series/blob/master/schematic/T3_V1.6.1.pdf). Kecocokan unit fisik user tetap perlu diperiksa.

## 5. Role, frekuensi dan alur radio

| Node | `NUSA_ROLE_A` | Radio onboard TX | Breakout RX |
|---|---|---|---|
| A | `1` | **920,5 MHz** | **922,5 MHz** |
| B | `0` | **922,5 MHz** | **920,5 MHz** |

```text
Node A onboard TX  -- 920,5 MHz --> Node B breakout RX
Node A breakout RX <-- 922,5 MHz -- Node B onboard TX
```

Parameter bersama: SF7, BW125 kHz, CR4/5, sync word `0x12`, preamble 8, CRC aktif, TX 2 dBm, paket diagnostik 160 byte setiap 10 detik, SPI 4 MHz, timeout TX 2 detik dan serial 115200 baud. Tidak ada CAD di sketch uji.

Frekuensi ini contoh pengujian; belum merupakan hasil penilaian kepatuhan regulasi maupun jaminan isolasi RF. Jangan mengganti frekuensi hanya di salah satu sisi tanpa menyesuaikan penerima pasangannya.

## 6. Cara build dan flash

### Arduino IDE

1. Buka `NusaDualRadio/NusaDualRadio.ino` dari folder dokumen ini.
2. Pastikan ESP32 core dan RadioLib terpasang. Pilih **TTGO LoRa32-OLED**.
3. Untuk A, gunakan `#define NUSA_ROLE_A 1` di bagian atas sketch, lalu upload ke port LilyGO A.
4. Untuk B, ubah menjadi `#define NUSA_ROLE_A 0`, lalu compile/upload ulang ke port LilyGO B.
5. Buka Serial Monitor masing-masing pada **115200** dan periksa role serta frekuensi pada log boot.

Kedua board memakai sketch yang sama. Default file saat dokumentasi ini ditulis adalah role A. `NUSA_ROLE_A` juga dapat ditentukan lewat flag build CLI, yang mengesampingkan default dalam `#ifndef`.

### Arduino CLI

Jalankan dari root repo `D:\TAA\aloyaloy`. Perintah lengkap ada di [README](README.md#build-dan-upload). COM5/COM6 di sana hanya contoh; belum ada penetapan port perangkat user.

Jalankan build A dan B **berurutan**. Pada build awal, kompilasi bersamaan sempat bentrok pada cache dan hook ekspor lokal yang menulis ke folder sketch yang sama. Pemisahan build path dan pengulangan A secara terpisah menghasilkan build berhasil. Jangan mengubah konfigurasi Arduino global untuk mengatasi ini tanpa memeriksa kebutuhannya.

## 7. Hasil verifikasi yang benar-benar tersedia

| Pemeriksaan | Status |
|---|---|
| Build role A | Revisi HSPI berhasil 2026-10-27 |
| Build role B | Revisi HSPI berhasil 2026-10-27 |
| Target board | `esp32:esp32:ttgo-lora32` |
| ESP32 core / RadioLib | 3.3.12 / 7.8.1 pada build tersebut |
| Penggunaan program | 312515 byte masing-masing role, revisi HSPI |
| Global RAM | 23960 byte masing-masing role, revisi HSPI |
| Binary role A/B | Terpisah, hash berbeda pada pemeriksaan awal |
| Upload ke perangkat | Belum dilakukan dalam pekerjaan ini |
| Wiring Cosmic terkonfirmasi | Belum |
| Paket A ke B dan B ke A di hardware | Belum diuji |
| RX ketika TX lokal aktif | Belum dibuktikan di hardware |
| Pengujian jarak, daya, filter dan antena | Belum |
| Integrasi ke NusaNode/BLE/mesh | Belum |

Angka build di atas adalah hasil build ulang revisi HSPI pada 2026-10-27. Build awal 2026-10-22 masih memakai bus bersama (312375 byte program / 23928 byte RAM) dan telah digantikan. Kedua role dibuild berurutan dan hasilnya diperbarui di `build/A` serta `build/B`. Jangan menyebut build sukses sebagai bukti full-duplex RF atau kesiapan lapangan.

## 8. Prosedur uji berikutnya

1. Gunakan tabel HSPI baru di bagian 4 dan sketch terbaru, bukan binary lama bus bersama. Konfirmasi model LilyGO dan Cosmic; periksa wiring dalam keadaan daya mati. Keluarkan microSD dan jangan aktifkan OLED/I2C.
2. Rakit dua node dengan dua antena per node. Catat suplai dan jarak antar-antena.
3. Upload role A/B, pastikan keduanya mencapai `[READY]` dan frekuensinya silang.
4. Matikan TX lokal A dengan perintah `t`; ukur penerimaan paket dari B sebagai baseline A. Ulangi sebaliknya untuk baseline B.
5. Aktifkan TX kedua node. Catat nomor urut peer, jumlah paket diterima, CRC/error dan kehilangan paket.
6. Atur fase boot/aktivasi supaya pancaran kedua node bertumpang tindih. Tidak ada sinkronisasi jadwal otomatis. Perintah `s` meminta pengiriman saat TX diaktifkan dan jeda minimum 10 detik sudah terpenuhi; bukan perintah burst bebas.
7. Bandingkan penerimaan saat TX lokal OFF dan ON. Naikkan daya atau ubah susunan antena secara bertahap setelah baseline tercatat.

| Log / counter | Interpretasi |
|---|---|
| `[READY]` | Init kedua radio dan start RX berhasil menurut driver |
| `tx` | Transmisi lokal selesai menurut driver; bukan ACK dari penerima |
| `rx` | Paket diagnostik peer yang lolos pemeriksaan sketch |
| `seq` pada `[RX]` | Nomor percobaan pengiriman peer; gunakan bersama error TX peer saat menilai paket hilang |
| `rxIrqDuringTx` | Paket peer valid dengan IRQ RX teramati ketika TX lokal aktif dan IRQ TX_DONE belum teramati |
| `crc` | Paket terdeteksi tetapi CRC gagal |
| `txErr`, `rxErr` | Error operasi TX/RX |
| `rejected` | Paket diterima driver tetapi format/role/panjang tidak sesuai |

`rxIrqDuringTx` merupakan indikasi overlap menurut waktu interrupt firmware, bukan pengukuran RF presisi atau bukti seluruh paket overlap. Nol dapat disebabkan jadwal tidak overlap. CRC nol juga tidak menjamin bebas gangguan: paket yang tidak terdeteksi tidak menghasilkan CRC error. RX menggunakan satu FIFO dan flag event; paket beruntun masih bisa hilang.

Simpan log A/B dan hasil pengukuran di folder hasil uji bertanggal, dengan role, versi sketch, parameter radio, sumber daya, susunan antena dan kondisi lokasi. Jangan mengisi hasil sebelum pengujian nyata.

## 9. Rework setelah uji hardware

1. Jika receiver melemah saat TX lokal aktif, selesaikan isolasi RF, penempatan antena, suplai dan kebutuhan filter terlebih dahulu.
2. Setelah hardware terbukti, tambahkan mode dua radio ke sumber utama `firmware/NusaNode/`: konfigurasi TX/RX terpisah, objek driver terpisah, IRQ terpisah, timeout/recovery dan telemetri per radio.
3. Pertahankan layanan RX ketika TX aktif. Jika CAD dipakai, lakukan pada kanal TX melalui radio TX.
4. Pertahankan antrean prioritas, pembatasan airtime dan fragmentasi. Dual radio tidak menghilangkan batas kapasitas kanal.
5. Uji perubahan SF, antrean, reassembly, BLE dan relay sebelum membuat ulang varian SF dari sumber utama.
6. Tentukan arsitektur kanal untuk mesh banyak node sebelum mengklaim kompatibel. Semua node TX=X/RX=Y tidak saling mendengar; role silang A/B hanya menghubungkan pasangan/kelompok yang kanalnya cocok, bukan otomatis mesh bebas.

**Titik lanjut sekarang:** pastikan varian Cosmic 3.3V/5V/Dual dan fungsi 5V EN melalui link produk/model atau foto belakang; tetapkan suplai serta antarmuka level logika dahulu. Sesudah kompatibilitas listrik jelas, rakit HSPI pada header, flash A/B dan uji komunikasi dua arah serta RX saat TX lokal aktif. Bus onboard tetap internal; OLED dan microSD tidak digunakan. Binary bus bersama 2026-10-22 tidak sesuai wiring baru. Breakout SPI tidak perlu di-flash sendiri.

## 10. Cabang uji beban tanpa solder

Peta modul firmware utama dan status untuk melanjutkan pekerjaan ada di [STRUCTURE.md](../STRUCTURE.md). Implementasi BLE/LoRa dipisahkan di `NusaNode/src/` tanpa mengubah `.ino` utama atau konfigurasi.

User menunda node B dan pemasangan Cosmic, lalu meminta uji kirim beban tinggi dengan dua LilyGO yang tersedia. Dibuat [radio-stress](../radio-stress/README.md) dengan sketch TX generator dan RX counter, memakai radio onboard masing-masing dan frekuensi sama. Ini **uji satu arah**, terpisah dari konfigurasi kanal silang dual-radio di atas.

Target pengukuran: offered/admitted, drop antrean, TX selesai/error, penerimaan unik, serta waktu tunggu antrean. Antrean nol tidak boleh diklaim berhasil jika paket dibuang atau producer diperlambat. Belum ada hasil hardware; prosedur flash dan parameter run ada di README cabang tersebut. Kebutuhan dua radio per endpoint untuk pembuktian tautan full-duplex tetap berlaku.
