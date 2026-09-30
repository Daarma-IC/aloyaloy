# NusaOS — Firmware Nusa Node

Firmware gateway LoRa untuk **LILYGO LoRa32 T3 v1.6.1** (ESP32 + SX1276, 923 MHz).

Node menjembatani dua kluster user yang terlalu jauh untuk saling menjangkau
lewat BLE. HP tersambung ke node lewat WiFi; antar-node bicara lewat radio LoRa.

**Node adalah relay bodoh.** Payload NusaMesh lewat dalam keadaan terenkripsi
Noise. Node tidak memegang kunci, tidak menyimpan plaintext, dan tidak pernah
menjadi otoritas — ia menambah jangkauan, bukan wewenang.

---

## Dua cara HP menyambung ke node — pilih di `config.h`

```c
#define USER_LINK_BLE  1   // 1 = BLE (Opsi A, DEFAULT) · 0 = WiFi AP
```

**BLE (Opsi A) — default.** Node beriklan dengan Service/Characteristic UUID yang
**sama persis** dengan mesh BLE antar-HP. Akibatnya HP memperlakukan node sebagai
peer biasa dan **menyambunginya otomatis** — aplikasi tidak perlu diubah sama
sekali. Ini yang membuat skenario pos ronda jalan sendiri:

```
        pos ronda
     ┌─────────────┐
     │  Nusa Node  │──LoRa──► desa lain
     └──────┬──────┘
        BLE │
      A ────┤   (dekat, connect langsung)
            │
      B ────┘   (agak jauh, masih dapat)
      │
      C         (jauh dari node, TAPI dekat B → di-relay B)
```

C tidak perlu menjangkau node. Paketnya di-relay B — mekanisme relay mesh HP yang
sudah ada. Node cuma "HP yang punya uplink LoRa". Hemat daya (cocok solar), tapi
hanya ~3 HP bisa connect **langsung** (`BLE_MAX_CLIENTS`); sisanya lewat relay.
Mekanisme relay ini (nebeng + aturan antreannya) bagian dari HCMA — lihat
[`docs/HCMA.md`](../../docs/HCMA.md).

**WiFi AP.** Node jadi hotspot `NusaNode-<id>`, HP bicara WebSocket. Lebih banyak
HP connect langsung (~8), tapi boros daya dan HP harus pindah WiFi.

## Kebutuhan

| Komponen | Versi | Untuk mode |
|---|---|---|
| ESP32 Arduino core | ≥ 3.0 | keduanya — Board Manager `esp32 by Espressif` |
| RadioLib | ≥ 7.0 | keduanya — driver SX1276 |
| U8g2 | ≥ 2.35 | keduanya — OLED SSD1306 |
| **NimBLE-Arduino** | ≥ 2.0 | **BLE** — h2zero |
| WebSockets | ≥ 2.4 | WiFi — Links2004 |

Board target: **TTGO LoRa32-OLED** (`esp32:esp32:ttgo-lora32`).

> Mode dipilih saat kompilasi. Berkas modul yang tak dipakai (`nusa_ble.cpp` atau
> `nusa_link.cpp`) dilewati lewat `#if`, jadi library mode lain tidak ikut ter-link.

---

## Sebelum flash — WAJIB

### 1. Ubah `NODE_ID` per unit

```c
// config.h
#define NODE_ID     1              // node A
#define NODE_NAME   "NusaNode-A"
```

Unit kedua diberi `NODE_ID 2` / `"NusaNode-B"`. ID yang kembar membuat kedua
node beriklan SSID yang sama dan saling membingungkan di uji jarak.

### 2. Samakan parameter radio di semua node

```c
#define LORA_FREQ   921.0f
#define LORA_BW     125.0f
#define LORA_SF     7
#define LORA_CR     5
#define LORA_SYNC   0x12
```

Node dengan SF, BW, frekuensi, atau sync word berbeda **tidak saling mendengar
sama sekali** — bukan "sinyalnya lemah", tapi benar-benar tuli. Ini penyebab
paling umum "LoRa saya tidak jalan".

**Kenapa default SF7, bukan SF10.** Simulasi ns-3 (`contrib/nusamesh`) di beban
6 node/2 user per node menunjukkan latensi rata-rata turun dari ~83 s (SF10) ke
~1,1 s (SF7) dengan antrean nol-drop, dengan konsekuensi jangkauan per node
lebih pendek (~3,9 km vs ~8,4 km di model path-loss yang dipakai). Kalau uji
jarak di bawah menunjukkan SF7 tidak cukup jauh untuk topologi Anda, naikkan
SF sesuai bagian itu — tapi ingat, **semua node harus dipasang SF yang sama**.

### 3. Verifikasi regulasi

`LORA_FREQ` dan `LORA_POWER` diisi nilai konservatif (921.0 MHz, 14 dBm) sebagai
titik awal, **bukan** sebagai pernyataan bahwa nilai itu sah. Batas daya dan
rezim waktu-bicara di Indonesia harus dikonfirmasi ke dokumen Komdigi yang
berlaku sebelum perangkat dioperasikan di luar meja lab.

---

## Build & flash

### Arduino IDE

1. **Tools → Board → esp32 → TTGO LoRa32-OLED**
2. **Tools → Port →** port COM board Anda
3. Buka `NusaNode.ino` → **Upload**
4. **Tools → Serial Monitor**, baud `115200`

### arduino-cli

```bash
arduino-cli lib install RadioLib U8g2 NimBLE-Arduino WebSockets
arduino-cli compile --fqbn esp32:esp32:ttgo-lora32 firmware/NusaNode
arduino-cli upload  --fqbn esp32:esp32:ttgo-lora32 -p COM5 firmware/NusaNode
arduino-cli monitor -p COM5 -c baudrate=115200
```

### Tanda node hidup

```
=========================================
  NusaOS — NusaNode-A (node 1)
  LILYGO T3 v1.6.1 / SX1276 / 923 MHz
=========================================
[RADIO] SX1276 siap — 921.0 MHz SF7 BW125 CR4/5 14 dBm
[RADIO] airtime frame penuh (255 B) = 400 ms
[LINK] AP "NusaNode-1" aktif di 192.168.4.1, WebSocket port 81
[NODE] siap. Hop LoRa maks 3, batas airtime duty cycle
```

Bila muncul `[RADIO] init GAGAL`, hampir selalu pinout. Lihat bagian
Pemecahan Masalah.

---

## Uji jarak dengan 2 board — lakukan ini lebih dulu

Sebelum menyentuh aplikasi sama sekali, buktikan dulu radionya sampai. Ini
Fase 1 di roadmap, dan menentukan SF yang dipakai seterusnya.

**Langkah:**

1. Flash kedua board (ID berbeda), nyalakan keduanya.
2. Tekan tombol **BOOT** di masing-masing board → OLED berganti ke layar
   `UJI JARAK`. Keduanya mulai memancar beacon tiap 5 detik.
3. Tinggalkan satu node di Desa A. Bawa node kedua menjauh.
4. Baca OLED node yang dibawa:

```
UJI JARAK          12s
-94 dBm
SNR -7.5 dB
kirim:143  terima:139
```

**Cara membaca angka:**

| Bacaan | Arti |
|---|---|
| RSSI > −100 dBm, SNR > 0 | Tautan sehat, masih ada cadangan |
| RSSI −100…−115, SNR −5…0 | Batas aman, mulai kehilangan paket |
| RSSI < −120, SNR < −15 | Di luar jangkauan SF ini |
| `-- dBm` | Tidak ada beacon >30 detik — putus |

**Yang benar-benar penting adalah rasio `terima/kirim` node lawan**, bukan RSSI
sesaat. RSSI bagus dengan 40% paket hilang berarti ada interferensi, bukan
masalah jarak.

**Kalau tidak sampai:** naikkan `LORA_SF` (7 → 8 → … → 12) di **kedua** board
dan ulangi. Tiap tingkat menambah jangkauan tapi **menggandakan waktu pancar** —
pakai SF terendah yang masih andal, jangan yang tertinggi.

Catat hasilnya: SF yang dipilih di sini menentukan kapasitas seluruh sistem.

---

## Cara node bekerja

### User → LoRa

1. HP kirim paket biner NusaMesh lewat WebSocket.
2. Node hitung `msgId` = FNV-1a 64-bit atas paket, **dengan byte TTL dinolkan**
   (TTL berubah tiap hop; ikut dihitung berarti dedup gagal total).
3. Cek dedup. Duplikat langsung dibuang.
4. Siarkan ke user lain di node yang sama — ini gratis, tidak menyentuh radio.
5. Cek rate limit per user (~1 pesan/10 detik). Baru di sini airtime dibelanjakan.
6. Pecah jadi fragmen ≤243 byte → masuk antrean TX berprioritas.

### LoRa → User

1. ISR menandai frame masuk; kerja nyata dilakukan di `loop()`.
2. Fragmen dirakit ulang berdasarkan `msgId` + bitmap.
3. Setelah lengkap → dedup → siarkan ke semua user lokal.
4. Bila `hop > 1`, fragmen ulang dan antre lagi ke LoRa.

### Penjadwal TX

```
Ada frame di antrean?
  → Anggaran airtime cukup?      (token bucket, DUTY_RATIO)
      → Kanal bebas?             (CAD)
          → Jitter acak lewat?
              → TX  (node TULI selama 0,4–8 detik, tergantung SF)
```

**Duty cycle, bukan bitrate, adalah leher botol sesungguhnya.** Default SF7
memancar ~0,4 detik untuk satu frame penuh (255 B). Pada `DUTY_RATIO` produksi
(1%), itu berarti ~40 detik diam sesudahnya. Repo ini saat ini diset ke **5%
(TESTING)** di `config.h` — ~8 detik diam, jauh lebih longgar — supaya tes
multi-pesan tidak terasa lambat sekali; **jangan lupa turunkan ke `0.01`
sebelum dipakai di luar meja lab.** Karena itu setiap byte yang tidak perlu
adalah waktu bicara yang dicuri dari seluruh desa.

---

## Format frame LoRa (12 byte header)

```
[0]      versi(4 bit) | flags(4 bit)
[1]      hop tersisa
[2..9]   msgId  (uint64, big-endian)
[10]     indeks fragmen
[11]     total fragmen
[12..]   potongan paket NusaMesh
```

Payload per frame: `255 − 12 = 243 byte`.

**Flags:** `0x01` Link-ACK · `0x02` beacon uji jarak.

Yang sengaja tidak ada: `MAGIC` (LoRa sudah punya sync word di PHY) dan
`fragLen` (explicit header LoRa sudah membawa panjang). Keduanya mubazir, dan
pada tautan ~51 bps efektif tiap byte berarti.

**msgId 64-bit, bukan 32-bit.** Pada 32-bit, cache 10.000 pesan punya peluang
tabrakan ~1,2% — dan tabrakan berarti pesan sah dibuang diam-diam sebagai
duplikat. Gagal senyap seperti itu nyaris mustahil didiagnosis di lapangan.

---

## Menyambungkan aplikasi

Node menyediakan WebSocket biner. Dari sisi Android:

```
1. Sambungkan ke WiFi "NusaNode-<id>"  (WifiNetworkSpecifier, Android 10+)
2. bindProcessToNetwork(network)       → internet aplikasi lain tetap jalan
3. Buka ws://192.168.4.1:81/
4. Kirim/terima paket NusaMesh sebagai frame BINER
```

Node tidak menafsirkan isi paket — apa pun yang dikirim akan diteruskan apa
adanya. Jadi sisi app cukup memakai `BinaryProtocol` yang sudah ada.

### ✅ Dua bug yang dulu memblokir Fase 2 — sudah dibereskan

**1. Bug deduplikasi.** `SecurityManager.generateMessageID()` dulu memakai
`routed.peerID` — tetangga yang barusan meneruskan, bukan pengirim asli. Paket
yang sama lewat dua jalur menghasilkan dua ID berbeda, lolos dedup, lalu
di-relay ulang dua kali. Di BLE ini boros; di LoRa berduty-cycle ini fatal.
**Diperbaiki:** sekarang pakai `packet.senderID`.

**2. MessagePadding.** Blok 256/512/1024/2048 byte membuat pesan "ok" menjadi
256 byte — beberapa menit kebisuan sedesa untuk amplop kosong. App tidak bisa
mematikan padding secara selektif untuk Nusa Node (mode BLE Opsi A sengaja
membuat node tak terbedakan dari HP biasa di mata app), jadi perbaikannya
**di sisi firmware**: `nusaStripPadding()` di `nusa_appproto.h`, dipanggil di
`onPacketFromUser()` (`NusaNode.ino`) sebelum msgId/broadcast/fragmentasi.
Aman tanpa app tahu-menahu — begitu ukuran paket bukan lagi salah satu dari 4
blok itu, `MessagePadding.unpad()` di HP otomatis no-op.

Detail historis keduanya ada di `NUSAOS_BLUEPRINT.md` (Section 6.2 dan 7.2).

---

## Pemecahan masalah

| Gejala | Penyebab paling mungkin |
|---|---|
| `[RADIO] init GAGAL` | Pinout salah. Cek `LORA_CS/RST/DIO0` terhadap silkscreen board. |
| OLED gelap | Coba ubah `OLED_RST` ke `-1`, atau ke `16`. Revisi board berbeda-beda. |
| Radio hidup, tidak ada `[BEACON]` | SF/freq/sync word berbeda antar node. Samakan `config.h`. |
| Beacon terlihat, `RX` tidak naik | CRC gagal — di tepi jangkauan. Naikkan SF. |
| `antre=` terus membesar | Anggaran airtime habis. Normal bila trafik padat; kalau menetap, turunkan SF atau kurangi trafik. |
| `buang=` naik | Antrean penuh, frame prioritas rendah digusur. |
| HP tidak bisa connect WiFi | `AP_PASSWORD` minimal 8 karakter; batas `AP_MAX_CLIENTS`. |
| Tegangan baterai meleset | Kalibrasi faktor `1.05f` di `NusaUI::readBatteryVolts()` terhadap multimeter. |

---

## Peta berkas

| Berkas | Isi |
|---|---|
| `config.h` | Semua yang bisa disetel: pin, radio, airtime, AP |
| `nusa_frame.h` | Format frame, FNV-1a 64-bit, cache dedup |
| `nusa_radio.*` | Radio SX1276, hitung airtime, token bucket, CAD, antrean TX |
| `nusa_fragment.*` | Pecah & rakit ulang paket app |
| `nusa_link.*` | WiFi SoftAP + WebSocket + rate limit per user |
| `nusa_ui.*` | OLED: layar status & layar uji jarak |
| `NusaNode.ino` | Perekat semuanya + mode uji jarak |

---

## Yang belum ada

Sesuai roadmap `NUSAOS_BLUEPRINT.md`, ini Fase 2 — cukup untuk membuktikan
konsep, belum siap produksi:

- **Link-ACK & retry** — flag `0x01` sudah dicadangkan, logikanya belum ditulis.
  Broadcast memang tidak akan pernah di-ACK (ACK serentak = badai tabrakan);
  ini hanya untuk unicast antar-node.
- **Store & forward di node** — pesan yang gagal terkirim saat ini hilang.
- **MAC antar-node** — belum ada autentikasi frame; siapa pun dengan radio pada
  SF/freq yang sama bisa menyuntik frame.
- **Adaptasi SF otomatis** — SF dikunci statis. Adaptasi butuh koordinasi antar
  node (SF berbeda = tidak saling dengar), jadi ditunda ke Fase 4.
- **OTA aman** — dan jangan pernah OTA lewat LoRa; image ratusan kilobyte pada
  51 bps akan memblokir backbone berjam-jam.


## Voice note Codec2

Dukungan voice note Codec2 berada di Android; node meneruskan paket biner.
Firmware memakai penerimaan antrean seluruh fragmen sekaligus dan timeout
perakitan lebih panjang. Lihat [panduan Codec2](../../docs/CODEC2_NUSA_NODE.md)
untuk build, batasan transport, dan langkah uji perangkat.
