# LilyGO RX/TX radio member

Penguatan terbaru: [FIRMWARE_QOS.md](../FIRMWARE_QOS.md). Token LISTEN lama/berlawanan ditolak, CAD gagal dipisahkan dari start TX ambigu (UNKNOWN), dan main membaca error anggota. Build terbaru di `../build/output/firmware-qos-member/`; flash berpasangan dengan main terbaru, wire tetap v3.

Flash `NusaRxCompanion.ino` ke LilyGO kedua untuk mode default **`LORA_QUASI_DUPLEX=1`, `LORA_QUASI_TAKEOVER=1`, `LORA_FULL_DUPLEX=0`, `LORA_RX_BACKEND=1`** pada NusaNode utama. Kedua board membentuk satu hub. Anggota kini dapat RX/TX terkoordinasi, bergantian per frame. Eksperimen full tidak aktif.

Wiring: main TX13 -> companion RX34, companion TX13 -> main RX34, GND bersama. Masing-masing mendapat daya USB sendiri; jangan satukan pin daya kedua board. Tidak perlu Cosmic atau kabel SPI. Pin UART2 ini 3,3 V dan microSD tidak digunakan.

Anggota menunggu konfigurasi frekuensi/SF/daya dari main melalui UART 115200. Ia mulai standby, mendengarkan setelah RESUME, meneruskan payload + RSSI/SNR, dan mengonfirmasi PAUSE sebelum salah satu radio memancar. Perintah TX bertoken memiliki status BUSY/DONE/FAILED/UNKNOWN/REJECTED. Ini protokol UART **v3**: flash ulang main dan anggota, jangan campur v1/v2. Anggota tidak menjalankan BLE/WiFi/routing dan tidak memiliki NODE_ID mesh sendiri. Lihat [QUASI_TAKEOVER.md](../QUASI_TAKEOVER.md) untuk perilaku, batas, wiring dan pengujian.

`src/protocol/nusa_duplex_wire.h` merupakan salinan canonical dari NusaNode, disinkronkan oleh `../sync-radio-modules.ps1`. Jangan mengembangkan dua versi protokol secara terpisah.
