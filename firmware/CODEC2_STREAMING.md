# Upgrade audio Codec2 bertahap — 28 Oktober 2026

Titik lanjut keseluruhan: [WALKTHROUGH.md](WALKTHROUGH.md); roadmap ACK admission/backpressure ada di [NEXT_STEPS.md](NEXT_STEPS.md), masih rencana.

## Status dan cara mencoba

Android sekarang memiliki jalur rekam → encode → kirim chunk → decode → playback bertahap. Encoder dan decoder Codec2 dipertahankan selama satu sesi. Tidak menunggu Stop untuk mengirim potongan pertama, dan tidak menunggu semua berkas untuk memulai playback ketika penerima mengaktifkannya.

1. Pasang APK debug terbaru pada **kedua HP Android**: `apps/nusamesh/composeApp/build/outputs/apk/debug/composeApp-debug.apk`.
2. Hubungkan mesh dan buka percakapan yang sama di kedua HP. Pengirim mengaktifkan **Kirim bertahap** (default aktif). Penerima mengaktifkan **Dengar langsung** sebelum rekaman dimulai (default mati).
3. Tekan mikrofon dan bicara. Potongan pertama disiapkan setelah 16 frame / 640 ms sampel terkumpul, ditambah startup mikrofon/encode/penjadwalan. Potongan berikutnya berisi paling banyak 36 frame / 1,44 detik. Ini durasi bundling, **bukan waktu suara tiba atau terdengar**.
4. Tekan Stop atau tunggu batas 10 detik. Salinan NC2 utuh tersedia untuk playback biasa di kedua HP setelah selesai. Tidak ada kiriman ulang seluruh berkas: salinan penerima berasal dari chunk yang sama.
5. Untuk peer aplikasi lama, matikan **Kirim bertahap** dan gunakan voice note biasa setelah Stop. Peer lama tidak memahami isi chunk NVS1. Format arsip NC2 v2/1200 dan NC2 v1/3200 lama tetap didukung.

Penerima hanya mendengarkan channel percakapan yang sedang dibuka. Satu pembicara diputar pada satu waktu; sesi lain tetap dapat dirakit sebagai voice note. Menyalakan Dengar langsung setelah chunk pertama terlewat tidak menyambung decoder di tengah sesi: dengarkan arsip setelah selesai atau aktifkan sebelum kiriman berikutnya. Menutup percakapan mematikan listening; rekaman yang masih aktif dibatalkan, pengiriman yang sudah selesai direkam dapat melanjutkan antreannya.

**Lingkup platform:** capture dan playback bertahap baru di Android. Platform lain memakai fallback voice note; reassembly berada pada kode bersama, tetapi build/perangkat iOS tidak diverifikasi pada host Windows ini. Tidak ada klaim live audio iOS atau layanan audio background.

## Implementasi

- Native `codec2_bridge.h/.c`: API sesi raw-frame create/encode/decode/destroy, tanpa alokasi ulang codec setiap frame/chunk. Instans encoder dan decoder terpisah. Kapasitas dicek sebelum state codec dipakai.
- `AndroidCodec2.Session`: handle JNI dengan penutupan satu kali dan serialisasi akses. Encoder menerima kelipatan 320 sampel, decoder kelipatan 6 byte. Rekaman 8 kHz, mono PCM16, Codec2 1200 bps tetap 150 byte codec per detik.
- `AndroidVoiceCapture`: AudioRecord dan encode di worker; bundling 16 frame awal lalu 36 frame, buffer dibatasi, padding nol hanya pada frame akhir yang parsial. Jumlah sampel asli dibawa di protokol. Tidak memotong hening saat streaming agar waktu/urutan sampel tetap konsisten. Fallback voice note tetap memakai trimmer lama.
- `VoiceStream`: validasi format NVS1 dan assembler urutan, duplikat, batas durasi, END/CANCEL. Decoder hanya menerima rangkaian chunk berurutan; lubang tidak ditutup dengan data palsu. Cancel menghentikan sesi meskipun chunk sebelumnya hilang. Arsip hanya terbentuk setelah semua sampel berurutan dan END sah.
- `AppController`: satu pengiriman audio aktif, paling banyak 8 sesi masuk; hingga 64 chunk per sesi dan timeout tidak aktif 180 detik. Transfer tidak lengkap tidak diakui sebagai voice note berhasil. Preview masuk tersedia sebelum arsip lengkap. Data yang sudah diterima tetap disimpan ketika listening dimatikan.
- `AndroidVoicePlayer`: MODE_STREAM, decoder persisten, antrean maksimal 64 chunk; output hanya jumlah sampel asli. Startup threshold Android 12+ diatur satu frame, sementara penutup silence membantu drain buffer pada perangkat lama/rekaman pendek. Timeout dan penghentian melepas decoder/AudioTrack. Event playback menandai penyerahan PCM ke AudioTrack, **bukan bukti akustik bahwa speaker sudah terdengar**.

Rujukan perilaku startup threshold/drain MODE_STREAM: [Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack). Mekanisme AudioTrack perlu diuji di HP nyata, termasuk rekaman kurang dari 640 ms, pergantian output Bluetooth/headset, serta Stop saat sedang buffering.

## Format dan keamanan

Chunk memakai FILE_TRANSFER yang sudah ada, MIME `application/x-nusa-voice`, nama pendek `v<stream-id>` ditambah tag channel bila ada. Header NVS1 **22 byte**, big endian:

| Offset | Isi |
|---|---|
| 0–3 | magic NVS1 |
| 4–11 | stream ID 64 bit |
| 12–13 | sequence 16 bit |
| 14–17 | offset sampel 32 bit |
| 18–19 | sampel asli dalam chunk 16 bit |
| 20 | flag END=1, CANCEL=2 |
| 21 | mode/container version 2 = Codec2 1200 |
| 22… | frame codec 6 byte, tanpa header NC2 per chunk |

Arsip akhir memakai satu header NC2 12 byte ditambah seluruh frame codec. Batas rekaman tetap 80.000 sampel / 10 detik / 1.500 byte codec sebelum header/overhead transport.

Enkripsi channel tim tetap berjalan **per chunk** lewat OperationSecurity, nonce baru, dan nama bertag channel sebagai AAD. Kunci saat rekaman dimulai dipertahankan untuk semua chunk sesi tersebut. Chunk yang terkunci/rusak tidak dimainkan atau diakui. Channel privat dengan kunci aktif menolak chunk yang tidak berhasil diverifikasi. Global tetap mengikuti kebijakan lampiran publik yang sudah ada.

Hint prioritas audio 0x08 ikut paket dan fragmen, berlaku juga untuk MIME streaming. Payload codec tidak dikompresi zlib lagi. Padding blok tambahan tidak dipakai pada paket dengan hint audio untuk menghindari fragmentasi BLE yang hanya disebabkan padding; payload dengan ukuran blok persis mendapat trailer nol agar decoder lama tidak salah menghapus byte codec. Ini mengurangi penyamaran ukuran trafik audio; enkripsi tetap berlaku pada channel yang menggunakannya.

Bundel 36 frame dipilih agar contoh terburuk nama tim 20 karakter + stream ID 16 digit + enkripsi masih muat batas fallback BLE 400 byte. Jumlah frame RF tetap mengikuti payload maksimum NusaFrame 243 byte, bukan ukuran BLE. Header/security/application byte ikut perhitungan airtime; jangan menggunakan 150 byte/detik codec sebagai throughput jaringan yang dibutuhkan secara keseluruhan.

## Airtime dan batas kinerja

Upgrade ini menurunkan ketergantungan pada rekaman/berkas selesai. **Tidak menambah kapasitas RF atau menonaktifkan limiter.** NodeQueue tetap memakai rate limit per HP, airtime pacing dan prioritas ACK → teks → audio → bulk. Firmware quasi dua radio tetap berbagi antrean/anggaran airtime dan hanya satu frame lokal dipancarkan pada satu waktu.

Chunk tambahan membawa overhead lebih besar daripada satu berkas utuh. Pada LoRa yang jatah airtimenya tidak mencukupi, suara bisa mulai lebih awal lalu buffering; waktu total selesai dapat lebih lama daripada voice note sekali kirim. SF tinggi, backlog, relay dan enkripsi juga memengaruhi. Tidak ada klaim streaming kontinu 1200 bps atau latensi 2–3 detik pada seluruh profil LoRa. Untuk membandingkan total waktu/airtime, uji Kirim bertahap aktif dan mati pada ukuran suara serta PHY yang sama.

Contoh model 10 detik, SF7/BW125/CR4-5, ID stream 16 digit, tanpa retry/hop: global menghasilkan 8 paket aplikasi / 14 frame RF, sekitar **4,130 detik TX murni**; nama tim 20 karakter + enkripsi menghasilkan 8 paket / 16 frame, sekitar **4,919 detik TX murni**. Menambahkan RX window minimal 500 ms per frame saja menghasilkan batas bawah 11,130 / 12,919 detik. Angka ini belum memasukkan pacing HP, airtime budget, CAD/jitter, UART dan playback; **bukan ETA**. Frame pertama contoh global membawa 209 byte aplikasi dan memerlukan sekitar 348 ms TX, setelah bundling 640 ms. Token awal/burst bisa membantu awal sesi tetapi tidak menjamin kesinambungan suara berikutnya.

Tidak ada perubahan firmware/config/INO pada tahap audio ini. Gunakan firmware quasi takeover wire v3 sebelumnya bila memakai dua LilyGo. Mode satu radio juga dapat meneruskan paket file ini; prioritas audio memerlukan firmware yang mengenali hint.

## Log dan prosedur pengukuran

Ekspor log transmisi dari Uji QoS. Event baru:

- `VOICE_CAPTURE_STARTED`: waktu sesi pengirim dimulai.
- `VOICE_ENCODE`: stream ID, sequence, jumlah sampel/byte codec, waktu encode mikrodetik, elapsed capture lokal.
- `VOICE_CHUNK_QUEUED` dan `VOICE_CHUNK_RECEIVED`: penyerahan chunk ke antrean dan penerimaan aplikasi; bukan konfirmasi TX RF.
- `VOICE_PLAYBACK`: decode, submitted, buffering/resumed/finished serta underrun AudioTrack. `arrival_to_write_ms` hanya dari penerimaan lokal ke penyerahan PCM, tidak mencakup seluruh jaringan atau latensi speaker.
- `VOICE_STREAM_COMPLETE`: sampel/arsip berurutan lengkap; baru setelah itu ACK penerimaan dikirim.

CSV suara lama juga mencatat `encode-stream` untuk arsip akhir beserta SHA-256; `process_ms` adalah jumlah waktu encode chunk, tanpa proses trim. Playback arsip tetap menghasilkan baris decode biasa sehingga checksum pengirim/penerima bisa dibandingkan.

Uji dulu BLE langsung dengan dua HP, lalu ulangi LoRa-only, lalu gambar + suara. Gunakan rekaman 0,1 / 0,64 / 3 / 10 detik; uji juga kehilangan chunk, disconnect, Stop, tutup chat, dan dua pembicara. Bandingkan arsip pengirim/penerima, konfirmasi penerimaan, jumlah frame RF, waktu awal terdengar dengan rekaman eksternal, waktu selesai dan kejadian buffering. Jam antardua HP perlu disejajarkan bila menghitung durasi dari timestamp perangkat yang berbeda.

## Verifikasi software

Sumber Codec2 asli dipakai oleh `powershell -File apps/nusamesh/scripts/test-codec2-host.ps1`: encoded frame sesi bertahap dibandingkan byte demi byte dengan encode sekali jalan untuk PCM identik; tes meliputi batas frame, buffer sempit sebelum perubahan state, decode bertahap, canary output dan kompatibilitas NC2 3200 lama.

Tes Kotlin mencakup reordering/duplikat/lubang/CANCEL, final frame parsial, enkripsi/nama tim panjang, ukuran paket tanpa padding, serta transfer antardua controller melalui FakeRadio. Integrasi memastikan chunk playback tersedia **sebelum END**, lalu satu arsip dan satu ACK yang sama; cancel tidak menghasilkan arsip/ACK sukses. FakeRadio adalah transport software, bukan RF/perangkat.

Hasil verifikasi 28 Oktober 2026:

- 160 unit test Kotlin lulus, 0 gagal/error/skipped; termasuk 7 tes format/assembler/ukuran/security dan 2 integrasi controller streaming.
- Host native dengan Codec2 asli lulus: keluaran encode bertahap identik dengan encode utuh, decode bertahap lolos, batas buffer dan kompatibilitas lama lolos.
- Android build debug berhasil; native empat ABI arm64-v8a, armeabi-v7a, x86, x86_64 masing-masing mengekspor enam entry point JNI (encode/decode arsip dan create/destroy/encode/decode sesi).
- APK akhir memuat kontrol Kirim bertahap/Dengar langsung serta kode sesi; checksum dan ringkasan validasi disimpan di `apps/nusamesh/composeApp/build/codec2-validation/validation.json` setelah packaging akhir.
- `NusaNode.ino` SHA256 tetap `C6B51D798F196B6073BD45F91679E50DFD53C4D297E0225974E4EC5C75AEC5CC`. Tahap ini tidak mengubah sumber firmware atau konfigurasi radio.

Belum ada instalasi APK, pengujian mikrofon/speaker HP, build/perangkat iOS, flashing baru atau pengukuran RF pada tahap ini. APK adalah build debug untuk uji, bukan release produksi. Perubahan sumber belum di-commit.
