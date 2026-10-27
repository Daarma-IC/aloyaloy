# Pekerjaan berikutnya: selaraskan aplikasi dan firmware

Status 28 Oktober 2026: **roadmap, belum diimplementasikan**. Baseline dan hasil terdahulu ada di [WALKTHROUGH.md](WALKTHROUGH.md). Mulai dari P1; jangan langsung menghapus limiter atau menaikkan bitrate Codec2.

## Tujuan

HP mengetahui apakah paket benar-benar diterima antrean node, kapan perlu menunggu, dan apakah terjadi penolakan. Pengiriman audio mengikuti kapasitas yang tersedia, sementara ACK/teks tetap responsif dan gambar dapat lanjut di sela audio. Antrean/buffering tidak disamarkan sebagai keberhasilan Codec2 atau penerimaan RF.

Target suara mulai terdengar 2–3 detik adalah sasaran yang perlu diukur, belum hasil. Ukur pula kesinambungan playback dan waktu total selesai; mulai cepat tetapi kemudian buffering panjang harus tetap terlihat.

## P1 — Observasi dan kontrak penerimaan paket

**Pekerjaan pertama pada sesi lanjut.** Tambahkan hasil admission HP–node yang dapat dikorelasikan dengan request ID:

| Hasil | Arti yang harus dijaga |
|---|---|
| ACCEPTED | Paket beserta seluruh fragmen yang diperlukan sudah diterima atomik oleh antrean tujuan lokal yang disebutkan |
| BUSY | Belum diterima; ruang/jatah sementara tidak cukup, tersedia alasan dan retry-after bila dapat dihitung |
| REJECTED | Tidak diterima karena format, ukuran, kemampuan, atau alasan permanen yang jelas |
| TX_DONE | Satu frame selesai dipancarkan; bukan ACK penerima akhir |
| Delivery ACK | Penerima aplikasi mengonfirmasi hasil lengkap sesuai protokol, berbeda dari admission dan BLE write |

Laporkan kapasitas secara terbatas: slot antrean bebas, batas paket aplikasi, estimasi airtime yang mengantre, sisa budget, SF/PHY aktif, dan alasan menunggu. Gunakan durasi relatif/retry-after agar tidak mengandalkan kesamaan jam HP–MCU. Pisahkan penolakan limiter, antrean penuh, CAD sibuk, UART stale, TX timeout dan RX CRC error.

Kontrak perlu mendefinisikan ingress yang menerima paket: routing lokal, hold mobility, antrean RF atau relay. Jangan mengirim ACCEPTED hanya karena callback BLE berhasil, token terpotong, atau sebagian fragmen sudah masuk. Bila ACK admission hilang, query/status atau dedup request harus mencegah enqueue ulang yang mungkin menggandakan RF.

Lokasi awal yang ditinjau:

- `NusaNode/src/ble/nusa_ble.*` dan `src/wifi/nusa_link.*`: akses HP, rate limit dan respons lokal.
- `NusaNode/src/lora/nusa_radio.*`, `nusa_fragment.*`: admission atomik, kapasitas slot, budget/airtime dan hasil TX.
- `NusaNode/src/protocol/`: pesan lokal kontrol/status baru dengan versi/capability yang jelas.
- Aplikasi `mesh/engine/MeshEngine.kt`, `mesh/core/NodeQueue.kt`: korelasi hasil dan diagnostik.

**Selesai bila:** semua penolakan yang diuji punya alasan, ACCEPTED selalu berarti admission utuh, respons lama/duplikat tidak menghabiskan kredit atau menggandakan enqueue, dan disconnect/reboot tidak menghasilkan keberhasilan palsu. Buat uji untuk antrean hampir penuh, batch multi-frame, dua HP bersamaan dan ACK admission hilang.

## P2 — Backpressure dan pengiriman mengikuti kapasitas

Setelah P1 tersedia, ganti jeda tetap 3500 ms sebagai mekanisme utama dengan kredit/kapasitas dari node. Satu kredit harus mewakili kapasitas yang dapat direservasi, bukan sekadar snapshot `queueFree` yang bisa dipakai ganda oleh beberapa HP.

- Tetapkan ownership kredit, expiry/session epoch dan reset setelah reconnect/reboot atau perubahan PHY.
- Debit/reservasi kapasitas secara konsisten untuk seluruh batch; ACK admission dan penggunaan kredit tidak boleh diproses dua kali.
- Pertahankan fairness per HP, cadangan ACK/teks dan anggaran airtime bersama termasuk trafik relay/internal. Satu HP atau audio tidak boleh mengambil semua slot terus-menerus.
- Tangani BUSY dengan retry terbatas, backoff dan alasan terlihat. Hindari loop kirim ulang cepat serta retry RF ambigu setelah TX timeout.
- Peer/firmware lama tetap memakai fallback konservatif sampai capability baru terkonfirmasi. Protokol akses HP–node berbeda dari UART wire v3 antar-radio; jangan menganggap versi keduanya sama.
- Evaluasi ulang limiter 0,3 paket/detik hanya setelah admission/backpressure terbukti. Jangan sekadar menaikkan angka atau menonaktifkan limiter sementara node masih membuang paket tanpa respons.

**Selesai bila:** antrean dibatasi, kredit tidak terpakai ganda, waktu menunggu terlihat, node lama tetap bisa dipakai, dan uji multi-HP tidak menghasilkan burst tanpa kontrol atau melebihi anggaran airtime yang dikonfigurasi.

## P3 — Bundling audio mengikuti kemampuan jalur

Baseline Android sekarang memakai 16 frame awal (640 ms), lalu hingga 36 frame (1,44 detik). Sebelum mengubahnya, ukur byte aplikasi sebenarnya setelah pembungkus/enkripsi, jumlah frame RF dan waktu antreannya.

- Pertahankan manfaat chunk pertama pendek jika budget tersedia.
- Sesuaikan bundel berikutnya dengan SF/BW/airtime dan backpressure, bukan jumlah byte codec saja. Bundel terlalu kecil menambah overhead; terlalu besar menambah waktu menunggu awal dan giliran trafik lain.
- Pertahankan predictor Codec2 per sesi, urutan sampel dan arsip akhir. Jangan reset encoder/decoder per chunk.
- Tentukan perilaku saat kapasitas tidak cukup: tampilkan buffering atau gunakan mode voice note setelah Stop secara jelas. Jangan menjanjikan streaming kontinu dari bitrate nominal chip.
- Tentukan kebijakan chunk terlambat/hilang. Saat ini lubang menahan urutan dan akhirnya membuat sesi tidak lengkap; belum ada retry/PLC khusus chunk. Peningkatan harus membedakan kelancaran live audio dari integritas arsip lengkap dan ACK-nya.

**Selesai bila:** dibanding baseline, overhead/backlog tidak bertambah tanpa manfaat yang terukur, tidak ada sampel hilang/diakui palsu, dan UI/log membedakan buffering, kirim selesai serta terima lengkap. Dukungan Android tidak otomatis menjadi bukti iOS; implementasi/perangkat platform lain menjadi pekerjaan tersendiri.

## P4 — Evaluasi pembagian pekerjaan dua radio

Baseline memakai giliran tetap per frame. Setelah observasi tersedia, evaluasi pemilihan pemancar berdasarkan kesiapan/kesehatan radio dan prioritas pekerjaan. Jika radio sibuk memancarkan, tunggu frame selesai; jangan memotong pancaran atau mengaktifkan TX lokal kedua bersamaan pada quasi satu kanal.

Pertahankan satu identitas hub, satu antrean/budget, msgId/indeks fragmen, dedup dua RX, token UART dan fail-closed saat status anggota stale/pembatalan belum terkonfirmasi. Handover bukan alasan mengirim salinan frame dua kali atau menghitung budget dua radio secara terpisah. Dua anggota aplikasi penuh dengan layanan BLE/WiFi masing-masing tetap rencana berbeda dari coprocessor radio yang aktif sekarang.

**Selesai bila:** uji hilangnya anggota, kegagalan start, CAD sibuk, timeout dan reconnect tidak membuat TX ganda atau kehilangan status frame. Perubahan ini harus punya manfaat terukur; giliran tetap tidak perlu diganti hanya demi menambah kompleksitas.

## P5 — Pembuktian di perangkat

| Tahap | Yang dibuktikan | Batas interpretasi |
|---|---|---|
| Host/native + unit/integrasi software | Format, predictor state, admission, kredit, reordering, cancel dan error | Bukan bukti mikrofon/speaker atau RF |
| Dua HP, BLE langsung | Capture/playback nyata, checksum arsip, audio pendek/panjang, lifecycle, buffering | Bukan bukti LoRa |
| LoRa tanpa gambar | Perilaku paket/audio pada PHY aktual dan anggaran airtime | Butuh node penerima di sisi lawan |
| LoRa gambar + audio + teks/ACK | Prioritas, waktu antrean, interferensi eksternal dan beban campuran | Bandingkan dengan baseline dan catat kondisi |
| Putus/sambung/reboot anggota | Koordinasi UART, recovery dan tidak ada TX ganda | Perlu log main, anggota dan penerima |

Dua LilyGo yang membentuk satu hub belum merupakan kedua ujung tautan RF. Untuk menguji hub dual-radio terhadap lawan diperlukan setidaknya satu node LoRa di sisi lawan; dua hub dual-radio lengkap memerlukan empat radio/board sesuai skema.

Catat: ukuran audio/gambar, PHY aktif, jarak/antena/daya, jalur/hop, waktu capture/chunk/antre/TX/penerimaan/playback, paket ditolak/hilang/duplikat, buffering/underrun dan waktu selesai. Bandingkan median serta p95 bila jumlah sampel cukup, termasuk hasil gagal. Gunakan rekaman eksternal untuk waktu suara benar-benar terdengar; log AudioTrack submitted bukan pengukuran akustik.

## Batas perubahan dan definisi selesai

- Pertahankan `NusaNode.ino` dan keenam INO varian. Kembangkan logika di modul; header penghubung menjaga pemanggilan utama tetap sama.
- Jangan reset perubahan lama, menyentuh identitas node pengguna, atau mengubah semua varian terpisah. Gunakan sumber kanonis dan skrip sinkronisasi setelah perubahan modul.
- Jangan mengaktifkan eksperimen full, memisahkan frekuensi, mengganti bitrate atau menaikkan duty hanya untuk membuat angka latensi tampak baik.
- Catat hasil aktual, versi build/protokol dan parameter pengujian. Bedakan software lulus, APK terpasang, RF terukur dan release produksi.
- Update walkthrough setelah setiap tahap selesai. P1–P5 tetap berstatus rencana sampai implementasi dan bukti masing-masing tersedia.

Prompt lanjut yang bisa dipakai:

> Baca firmware/WALKTHROUGH.md dan firmware/NEXT_STEPS.md. Lanjutkan P1: observasi dan kontrak ACK admission HP–node, dengan uji penolakan/antrean penuh/duplikat/reconnect. Pertahankan INO utama, baseline quasi takeover wire v3, kompatibilitas node lama, batas buffer dan anggaran airtime. Jangan mengklaim hasil RF dari host test. Jangan mulai P2 dengan sekadar menghapus limiter.
