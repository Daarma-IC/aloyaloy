# P1: konfirmasi paket masuk antrean node

Implementasi 28 Oktober 2026. P1 menambahkan pengamatan/penerimaan paket; **belum P2 kredit/backpressure dinamis**, bukan bukti RF atau penerimaan tujuan. `NusaNode.ino` tetap tidak diubah.

## Perubahan yang terlihat di aplikasi

- Di gelembung pesan keluar, di bawah progres suara/lampiran: menunggu konfirmasi, diterima antrean node/lokal beserta jumlah paket, BUSY dengan alasan, REJECTED dengan alasan, atau belum terkonfirmasi.
- Pada detail ukur saat gelembung digeser: laporan terakhir slot bebas, SF, estimasi airtime antrean, sisa budget, kesiapan radio, dan saran tunggu bila tersedia. Snapshot ini bukan penghitung waktu real-time atau ETA sampai tujuan.
- ACK penerima akhir tetap indikator terpisah dan lebih kuat. Persentase pada node yang mendukung P1 mengikuti paket aplikasi yang dikonfirmasi admission; jumlah ini bukan jumlah frame Codec2 atau frame RF.
- Cakupan UI P1 adalah link HP yang tersambung langsung ke node. Pada jalur nebeng, HP perantara dapat memeriksa admission node untuk paket titipan, tetapi laporan tersebut belum diteruskan balik ke UI HP asal; UI asal tetap memakai status transport ke perantara sampai ACK tujuan tersedia.
- Node lama tetap dapat dipakai. Hasil BLE sukses ditulis sebagai transport diterima, **antrean node belum terkonfirmasi**, bukan disamakan dengan admission.
- Pada suara bertahap, konfirmasi tiap chunk dipetakan ke satu gelembung voice note. Total paket yang diajukan bertambah selama rekaman; 1/1 saat chunk pertama bukan berarti rekaman sudah selesai.

## Negosiasi dan protokol lokal

Firmware menambahkan byte capability bernilai 1 pada offset 7 payload LORA_HEALTH. Tujuh byte awal tetap kompatibel. Aplikasi hanya mempercayainya dari koneksi node langsung, sender yang cocok, TTL 1 dan paket yang diterima guard. Tidak mengirim probe tipe baru ke firmware lama yang tidak mengiklankan capability; ini mencegah kontrol probe ikut diteruskan ke LoRa oleh node lama.

Protokol HP–node ini **versi 1**, berbeda dari UART wire v3 antara main dan anggota radio. Anggota tidak perlu perubahan protokol UART pada P1.

| Tipe | Kegunaan |
|---|---|
| 0x1B | Kontrol lokal HELLO/QUERY, TTL 1, recipient node |
| 0x1C | Respons capability/admission lokal, TTL 1, recipient HP |
| Flag aplikasi 0x10 | Meminta admission untuk paket data setelah capability/HELLO terkonfirmasi |

HELLO memakai ID sesi koneksi 64 bit bukan nol dan mengikat penerima respons ke HP pemilik sesi koneksi. Paket titipan dapat memiliki sender ID HP lain; respons admission tetap ditujukan ke HP perantara yang menulis ke node, bukan ke pengirim asli yang tidak tersambung langsung. QUERY membawa ID sesi dan request ID. Request ID adalah FNV-1a 64 bit paket yang padding-nya dilepas, TTL dinolkan, dan flag tracking 0x10 dihapus. Fingerprint tidak menambah envelope ukuran paket; payload aplikasi dan enkripsinya tidak diubah. Flag tracking dihapus sebelum handler lama, broadcast lokal, dedup atau fragmentasi RF, sehingga tidak mengubah msgId radio baseline.

Respons payload 43 byte, big endian:

| Offset | Isi |
|---|---|
| 0 | Versi 1 |
| 1 | Kind: 0 capability, 1 hasil admission/query |
| 2–9 | Sesi HP |
| 10–17 | Request ID |
| 18 | Hasil: 0 UNKNOWN, 1 ACCEPTED, 2 BUSY, 3 REJECTED |
| 19 | Alasan |
| 20 | Scope: 0 tidak ada, 1 antrean radio, 2 antrean notify lokal |
| 21 | Bit 0: radio ready |
| 22–23 | Slot TX bebas |
| 24–25 | Maksimum paket tracking, saat ini 512 byte |
| 26–29 | Estimasi airtime antrean, ms |
| 30–33 | Budget airtime bertanda, ms; bisa negatif |
| 34–37 | Saran retry-after, ms; 0 berarti tidak diketahui |
| 38 | SF aktif |
| 39–42 | Boot epoch node |

Alasan membedakan rate limit, antrean penuh, ukuran, format, duplikat yang hasil lamanya tak diketahui, notify lokal gagal, jalur tidak teramati, sesi berubah, request tidak ditemukan dan tabel konfirmasi penuh. Capability/status lokal tidak masuk RF atau guard/relay sebagai pesan pengguna.

## Arti admission dan ketahanan respons

- **ACCEPTED/RADIO_QUEUE:** enqueue seluruh fragmen paket aplikasi berhasil atomik. Ini belum TX_DONE dan belum ACK penerima.
- **ACCEPTED/LOCAL_NOTIFY:** routing memilih jalur lokal dan setidaknya satu transport notify menerima pengiriman lokal. Ini bukan jaminan aplikasi tujuan sudah membaca/merakit pesan.
- **BUSY:** paket belum diterima oleh antrean yang dilaporkan. Angka 0,3 paket/detik, burst dan kebijakan airtime tetap berlaku.
- **REJECTED:** ukuran/format tracking tidak sesuai. Header yang terlalu pendek untuk korelasi aman diabaikan; aplikasi berakhir UNKNOWN, bukan menerima sukses palsu.
- **UNKNOWN:** tidak ada bukti admission, misalnya respons hilang, sesi berubah, cache kedaluwarsa, atau paket sudah pernah dilihat tetapi admission lamanya tidak diketahui. UNKNOWN tidak berarti paket pasti gagal atau pasti berhasil.

Firmware menyimpan paling banyak 64 hasil selama 60 detik. Cache tidak menggusur hasil aktif untuk mengakui permintaan baru: saat penuh, respons BUSY. Permintaan sama yang sudah tercatat dapat dijawab ulang tanpa enqueue, broadcast, atau konsumsi token ulang. QUERY hanya membaca hasil; tidak memicu pengiriman data/RF. Node dapat mengonfirmasi salinan request yang diketahui sudah diterima dari klien lain tanpa menduplikasi RF.

Observer callback menggunakan konteks per task/thread. Pekerjaan radio/relay di task lain tidak dapat membuat hasil admission sukses bagi paket HP yang sedang diperiksa. Cache dilindungi critical section; cache dedup juga dilindungi antar-task. Bila admission tracking gagal setelah dedup baru dicatat, pencatatan barunya dibatalkan; entri duplikat yang sudah ada tidak dihapus. Jalur legacy tetap memakai perilaku lama.

Jika respons data tidak muncul dalam 2 detik, aplikasi melakukan **satu QUERY**, lalu menunggu hingga 2 detik lagi. Data tidak dikirim ulang otomatis, termasuk bila hasil write BLE tidak pasti. Disconnect menutup permintaan tertunda; epoch berbeda menghasilkan UNKNOWN sesi berubah. Hasil BUSY/REJECTED menghentikan batch yang belum dikirim dan tetap terlihat di UI. Pengiriman ulang/penjadwalan otomatis saat BUSY menjadi P2; QUERY atas hasil BUSY tidak mengubahnya menjadi percobaan baru. Cache bersifat sementara dan bukan receipt penyimpanan permanen.

## Lokasi implementasi

| Bagian | Berkas |
|---|---|
| Dispatch/control/cache status | `NusaNode/src/protocol/nusa_admission.*` |
| Observer admission per callback | `NusaNode/src/protocol/nusa_admission_trace.h` |
| Dedup rollback dan kunci antar-task | `NusaNode/src/protocol/nusa_frame.h` |
| Capability health | `NusaNode/src/protocol/nusa_appproto.h` |
| Targeted reply/rate/notify | `NusaNode/src/ble/nusa_ble.cpp`, `src/wifi/nusa_link.cpp` |
| Bukti atomic enqueue dan airtime snapshot | `NusaNode/src/lora/nusa_radio.*` |
| Jalur lokal routing | `NusaNode/src/routing/nusa_mobility.cpp` |
| Klien/korelasi/query dan label | aplikasi `mesh/protocol/NodeAdmission*.kt` |
| Negosiasi perangkat dan event hasil | aplikasi `mesh/engine/MeshEngine.kt` |
| Pemetaan status ke pesan/chunk | aplikasi `AppController.kt`, `data/MeshRepository.kt`, `domain/Models.kt` |
| Gelembung dan detail ukur | aplikasi `ui/ChatScreen.kt` |

## Cara uji di perangkat

1. Bangun/flash **main** dengan firmware P1 dan pasang APK baru. Anggota tetap menggunakan sketch wire v3 yang kompatibel; P1 tidak mengubahnya. Catat config/identitas/PHY aktual.
2. Tunggu health/capability node diterima. Kirim teks/voice note; lihat WAITING lalu jumlah ACCEPTED antrean pada gelembung. Geser gelembung untuk snapshot kapasitas.
3. Bandingkan dengan firmware lama: pengiriman masih memakai jalur konservatif, label antrean belum terkonfirmasi. Jangan membaca centang transport sebagai sukses antrean.
4. Uji rate limit/antrean penuh: alasan BUSY harus terlihat, tanpa hasil admission sukses bagi sebagian fragmen. Pacing tetap belum diganti.
5. Uji respons hilang/disconnect/reboot: QUERY tidak menambah frame data, status bisa UNKNOWN dan tidak diklaim sebagai penolakan pasti. Cocokkan log `NODE_ADMISSION_*`, log UART/main serta penerima RF.
6. ACK penerima akhir hanya setelah pesan/arsip benar-benar lengkap. Jangan menyimpulkan RF berhasil hanya dari admission atau notify.

Format saat ini tidak menambah per-request notifikasi TX_DONE ke HP. Telemetri RF dan ACK tujuan tetap terpisah; pekerjaan P2 masih diperlukan untuk kredit kapasitas yang dapat direservasi, pacing adaptif, fairness dan retry terkontrol. Tidak ada target latensi baru yang diklaim.

## Validasi

- Sepuluh suite host firmware lulus. Suite admission meliputi capability, admission atomik saat antrean hampir penuh, rollback dedup saat BUSY, query respons hilang, dua klien, callback lokal, ukuran/format salah, isolasi thread, reconnect/expiry, tabel cache penuh, dedup konkuren dan recipient sesi untuk paket titipan.
- 167 unit test aplikasi lulus, 0 gagal/error/skipped: enam tes protokol/klien admission dan satu integrasi engine baru, ditambah 160 tes terdahulu. Query tidak mengulang DATA, BUSY tidak menjadi sukses, node lama tidak menerima probe, sesi/epoch lama ditolak, dan disconnect/timeout tidak mengarang ACCEPTED.
- Firmware utama BLE berhasil dibangun: **705822 byte flash / 76252 byte RAM**. Profil WiFi terisolasi berhasil: **1026902 / 88560**; config utama tetap BLE. Library WebSockets 2.7.2 dipasang lewat Arduino Library Manager untuk memvalidasi jalur WiFi yang sebelumnya belum memiliki dependensi lokal.
- Arduino core ESP32 3.3.12, NimBLE-Arduino 2.5.1 dan RadioLib 7.8.1 digunakan. Anggota radio tetap memakai wire v3; protokol UART tidak berubah.
- Android `testDebugUnitTest` dan `assembleDebug` berhasil. APK debug: `apps/nusamesh/composeApp/build/outputs/apk/debug/composeApp-debug.apk`. Output main: `firmware/build/output/admission-p1/`; profil WiFi uji: `firmware/build/output/admission-wifi/`. Folder output diabaikan Git dan bukan release produksi.
- Semua 7 file INO tetap SHA256 `C6B51D798F196B6073BD45F91679E50DFD53C4D297E0225974E4EC5C75AEC5CC`. Modul semua varian SF disinkronkan dengan sumber utama, tanpa mengganti identitas/config pengguna.

Uji host memakai UART/SPI stub dan integrasi aplikasi memakai FakeRadio; belum ada flash, pemasangan APK, pengujian WiFi perangkat, bukti RF atau bukti latensi baru. Tahap berikutnya validasi perangkat P1, lalu P2 untuk kredit/pacing dinamis dan penerusan laporan ke HP asal bila memakai nebeng. Perubahan sesi ini belum di-commit/push.
