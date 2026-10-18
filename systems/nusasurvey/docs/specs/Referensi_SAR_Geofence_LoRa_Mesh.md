# Referensi Sistem dan Penelitian: Geofence, Penyisiran SAR, dan Navigasi Korban melalui BLE–LoRa Mesh

Tanggal penelusuran: 13 Oktober 2026.

## 1. Tujuan dan kesimpulan penelusuran

Rencana NusaNode: node LoRa mesh disebar di wilayah terdampak; HP korban dan petugas mengaksesnya melalui BLE; koordinat korban, penugasan sektor, dan status dikirim melalui mesh; petugas menyisir wilayah yang ditentukan dan memperoleh panduan menuju korban.

Sistem yang relevan tidak hanya OSRM, CalTopo, dan CompassRespond. Ada ATAK, QField, SARCOP, ArcGIS Field Maps, ARTEMIS, dan integrasi Meshtastic–TAK. Namun, kemampuan dan bukti penerapannya berbeda. Tidak semua menyediakan geofence otomatis, tidak semua open source, dan tidak semua menggunakan LoRa.

Penelitian yang mengimplementasikan bagian-bagian ide ini sudah ada: tracking SAR berbasis LoRa, geofence pendaki berbasis LoRa, pencarian LoRa–UAV, serta geofence keselamatan perairan. Dalam penelusuran ini, belum terverifikasi satu publikasi yang sekaligus menguji geofence sektor tim darat, panduan korban, BLE–LoRa mesh, dan mobilitas pengguna. Ini batas penelusuran, bukan bukti bahwa kombinasi tersebut belum pernah diteliti.

Prioritas referensi ilmiah adalah 2023–2026, mengikuti kebutuhan referensi terbaru TA. Dokumentasi perangkat lunak tidak diperlakukan sebagai jurnal.

## 2. Pisahkan fungsi sebelum menentukan kebaruan

| Fungsi | Pertanyaan yang dijawab | Implementasi yang dibutuhkan |
|---|---|---|
| Registrasi pengguna | Pengguna sedang dilayani node mana? | Tabel user–node dan pembaruan registrasi |
| Lokalisasi | Pengguna berada di koordinat mana? | GPS HP/perangkat atau metode estimasi posisi |
| Tracking | Bagaimana posisi berubah terhadap waktu? | Koordinat, timestamp, identitas, rekaman lintasan |
| Pembagian sektor | Tim mana bertugas di wilayah mana? | Poligon sektor dan penugasan |
| Geofence | Posisi berada di dalam/luar batas? | Pemeriksaan titik terhadap poligon dan aturan notifikasi |
| Navigasi langsung | Ke arah mana dan sejauh apa korban? | Koordinat petugas dan korban, bearing dan jarak |
| Routing medan | Lintasan mana yang dapat dilalui? | Data elevasi, tutupan lahan, hambatan, dan biaya lintasan |
| Evaluasi penyisiran | Bagian mana yang sudah diperiksa? | Lintasan, asumsi jangkauan pengamatan, laporan tim |

Geofence tidak menghitung rute. Koordinat sebuah titik tidak menunjukkan bahwa lintasan menuju titik itu dapat dilalui. Rekaman lintasan juga tidak membuktikan seluruh sektor sudah diperiksa atau korban pasti terdeteksi.

## 3. Sistem, repo, dan dokumentasi

### S1. CalTopo/SARTopo — acuan alur kerja SAR

Menyediakan objek sektor pencarian dan penugasan, rekaman lintasan, serta navigasi langsung atau mengikuti garis. Studi kasus Inyo County SAR di Mt. Williamson menunjukkan penggunaan peta medan dan lintasan terdahulu dalam penyelamatan pegunungan. Jangan menganggap kasus itu membuktikan penggunaan alarm geofence. [D1–D4]

- [Membuat search segments dan assignments](https://training.caltopo.com/firstresponse/course/segment)
- [Mengelola tracks dan data lapangan](https://training.caltopo.com/firstresponse/course/tracks)
- [Fungsi navigasi](https://training.caltopo.com/all_users/mobile/navigation)
- [Kasus Inyo County SAR](https://caltopo.com/rescue)

Status kode: repo publik aplikasi utama belum ditemukan dalam penelusuran ini. Gunakan sebagai acuan kebutuhan operasional.

### S2. ATAK-CIV — aplikasi koordinasi dengan source code

ATAK menyediakan kesadaran situasional, navigasi, pertukaran informasi lokasi, dan geofence dengan pemicu. Source code tersedia di organisasi TAK Product Center. [D5–D6]

- [Repo ATAK-CIV](https://github.com/TAK-Product-Center/atak-civ)
- [Petunjuk build](https://github.com/TAK-Product-Center/atak-civ/blob/main/BUILDING.md)
- [Penjelasan fitur](https://www.civtak.org/atak-about/)

Relevansi: acuan geofence, penanda korban, dan koordinasi tim pada peta. Tidak otomatis kompatibel dengan protokol firmware NusaNode.

### S3. Meshtastic–TAK — jembatan mesh dan peta

Meshtastic Android mempunyai modul Local TAK Server untuk menjembatani data mesh dan format Cursor on Target (CoT). Repo pluginmeshtastic menawarkan koneksi perangkat langsung melalui BLE/USB. [D7–D8]

- [Repo Meshtastic Android](https://github.com/meshtastic/Meshtastic-Android)
- [Docs Local TAK Server](https://github.com/meshtastic/Meshtastic-Android/blob/main/core/takserver/README.md)
- [Plugin langsung BLE/USB](https://github.com/meshtastic/pluginmeshtastic)
- [Plugin terdahulu](https://github.com/meshtastic/ATAK-Plugin)

Catatan versi: dokumentasi Local TAK Server menyebut perubahan API yang memengaruhi plugin lama. Periksa README dan kompatibilitas versi sebelum mencoba APK. Integrasi ini menjadi pembanding arsitektur, bukan bukti bahwa semua fitur SAR sudah diuji di lapangan. NusaNode dengan protokol sendiri memerlukan adaptor.

### S4. QField/QGIS — acuan kode geofence dan navigasi lapangan

QField menyediakan geofence berdasarkan poligon: memberi tahu pengguna saat masuk/keluar atau memperingatkan ketika berada di dalam/luar zona. Navigasinya membantu menuju fitur/titik target. Ini perangkat GIS lapangan; dokumentasi fitur tidak membuktikan pemakaian khusus oleh tim SAR tertentu. [D9–D11]

- [Repo QField](https://github.com/opengisch/QField)
- [Docs geofence](https://docs.qfield.org/how-to/navigation-and-positioning/geofencing/)
- [Docs navigasi](https://docs.qfield.org/how-to/navigation-and-positioning/navigation/)
- [Pengumuman implementasi geofence QField 3.4, 2024](https://qfield.org/blog/2024/10/08/qfield-3.4-ebo-geofencing-and-processing-out-of-the-box/)

Relevansi: contoh nyata perilaku geofence, tampilan peringatan, dan integrasi batas poligon. Tambahan komunikasi mesh tetap harus dirancang.

### S5. SARCOP — koordinasi dan sektor pencarian kebencanaan

Search and Rescue Common Operating Platform mendukung pertukaran informasi lintas lembaga. Layanan datanya memiliki search segments dan waypoints. Jangan menyamakan layer sektor dengan fitur alarm geofence. [D12–D13]

- [NAPSG Foundation](https://www.napsgfoundation.org/)
- [Pelatihan yang membahas SARCOP](https://www.napsgfoundation.org/inspire2023)
- [Contoh skema search segments](https://services.arcgis.com/0ZRg6WRC7mxSLyKX/ArcGIS/rest/services/20230322_Testing_Segments/FeatureServer/0)

Status kode: repo publik lengkap platform belum ditemukan. Berguna untuk struktur sektor, status, dan informasi operasi.

### S6. CompassRespond — tracking radio dan geofence

Produk menyediakan lokasi radio, peta offline, replay lintasan, dan geofence masuk/keluar yang dapat ditentukan saat respons. SAR termasuk penggunaan yang disebut penyedia. Halaman tersebut belum cukup untuk membuktikan operasi SAR tertentu menggunakan alarmnya. [D14]

- [Dokumentasi produk dan FAQ](https://compasscom.com/products/compassrespond/)

Pembanding: fungsi geofence lokal dan koordinasi radio. Ini produk komersial; bukan repo open source.

### S7. ArcGIS Field Maps — geofence, penugasan, dan rekaman lokasi

Geofence dapat memicu notifikasi serta mulai/berhenti berbagi lokasi. Esri memberi contoh kemungkinan pemakaian untuk mengetahui jalur yang sudah dijelajahi SAR; contoh tersebut merupakan skenario konfigurasi, bukan laporan operasi nyata. [D15]

- [Docs/tutorial geofence dan penugasan, 2026](https://www.esri.com/arcgis-blog/products/field-maps/field-mobility/arcgis-field-maps-let-task-status-drive-location-sharing-using-geofencing)

Pembanding: hubungan status tugas, batas wilayah, dan aktivitas tracking.

### S8. ARTEMIS — sistem deteksi dan lokasi ponsel untuk SAR

Smith Myers mendokumentasikan sistem deteksi/lokasi ponsel dengan mass mapping dan geofencing untuk membantu pencarian. Fungsi ini tidak identik dengan geofence batas tugas petugas. [D16]

- [Dokumentasi ARTEMIS Land & Sea](https://smithmyers.com/landsea/)
- [Dokumentasi ARTEMIS Air](https://smithmyers.com/air/)

Ini sistem komersial dengan arsitektur berbeda dari HP yang secara sukarela mengirim GPS melalui BLE. Jangan menganggap LoRa dapat mendeteksi semua ponsel tanpa aplikasi/perangkat kompatibel.

### S9. SARcom — kandidat produk pendukung

Menawarkan tracking personel/kendaraan, batas misi, grid, dan peringatan geofence. Bukti yang diperiksa adalah halaman penyedia, bukan laporan pengujian independen atau adopsi oleh tim tertentu. [D17]

- [Team & Vehicle Tracking](https://sarcom.co.uk/team-tracking.html)

### Komponen untuk aplikasi sendiri

| Komponen | Referensi | Fungsi |
|---|---|---|
| Peta Android offline | [MapLibre Native](https://github.com/maplibre/maplibre-native), [API offline](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/index.html) | Menampilkan peta yang sumber daya wilayahnya disiapkan sebelumnya |
| Titik di dalam poligon | [Turf point-in-polygon](https://turfjs.org/docs/7.2.0/api/booleanPointInPolygon), [kode](https://github.com/Turfjs/turf/blob/master/packages/turf-boolean-point-in-polygon/index.ts) | Dasar pemeriksaan geofence |
| Arah dan jarak | [Bearing](https://turfjs.org/docs/api/bearing), [distance](https://turfjs.org/docs/next/api/distance) | Dasar panduan menuju target |
| Struktur pesan mesh | [Meshtastic protobufs](https://github.com/meshtastic/protobufs/) | Pembanding format paket dan informasi posisi |

MapLibre adalah renderer, bukan mesin routing medan. Turf.js menggunakan JavaScript. Peta dasar sebaiknya disiapkan di HP sebelum operasi; mesh membawa pembaruan ringkas, bukan seluruh tile peta.

## 4. Publikasi ilmiah dan batas bukti

Bagian “peluang” di bawah adalah analisis untuk NusaNode, bukan pernyataan bahwa penulis mengusulkannya. Tidak ada angka kinerja yang diisi berdasarkan tebakan. Ringkasan yang hanya didasarkan pada abstrak ditandai.

### P1. Tracking tim SAR Sukoharjo menggunakan LoRa — jurnal, 2024

**A. Ari Andrianto**, “Implementation of Internet of Things (IoT) in Tracking the Position of Search and Rescue Teams in Sukoharjo District Using LoRa,” SMATIKA, 14(1), hlm. 86–95.

- [Artikel](https://jurnal.ubhinus.ac.id/SMATIKA/article/view/1222)
- [PDF lengkap](https://jurnal.ubhinus.ac.id/index.php/SMATIKA/article/download/1222/751/5232)
- [DOI pada halaman artikel](https://doi.org/10.32664/smatika.v14i01.1222)

**Metode/bukti:** prototipe GPS–Mappi32 mengirim koordinat melalui LoRa ke gateway; data diteruskan melalui internet ke Firebase dan aplikasi Flutter. Demonstrasi menunjukkan perubahan lokasi pada peta. Tabel 1, hlm. 94, melaporkan waktu memperoleh GPS 20 menit indoor dan 8 menit outdoor untuk setup tersebut.

**Batas:** rantai gateway–server–aplikasi masih memakai internet. Artikel tidak memberi pembuktian kuantitatif lengkap geofence, mesh multihop, handover, PDR, atau efisiensi pencarian korban.

**Peluang NusaNode:** pengoperasian lokal tanpa Firebase, registrasi/handover BLE, dan pengujian keandalan paket serta usia lokasi. Angka waktu GPS tersebut bukan spesifikasi umum GPS.

**Catatan bibliografi:** DOI di kepala PDF berbeda dengan DOI halaman artikel; gunakan metadata halaman penerbit dan periksa kembali saat memasukkan ke pengelola referensi.

### P2. Geofencing pendaki berbasis LoRa — prosiding, 2026

**Nur April Irfansyah, Arief Jaenul, Legenda Prameswono Pratama**, “Rancang Bangun Sistem Pemantauan Pendaki Gunung dengan Geofencing Berbasis LoRa,” SINERGI, 2(1), 4 Mei 2026.

- [Artikel](https://journal.unj.ac.id/unj/index.php/sinergi/article/view/67674)
- [DOI](https://doi.org/10.21009/sinergi.v2i1.67674)
- [Halaman PDF](https://journal.unj.ac.id/unj/index.php/sinergi/article/view/67674/23253)

**Metode/bukti:** abstrak menyebut GPS Beitian BN-220, Heltec LoRa 32 V3, zona berbahaya, SOS, buzzer, serta pengiriman ke cloud/Telegram. Penulis melaporkan keberhasilan geofence dan komunikasi darurat.

**Batas verifikasi:** yang berhasil dibaca adalah abstrak. Angka akurasi, delay, packet loss, bentuk zona, dan kondisi pengujian belum diverifikasi dari PDF. Ini prosiding, bukan jurnal. Bukan bukti pembagian sektor petugas SAR.

**Peluang NusaNode:** geofence sektor tim dan zona korban secara lokal, sinkronisasi melalui mesh, serta pengukuran false alarm dan delay lintas hop.

### P3. Body–UAV LoRa di hutan — jurnal, 2023

**Giulio Maria Bianco dan Gaetano Marrocco**, “Body-UAV Near-Ground LoRa Links through a Mediterranean Forest,” IEEE Transactions on Antennas and Propagation.

- [Metadata dan naskah terbuka](https://arxiv.org/abs/2303.12754)
- [DOI jurnal](https://doi.org/10.1109/TAP.2023.3260580)

**Metode/bukti:** pengukuran link 868 MHz antara radio yang dikenakan manusia dan UAV hingga ketinggian 30 m. Mengevaluasi jangkauan, PDR, fading, dan model path loss yang memperhitungkan tubuh serta gerakan.

**Hasil:** jangkauan pada setup sekitar 600 m; model baru menurunkan median kesalahan prediksi path loss hingga sekitar 10 dB dibanding model pembanding.

**Batas:** bukan geofence atau mesh tim darat. Frekuensi, antena, vegetasi, dan geometri berbeda dari NusaNode 923 MHz.

**Peluang:** uji PDR, RSSI/SNR, penempatan node, dan bayangan tubuh pada skenario lokal. Jangan menjadikan 600 m sebagai jaminan jangkauan perangkat sendiri.

### P4. Catch Me If You Can — LoRa–UAV dan deep meta-RL

**Mehdi Naderi Soorki dkk.**, “Catch Me If You Can: Deep Meta-RL for Search-and-Rescue Using LoRa UAV Networks.”

- [Naskah terbuka, 2023](https://arxiv.org/abs/2306.02911)
- [PDF](https://arxiv.org/pdf/2306.02911)
- [DOI publikasi jurnal yang teridentifikasi](https://doi.org/10.1109/TMC.2024.3468382)

**Metode/bukti:** prototipe node LoRa korban, gateway UAV, dan gateway darat. Pengukuran dilakukan di dataran dan ngarai; kebijakan pencarian dinilai dengan data radio. Naskah menyebut evaluasi numerik kebijakan yang dilatih memakai data nyata.

**Hasil:** perbandingan yang dilaporkan adalah 141 slot untuk deep RL dan 50 slot untuk deep meta-RL. Slot bukan detik. Jangan menyebut seluruh angka sebagai keberhasilan operasi SAR nyata atau bukti pengendalian penerbangan otonom end-to-end tanpa memeriksa setup lebih lanjut.

**Status:** isi di sini berdasarkan naskah terbuka 2023; metadata publikasi final jurnal perlu dicocokkan sebelum sitasi final.

**Peluang:** metrik waktu hingga target ditemukan dan biaya komunikasi; UAV/AI bukan keharusan untuk pengembangan mesh darat.

### P5. Dataset eksperimen SAR longsoran salju berbasis LoRa — jurnal dan dataset, 2024

**Michele Girolami dkk.**, “An Experimental Dataset for Search and Rescue Operations in Avalanche Scenarios Based on LoRa Technology,” IEEE Access, 12, hlm. 171015–171035.

- [DOI jurnal](https://doi.org/10.1109/ACCESS.2024.3497654)
- [Dataset v2 dan dokumentasi eksperimen](https://zenodo.org/records/13932869)

**Metode/bukti:** data lapangan RSSI/SNR, posisi referensi, kedalaman pemancar, dan kondisi salju. Ada uji penerima darat, jangkauan, serta UAV di 121 titik grid pada area 100 m².

**Batas verifikasi:** metode diringkas dari dokumentasi dataset penulis; PDF jurnal penuh tidak berhasil dibaca. Dataset bukan bukti algoritma geofence atau navigasi petugas sudah diimplementasikan.

**Peluang:** meniru disiplin pencatatan ground truth, kondisi lingkungan, dan orientasi antena. Hasil lingkungan salju tidak langsung berlaku pada reruntuhan, banjir, atau hutan Indonesia.

### P6. Geofence dan rescue boat — prosiding, 2026

**M. G. D. Shakthi dkk.**, “Implementation of a GPS Guided Remote Controlled Surveillance Rescue Boat,” 23rd Academic Sessions, University of Ruhuna, hlm. 80, 4 Maret 2026.

- [Rekaman universitas, abstrak, dan PDF](https://ir.lib.ruh.ac.lk/handle/iruor/21726)

**Metode/bukti:** prototipe kapal penyelamat dan gelang GPS dengan geofence. Pelanggaran batas memicu SMS koordinat melalui GSM. Abstrak melaporkan latensi notifikasi kurang dari 5 detik pada uji lapangan.

**Batas:** prosiding sesi akademik, bukan jurnal; ringkasan berdasarkan abstrak universitas. Ketergantungan GSM dan geofence perenang berbeda dari sektor penyisiran tim darat.

**Peluang:** alarm batas tanpa GSM dan evaluasi latensi lokal dibanding latensi penerimaan alarm di posko.

### P7. Perilaku petugas SAR dari data GPS — jurnal empiris, 2026

**Amanda Hashimoto, Eighdi Aung, Robert Koester, Nicole Abaid**, “Speed, slope, and synchrony: Empirical insights into SAR searcher behavior,” PLOS ONE, 21(6), e0339541.

- [Artikel lengkap](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0339541)
- [DOI](https://doi.org/10.1371/journal.pone.0339541)
- [Dataset GPS yang dirujuk penulis](https://doi.org/10.5281/zenodo.18637221)

**Metode/bukti:** analisis 61 lintasan dari 13 kasus, diseleksi dari kumpulan 64 insiden SAR. Membandingkan hasty, sweep, kemiringan, dan koordinasi tim. Median kecepatan hasty 0,53 m/s dan sweep 0,39 m/s pada data studi.

**Batas:** bukan implementasi LoRa/geofence. Tidak boleh memakai angka tersebut sebagai kecepatan universal tim SAR.

**Peluang:** evaluasi coverage, jarak antarpetugas, waktu penyisiran, dan interval lokasi berdasarkan pola gerak. Ini rujukan parameter operasional, bukan baseline jaringan.

### Kandidat ilmiah tambahan yang perlu dibaca lebih lanjut

1. **A LoRa-Based Disaster Management System for Search and Rescue Mission**, IEEE Internet of Things Journal, 11(20), 2024. [Halaman penerbit](https://ieeexplore.ieee.org/document/10614393/). Abstrak yang terindeks menyebut Titan DMS. PDF/metode/hasil belum diverifikasi; belum dipakai untuk angka atau klaim geofence.
2. **Transponder: Support for Localizing Distressed People through a Flying Drone Network**, Drones, 8(9), 465, 2024. [Artikel](https://www.mdpi.com/2504-446X/8/9/465), [DOI](https://doi.org/10.3390/drones8090465). Abstrak menyebut solusi Wi-Fi dan prototipe. Jangan otomatis menggolongkan versi 2024 sebagai LoRa karena ada karya Transponder terdahulu dengan arsitektur berbeda. Detail implementasi belum dibaca penuh.
3. Publikasi geolokasi LoRaWAN tahun 2020 dapat menjadi bacaan historis, tetapi berada di luar prioritas referensi terbaru: [Geolocation analysis for Search And Rescue systems using LoRaWAN](https://doi.org/10.1002/dac.4593).

## 5. Parameter improvement yang dapat diuji

Semua pilihan berikut adalah rancangan eksperimen yang diusulkan untuk NusaNode, bukan hasil penelitian yang sudah diperoleh.

| Kandidat improvement | Baseline yang harus dibuat | Parameter utama | Tradeoff |
|---|---|---|---|
| Pengiriman posisi adaptif | Interval tetap | Jumlah paket, airtime, energi, usia lokasi, error posisi saat ditampilkan | Penghematan dapat membuat posisi lebih lama |
| Geofence dengan hysteresis dan waktu konfirmasi | Point-in-polygon setiap sampel | False alarm, missed event, delay alarm lokal | Alarm lebih stabil dapat terlambat |
| Pengiriman alarm prioritas | Antrean FIFO | PDR alarm, p95 delay, retransmisi, keterlambatan pesan biasa | Prioritas dapat menunda traffic lain |
| Sinkronisasi sektor melalui mesh | Kirim ulang seluruh poligon | Byte/hop, waktu sinkronisasi, keberhasilan penerimaan, konsistensi versi | Delta membutuhkan recovery saat versi hilang |
| Mobilitas BLE dan registrasi pengguna | Akses node tetap atau reconnect biasa | Lama putus, waktu registrasi, packet loss saat berpindah, salah tujuan | Signaling menambah airtime |
| Navigasi korban dengan indikator freshness | Marker tanpa timestamp | Usia lokasi, error arah/jarak, waktu mencapai target | Tidak memperbaiki GPS atau medan secara otomatis |
| Pencatatan coverage penyisiran | Hanya menampilkan jejak | Cakupan geometris, overlap, waktu, area kosong | Lebar pengamatan harus dikalibrasi |
| Routing medan dengan zona hambatan | Panduan garis lurus atau lintasan manual | Panjang rute, waktu perjalanan, pelanggaran zona terlarang, keberhasilan mencapai target | Membutuhkan data medan terbaru |

### Definisi metrik agar pengujian tidak ambigu

- **PDR end-to-end:** paket unik yang diterima tujuan dibagi paket unik yang dibuat sumber. Bedakan dari keberhasilan per-hop; paket duplikat tidak menambah keberhasilan.
- **Delay end-to-end:** selisih waktu pengiriman sumber dan penerimaan tujuan; membutuhkan sinkronisasi waktu yang diketahui akurasinya.
- **Delay alarm lokal:** waktu sejak crossing referensi hingga notifikasi HP.
- **Delay alarm posko:** waktu sejak crossing referensi hingga alarm diterima posko. Pisahkan keterlambatan sensor, keputusan geofence, antrean, dan radio.
- **Age of Information (AoI):** waktu sekarang dikurangi timestamp pengambilan posisi terbaru yang tersedia di penerima. Mengukur kesegaran, bukan hanya kecepatan sebuah paket.
- **False alarm:** alarm crossing yang tidak sesuai crossing referensi; laporkan per kejadian atau per jam dengan denominator jelas.
- **Missed event:** crossing referensi yang tidak terdeteksi dalam batas waktu yang telah ditentukan.
- **Energi:** joule atau mWh per jam operasi dan per laporan berhasil; ukur HP dan node terpisah.
- **Coverage geometris:** luas gabungan buffer lintasan di dalam sektor dibagi luas sektor. Gunakan radius buffer sebagai asumsi eksplisit; metrik ini bukan probability of detection korban.
- **Waktu menuju korban:** mulai setelah target dipilih sampai petugas masuk radius kedatangan yang ditentukan berdasarkan akurasi posisi.

Crossing referensi perlu diukur secara independen dari log GPS yang sama yang menjadi input algoritma. Untuk uji awal bisa memakai lintasan yang diketahui, penanda batas, dan rekaman waktu; dokumentasikan ketidakpastian ground truth.

## 6. Fokus yang realistis untuk TA empat node

Usulan fokus: **geofence lokal yang stabil dan pelaporan posisi adaptif melalui BLE–LoRa mesh, dengan panduan arah/jarak menuju korban.**

Implementasi minimum:

1. Peta offline tersedia pada HP petugas.
2. Komandan membuat sektor poligon sederhana dan menugaskannya pada tim.
3. Paket sektor membawa ID serta nomor versi; penerima mengonfirmasi versi.
4. HP mengevaluasi geofence secara lokal, sehingga notifikasi lokal tidak menunggu mesh.
5. Crossing dan SOS dikirim sebagai kejadian prioritas; lokasi biasa dikirim adaptif.
6. Korban mengirim koordinat, timestamp, akurasi yang dilaporkan GPS, dan kondisi.
7. HP petugas menampilkan arah, jarak, dan usia laporan; posisi lama tetap diberi label.
8. Rekaman lintasan tersimpan lokal dan diperbarui ke posko ketika komunikasi tersedia.

Rancangan paket ini konseptual; identitas, otorisasi, ukuran field, dan penanganan paket hilang harus disesuaikan dengan protokol NusaNode. Hindari mengirim GeoJSON panjang berulang kali jika format biner ringkas cukup.

GPS dan kompas HP memberikan input navigasi. RSSI BLE dipakai untuk keputusan koneksi/handover; jangan dianggap sebagai koordinat korban. RSSI LoRa juga tidak otomatis menjadi jarak yang akurat.

### Eksperimen yang disarankan

**Eksperimen A — stabilitas geofence.** Bandingkan baseline dengan hysteresis/waktu konfirmasi. Uji diam dekat batas, berjalan masuk/keluar, lalu berjalan sejajar batas. Variasikan buffer dan durasi konfirmasi; ukur false alarm dan delay.

**Eksperimen B — kesegaran posisi vs airtime.** Bandingkan interval tetap dengan pemicu perpindahan/kejadian plus batas waktu maksimum. Uji korban diam, petugas berjalan, dan perpindahan BLE. Ukur paket, airtime, energi, AoI, dan error posisi di penerima.

**Eksperimen C — alarm lintas mesh.** Uji 1–3 hop sesuai empat node yang tersedia, dengan traffic lokasi bersamaan. Bandingkan FIFO dan prioritas; ukur PDR, p50/p95 delay, dan retransmisi. Uji ulang dengan relay gagal.

**Eksperimen D — fungsi navigasi.** Gunakan target terkendali, panduan arah/jarak, dan waypoint yang telah diperiksa. Ukur keberhasilan mencapai target serta usia koordinat. Jangan menyebut hasilnya routing aman otomatis bila tidak menguji hambatan dan data medan.

Jaga beban, parameter radio, perangkat, dan lintasan pembanding setara. Laporkan jumlah pengulangan, distribusi hasil, serta kasus gagal. Hipotesis improvement perlu diuji; tidak menetapkan persentase keberhasilan sebelum data tersedia.

## 7. Posisi kebaruan yang dapat dipertanggungjawabkan

Geofence, navigasi waypoint, peta offline, dan pengiriman posisi melalui LoRa masing-masing sudah ada. Klaim “sistem geofence SAR pertama” atau “pertama menghubungkan mesh dengan peta” tidak didukung penelusuran ini.

Kontribusi yang lebih terukur adalah:

> Merancang dan mengevaluasi pengiriman posisi adaptif serta notifikasi geofence lokal pada arsitektur BLE–LoRa mesh dengan dukungan mobilitas pengguna, untuk menjaga kesegaran informasi lokasi dan keandalan alarm saat konektivitas terbatas.

Navigasi arah/jarak dapat menjadi layanan pendukung. Routing medan otomatis merupakan scope tambahan yang memerlukan evaluasi tersendiri. Memasukkan terlalu banyak fitur tanpa baseline dan metrik membuat improvement sulit dibuktikan.

## 8. Urutan baca

1. P1: baseline tracking SAR, ketergantungan internet, dan kekurangan evaluasi.
2. P2: implementasi geofence–LoRa terdekat; baca PDF sebelum mengutip angka.
3. QField: perilaku geofence dan navigasi yang bisa dipelajari dari kode.
4. ATAK dan Meshtastic–TAK: pembanding integrasi mesh–peta.
5. P3 dan P5: desain pengukuran radio dan kondisi lingkungan.
6. P7: parameter gerak dan coverage manusia.
7. P4: pendekatan pencarian yang lebih kompleks sebagai pengembangan lanjutan.

## 9. Indeks sumber dokumentasi

D1–D4: empat tautan CalTopo di S1.  
D5–D6: repo ATAK dan penjelasan fitur di S2.  
D7–D8: README Local TAK Server dan pluginmeshtastic di S3.  
D9–D11: repo QField, docs geofence, dan docs navigasi di S4.  
D12–D13: NAPSG dan layer search segments di S5.  
D14: CompassRespond di S6.  
D15: tutorial Esri di S7.  
D16: Smith Myers Land & Sea di S8.  
D17: SARcom di S9.

Sumber ilmiah P1–P7 memiliki tautan penerbit/DOI/naskah pada masing-masing bagian. Identitas dataset dan artikel jurnal berbeda; sitasikan sesuai yang benar-benar digunakan. Daftar ini bukan systematic literature review dan bukan daftar seluruh sistem SAR di dunia.

