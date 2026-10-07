# Firmware Nusa Node per Spreading Factor (uji QoS)

Satu folder = satu SF yang **dikunci**. Untuk ganti SF, cukup flash folder lain.

| Folder | SF | Airtime frame penuh (255 B, BW125) |
|---|---|---|
| `NusaNode_SF7`  | 7  | ±0,4 s |
| `NusaNode_SF8`  | 8  | ±0,7 s |
| `NusaNode_SF9`  | 9  | ±1,3 s |
| `NusaNode_SF10` | 10 | ±2,3 s |
| `NusaNode_SF11` | 11 | ±5,0 s |
| `NusaNode_SF12` | 12 | ±9,0 s |

## Cara flash

1. Arduino IDE → **File → Open** → pilih `NusaNode_SF<n>/NusaNode_SF<n>.ino`.
2. Board **TTGO LoRa32-OLED** (`esp32:esp32:ttgo-lora32`).
3. Di `config.h` folder itu, atur **`NODE_ID`** (unik: 1, 2, 3, …) dan **`NODE_NAME`** untuk node yang akan di-flash.
4. Upload. OLED menampilkan `SF<n> 921.0 MHz` saat menyala.
5. **Flash semua node dengan SF yang sama** — node beda SF tidak saling mendengar.

Di varian ini SF **tidak bisa diganti dari app** dan **SF lama yang tersimpan di memori node diabaikan**,
jadi node pasti memakai SF folder yang di-flash. (Folder utama `../NusaNode` tetap bisa ganti SF dari app.)

## Mengubah kode

Folder di sini **dibuat otomatis** — jangan edit kodenya di sini (akan tertimpa). Ubah `../NusaNode/`, lalu:

```sh
cd firmware && ./make-sf-variants.sh
```

(`NODE_ID`/`NODE_NAME` di `config.h` varian juga ikut kembali ke nilai dari `../NusaNode/config.h`.)
