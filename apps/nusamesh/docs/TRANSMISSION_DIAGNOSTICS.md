# Diagnostik transmisi BLE / LoRa

Buka **Pesan**: daftar percakapan tampil lebih dulu. Panel **Kualitas jaringan** berada di bawah daftar dengan jarak 28 dp dan tertutup secara default. Ketuk **Buka** untuk grafik gabungan QoS LoRa & BLE, atau **Tutup** untuk merapikannya lagi. Dari grafik, ketuk **Detail** untuk grafik yang dapat dijeda, filter device, **Metrik** (ringkasan/ekspor CSV), dan **Log** (event mentah). Navbar tetap Pesan, Beranda, dan Peta. Pencatatan berjalan selama engine aktif walau panel ditutup; log sesi awal/pengajuan tanpa koneksi juga dicatat. Jeda tampilan tidak menghentikan pengukuran. Filter layer, device, warning/error, atau cari message ID untuk mengikuti perjalanan pesan. CSV log dan CSV metrik dapat disimpan lewat pemilih dokumen platform.

## Tahap yang diukur

- BLE: scan RSSI (maksimal sekali per device per 10 detik), percobaan koneksi, koneksi siap, putus, MTU, RSSI read, byte RX, write mulai/hasil, durasi callback. Android mencatat alasan GATT: callback status, timeout, penolakan stack, MTU atau subscription tidak tersedia. Platform lain tetap mencatat hasil Boolean send yang tersedia.
- Antrean: batch diterima/ditolak, jumlah fragmen/pending, dequeue dan waktu tunggu tiap fragmen, batch selesai/gagal. `modeled_airtime_ms` adalah estimasi model NodeQueue, bukan bukti radio memancar. Antrean node aktif tampak pada ringkasan.
- Mesh: decode gagal, guard drop (own, TTL, umur, kosong), duplikat, reassembly dan relay.
- Pesan: ID pengajuan, pengajuan ulang ID yang sama, hasil lokal, penerimaan, ACK cocok/tambahan/tidak cocok. Berkas mencatat ukuran tanpa nama/isi.
- LoRa: NODE_HEALTH mengukur tetangga terkuat node, bukan sinyal paket tertentu. RX_META hanya menghasilkan metrik RSSI/SNR/SF paket bila hash msgId cocok; mismatch/metadata kedaluwarsa dicatat terpisah. Pengukuran adalah frame terakhir yang melengkapi paket, bukan rata-rata seluruh fragmen.

## Arti angka QoS

| Angka | Definisi |
|---|---|
| Stack BLE OK / gagal | Jumlah pemanggilan send selesai true / false, termasuk fragmen, kontrol dan relay. Send dibatalkan dicatat terpisah. Bukan jumlah pesan atau LoRa TX. |
| Penerimaan stack BLE (%) | OK / (OK + gagal) x 100. Tidak memasukkan write yang belum selesai/dibatalkan. |
| TX / RX byte BLE | Byte wire (termasuk header/padding/telemetri) yang diterima stack BLE / masuk aplikasi. Bukan goodput payload LoRa. |
| Laju BLE rata-rata sesi | TX byte BLE dibagi durasi sesi yang teramati, termasuk waktu idle. |
| BLE p50 / p95 | Durasi send selesai sukses (256 sampel terakhir), termasuk antrean GATT internal platform. |
| Antre p95 | Sejak batch diterima antrean aplikasi sampai fragmen mulai dikirim (256 sampel terakhir). |
| RTT ACK p50 / p95 | Waktu monotonic lokal dari pengajuan pertama message ID sampai ACK penerima pertama. Termasuk antrean, perjalanan pergi, jeda ACK dan perjalanan balik. ACK dari penerima tambahan tidak menggandakan hitungan pesan. |
| Pesan terlacak tanpa ACK | Pesan belum memperoleh ACK; bukan estimasi packet loss. Sebagian jenis telemetri tidak mengirim ACK. |

Ringkasan global dan per-device menggunakan sesi runtime sekarang. Pemilihan device memfilter log; ringkasan global tetap berlabel sesi, metrik per-device ada di bagian Metrik lengkap dan CSV. Maksimal 64 device terbaru disimpan pada ringkasan. Korelasi ACK maksimal 2.048 message ID; eviction ditampilkan, ACK yang tidak lagi bisa dipasangkan tidak diklaim sebagai delivery. Broadcast tidak mempunyai jumlah penerima yang diketahui, sehingga PDR/loss trafik umum tidak ditampilkan sebagai angka palsu. Uji paket bernomor yang sudah ada pada menu QoS tetap terpisah. Total pada probe adalah total yang diumumkan pengirim; untuk pengujian yang dihentikan/gagal di tengah jalan, cocokkan dengan CSV pengajuan/hasil BLE pengirim sebelum menyebut rasio itu PDR.

Firmware saat ini **belum** mengirim per-packet LoRa TX_DONE/reject/airtime aktual atau retry radio. Maka BLE accepted dan batch complete tidak boleh ditampilkan sebagai konfirmasi pancar LoRa. RX_META membuktikan jalur penerimaan, ACK membuktikan konfirmasi aplikasi penerima; keduanya bukan ACK radio per-hop. Pengajuan ulang ID yang sama bukan otomatis retry LoRa.

## Riwayat dan ekspor

Log maksimal 1.000 event, terbaru menggantikan terlama. Riwayat dipersist setiap 2 detik lewat KeyValueStore; force kill mendadak dapat kehilangan ekor log terakhir. Kegagalan penyimpanan ditampilkan, data di memori masih bisa diekspor dan penyimpanan dicoba ulang. Setiap runtime mempunyai session_id baru; riwayat sesi sebelumnya tetap dapat dicari/diekspor, tetapi counter sesi baru mulai nol.

Hapus log menghapus riwayat event setelah konfirmasi, bukan counter sesi atau korelasi ACK yang masih menunggu. Counter dan distribusi durasi tidak dihitung ulang dari log yang sudah terpangkas. CSV mengandung session ID, epoch timestamp, event/layer/level, device/message ID, bytes, duration, RSSI/SNR/SF dan detail. CSV metrik menjelaskan batas pengukuran dan jumlah sampel. String device diperlakukan sebagai teks agar tidak menjadi formula spreadsheet. Tidak mencatat payload chat/media, koordinat, atau kunci operasi.

## Verifikasi

`TransmissionDiagnosticsTest` mencakup clock jump, ACK pertama/tambahan/tidak cocok, retention, clear saat ACK masih menunggu, denominator BLE, CSV/JSON, batas korelasi dan kegagalan penyimpanan. `MeshEngineTest` memeriksa integrasi pengiriman/ACK serta callback progres/selesai lampiran suara saat sukses, tanpa jalur, dan koneksi terputus melalui radio simulasi. `DiagnosticChartsTest` memeriksa pemisahan device/sesi, metadata LoRa yang cocok, retensi grafik, dan skala kurva dengan satuan berbeda (termasuk RSSI negatif dan sampel konstan/kosong). Test simulasi tidak menggantikan uji BLE/LoRa fisik. Browser preview tidak menyediakan radio BLE.

Preview browser mendukung unduhan CSV log/metrik, tetapi tidak menyediakan pengukuran radio BLE/LoRa. Untuk menguji UI: `node tools/diagnostics/check-ui.mjs` setelah server preview siap.

## Grafik gabungan di Pesan

Satu kartesian menampilkan beberapa kurva dengan waktu bersama hingga lima menit sesi aktif: hasil BLE OK/gagal (paket/10 detik), laju wire TX/RX (B/s), RTT ACK dan waktu antre (ms), RSSI BLE/LoRa (dBm), serta SNR LoRa (dB). Chip parameter dapat dinyalakan/dimatikan bersamaan dan digeser horizontal. Sumbu Y 0?100% menunjukkan posisi dalam rentang masing-masing kurva, bukan skor kualitas jaringan. Legenda menyebut batas skala asli, nilai sampel, satuan dan timestamp per parameter agar satuan yang berbeda tidak dicampur. Ketuk plot untuk membaca sampel terdekat beserta waktunya; Jeda membekukan tampilan. Jumlah hasil kirim dan laju dikelompokkan per 10 detik, bucket terakhir memakai durasi teramati minimal satu detik. Grafik dan log mengikuti filter device; ringkasan metrik tetap berlabel seluruh sesi.

Tidak ada data simulasi di runtime. Grafik kosong menjelaskan pengukuran yang belum tersedia. NODE_HEALTH atau metadata LoRa yang hash-nya tidak cocok tidak dimasukkan sebagai sinyal per paket. Riwayat yang terpangkas/dihapus membatasi awal plot agar data hilang tidak dianggap nol trafik. Garis tidak disambung melewati jeda sampel lebih dari satu menit.

## Loading voice note

UI setelah rekaman selesai memakai kartu persiapan dengan ikon mikrofon, judul Rekaman selesai, dan loading bar indeterminate selama kompresi. Setelah berkas siap, bubble suara memiliki kartu pengiriman: status, badge codec, jalur, persentase besar, progress bar 8 dp, perkiraan waktu, dan jumlah fragmen. Bar dianimasikan mengikuti perubahan progres asli; menunggu penerima dan konfirmasi diterima tampil di kartu yang sama. Persentase kompresi tidak dibuat-buat.

[Pratinjau desain interaktif](previews/voice-transfer.html) menyediakan contoh lima tahap. Angka pada pratinjau adalah fixture desain yang terpisah dari runtime app dan bukan hasil pengukuran radio.

Setelah Selesai rekam, composer menampilkan spinner menyiapkan/mengompresi suara selama callback encoding belum selesai. Setelah file siap, bubble menampilkan spinner menunggu giliran kirim (progres 0), lalu mengirim suara beserta persentase fragmen dan durasi tunggu/kirim. Selesai/gagal mengikuti callback transport yang sebenarnya; timestamp selesai dicatat terpisah dari waktu pengajuan. Selesai transport BLE belum membuktikan TX radio LoRa atau penerimaan di HP tujuan. Error encoding menutup indikator dan menampilkan alasan. Rekaman/Codec2 dan transmisi fisik perlu diuji pada Android dengan node nyata.

### Perkiraan sisa kirim dan konfirmasi suara

Setelah encoding menghasilkan berkas, bubble menampilkan perkiraan sisa kirim dan jumlah fragmen. Untuk node langsung, forecast mensimulasikan antrean lokal yang sama: prioritas/FIFO/aging, token bucket, kredit airtime, SF node, serta durasi write BLE yang terukur. Forecast tidak mengonsumsi kredit dan tidak mengubah urutan pengiriman. Dihitung ulang saat antrean berubah dan selama menunggu (maksimal 500 ms per recheck). Jika SF belum dilaporkan, UI menyebut asumsi SF7. Untuk BLE langsung, perkiraan tersedia setelah write pertama, memakai rata-rata waktu write dan jeda antar-fragmen. Bila broadcast melalui beberapa koneksi, dipakai jalur lokal yang diperkirakan selesai paling lambat.

Ini perkiraan **selesai keluar dari HP**, bukan janji waktu tiba di penerima. Antrean firmware, hop LoRa berikutnya, kehilangan paket, dan penerima offline tidak dapat ditentukan dari antrean HP. Jika nebeng, UI menjelaskan batas estimasi hanya sampai HP perantara. Selama encoding/kecepatan BLE belum terukur, tampil menghitung; forecast yang kedaluwarsa atau mencapai nol tidak mengubah status menjadi terkirim/diterima. Jeda kirim, sinyal, SF, dan trafik baru dapat mengubah estimasi.

Voice note kini memakai DELIVERY_ACK yang sudah ada. Timestamp FILE_TRANSFER disamakan dengan timestamp pengajuan UI agar ID `file-<timestamp>-<sender>` identik di kedua sisi. App penerima baru mengirim ACK setelah berkas direassembly, bisa dibuka, dan lolos filter channel. Setelah kirim lokal selesai, bubble menunggu konfirmasi; nama penerima dan waktu konfirmasi lokal muncul hanya setelah ACK diterima. ACK membuktikan berkas utuh diterima app, bukan suara sudah diputar, bukan semua anggota broadcast sudah menerima. Kedua HP perlu versi app yang mendukung ACK suara; app lama tetap bisa menerima suara tetapi tidak mengirim konfirmasi ini. ACK dapat tertunda/hilang sehingga tidak ada ACK tidak otomatis berarti suara hilang.

Verifikasi otomatis mencakup countdown/staleness, forecast dibanding waktu scheduler sebenarnya, perubahan SF, trafik prioritas yang menyela, putus saat write aktif, dan pengiriman voice antar dua controller melalui FakeRadio sampai nama penerima terkonfirmasi. Hasil simulasi belum merupakan kalibrasi ETA dengan perangkat fisik.
