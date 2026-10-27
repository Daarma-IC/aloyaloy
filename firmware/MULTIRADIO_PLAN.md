# Rencana jalur transfer besar dan komunikasi interaktif

> Update audio 28 Oktober 2026: [jalur Codec2 bertahap Android](CODEC2_STREAMING.md) sudah ditambahkan. Keterangan voice-note-only di bawah adalah snapshot tahap sebelumnya; lihat dokumen baru untuk status, aktivasi, batas dan validasi.

> Update aktif 27 Oktober 2026: [quasi dua radio dengan takeover dan perbaikan Codec2](QUASI_TAKEOVER.md). Bagian implementasi RX-only/target lama di bawah adalah riwayat; baca status terbaru sebelum melanjutkan.


## Riwayat kebutuhan sebelum baseline takeover

Target khusus berikutnya adalah suara mulai terdengar dalam 2-3 detik saat gambar sedang ditransfer. Audit aktual menemukan jalur Codec2 masih voice note setelah Stop, encoder 1200 bps, playback statis, serta pembatas antrean/airtime yang belum mendukung stream kontinu. Lihat [VOICE_LATENCY_AUDIT.md](VOICE_LATENCY_AUDIT.md). Belum ada dukungan streaming atau handover antaranggota penuh yang diaktifkan.

User meminta satu hub/identitas mesh logis dengan dua LilyGO **setara kemampuannya**: kedua board dapat melayani HP lewat BLE atau WiFi serta menjalankan routing, fragmentasi dan LoRa RX/TX. Peran bulk/chat dibagi secara terkoordinasi, bukan mengunci board kedua sebagai RX-only. Ini bukan dua node mandiri ber-ID sama.

Rancangan harus memisahkan identitas hub yang dilihat aplikasi dari identitas anggota internal, menyinkronkan pesan/status, mencegah relay atau pengiriman ganda, dan mengatur giliran akses radio. Quasi pada satu kanal tetap mengirim satu frame pada satu waktu: membagi CPU/antrean/pekerjaan antardua MCU tidak otomatis menambah kapasitas udara. Radio untuk bulk dan radio untuk chat dapat memakai pembagian kerja dinamis, tetapi chat tetap menunggu giliran frame yang sedang dipancarkan. Interferensi dari node lain tetap mungkin.

**Status implementasi:** kode yang ada masih arsitektur main + companion RX-only dengan PAUSE/RESUME. Firmware hub kooperatif dua anggota penuh belum dibuat atau diaktifkan. Tidak ada klaim dukungan transfer file 2 MB, throughput dua kali lipat, upload atau hasil RF baru. Rancangan dua kanal yang dibahas dalam riwayat berikut bukan keputusan aktif untuk quasi satu kanal.

## Riwayat pembahasan sebelumnya

Status: **rancangan kebutuhan berikutnya, belum fitur aktif**. Dokumen ini mengikuti klarifikasi user setelah implementasi receiver companion. Jangan menganggap firmware RX-only yang ada sudah mendukung TX interaktif pada radio kedua.

**Keputusan terbaru:** user meminta kembali ke quasi-duplex. Rancangan dua jalur bulk/chat dan pertanyaan implementasi file 2 MB di bawah ditunda, bukan syarat pekerjaan sekarang. Mode aktif memakai dua radio khusus TX/RX yang dijadwalkan bergantian dengan PAUSE/RESUME; lihat [DUPLEX.md](DUPLEX.md). Tidak ada fitur file 2 MB atau companion TX yang diam-diam diaktifkan.

## Maksud pemakaian yang sedang dibahas

- Radio pertama melayani transfer besar sebagai rangkaian paket kecil.
- Radio kedua tetap tersedia untuk pesan pendek/ACK/kontrol, dan dapat bergantian RX atau TX pada radionya sendiri.
- Keduanya bagian dari satu node/hub; HP tetap masuk melalui BLE atau WiFi NusaNode utama.
- Antar-LilyGO saat ini tersedia link UART. BLE antar-board belum diimplementasikan.
- `.ino` utama tetap dipertahankan; implementasi baru nantinya ditempatkan di modul driver/protokol.

Ini berbeda dari mode yang sudah dibuat di [DUPLEX.md](DUPLEX.md): mode itu memisahkan radio utama TX dan companion RX-only. Companion saat ini belum memiliki perintah TX, antrean TX atau laporan penyelesaian TX dari main.

## Yang sudah ada dan bisa digunakan

Frame radio maksimum 255 byte dengan header NusaFrame 12 byte, sehingga payload fragmen maksimum 243 byte. `NusaFrag::sendPacket()` sudah memecah paket aplikasi, tetapi membatasi paket yang dirakit ulang pada `REASM_MAX_BYTES=2560`. Antrean TX berisi 24 frame.

Chat teks memiliki prioritas `PRIO_TEXT=1`, di atas `PRIO_LOCAL=2` untuk lampiran/bulk. Penjadwal memilih frame berikutnya berdasarkan prioritas. Karena itu chat bisa menyela **di antara frame**, bukan memotong pancaran frame yang sedang berlangsung. Anggaran airtime tetap bisa menahan keduanya. Ini belum menjamin latensi chat pada beban tertentu.

File 2 MB bukan satu paket SX1276. Transfer file besar membutuhkan pemecahan tingkat aplikasi/transfer, di atas fragmentasi frame radio. Jangan sekadar menaikkan buffer RAM reassembly menjadi 2 MB.

## Pilihan arsitektur berikutnya

1. **Satu jalur dengan fragmentasi dan prioritas:** kirim bulk per potongan kecil, sisipkan chat/ACK antar-frame. Tidak menambah kapasitas radio, tetapi mengatur pembagian waktu.
2. **Dua jalur:** radio bulk dan radio interaktif pada kanal yang dirancang terpisah. Masing-masing tetap bergantian RX/TX. Supaya radio interaktif bisa bekerja sementara bulk TX memancar, isolasi RF/antena perlu diuji. Ini tidak otomatis bebas bentrok.
3. **Membagi satu file ke dua radio:** baru masuk akal jika sisi lawan dapat menerima kedua jalur dan protokol mengatur nomor potongan, dedup, ACK/retry serta penyusunan ulang. Menghabiskan kedua radio untuk bulk juga dapat mengurangi manfaat jalur chat khusus.

Untuk kebutuhan menjaga chat tetap tersedia, arah rancangan yang dibahas adalah opsi 2. Kedua endpoint perlu memiliki radio/protokol yang cocok pada kedua jalur. Dua LilyGO yang ada baru membentuk satu sisi; belum membuktikan penerimaan kedua kanal pada sisi lawan.

## Implikasi firmware bila opsi 2 dipilih

- Tambahkan pengiriman, ACK penerimaan perintah dan laporan TX_DONE/error pada link companion; jangan menganggap data yang masuk UART sudah terkirim RF.
- Companion perlu berganti RX/TX dan kembali mendengarkan setelah TX selesai/timeout.
- Pisahkan antrean dan anggaran airtime per jalur; tentukan klasifikasi chat/ACK vs bulk sebelum memindahkan trafik.
- Radio utama perlu dapat menerima trafik bulk/ACK pada saat tidak memancar; peran RX/TX tidak dikunci satu arah.
- Peer kedua harus menggunakan kanal dan format yang sama untuk setiap jalur. Ini bukan hanya mengganti flag full/half pada konfigurasi lama.
- Pertahankan identitas paket, routing dan payload terenkripsi. Jangan menyatakan semua pesan dengan prioritas tinggi sebagai bukti transfer file sudah andal.
- Tetapkan batas buffer, backpressure, retry dan arti counter pada kedua jalur. Untuk file sungguhan, tentukan juga penyimpanan, integritas akhir dan resume.

## Skala waktu contoh 2 MiB

Perhitungan ideal dari parameter yang ada: 2.097.152 byte / 243 byte menghasilkan 8.631 frame. Frame penuh SF7/BW125/CR4-5, preamble 8, explicit header dan CRC memiliki airtime sekitar 399,616 ms. Total sekitar **57,5 menit pancaran murni** pada satu radio, tanpa overhead transfer tambahan, jeda, kegagalan atau retry.

Jika jatah airtime konfigurasi 5% diterapkan dalam jangka panjang, skala ideal menjadi sekitar **19,2 jam** sebelum overhead lain. Ini estimasi matematis berdasarkan parameter repo, bukan hasil uji dan bukan pernyataan bahwa jatah tersebut sah untuk operasi tertentu. SF/ukuran paket/anggaran airtime yang berbeda menghasilkan waktu berbeda.

## Keputusan yang masih menunggu klarifikasi

User ditanya apakah 2 MB hanya contoh untuk menjelaskan beban besar, atau persyaratan transfer file nyata. Jawaban menentukan apakah pekerjaan berikutnya sebatas penjadwalan/jalur interaktif, atau sekaligus protokol transfer file dengan penyimpanan dan pemulihan. Belum ada mode baru ini yang diaktifkan, upload atau pengukuran RF.

Referensi kemampuan dan mode radio: [Semtech SX1276](https://www.semtech.com/products/wireless-rf/lora-connect/sx1276), [register mode referensi Semtech](https://github.com/Lora-net/LoRaMac-node/blob/master/src/radio/sx1276/sx1276Regs-LoRa.h). Batas buffer/prioritas di atas diverifikasi pada sumber NusaNode lokal.
