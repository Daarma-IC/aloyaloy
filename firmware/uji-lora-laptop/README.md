# Uji LoRa dari laptop

Alat uji mandiri untuk membuktikan gambar dan voice note NusaMesh benar-benar
sampai lewat LoRa. Tidak memakai dan tidak mengubah firmware NusaNode.

```text
Laptop (Python)
   │ USB serial
   ▼
LilyGO TX ── LoRa ──► LilyGO RX
                         │ USB serial
                         ▼
                   Laptop yang sama
```

## Isi folder

| Berkas | Fungsi |
|---|---|
| `UjiLoraLaptop/UjiLoraLaptop.ino` | Firmware, sama untuk kedua board |
| `laptop/uji_lora.py` | Script kirim, terima, dan rekap hasil |

## Yang diuji

- **Gambar** dikompres persis seperti app (`LoraImageCodec`): preset Cepat
  160 px/2 KB, Seimbang 256 px/4 KB, Detail 384 px/8 KB, atau ukuran
  bebas seperti `512:16000`.
- **Suara** memakai voice note asli dari app (format NC2, Codec2 1200 bps,
  sekitar 150 B per detik). Bila belum ada, tersedia suara sintetis dengan
  ukuran yang sama untuk menguji pengiriman saja.
- Data dipecah menjadi frame 255 byte (header 12 + isi 243, sama dengan
  NusaNode). Radio default sama dengan NusaNode: 921 MHz, SF7, BW125,
  CR4/5, sync word 0x12. Daya default 2 dBm untuk uji di meja.
- Pengiriman mematuhi duty cycle (default 5%). Frame yang hilang dapat
  dikirim ulang (`--perbaiki`).
- Bukti valid: berkas yang diterima dibandingkan byte per byte dengan yang
  dikirim (kolom `isi_sama` dan `sha256`).

## Persiapan

1. Dua LilyGO T3 v1.6.1, **antena terpasang sebelum dinyalakan**.
2. Python 3 dengan paket berikut:

   ```powershell
   pip install pyserial pillow
   ```

3. Flash firmware ke kedua board. Ganti `COM5` dengan port board:

   ```powershell
   cd D:\TAA\aloyaloy\firmware\uji-lora-laptop
   $fqbn = "esp32:esp32:ttgo-lora32"

   arduino-cli compile --fqbn $fqbn UjiLoraLaptop
   arduino-cli upload --fqbn $fqbn -p COM5 UjiLoraLaptop
   ```

   Ulangi `upload` untuk board kedua dengan port lainnya. Firmware
   NusaNode di board akan tertimpa; flash ulang NusaNode setelah uji.

## Menjalankan

Masuk ke folder script dan cek port:

```powershell
cd D:\TAA\aloyaloy\firmware\uji-lora-laptop\laptop
python uji_lora.py port
```

Coba alur tanpa board (frame hilang acak 10%):

```powershell
python uji_lora.py uji --simulasi --hilang 0.1 `
    --gambar foto `
    --suara-sintetis 10 30
```

Uji nyata, kedua board di laptop yang sama:

```powershell
python uji_lora.py uji --tx COM5 --rx COM6 `
    --gambar foto `
    --suara suara `
    --ulang 3
```

Opsi yang sering dipakai:

| Opsi | Arti | Default |
|---|---|---|
| `--preset` | `cepat,seimbang,detail` atau `SISI:BYTE` | ketiganya |
| `--ulang` | ulangi seluruh daftar N kali | 1 |
| `--duty` | batas duty cycle | 0.05 |
| `--perbaiki` | putaran kirim ulang frame hilang | 2 |
| `--daya` | daya pancar dBm, 2..14 | 2 |
| `--sf`, `--bw` | spreading factor, bandwidth kHz | 7, 125 |

Script menampilkan perkiraan durasi sebelum mulai. Ctrl+C menghentikan
uji; hasil yang sudah ada tetap tersimpan.

### Dua laptop

Laptop penerima:

```powershell
python uji_lora.py terima --rx COM6
```

Laptop pengirim (tanpa kirim ulang karena tidak ada jalur balik):

```powershell
python uji_lora.py kirim --tx COM5 --gambar foto
```

Cocokkan `sha256` di `diterima.csv` dengan `hasil.csv` pengirim.

### Voice note asli dari HP

Rekam beberapa voice note di app, lalu salin lewat kabel USB. Hanya untuk
APK debug, dengan USB debugging aktif:

```powershell
python uji_lora.py ambil-suara --keluar suara
```

## Hasil

Tersimpan di `hasil/<tanggal-jam>/`:

- `hasil.csv`: satu baris per kiriman, berisi ukuran, jumlah frame, frame
  hilang di putaran pertama, putaran kirim ulang, utuh atau tidak, waktu
  sampai, airtime, RSSI, dan SNR.
- `*_kirim.webp|nc2` dan `*_terima.webp|nc2`: berkas asli dan hasil
  terima, untuk dibandingkan atau diuji AI di Colab.
- `ringkasan.json`: jumlah frame CRC rusak dan frame asing.

`waktu_sampai_s` mencakup jeda duty cycle, jadi itu waktu yang dirasakan
pengguna, bukan sekadar airtime.

## Batasan

- Mengukur jalur radio LoRa. BLE, enkripsi, antrean, dan prioritas
  NusaNode tidak ikut diuji, jadi ukuran paket app sedikit lebih besar.
- Frekuensi dibatasi 920-923 MHz dan daya maksimal 14 dBm. Batas resmi
  daya dan duty cycle tetap harus dicek ke aturan Komdigi.
- Uji di meja dengan daya rendah tidak mewakili jangkauan. Untuk uji jarak,
  naikkan `--daya` sesuai aturan dan catat posisi kedua board.
