# Uji beban dua LilyGO tanpa solder

Peta firmware BLE/LoRa, keputusan struktur dan titik lanjut keseluruhan ada di [STRUCTURE.md](../STRUCTURE.md). Sketch stress ini tetap terpisah dari relay utama.

Tujuan: mengukur offered load, antrean, drop sebelum radio, penyelesaian TX dan penerimaan nyata. Ini generator paket sintetis, **belum uji chat/BLE NusaNode dan bukan pembuktian full-duplex**. Dua LilyGO ini berfungsi sebagai sumber dan penerima satu arah. Tidak ada Cosmic, kabel SPI antar-board atau pengubahan pin header.

## Perangkat dan flash

- LilyGO pertama: flash `NusaRadioStress/NusaRadioStress.ino` dengan `NUSA_ROLE_A 1` sebagai TX.
- LilyGO kedua: sketch sama, `NUSA_ROLE_A 0` sebagai RX.
- Board Arduino: **TTGO LoRa32-OLED**, RadioLib, serial **115200**. Masing-masing terhubung USB ke laptop dan memiliki antena sesuai band.
- Keduanya 920,5 MHz, SF7, BW125, CR4/5, CRC aktif, daya awal 2 dBm. Jarak antar-antena perlu diperhatikan agar penerima tidak overload.
- Tidak ada TX otomatis saat boot. Perintah harus diakhiri newline.
- Saat pemeriksaan awal terdeteksi port COM16 dan COM17, tetapi identitas board/role belum ditetapkan. Jangan menganggap COM tersebut selalu sama. Upload akan mengganti firmware lama; belum dilakukan oleh agent.

Kode menggunakan SPI onboard GPIO5/19/27, CS18, RST23, DIO0=26, DIO1=33. Itu koneksi internal PCB, tidak perlu diakses lewat jumper.

## Langkah pengujian

1. Nyalakan RX dulu, pastikan `[READY]`, lalu TX dan pastikan role benar.
2. Buka dua Serial Monitor, 115200 baud, newline aktif. Simpan log masing-masing.
3. Di RX, kirim `reset` sebelum tiap percobaan untuk mereset seluruh counter error.
4. Di TX, jalankan baseline:

   ```text
   run 100 400 200
   ```

   Artinya tawarkan 100 paket, interval 400 ms, panjang 200 byte. Ini bukan jaminan bebas antrean: periksa counter. Tunggu `[DONE]` di TX dan beri RX sekitar 2 detik, lalu `status` di kedua board.

5. Uji overload:

   ```text
   run 1000 1 200
   ```

   Menawarkan 1000 paket pada interval 1 ms. LoRa tidak mengirim 1000 paket/detik; producer membebani antrean dan mengukur berapa yang ditolak.

6. Uji burst serentak:

   ```text
   run 1000 0 200
   ```

   Semua paket ditawarkan sebelum antrean dikuras. Kapasitas antrean 32: 32 diterima antrean dan 968 ditolak, kemudian paket yang diterima dikirim. Ini sengaja menunjukkan bahwa membatasi antrean bisa menghasilkan banyak drop, bukan pengiriman tanpa kehilangan.

7. Coba paket pendek `run 1000 1 32`, atau variasikan interval 10/50/100/200/400 ms. Tunggu selesai sebelum run berikutnya; jangan mengganti parameter SF/timeout tanpa menghitung ulang airtime.
8. Tukar peran kedua board bila perlu, dengan flash ulang role. Ini tetap dua uji satu arah yang terpisah, bukan full-duplex simultan.

Untuk beban berkelanjutan (bukan burst pendek), gunakan `run 4096 50 200`: producer menawarkan paket selama sekitar 205 detik sambil radio menguras antrean. Akan ada drop bila kapasitas radio tidak mencukupi. `run 1000 320 200` mencoba laju dekat airtime SF7 untuk paket ini selama sekitar 320 detik; ukur hasil, jangan menganggap interval 320 ms otomatis menjamin semua sampai. Pada mode 1 ms atau 0 ms, run 1000 tidak berarti 1000 paket dipancarkan: periksa admitted/txDone.

Perintah lain: `status`, `stop` pada TX, `reset` pada RX. `stop` membatalkan antrean dan paket yang belum ditawarkan, tetapi membiarkan paket RF yang sedang berjalan selesai. Percobaan yang dihentikan tidak memakai persamaan accounting run selesai normal.

## Membaca hasil

| Counter | Arti |
|---|---|
| `run` | ID percobaan acak, harus sama di log TX/RX |
| `offered` | Paket yang dibuat generator |
| `admitted` | Paket yang berhasil masuk antrean |
| `dropQueue` | Paket yang langsung ditolak karena antrean 32 penuh |
| `txDone` | TX selesai menurut driver; bukan ACK dari penerima |
| `txErr`, `timeout` | Kegagalan/timeout TX; timeout juga termasuk txErr |
| `q`, `peak` | Isi antrean saat ini dan puncaknya; tidak termasuk paket yang sedang TX |
| `waitMaxMs`, `waitMeanMs` | Waktu tunggu dalam antrean sampai mulai percobaan TX, tidak termasuk airtime/latensi app |
| RX `unique` | Jumlah nomor urut unik yang diterima dan lolos CRC/validasi pola |
| RX `duplicate` | Paket dengan nomor urut yang sudah diterima pada run yang sama |
| RX `crc`, `rxErr`, `bad` | Error sejak reset RX; tidak semuanya dapat diatribusikan ke run tertentu |

Pada `[DONE]` normal: `offered = admitted + dropQueue` dan `admitted = txDone + txErr`. Cocokkan `run`, lalu bandingkan RX `unique` dengan TX `txDone`. Selisih adalah paket yang tidak teramati RX terhadap penyelesaian TX; counter driver bukan pengukuran RF presisi. Paket yang sama sekali gagal terdeteksi tidak menaikkan CRC error. Jika RX tidak menerima satu paket pun, ia tidak dapat mengetahui run ID: simpan fakta RX tetap nol bersama log TX.

Rasio end-to-end = `unique / offered`. Rasio terhadap TX selesai = `unique / txDone`. Jangan membagi unique dengan target lalu menyebut semua kehilangan sebagai masalah RF: paket `dropQueue` belum pernah masuk radio.

**Antrean nol tidak otomatis berhasil:** jika drop tinggi atau producer menunggu TX sebelum menawarkan paket berikutnya, beban sebenarnya tidak terbukti tertangani. Ukur loss dan laju output bersama waktu tunggu. Full-duplex juga tidak menambah kapasitas satu pemancar untuk mengirim banyak paket sekaligus.

Sebagai pembanding teoritis: paket radio 200 byte pada SF7/BW125/CR4-5, explicit header, CRC dan preamble 8 memakai airtime sekitar **317,7 ms** (rumus airtime LoRa). Batas ideal sekitar **3,15 paket/detik** sebelum overhead driver. Karena itu interval producer 1 ms sengaja jauh melebihi kapasitas radio; jangan menafsirkannya sebagai pancaran satu paket setiap 1 ms.

RX memakai satu FIFO radio dan satu flag IRQ, bukan penerima multikanal. Pengujian ini mengukur keseluruhan jalur radio/driver di sketch, bukan memisahkan setiap penyebab kehilangan. Tidak ada ACK/retry, autentikasi, enkripsi, CAD atau duty pacing. Run dibatasi maksimum 4096 paket, panjang 16..240 byte, interval 0..60000 ms. Karena TX dapat berjalan beruntun, lakukan uji beban dalam setup RF lab terkontrol sesuai batas operasi yang berlaku; jangan memakai generator ini sebagai firmware produksi.

## Menjalankan dari laptop

## Jalankan dan simpan log otomatis

Sesudah kedua role di-upload, tutup Arduino Serial Monitor supaya port tidak terkunci. Contoh di bawah hanya jika identitas COM16=TX dan COM17=RX sudah dikonfirmasi:

```powershell
powershell -ExecutionPolicy Bypass -File firmware/radio-stress/run-stress.ps1 -TxPort COM16 -RxPort COM17 -Count 100 -IntervalMs 400 -PacketBytes 200
powershell -ExecutionPolicy Bypass -File firmware/radio-stress/run-stress.ps1 -TxPort COM16 -RxPort COM17 -Count 1000 -IntervalMs 1 -PacketBytes 200
```

Skrip memeriksa role dari respons firmware, menghentikan run lama, mereset RX, menjalankan run terbatas, menunggu DONE, lalu menyimpan `tx.log`, `rx.log` dan `run.json` dalam `results/<timestamp>/`. Skrip akan memancarkan paket setelah role terkonfirmasi. Hasil timeout bukan run berhasil; log parsial tetap disimpan. Belum diuji dengan perangkat.

### Perintah build

Jalankan **berurutan** dari root repo; contoh port belum menetapkan identitas perangkat:

```powershell
$stressBuild = Join-Path $PWD 'firmware/radio-stress/build'
arduino-cli compile --jobs 8 --fqbn esp32:esp32:ttgo-lora32 --build-path "$stressBuild/work-TX" --build-property compiler.cpp.extra_flags=-DNUSA_ROLE_A=1 --output-dir "$stressBuild/TX" firmware/radio-stress/NusaRadioStress
arduino-cli upload --fqbn esp32:esp32:ttgo-lora32 -p COM16 --input-dir "$stressBuild/TX" firmware/radio-stress/NusaRadioStress
arduino-cli compile --jobs 8 --fqbn esp32:esp32:ttgo-lora32 --build-path "$stressBuild/work-RX" --build-property compiler.cpp.extra_flags=-DNUSA_ROLE_A=0 --output-dir "$stressBuild/RX" firmware/radio-stress/NusaRadioStress
arduino-cli upload --fqbn esp32:esp32:ttgo-lora32 -p COM17 --input-dir "$stressBuild/RX" firmware/radio-stress/NusaRadioStress
```

## Status

Validasi 2026-10-27: kedua role berhasil build untuk `esp32:esp32:ttgo-lora32`, ESP32 core 3.3.12 dan RadioLib 7.8.1. TX: 331459 byte program / 23992 byte RAM; RX: 330551 byte program / 23968 byte RAM. Binary tersedia lokal di `build/TX` dan `build/RX` (diabaikan Git). Parser PowerShell dan konstruksi objek serial tanpa membuka port juga diperiksa; komunikasi serial aktual belum diuji.

Belum di-upload dan belum ada hasil pengujian hardware. Setelah diuji, simpan log TX/RX, ID run, parameter, identitas port/board, sumber daya dan susunan antena dalam folder hasil bertanggal. Jangan menganggap contoh perintah sebagai hasil pengukuran.
