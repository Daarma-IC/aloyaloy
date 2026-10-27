# Half/full-duplex NusaNode: dua LilyGO sekarang, satu MCU + dua radio nanti

> Update aktif 27 Oktober 2026: [quasi dua radio dengan takeover dan perbaikan Codec2](QUASI_TAKEOVER.md). Bagian implementasi RX-only/target lama di bawah adalah riwayat; baca status terbaru sebelum melanjutkan.


**Mode aktif:** quasi duplex dua LilyGo dengan takeover per frame. Kedua radio dapat RX/TX, satu antrean dan satu anggaran airtime. Anggota kini bukan RX-only. Main tetap `NusaNode.ino`; anggota tetap `NusaRxCompanion.ino` dan wajib wire v3. Layanan BLE/WiFi/routing tetap di utama.

```cpp
#define LORA_QUASI_DUPLEX   1
#define LORA_QUASI_TAKEOVER 1
#define LORA_FULL_DUPLEX    0
#define LORA_RX_BACKEND     1
```

Wiring: GPIO13 utama ke GPIO34 anggota, GPIO13 anggota ke GPIO34 utama, GND bersama; USB masing-masing. Semua quasi memakai PHY yang sama (default 921 MHz). TX bergantian setelah ACK standby; pergantian hanya antar-frame. Audio Codec2 memiliki prioritas di atas gambar yang masih antre. Detail algoritme, batas, flash dan verifikasi terkini ada di [QUASI_TAKEOVER.md](QUASI_TAKEOVER.md).

Bagian berikut menyimpan pilihan dan hasil tahap sebelumnya. Keterangan receiver-only/wire v2 merujuk mode lama (`LORA_QUASI_TAKEOVER=0`) atau riwayat, bukan baseline aktif. Eksperimen full tetap nonaktif. Tidak ada klaim RF simultan, tanpa antrean, file 2 MB, atau latensi suara 2-3 detik.

## Pilihan di config.h

Entry point tetap **`NusaNode/NusaNode.ino`**. File itu tidak diubah. Mode dipilih saat kompilasi, kemudian upload ulang:

```cpp
#define LORA_FULL_DUPLEX 0  // 0 = half-duplex; 1 = TX dan RX independen
#define LORA_QUASI_DUPLEX 1 // dua radio bergantian ketika FULL=0
#define LORA_RX_BACKEND  1  // 1 = companion LilyGO lewat UART; 2 = radio SPI kedua
#define LORA_DUPLEX_SIDE 0  // 0 = sisi A; 1 = sisi B (kanal silang)
```

Default sekarang quasi dua radio. Untuk perangkat satu radio lama, set **QUASI=0 dan FULL=0**. `USER_LINK_BLE` tetap memilih BLE=1 atau WiFi=0 seperti sebelumnya. Tidak perlu mengubah include library, callback, setup atau loop pada `.ino` utama. Antrean, prioritas, fragmentasi, CAD dan pembatas airtime tetap berlaku. Full-duplex tidak menjamin antrean nol.

## 1. Half-duplex

Set `LORA_QUASI_DUPLEX=0` dan `LORA_FULL_DUPLEX=0`, flash NusaNode ke satu LilyGO per node. Radio onboard bergantian RX/TX pada `LORA_FREQ` (default 921 MHz). Companion tidak diperlukan. Ini kompatibel dengan desain satu radio sebelumnya pada parameter PHY yang sama.

## 2. Full-duplex dengan dua LilyGO untuk satu node

Set di NusaNode utama:

```cpp
#define LORA_FULL_DUPLEX 1
#define LORA_RX_BACKEND  1
#define LORA_DUPLEX_SIDE 0
```

| Board | Firmware yang di-upload | Fungsi |
|---|---|---|
| LilyGO utama | `NusaNode/NusaNode.ino` | BLE atau WiFi, routing, antrean, LoRa TX |
| LilyGO pendamping | `NusaRxCompanion/NusaRxCompanion.ino` | LoRa RX, meneruskan frame biner + RSSI/SNR melalui UART2 |

**Kedua LilyGO tersebut membentuk SATU node.** Companion tidak mengiklankan BLE/WiFi, tidak menjalankan relay dan tidak memancarkan RF. Ia menerima konfigurasi radio dari pengendali; frekuensi/SF tidak diatur manual pada sketch companion.

### Wiring antarkedua LilyGO

Semua pin berikut tersedia pada header board yang ditunjukkan user:

| LilyGO utama | LilyGO companion |
|---|---|
| **GPIO13 / TX UART2** | **GPIO34 / RX UART2** |
| **GPIO34 / RX UART2** | **GPIO13 / TX UART2** |
| **GND** | **GND** |

Gunakan kabel pendek; level UART ESP32 3,3 V. Tidak ada kabel SPI antar-board dan tidak perlu mengakses GPIO5/27 internal. Keluarkan kartu microSD karena GPIO13 dipakai UART. Pin ini tidak memakai GPIO21/22 OLED. Default OLED NusaNode tetap mati; tidak ada perubahan UI dalam fitur ini.

Untuk meja uji, beri daya masing-masing LilyGO dari USB sendiri. **Jangan hubungkan pin 5V/3V3 kedua board satu sama lain ketika keduanya mendapat daya USB.** Ground tetap disambungkan. Tidak memerlukan Cosmic, regulator Cosmic atau jumper 5V EN.

UART 115200 baud, 4096 byte buffer RX dan 2048 byte buffer TX. RX GPIO34 hanya input, sesuai fungsi UART RX. Kalau mengubah pin/baud di NusaNode, sesuaikan juga konfigurasi companion; baud bukan konfigurasi yang dapat dinegosiasikan lewat link yang belum tersambung.

### Frekuensi silang

| Node gabungan | Side | Radio utama TX | Radio companion RX |
|---|---|---|---|
| A (dua LilyGO) | `0` | 920,5 MHz | 922,5 MHz |
| B (dua LilyGO lain) | `1` | 922,5 MHz | 920,5 MHz |

Nilai ini parameter eksperimen, bukan penetapan kepatuhan regulasi atau bukti isolasi RF. Dua antena dekat tetap bisa mengalami desensitisasi. Gunakan daya yang sesuai setup uji dan ukur kehilangan paket saat TX lokal OFF/ON.

Dengan dua LilyGO total, baru node A yang lengkap. Untuk pembuktian tautan full-duplex dua arah A-B, diperlukan penerima dan pemancar di sisi B juga. Jangan menafsirkan tidak ada RX pada node A sebagai kerusakan jika belum ada sumber RF di 922,5 MHz. Mode ini juga tidak otomatis membuat semua node mesh pada satu kelas side saling mendengar; topologi kanal banyak node perlu perancangan tersendiri.

### Sinkronisasi dan kegagalan link

1. Main mengambil SF aktif dari konfigurasi/NVS, kemudian mengirim frekuensi RX, BW, SF, CR, sync dan preamble melalui frame UART COBS + CRC16.
2. Companion mengonfigurasi SX1276, tetap standby sampai RESUME, dan mengembalikan status dengan nomor transaksi serta digest konfigurasi.
3. Pada eksperimen full, main menahan TX sampai konfigurasi cocok dan RESUME dikonfirmasi. Pada quasi, TX justru menunggu PAUSE setelah kesempatan RX. BLE/WiFi tetap dapat berjalan; antrean dapat terisi/penuh selama receiver/link belum siap.
4. Status dikirim setiap 1 detik. Jika tidak ada status valid selama 3,5 detik, `healthy()` menjadi false dan TX baru ditahan. Transmisi yang sudah berjalan diselesaikan/dibatasi watchdog.
5. Main mengirim ulang konfigurasi saat link tidak siap. Companion yang restart bisa dikonfigurasi ulang tanpa restart main. Paket yang hilang selama restart tidak dipulihkan otomatis.
6. Perubahan SF dari aplikasi menunggu TX selesai, mengubah radio TX dan mengirim konfigurasi RX baru. TX ditahan lagi sampai companion mengonfirmasi. Node lawan tetap perlu memakai SF yang cocok; ini bukan koordinasi SF seluruh jaringan.

UART dibaca juga saat radio TX sedang memancar. Frame RX diteruskan ke callback yang sudah ada pada NusaNode; fragmentasi/routing dan BLE/WiFi tidak memerlukan perubahan `.ino`.

## 3. Satu MCU + dua radio SPI

Set `LORA_FULL_DUPLEX=1`, `LORA_RX_BACKEND=2`. Radio onboard/tambahan TX memakai `LORA_*` asli; radio RX tambahan memakai HSPI:

| Radio RX eksternal | GPIO ESP32 |
|---|---|
| SCK | 14 |
| MISO | 39 |
| MOSI | 13 |
| CS / NSS | 21 |
| RESET | 22 |
| DIO0 | 34 |
| DIO1 | Tidak digunakan |

Backend ini memakai pin OLED 21/22 dan sebagian microSD; OLED harus disabled (diperiksa saat compile) dan microSD tidak dipakai. UART companion tidak aktif. Tegangan catu/level logika modul harus sesuai hardware final; foto Cosmic 5V sebelumnya belum cukup untuk menyatakan kompatibel langsung dengan ESP32. Tabel ini mengatur sinyal, bukan menentukan sambungan daya Cosmic.

## Log dan batas pengukuran

- `[DUPLEX] HALF` atau `FULL backend=...` menunjukkan mode hasil kompilasi.
- `companion ready=1` menunjukkan konfigurasi UART dikonfirmasi, bukan bukti RX RF dari node lawan.
- `rxReady`, `wireBad`, `wireDrop`, `txErr` dan `q` membantu memisahkan kegagalan link internal dan antrean.
- `rxDeliveredDuringTx` menghitung frame mentah yang diteruskan backend ke driver radio ketika main menganggap TX masih aktif, sebelum validasi header NusaFrame. Ini bukan jumlah pesan aplikasi atau ACK. Pada UART ada penundaan pengiriman; ini **bukan bukti waktu RF overlap yang presisi**.
- `txCount` tetap TX selesai menurut driver, bukan ACK bahwa node lawan menerima paket.
- Buffer UART, FIFO radio dan antrean tetap terbatas. `wireDrop` menunjukkan frame RX yang tidak berhasil diantrekan ke UART companion. Counter CRC/drop dari companion dapat mulai ulang ketika companion restart.
- CRC dan framing UART melindungi pembacaan paket dari gangguan/truncation. Tidak ada retry payload RX atau penyimpanan persisten yang ditambahkan.

## Build dan pemeliharaan

Build main half/full dan companion dengan board `esp32:esp32:ttgo-lora32`. Main memerlukan RadioLib, NimBLE-Arduino (mode BLE) dan U8g2; companion hanya RadioLib dan core ESP32. Jangan meng-upload image half saat mengharapkan full; periksa log mode setelah boot.

Contoh build tanpa mengubah default config:

```powershell
arduino-cli compile --fqbn esp32:esp32:ttgo-lora32 --build-property compiler.cpp.extra_flags=-DLORA_FULL_DUPLEX=1 firmware/NusaNode
arduino-cli compile --fqbn esp32:esp32:ttgo-lora32 firmware/NusaRxCompanion
```

Untuk pengujian SPI backend, tambahkan `-DLORA_RX_BACKEND=2` pada extra flags. Jalankan build yang memakai sketch main yang sama secara berurutan agar hook ekspor lokal tidak bentrok. Identitas port COM belum ditetapkan; tidak ada upload otomatis dalam perubahan ini.

Protokol UART canonical berada di `NusaNode/src/protocol/nusa_duplex_wire.h`. Jalankan `firmware/sync-radio-modules.ps1` setelah mengubahnya; script menyalin protokol ke companion dan implementasi utama ke enam varian SF, sambil mempertahankan `.ino` dan konfigurasi per unit. Generator SF lengkap juga menyalin protokol companion, tetapi tetap mereset konfigurasi varian sesuai perilaku sebelumnya.

**Status:** implementasi menambahkan pilihan mode dan dua backend RX. Belum ada bukti hardware full-duplex, pengujian jarak atau hasil chat BLE/WiFi end-to-end. Bukti build/test dan keterbatasannya dicatat setelah validasi perangkat lunak; jangan menggantinya dengan klaim antrean nol.

## Riwayat validasi sebelum koordinasi quasi v2

| Pemeriksaan | Hasil |
|---|---|
| NusaNode half, default BLE | Build berhasil: 696070 byte program / 71916 byte RAM |
| NusaNode full + UART companion, default BLE | Build berhasil: 698434 byte program / 72340 byte RAM |
| Firmware NusaRxCompanion | Build berhasil: 312695 byte program / 23728 byte RAM |
| Driver radio full + SPI kedua, sketch validasi minimal | Build berhasil: 308474 byte program / 30404 byte RAM; bukan build seluruh aplikasi BLE pada backend SPI |
| Framing UART | Semua ukuran payload 0..272 dan tiga pola biner lolos round-trip; CRC standar, truncation, overflow, resync dan batas konfigurasi diperiksa |
| Driver UART | ACK/digest salah menahan TX; RX diteruskan saat TX belum selesai; SF menunggu TX dan ACK baru; stale link menahan TX; retry, timeout dan start failure diperiksa |
| Driver SPI | RX dibaca/rearm selama TX aktif; radio TX tidak dialihkan ke RX |
| Regresi half dan mobility | Suite audio transport dan mobility yang sudah ada lolos |
| `.ino` utama | SHA256 tetap `C6B51D798F196B6073BD45F91679E50DFD53C4D297E0225974E4EC5C75AEC5CC` |

Target build ESP32 `esp32:esp32:ttgo-lora32`, core 3.3.12, RadioLib 7.8.1; aplikasi utama BLE memakai NimBLE-Arduino 2.5.1 dan U8g2 2.36.19. Mode WiFi belum dibuild pada fitur ini. Lima suite software memakai stub radio/UART dan compiler host Zig 0.15.2 yang disimpan lokal pada `firmware/build/tools/`; archive diverifikasi SHA256 terhadap index resmi Zig. Tidak ada compiler yang di-install global. Satu warning variabel penghitung full-duplex tidak dipakai pada test half tidak menggagalkan test.

Jalankan ulang tes dengan `powershell -File firmware/tests/run_duplex_tests.ps1`, atau tentukan `-Compiler` menunjuk `g++.exe`/`zig.exe`. Runner tidak mengunduh compiler otomatis. Hasil test host ada di `firmware/build/host-tests/`. Binary ESP32 lokal ada di `firmware/build/output/half`, `full-uart`, `companion` dan `full-spi`; semuanya artefak build yang diabaikan Git.

**Belum dilakukan:** upload, sambungan UART fisik, RX RF saat TX lokal ON/OFF, isolasi antena, uji beban chat BLE/WiFi, ataupun penerimaan node B. Dua LilyGO total belum membuktikan tautan full-duplex dua endpoint. Counter/dummy software tidak menggantikan pengukuran perangkat.

## Validasi software quasi v2

Tes host mencakup framing biner/CRC, eksperimen full UART/SPI, quasi UART/SPI, regresi transport satu radio dan mobility. Tes quasi secara khusus memeriksa RX window, larangan TX sebelum PAUSE ACK, penolakan token lama, prioritas chat, RX kembali aktif, serta reconnect yang tidak mengizinkan TX hanya berdasarkan ACK konfigurasi. Tes adalah simulasi driver, belum hasil perangkat.

Runner: `powershell -File firmware/tests/run_duplex_tests.ps1`. Runner audio shell juga memilih QUASI=0 secara eksplisit untuk regresi mode satu radio. Satu warning penghitung full tidak dipakai pada mode satu radio tetap tidak menggagalkan test. Hasil build terbaru dicatat setelah kompilasi; image eksperimen full lama bukan image quasi v2.

Hasil akhir revisi quasi v2 (2026-10-27): **tujuh suite host lolos**, mencakup quasi UART dan quasi SPI, serta regresi lima suite sebelumnya. Build aplikasi main quasi UART berhasil (**699326 byte program / 72356 byte RAM**); companion v2 berhasil (**312987 byte program / 23736 byte RAM**), target ESP32/core/library sama seperti riwayat di atas. Image lokal terbaru ada di `firmware/build/output/quasi-uart` dan `firmware/build/output/quasi-companion-v2`.

Hash tujuh `.ino` asli tetap sama; konfigurasi keenam varian SF sudah memilih QUASI=1/FULL=0 dan modul/protokol disinkronkan. Tidak ada upload, hasil RF, pengujian chat perangkat atau pembuktian throughput baru. `NusaNode.ino` tetap entry point.
