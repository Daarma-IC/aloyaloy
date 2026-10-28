# Titik lanjut percakapan — firmware, Codec2 dan dual LoRa

Diperbarui 28 Oktober 2026, workspace `D:\TAA\aloyaloy`. Ini ringkasan keputusan dan pekerjaan percakapan, bukan transkrip verbatim. Pengguna meminta catatan disimpan agar sesi berikutnya bisa melanjutkan dari file lokal. Baca kode, config dan `git status` aktual sebelum mengubahnya; hasil build di bawah adalah snapshot tahap implementasi, bukan tes yang diulang saat MD ini ditulis.

## Keinginan pengguna dan batas perubahan

- Prioritaskan firmware, terutama pengangkutan audio Codec2 dan koordinasi dua radio, sebelum menambah mekanisme aplikasi.
- Entry point tetap `NusaNode/NusaNode.ino`. Jangan mengubah pemanggilan library/setup/loop/callback pada INO utama maupun keenam INO varian SF. Kembangkan modul di `NusaNode/src/` lalu sinkronkan dengan `sync-radio-modules.ps1` tanpa menimpa identitas config.
- Jangan reset pekerjaan sebelumnya, mengaktifkan eksperimen full duplex, mengganti identitas/frekuensi atau menaikkan limiter/duty untuk mengklaim peningkatan performa.
- Pengguna akan menguji hardware nanti. Belum ada perintah flash, commit atau push otomatis pada penutupan percakapan ini.

## Keputusan dual LoRa yang berlaku

Awalnya dibahas SX1276 Cosmic dihubungkan ke LilyGo, lalu dua LilyGo agar tidak perlu solder breakout dahulu. Eksperimen full duplex/frekuensi silang ditunda; baseline yang dipertahankan adalah **quasi duplex dua radio pada kanal sama**.

| Board | Sketch | Tanggung jawab |
|---|---|---|
| LilyGo utama | `NusaNode/NusaNode.ino` | BLE atau WiFi, routing, antrean, satu identitas hub dan radio onboard RX/TX |
| LilyGo anggota | `NusaRxCompanion/NusaRxCompanion.ino` | Radio onboard RX/TX terkoordinasi UART; PHY dari main, tanpa NODE_ID mesh mandiri |

Kedua radio dapat RX dan TX, bukan pembagian permanen satu TX dan satu RX. Saat idle keduanya dapat mendengarkan. Sebelum satu radio memancar, RX lokal dijeda dengan PAUSE terkonfirmasi; tidak ada TX lokal kedua bersamaan. Saat sehat TX bergantian per frame; msgId/indeks fragmen tetap ketika berganti pemancar. Handover dilakukan antar-frame atau sesudah kegagalan sebelum TX yang diketahui aman dicoba kembali, bukan memotong pancaran aktif.

Satu hub memakai satu antrean dan satu budget airtime. Dua radio tidak menggandakan kapasitas kanal atau menghapus antrean/interferensi. Node ID berbeda tidak mencegah interferensi RF. Keinginan dua anggota aplikasi penuh yang sama-sama melayani BLE/WiFi belum terimplementasi: companion sekarang coprocessor radio. Target satu MCU + dua modul tetap rencana hardware; backend SPI tersedia tetapi bukan bukti Cosmic/catu/pinout spesifik sudah tervalidasi.

### Config dan wiring aktif

```cpp
#define LORA_FULL_DUPLEX      0
#define LORA_QUASI_DUPLEX     1
#define LORA_QUASI_TAKEOVER   1
#define LORA_RX_BACKEND       1
```

| Main | Anggota |
|---|---|
| GPIO13 TX UART2 | GPIO34 RX UART2 |
| GPIO34 RX UART2 | GPIO13 TX UART2 |
| GND | GND |
| USB sendiri | USB sendiri |

UART 115200; jangan satukan rail daya USB, jangan gunakan microSD pada GPIO13 bersamaan. Radio onboard memakai SPI internal, tidak perlu mengambil GPIO27 dari footprint. Flash main dan companion dari tahap yang sama, wire **v3**. Default quasi 921 MHz; cek frekuensi/SF aktual karena NVS dapat memengaruhi SF kecuali varian fixed. Label board 923 MHz bukan bukti frekuensi firmware aktual.

## Yang sudah diimplementasikan

1. Struktur BLE, WiFi, LoRa, routing, protokol dan UI modular; INO NusaNode tetap.
2. Jalur Codec2 Android bertahap: encoder/decoder persisten di HP, 1200 bps, 320 sampel/40 ms menjadi 6 byte. Bundle awal 16 frame/640 ms, selanjutnya sampai 36 frame/1,44 detik, batas rekaman 10 detik. NVS1 membawa urutan dan akhir/cancel; arsip lengkap berasal dari chunk yang sama. Capture/playback bertahap baru di Android, bukan bukti perangkat/iOS.
3. Firmware meneruskan chunk, bukan menjalankan encoder/decoder Codec2. Hint audio `0x08` ikut fragmen/relay, admission multi-fragmen atomik, reassembly dibatasi.
4. P1 admission HP–node: capability, ACCEPTED/BUSY/REJECTED/UNKNOWN, query/cache/dedup dan pelaporan kapasitas. ACCEPTED berarti admission pada lingkup yang dilaporkan, bukan RF/delivery ACK. Jalur HP asal melalui HP perantara masih memiliki batas penerusan admission; lihat dokumen P1.
5. Penguatan firmware terakhir: cadangan slot untuk enqueue mentah dan batch (bulk menyisakan 6, audio 2, teks 1, ACK 0); FIFO stabil ketika timestamp sama; aging tidak menaikkan bulk ke audio; satu giliran bulk terjangkau setelah tiga percobaan audio yang dibebankan airtimenya, sementara ACK/teks tetap lebih tinggi.
6. CAD dilakukan pemancar terpilih, busy kanal dibackoff bersama, error CAD memberi cooldown dan kesempatan radio lain. RX tetap aktif selama jitter dan waktu idle RX terkonfirmasi ikut memenuhi jendela berikutnya. Token LISTEN lama/berlawanan ditolak, rollover token/timer ditangani.
7. `startTransmit()` error, finish gagal atau timeout tidak dianggap sukses. Percobaan ambigu dibebankan airtime dan tidak direplay otomatis; standby/status pembatalan harus terkonfirmasi sebelum operasi berikutnya. Ini mencegah pengiriman ganda berbasis asumsi bahwa error berarti belum memancar.

Limiter dan pacing belum diganti: firmware BLE sekitar 0,3 paket aplikasi/detik per HP dengan burst 3; aplikasi pacing 3500 ms/burst 2. `DUTY_RATIO=0.05` masih bertanda TESTING. Codec2 150 byte codec/detik tidak sama dengan throughput aplikasi/RF sesudah pembungkus/enkripsi/fragmentasi. Performa end-to-end, suara pertama 2–3 detik dan nol antrean belum terbukti.

## Bukti terakhir dan batasnya

- **11 suite host firmware PASS**: framing, mode half/quasi/full eksperimental, takeover UART/SPI, scheduler, audio transport, admission dan mobility. Handler companion asli dipakai dengan stub UART/SPI. Rincian dan perintah di [FIRMWARE_QOS.md](FIRMWARE_QOS.md).
- Build main BLE: **707430 byte program / 76404 byte RAM**, output `build/output/firmware-qos-main/`.
- Build companion: **314031 / 23752**, output `build/output/firmware-qos-member/`.
- Build main WiFi dari profil terpisah: **1028478 / 88712**, output `build/output/firmware-qos-wifi/`. Config utama tetap BLE.
- Modul kanonis cocok pada enam varian SF. Hash ketujuh INO NusaNode tetap `C6B51D798F196B6073BD45F91679E50DFD53C4D297E0225974E4EC5C75AEC5CC`.
- Manifest lokal: `build/output/firmware-qos-main/validation.json`; output build/compiler/cache diabaikan Git dan mungkin tidak tersedia pada clone baru. Bangun ulang bila diperlukan.
- Tahap penguatan firmware tidak mengubah/membangun ulang Android. Hasil Android/native sebelumnya dipisahkan dalam dokumen Codec2/P1; jangan memindahkan angka lama menjadi bukti build baru.
- **Belum ada flash, pengukuran RF, tes mikrofon/speaker, instalasi APK pada perangkat atau bukti latensi baru.** Penulisan MD ini hanya memeriksa dokumentasi, bukan menjalankan ulang suite/build tersebut.

## Usulan terakhir yang belum diimplementasikan

Sesudah pengguna menanyakan pembaruan berikutnya, diusulkan diagnostik pengiriman yang lebih terperinci:

1. Alasan menunggu: antrean, budget airtime, CAD, PAUSE, companion stale/terputus.
2. Jejak frame: msgId, fragIdx, pemancar terpilih, waktu antre dan hasil DONE/REJECTED/UNKNOWN.
3. Statistik per kelas audio/bulk: masuk, terkirim, ditolak dan durasi antre.
4. Laporan ringkas kondisi node ke HP agar tidak selalu membuka serial.

**Keempat poin ini masih usulan.** Telemetri serial agregat dan laporan kapasitas P1 sudah ada, tetapi trace per-frame, statistik latensi per kelas, serta seluruh laporan HP baru tersebut belum dibangun. Pengguna menjawab akan tes nanti; jangan menandai usulan sebagai selesai atau menganggap ada hasil perangkat.

## Urutan lanjut

1. Baca catatan ini, walkthrough dan roadmap; periksa diff/config, versi build dan board/COM aktual. Pertahankan perubahan pengguna.
2. Saat hardware tersedia, flash main + companion berpasangan dan uji UART, giliran TX, serta putus/reboot anggota. Catat batas bukti jika hanya ada dua LilyGo.
3. Uji admission dari HP, kemudian teks → audio → gambar sambil audio dengan node lawan; simpan log kedua board dan hasil penerima. Dua LilyGo sekarang satu hub, sehingga end-to-end RF memerlukan minimal satu node tambahan; dua hub dual-radio lengkap memerlukan empat board.
4. Jika perlu pembaruan kode sebelum/selama uji, diagnostik di atas merupakan calon pekerjaan berikutnya. Tentukan lingkupnya dari permintaan pengguna dan bukti, tanpa mengubah INO.
5. Sesudah baseline perangkat tercatat, kerjakan **P2 kredit/backpressure** HP–node, lalu **P3 bundling audio adaptif**. Evaluasi kualitas RF/beban radio merupakan lanjutan P4; jangan sekadar menghapus limiter atau menaikkan bitrate/duty.

Checklist dan template laporan perangkat: [NEXT_STEPS.md](NEXT_STEPS.md). Bedakan BLE write, admission, TX_DONE dan delivery ACK. Diagnosis satu tahap tidak membuktikan tahap berikutnya berhasil.

## Peta MD

| Dokumen | Isi |
|---|---|
| [WALKTHROUGH.md](WALKTHROUGH.md) | Indeks pekerjaan, baseline, wiring, riwayat dan titik lanjut |
| [FIRMWARE_QOS.md](FIRMWARE_QOS.md) | Penguatan firmware terbaru, perilaku error, build dan pengujian |
| [NEXT_STEPS.md](NEXT_STEPS.md) | Roadmap, urutan kerja, checklist dan template hasil perangkat |
| [QUASI_TAKEOVER.md](QUASI_TAKEOVER.md) | Arsitektur quasi dua radio dan riwayat pengujian |
| [CODEC2_STREAMING.md](CODEC2_STREAMING.md) | Pipeline Android, format audio, kompatibilitas dan batas airtime |
| [ADMISSION_P1.md](ADMISSION_P1.md) | Kontrak admission HP–node dan batas interpretasinya |
| [STRUCTURE.md](STRUCTURE.md) | Struktur modul dan sinkronisasi |
| [NusaRxCompanion/README.md](NusaRxCompanion/README.md) | Peran dan sketch anggota |
| [dual-radio-test/WALKTHROUGH.md](dual-radio-test/WALKTHROUGH.md) | Riwayat eksperimen awal, bukan wiring/arsitektur aktif |

## Penyimpanan dan pemanggilan ulang

Pengguna sudah melaporkan push baseline sebelumnya; perubahan P1/penguatan firmware/MD setelahnya masih perlu ditinjau dan disertakan dalam commit/push pengguna. Jangan menganggap semuanya sudah ada di GitHub. `git status` dan file lokal menjadi sumber status aktual.

Proyek lokal, Arduino build dan flash tidak memerlukan langganan ChatGPT Plus. Akses/model/limit bantuan AI bergantung paket dan ketentuan saat melanjutkan; jangan menjanjikan model, sesi atau konteks percakapan yang sama. Catatan lokal ini memungkinkan sesi baru membaca kembali keputusan tanpa bergantung pada ingatan chat.

Prompt lanjut yang bisa dipakai:

> Lanjut proyek NusaNode di D:\TAA\aloyaloy. Baca firmware/SESSION_HANDOFF.md, firmware/WALKTHROUGH.md, firmware/FIRMWARE_QOS.md dan firmware/NEXT_STEPS.md, lalu periksa git status serta config aktual. Fokus firmware audio Codec2 dan dual LoRa quasi satu hub: main NusaNode + companion wire v3, satu antrean/budget, tidak TX bersamaan. Pertahankan ketujuh INO NusaNode dan identitas config. Pisahkan implementasi/PASS host dari hasil perangkat; diagnostik detail terakhir masih usulan, P2/P3 belum dikerjakan. Lanjut dari hasil tes/perintah terbaru saya, jangan reset pekerjaan lama atau flash/push tanpa instruksi.
