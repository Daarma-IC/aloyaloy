# Target suara 2-3 detik saat gambar sedang ditransfer

> Update audio 28 Oktober 2026: [jalur Codec2 bertahap Android](CODEC2_STREAMING.md) sudah ditambahkan. Keterangan voice-note-only di bawah adalah snapshot tahap sebelumnya; lihat dokumen baru untuk status, aktivasi, batas dan validasi.

> Update aktif 27 Oktober 2026: [quasi dua radio dengan takeover dan perbaikan Codec2](QUASI_TAKEOVER.md). Bagian implementasi RX-only/target lama di bawah adalah riwayat; baca status terbaru sebelum melanjutkan.


Status: audit sumber dan kebutuhan, **belum implementasi streaming/hub kooperatif dua MCU penuh**. Target terbaru user adalah dua radio setara pada satu identitas hub/mesh, pembagian pekerjaan cerdas, quasi-duplex, dan suara tidak tertahan transfer gambar. `.ino` utama tidak diubah dalam audit ini.

## Angka kapasitas dan airtime

Codec2 bekerja per frame, bukan menetapkan batas berkas dalam MB. Mode saat ini 1200 bps menghasilkan **48 bit / 6 byte per 40 ms** (320 sampel pada 8 kHz), sehingga 150 byte/detik. Header berkas NC2 di aplikasi menambah 12 byte. Contoh tanpa hening yang dipotong: suara 10 detik menjadi 1512 byte, bukan 160 KB PCM. Batas rekaman aplikasi tetap 10 detik. Referensi: [API frame Codec2 resmi](https://github.com/drowe67/codec2/blob/main/src/codec2.c).

Codec2 700C menghasilkan 28 bit per 40 ms, tetapi API `codec2_bytes_per_frame()` membulatkan menjadi 4 byte. Jika setiap frame disimpan dalam byte utuh, keluaran menjadi sekitar **100 byte/detik**, bukan 87,5 byte/detik; angka terakhir hanya bitstream 700 bit/detik yang dipadatkan lintas frame. Encoder/container aplikasi saat ini belum memakai mode itu. Mode 2400 dan 3200 masing-masing menghasilkan 300 dan 400 byte/detik.

SX1276 dalam mode LoRa mendukung payload radio hingga 255 byte. NusaFrame menggunakan 12 byte, sehingga sisa fragmen paket aplikasi 243 byte, sebelum overhead aplikasi/enkripsi. Batas bitrate LoRa chip hingga 37,5 kbit/detik berlaku pada parameter tercepatnya, bukan profil repo. Angka chip hingga 300 kbit/detik merujuk mode FSK yang berbeda. Referensi: [datasheet asli Semtech, salinan SparkFun](https://cdn.sparkfun.com/assets/a/a/0/b/5/DS_SX1276-7-8-9_W_APP_V5.pdf), bagian product variants dan time on air.

Profil repo SF7/BW125/CR4-5 menghasilkan bitrate nominal 5,469 kbit/detik. Setelah header/preamble/CRC, batas ideal fragmen NusaFrame penuh sekitar 608 byte/detik **jika TX terus-menerus**; jadwal quasi, airtime budget dan antrean HP belum diterapkan pada angka itu.

| Contoh data | Byte input fragmentasi | Jumlah frame | TX murni | Minimum termasuk RX window quasi |
|---|---:|---:|---:|---:|
| Codec2 suara 1 detik + header NC2 | 162 | 1 | 0,282 s | 0,782 s |
| Codec2 suara 2 detik + header NC2 | 312 | 2 | 0,543 s | 1,543 s |
| Codec2 suara 5 detik + header NC2 | 762 | 4 | 1,291 s | 3,291 s |
| Codec2 suara 10 detik + header NC2 | 1512 | 7 | 2,521 s | 6,021 s |
| Data gambar 2 KiB | 2048 | 9 | 3,392 s | 7,892 s |

**Tabel tersebut batas bawah matematis, bukan ETA aplikasi.** Hanya memodelkan data contoh dan header NusaFrame 12 byte: belum memasukkan pembungkus file, enkripsi, fragmentasi BLE/app, limiter HP, jitter/CAD, UART, hop, kehilangan/retry atau playback. Token airtime awal/burst juga memengaruhi waktu selesai. Jumlah frame aktual dapat lebih banyak karena overhead dan fragmentasi berlapis.

Untuk payload radio penuh 255 byte, airtime SF7/SF8/SF9/SF10/SF11/SF12 pada BW125 berturut-turut sekitar **0,400 / 0,707 / 1,250 / 2,296 / 5,001 / 9,019 detik**. Jangan menaikkan SF untuk semua trafik tanpa menghitung ulang deadline audio.

Kalkulator tanpa dependensi: `powershell -File firmware/tools/airtime-budget.ps1 -DataBytes 1512 -SF 7 -DutyRatio 0.01`. Field `AirtimeBudgetEquivalentSeconds` adalah konsumsi anggaran airtime dalam satuan waktu, **bukan waktu transfer pasti**. Field `QuasiLowerBoundSeconds` belum mencakup limiter dan overhead aplikasi. Ganti input SF/BW/data sesuai pengukuran paket nyata.

## Kebijakan agar overload tidak disembunyikan sebagai latensi

- Jangan mengirim satu frame Codec2 1200 (6 byte/40 ms) sebagai satu paket radio. Bahkan sebelum header aplikasi/enkripsi, 6+12 byte payload radio memakai sekitar **51,456 ms airtime** pada SF7/BW125. Dua puluh lima paket/detik memerlukan **1,2864 detik TX per detik**, sudah melebihi kapasitas satu radio tanpa limiter. Beberapa frame codec harus digabung ke satu paket; ukuran dan interval bundling dipilih dari anggaran airtime/deadline, bukan sekadar "paket sekecil mungkin".
- Batasi berdasarkan **total airtime yang mengantre dan deadline**, bukan hanya jumlah byte/slot.
- Gambar tetap kecil dan berprioritas rendah; profil aplikasi saat ini membatasi gambar LoRa 2 KiB. Jangan menaikkan batas 96 KiB media umum menjadi target gambar LoRa.
- Audio harus memiliki kelas/deadline sendiri; gambar dapat dijeda di antara frame. Teruskan gambar setelah kapasitas tersedia, bukan membanjiri kedua anggota dengan semua fragmen.
- Jika kebutuhan audio melampaui kapasitas yang tersedia, beri hasil/estimasi yang jujur atau negosiasikan profil; jangan menjanjikan 2-3 detik dengan membuang audio diam-diam.
- Dua radio pada quasi satu kanal tidak menggandakan kapasitas udara. Lakukan pengukuran end-to-end sebelum mengubah limiter atau mengklaim streaming berhasil.

## Temuan sumber yang diverifikasi

| Bagian | Kondisi kode sekarang |
|---|---|
| Android `MainActivity.stopVoiceNote()` | Menghentikan rekaman, mengumpulkan PCM, membuang hening, baru memanggil encoder dan menghasilkan attachment |
| `ChatScreen.stopVoice()` | Memanggil pengiriman attachment setelah callback `onRecorded` |
| `codec2_bridge.c` | Encoder format NC2 v2 memakai Codec2 **1200 bps**, sekitar 150 byte/detik audio sebelum overhead transport |
| Bridge decoder | Memvalidasi ukuran berkas lengkap dan total frame; belum API decoder stream persisten |
| Android playback | Decode berkas menjadi PCM, lalu memakai `AudioTrack.MODE_STATIC`; bukan pemutaran potongan sambil diterima |
| `VoiceLog.MAX_SECONDS` | Batas rekaman 10 detik milik aplikasi, bukan batas bawaan jumlah paket Codec2 |
| `NodeQueue` di HP | Jatah airtime 4%, interval token 3500 ms, burst terbatas; dapat menahan audio sebelum mencapai node |
| Firmware `config.h` | Duty 5% untuk testing, jendela RX quasi dan antrean 24 frame; bukan profil yang terbukti memenuhi latensi 2-3 detik |
| Prioritas firmware | ACK dan teks didahulukan; voice note dan gambar sama-sama kelas lampiran/bulk pada jalur yang diperiksa |
| Firmware companion | Masih RX-only terkoordinasi, belum anggota penuh yang bisa mengambil giliran TX dari board lain |

Lokasi sumber:

- `apps/nusamesh/composeApp/src/androidMain/kotlin/id/nusamesh/app/MainActivity.kt`
- `apps/nusamesh/composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/ChatScreen.kt`
- `apps/nusamesh/composeApp/src/nativeInterop/codec2/codec2_bridge.c`
- `apps/nusamesh/composeApp/src/commonMain/kotlin/id/nusamesh/app/media/VoiceLog.kt`
- `apps/nusamesh/composeApp/src/commonMain/kotlin/id/nusamesh/app/mesh/core/NodeQueue.kt`
- `firmware/NusaNode/src/lora/nusa_radio.h` dan `config.h`

Kesimpulan: jalur sekarang adalah **voice note setelah rekaman selesai**, bukan streaming suara langsung. Menambahkan radio atau menghapus batas rekaman saja tidak mengubah alur tersebut.

## Batas kapasitas yang harus diselesaikan

Pada parameter repo SF7/BW125/CR4-5, satu frame 255 byte dengan payload transport 243 byte memakai airtime sekitar 399,616 ms. Batas ideal payload sebelum overhead aplikasi sekitar 608 byte/detik jika memancar terus.

- Pada jatah 4%: sekitar **24,3 byte/detik** dalam jangka panjang.
- Pada jatah 5%: sekitar **30,4 byte/detik** dalam jangka panjang.
- Codec2 mode yang digunakan menghasilkan sekitar **150 byte/detik** sebelum enkripsi, header dan retry.

Ini perhitungan ideal, bukan benchmark. Burst awal dapat mengirim voice note singkat lebih cepat, tetapi tidak membuktikan streaming berkelanjutan stabil. Profil sekarang tidak mencukupi untuk mempertahankan stream 1200 bps terus-menerus; menambah dua MCU tidak menghilangkan defisit airtime. Quasi pada satu kanal juga tetap membagi waktu udara.

[Permenkomdigi 2/2025, Lampiran II LPWAN](https://peraturan.bpk.go.id/Download/378118/permenkomdigi-no-2-tahun-2025.pdf) mencantumkan duty cycle uplink/downlink 1% pada 920-923 MHz. Konfigurasi testing 5% di repo bukan bukti kepatuhan produksi. Jangan menjadikan pematian limiter sebagai solusi produksi tanpa pemeriksaan profil radio dan ketentuan yang berlaku.

## Arsitektur yang sesuai target

1. Kedua MCU/radio harus menjadi anggota penuh dengan satu identitas hub logis, identitas anggota internal berbeda, serta sinkronisasi antrean dan kepemilikan radio. Ini belum diwujudkan oleh companion RX-only.
2. Gambar/file dipecah menjadi potongan. Pekerjaan tidak mengunci satu radio sampai seluruh berkas selesai.
3. Handover dilakukan pada batas frame/potongan: identitas transfer, nomor urut dan penerimaan yang sudah dikonfirmasi dipertahankan. Pancaran frame yang sedang berlangsung tidak dipindahkan di tengah jalan.
4. Audio perlu kelas trafik/deadline yang dapat dikenali oleh aplikasi dan node, dengan kontrol/ACK yang relevan tetap dilayani. Jangan mengorbankan enkripsi dengan membongkar payload media pada relay hanya untuk mengetahui prioritas.
5. Gambar dapat dijeda ketika audio mendekati deadline, lalu dilanjutkan; prioritas tidak otomatis menambah kapasitas udara.
6. Jika yang dimaksud live streaming: capture -> encode frame bertahap -> packetizer -> antrean berdeadline -> radio -> buffer penerima kecil -> decode/pemutaran bertahap. Tidak menunggu seluruh rekaman/attachment lengkap.
7. Potongan audio perlu stream ID, urutan/waktu dan penanganan kehilangan paket. Potongan kedaluwarsa tidak boleh membuat penundaan bertambah tanpa batas karena retry; kebijakan audio berbeda dari keandalan berkas gambar.
8. Jika tetap quasi satu kanal, hanya satu pemancar grup yang diberi giliran; pembagian beban tidak boleh mengaktifkan dua TX bersamaan secara tidak sengaja. Tidak ada janji bebas interferensi dengan node lain.
9. Parameter PHY, rate codec, overhead dan airtime harus dibuktikan cukup sebelum target 2-3 detik dijanjikan. Jika tidak cukup, ubah persyaratan/profil transport secara eksplisit; jangan menyembunyikan packet drop sebagai latensi rendah.

## Definisi target yang masih ditanyakan

User ditanya apakah 2-3 detik dihitung sejak **mulai bicara** (penerima mendengar saat pengirim masih merekam) atau sejak **menekan Kirim setelah rekaman selesai**. Ini membedakan fitur live streaming dan percepatan voice note. Audit tidak menganggap dua kebutuhan tersebut sama.

## Bukti yang diperlukan nanti

- Ukur rekaman/kirim mulai, encode chunk pertama, paket RF pertama, paket RX pertama dan PCM pertama benar-benar dimainkan, dengan stream/message ID yang sama.
- Ukur saat transfer gambar tetap aktif dan saat idle, untuk tiap arah serta pergantian anggota.
- Pisahkan waktu tunggu HP, penjadwal grup, antrean node, airtime, retry dan buffer audio.
- Timestamp lintas perangkat memerlukan koreksi selisih jam atau referensi pengukuran bersama. TX selesai/diterima stack BLE bukan bukti penerima sudah mendengar.
- Laporkan latensi awal, kestabilan pemutaran, bagian suara hilang, throughput gambar dan kepatuhan airtime. Jangan hanya mengukur pengiriman potongan pertama tanpa pemutaran.

Tidak ada kode aplikasi/firmware, build release, upload atau pengukuran perangkat yang diubah/dijalankan untuk audit ini. Keberhasilan build/test firmware quasi sebelumnya tidak membuktikan target suara ini sudah tercapai.
