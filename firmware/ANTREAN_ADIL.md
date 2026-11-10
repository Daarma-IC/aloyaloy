# Antrean LoRa adil per HP — 10 November 2026

Catatan perubahan firmware agar satu HP yang spam tidak menahan HP lain pada node yang sama.

## Status

| Hal | Status |
|---|---|
| Kode `NusaNode` dan varian SF7–SF12 | Sudah diubah, belum di-commit |
| Compile `esp32:esp32:ttgo-lora32:Revision=TTGO_LoRa32_v21new` | Berhasil: 710034 byte flash, 76492 byte RAM global |
| Tes host | 11 suite lulus |
| Flash ke board | **Belum.** Saat dicoba, `COM5`/`COM6` gagal dibuka (`A device attached to the system is not functioning`) |
| Uji RF dengan 2 node | **Belum** |

Semua angka waktu di dokumen ini hitungan dari kode, bukan hasil ukur di board.

## Masalah yang diperbaiki

Sebelumnya antrean TX tidak tahu frame milik HP mana. Pemilihan frame hanya melihat prioritas dan umur, jadi HP yang lebih dulu mengisi antrean akan dilayani lebih dulu. Satu HP juga bisa memakai sampai 18 dari 24 slot untuk gambar.

## Yang berlaku sekarang

Urutan pemilihan frame:

1. Prioritas: ACK, teks, suara, gambar/lampiran, relay, gossip.
2. Dalam prioritas yang sama: HP dengan total airtime terlayani paling kecil.
3. Kalau seri: frame yang paling lama menunggu.

Tiap HP adalah satu flow. Trafik node sendiri, yaitu relay dan gossip, adalah satu flow lagi dan tidak kena batas per HP.

### Jatah per HP

Batas dasarnya `TXQ_OWNER_MAX`, default `TXQ_SLOTS / 3`, yaitu 8 frame.

| Jenis data | Batas frame antre per HP |
|---|---:|
| ACK | 8 |
| Teks dan suara | 7 |
| Gambar/lampiran dan kelas di bawahnya | 6 |

Kalau batas terlewati, paket ditolak dengan status sibuk dan HP mencoba lagi. Paket yang lebih besar dari jatah tetap boleh masuk saat antrean HP itu kosong, karena paket tidak bisa dipecah di tahap ini. Batas antrean global dari `FIRMWARE_QOS.md` tetap berlaku.

### HP yang baru aktif

HP yang baru punya antrean mulai dari pemakaian terendah milik flow yang sedang aktif. HP yang diam tidak menabung jatah, dan beban lamanya juga tidak dibawa. Saat antrean benar-benar kosong, semua catatan pemakaian kembali ke nol.

### Batas lain yang tidak berubah

| Batas | Nilai `TESTING` | Nilai produksi menurut komentar kode | Kalau habis |
|---|---|---|---|
| Laju per HP | Burst 3 paket, 0,3 paket/detik | 0,1 paket/detik | Paket ditolak, app menampilkan "Node sibuk: Batas laju HP" |
| Airtime bersama | Duty 5%, tabungan 8000 ms | Duty 1% | Frame menunggu di antrean |

## Contoh kasus

HP A, B, dan C tersambung ke satu node dan mengirim ke node lain lewat LoRa. Tabungan airtime sudah habis, jadi satu frame gambar 400 ms perlu menunggu sekitar 8 detik dan chat 300 ms sekitar 6 detik. A spam gambar lebih dulu, B mengirim gambar sedikit setelahnya, dan C mengirim satu chat.

| | Urutan kirim | B mulai terkirim setelah |
|---|---|---|
| Sebelum | `C, A1, A2, ... A18, B1` | Sekitar 150 detik |
| Sesudah | `C, A1, B1, A2, B2, ...` | Sekitar 14 detik |

Kapasitas node tidak bertambah. Yang berubah hanya pembagiannya.

## Istilah

Mekanisme ini adalah **antrean adil berbasis airtime (least-served-first)**, bukan Deficit Round Robin murni. Tidak ada quantum, saldo, atau putaran. DRR berasal dari Shreedhar dan Varghese, SIGCOMM 1995 (verifikasi lagi sebelum dipakai sebagai sitasi). Jangan menyebut implementasi ini DRR di laporan kecuali kodenya diganti.

DRR murni baru perlu bila HP tertentu harus mendapat porsi berbeda. Quantum awal yang masuk akal adalah airtime satu frame penuh pada SF aktif, sekitar 400 ms di SF7.

## Batasan

- Prioritas tetap mutlak. Spam teks masih bisa mendahului suara dan gambar HP lain, walau jumlahnya dibatasi.
- Penerusan BLE ke HP lain di node yang sama terjadi sebelum cek batas laju, dan tidak melewati antrean LoRa.
- Tes 1 node dengan beberapa HP tidak memperlihatkan efek ini. Pesan antar-HP di node yang sama lewat BLE. Buktinya perlu 2 node.
- HP yang menitip lewat HP perantara dihitung sebagai flow HP perantara. Keadilan di antara mereka tetap diatur `NodeQueue.kt` di HP perantara.
- Nomor HP dipetakan ke 8 flow dengan modulo, jadi HP bernomor 8 berbagi flow dengan HP 0. Untuk BLE dengan 3 slot ini tidak berpengaruh.

## Letak perubahan

| File | Isi |
|---|---|
| `NusaNode/src/protocol/nusa_admission_trace.h` | `OWNER_NODE`, `currentOwner`, `OwnerScope` |
| `NusaNode/src/protocol/nusa_admission.cpp` | `dispatch` menandai HP asal paket |
| `NusaNode/src/lora/nusa_radio.cpp` | Pemilik slot, batas per HP, pemilihan adil, penagihan airtime |
| `tests/scheduler_test.cpp` | Bergantian, batas jatah, HP baru, adil menurut airtime, flow node |
| `tests/admission_test.cpp` | HP asal terbaca benar selama `dispatch` dan bersih sesudahnya |

`.ino` dan `config.h` tidak diubah. Setelah mengubah `NusaNode/src`, jalankan `sync-radio-modules.ps1` agar varian SF ikut sama.

## Catatan menjalankan tes

`tests/run_duplex_tests.ps1` butuh `g++` atau `zig`, dan keduanya tidak terpasang di PC ini. Hasil di atas memakai Visual C++ 2022 dengan daftar suite dan define yang sama.

Suite SPI (`duplex-spi`, `quasi-spi`, `takeover-spi`) gagal compile selama `OLED_ENABLED 1`, karena `config.h` sengaja menolak OLED bersama radio SPI eksternal. Suite itu dijalankan pada salinan sementara dengan OLED dimatikan.

## Langkah berikutnya

1. Bereskan koneksi USB, lalu flash board. Colok ke port yang terbukti jalan, yaitu Port 5 Hub 1 atau port yang dipakai HP saat instal APK.
2. Uji 2 node: HP A spam gambar, HP B kirim gambar, HP C kirim chat. Catat urutan, waktu tunggu, dan status sibuk.
3. Ubah batas laju per HP menjadi berbasis airtime.
4. Batasi penerusan BLE di node yang sama.
5. Buat jatah airtime di app (`DUTY_SHARE`) mengikuti jumlah HP aktif, sejalan dengan P2 di `NEXT_STEPS.md`.
