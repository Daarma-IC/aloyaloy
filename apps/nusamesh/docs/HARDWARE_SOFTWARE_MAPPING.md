# Pemetaan endpoint NusaNode ↔ aplikasi

Firmware tidak menyediakan REST endpoint. "Endpoint" koneksinya adalah satu
service dan satu characteristic BLE GATT.

| Arah | Endpoint | Operasi | Isi |
|---|---|---|---|
| Scan | Service `A1B2C3D4-E5F6-7890-1234-567890ABCDEF` | advertisement | Menemukan NusaNode |
| Scan | Service data UUID yang sama | read advertisement | `peerId` node, tepat 8 byte |
| App → node | Characteristic `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D` | WRITE / WRITE_NR | Satu paket aplikasi v2 utuh |
| Node → app | Characteristic yang sama | NOTIFY | Satu paket aplikasi v2 utuh |
| Subscribe | Descriptor `0x2902` | enable notification | Memicu ANNOUNCE node |

Urutan koneksi:

1. App scan dengan filter Service UUID.
2. App memilih sinyal terkuat dan connect.
3. App meminta MTU 517 di Android; iOS memakai MTU hasil negosiasi sistem.
4. App menemukan service dan characteristic.
5. App mengaktifkan NOTIFY melalui CCCD.
6. Firmware mengirim ANNOUNCE; app mengirim ANNOUNCE untuk menyinkronkan jam
   node dari timestamp epoch ponsel.
7. App menulis paket; node broadcast ke pengguna lokal lalu memfragmentasi
   paket untuk LoRa. Di tujuan, node merakit dan NOTIFY ke ponsel.

## Header aplikasi v2

| Offset | Ukuran | Field |
|---:|---:|---|
| 0 | 1 | versi `0x02` |
| 1 | 1 | tipe paket |
| 2 | 1 | TTL aplikasi |
| 3 | 8 | timestamp epoch ms, big-endian |
| 11 | 1 | flags; bit 0 berarti recipient tersedia |
| 12 | 4 | panjang payload, big-endian |
| 16 | 8 | sender ID |
| 24 | 8 opsional | recipient ID |
| berikutnya | variabel | payload |

Tipe yang dipakai UI saat ini: `0x01` ANNOUNCE, `0x04` MESSAGE, dan `0x18`
LORA_HEALTH. Payload health adalah `[neighborCount][RSSI int8][SNR int8 × 0.5]`.

Panjang satu GATT write dibatasi hasil negosiasi MTU. Firmware menganggap satu
write sebagai satu paket utuh, sehingga aplikasi menolak pesan yang melebihi
kapasitas GATT. Lampiran besar perlu memakai tipe fragment aplikasi
`0x05/0x06/0x07`; ini berbeda dari fragmentasi LoRa internal firmware.

