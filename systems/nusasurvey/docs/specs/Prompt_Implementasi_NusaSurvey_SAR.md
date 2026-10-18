# Prompt Implementasi NusaSurvey — Mesin Navigasi Medan untuk Pertolongan SAR

## Cara memakai

Untuk pengarahan awal yang to the point, salin bagian “Prompt singkat siap pakai”. Lampirkan dokumen ini sebagai spesifikasi rinci. Prioritas utama adalah mesin routing medan dan navigasi SAR; fitur koordinasi mengikuti setelah alur navigasi bekerja. Lampirkan repo aplikasi dan spesifikasi protokol NusaNode bila tersedia.

## Prompt singkat siap pakai

Bangun **NusaSurvey**, sistem navigasi buatan sendiri dengan fungsi seperti OSRM, khusus untuk mengarahkan tim SAR menuju korban saat pertolongan kebencanaan.

**Target utama:** aplikasi menerima posisi petugas dan koordinat korban, menghitung kandidat rute berdasarkan data medan, lalu memberikan panduan perjalanan, jarak sepanjang rute, dan estimasi waktu tempuh. Sistem harus dapat merencanakan lintasan ketika tidak ada jalan atau jalur terpetakan, selama data medan tersedia dan constraint memungkinkan.

Implementasikan berurutan:
1. Impor paket wilayah berisi elevasi, tutupan lahan, jalan/jalur, dan hambatan. Simpan lokal dengan sumber, resolusi, tanggal, dan versi.
2. Bangun mesin routing medan sendiri memakai graph/grid serta Dijkstra atau A*. Biaya mempertimbangkan grade naik/turun, tutupan lahan, kemampuan gerak tim, dan hambatan. Zona yang dilarang dilalui menjadi hard constraint.
3. Sediakan layanan routing milik aplikasi: input posisi awal, tujuan, profil tim, dan versi data; output geometry rute, waypoint, jarak, estimasi waktu, profil elevasi, serta keterbatasan data.
4. Integrasikan rute pada peta dan navigasi aktif: posisi aktual petugas, arah waypoint berikutnya, jarak tersisa, waktu tersisa, serta deteksi keluar rute.
5. Izinkan petugas melaporkan hambatan, tandai rute terdampak sebagai outdated, dan hitung ulang memakai data terbaru.
6. Buktikan perhitungan dan navigasi offline pada wilayah terbatas dengan paket data lokal. Jika perhitungan hanya berjalan di posko, nyatakan kemampuan itu terpisah dari rerouting lokal di HP.
7. Integrasikan koordinat korban dan laporan melalui BLE–LoRa mesh. Pertahankan adaptor simulator bila hardware belum tersedia.
8. Tambahkan sektor pencarian, geofence, dan pencatatan penyisiran sebagai pendukung alur SAR.

**Peran OSRM:** referensi arsitektur/API dan baseline untuk jalan/jalur terpetakan. Self-hosted OSRM boleh menjadi provider tambahan. Mesin routing medan milik NusaSurvey merupakan deliverable inti. Penggunaan library GIS dan algoritma open source diperbolehkan; algoritma tidak perlu ditemukan ulang.

**Hasil pertama yang harus didemonstrasikan:** pilih dua titik pada wilayah yang memiliki data elevasi tetapi tidak memiliki jalur jalan; mesin menghasilkan kandidat lintasan yang mengikuti model biaya dan menghindari hambatan, menghitung jarak serta ETA, lalu aplikasi memandu posisi petugas sepanjang rute. Tambahkan hambatan baru dan demonstrasikan perubahan rute atau status tidak ada lintasan yang memenuhi constraint.

Gunakan data nyata bila tersedia; fixture sintetis wajib diberi label. ETA memakai model yang dinyatakan. Rute hasil perhitungan diberi status belum diverifikasi lapangan. Jangan menyebut hasil selesai jika baru berupa dashboard, garis lurus, atau respons API OSRM tanpa implementasi mesin routing medan.

Baca bagian N untuk sumber data, resource resmi, kontrak routing, dan pengujian. Audit repo terlebih dahulu, lalu kerjakan alur inti sampai dapat diuji. Fitur administratif dan statistik tambahan mengikuti setelah navigasi berfungsi.

Dokumen ini merupakan spesifikasi pengembangan, bukan klaim sistem sudah layak digunakan pada operasi SAR nyata. Fitur inti harus bisa diuji melalui simulator bila perangkat belum terhubung.

## Evaluasi screenshot referensi

Screenshot menunjukkan dashboard dengan peta operasi dan panel korban. Dari gambar saja, fungsi tombol dan sumber datanya tidak dapat diverifikasi. Perubahan yang perlu diminta:

- Tampilkan poligon sektor, anggota tim, lintasan, korban, dan zona bahaya secara jelas, bukan hanya titik node.
- Pecah “Mesh health 98,2%” menjadi indikator yang dapat dibuktikan: last heard node, link RSSI/SNR bila tersedia, delivery ratio dari ACK, dan delay dengan jumlah sampel.
- Coverage harus dihitung dari lintasan dan asumsi buffer, bukan angka tetap.
- False positive hanya muncul dalam mode evaluasi dengan ground truth; operasi biasa tidak bisa mengetahui kebenaran semua alarm secara otomatis.
- Bedakan jarak dari basecamp dengan jarak dari posisi petugas. Navigasi harus memakai posisi petugas.
- ETA 04:12 untuk jarak 815 m memerlukan asumsi kecepatan dan lintasan. Jangan menampilkan ETA presisi tanpa dasar.
- Registrasi BLE melalui ND-02 menunjukkan node pelayanan; tidak membuktikan sumber koordinat korban maupun lintasan radio yang benar-benar dilewati.
- Tampilkan akurasi sebagai nilai yang dilaporkan provider lokasi, bukan jaminan batas kesalahan.
- Peta “offline” harus diuji tanpa jaringan setelah sumber daya peta disiapkan.

---

# Prompt utama

Anda adalah engineer routing geospasial dan aplikasi lapangan. Bangun atau perbaiki **NusaSurvey**, mesin navigasi dengan fungsi seperti OSRM yang ditujukan untuk perjalanan tim SAR menuju korban di wilayah kebencanaan. Utamakan mesin routing medan sendiri, perhitungan jarak/waktu, dan panduan perjalanan. Gunakan bagian N sebagai spesifikasi inti dan bagian lain untuk fitur operasi pendukung. Hasil harus dapat digunakan dan diuji dengan penyimpanan lokal serta state yang konsisten.

## A. Konteks dan tujuan

NusaSurvey bertujuan mengarahkan tim SAR menuju korban pada wilayah terdampak bencana, termasuk medan tanpa jalur terpetakan. Navigasi menggunakan data elevasi, tutupan lahan, serta hambatan dan harus mendukung operasi lokal ketika layanan seluler tidak tersedia. BLE–LoRa mesh menyediakan pertukaran informasi; perhitungan rute dikerjakan aplikasi/mesin routing pada perangkat komputasi.

Arsitektur yang direncanakan:
- HP korban/petugas terhubung ke node NusaNode melalui BLE.
- Node meneruskan paket melalui LoRa mesh.
- Aplikasi bertukar lokasi, status, sektor penugasan, dan pesan darurat.
- Peta dasar disiapkan sebelum operasi dan tidak dikirim sebagai tile melalui LoRa.
- Registrasi pengguna dan perpindahan koneksi BLE tersedia melalui firmware/protokol bila sudah diimplementasikan.

Tujuan pengguna:
1. Komandan membuat operasi, membagi wilayah pencarian, menugaskan tim, dan melihat laporan.
2. Petugas menerima penugasan, menyisir sektor, mencatat temuan, dan mendapat peringatan geofence.
3. Korban mengirim lokasi serta permintaan bantuan.
4. Petugas mendapat panduan arah/jarak menuju korban dan waypoint yang ditentukan.
5. Semua aktivitas tetap tersimpan ketika komunikasi terputus.

Jangan menjadikan fitur routing jalan sebagai syarat agar panduan arah/jarak bekerja. Panduan arah langsung tidak menjamin medan dapat dilalui.

## B. Mulai dari repo yang ada

Sebelum mengubah kode:
- Baca README dan instruksi repo; identifikasi framework, platform, peta, storage, serta integrasi BLE yang sudah ada.
- Pertahankan stack dan fungsi yang telah bekerja.
- Buat daftar singkat fitur yang sudah ada, belum ada, dan perlu adaptor.
- Kerjakan dalam repo yang ada; hindari mengganti seluruh aplikasi hanya untuk mengikuti screenshot.
- Bila tidak ada repo, nyatakan asumsi platform. Pisahkan antarmuka komandan desktop/tablet dan petugas mobile dalam satu model domain.
- Jangan menjanjikan BLE, GPS background, dan offline identik di semua browser. Nyatakan kemampuan platform yang diuji.
- Bila format paket firmware belum tersedia, buat transport simulator dan kontrak adaptor. Jangan menyebut simulator sebagai koneksi perangkat nyata.

## C. Peran dan alur operasi

### Komandan

Alur:
buat operasi → tetapkan area → buat sektor → beri penugasan → kirim penugasan → terima konfirmasi → pantau → verifikasi temuan/penyisiran → tutup operasi.

Fitur:
- Nama operasi, ID, deskripsi, basecamp, tanggal, dan status.
- Sektor poligon yang dapat digambar, diedit, dinamai, diberi warna dan tim.
- Validasi minimal tiga titik, poligon tidak berpotongan dengan dirinya sendiri, serta peringatan overlap antar sektor.
- Sektor memiliki versi; pengubahan batas mempertahankan audit.
- Status penugasan: draft, dikirim, diterima, aktif, dilaporkan selesai, diverifikasi.
- Status pengiriman berbeda dari status pekerjaan.
- Penugasan belum “diterima” sampai ada konfirmasi penerima yang tepat.
- Dashboard menampilkan korban butuh bantuan, tim aktif, laporan posisi yang lama, penugasan belum diterima, dan alarm belum ditindaklanjuti.
- Pergantian tim atau sektor menghasilkan revision baru.
- Permintaan menutup operasi aktif harus menjelaskan konsekuensi terhadap logging.

### Petugas SAR

Alur:
pilih operasi → hubungkan node → pilih identitas → terima sektor → mulai penyisiran → catat temuan → navigasi ke target bila ditugaskan → laporkan hasil.

Fitur:
- Peta mobile layar penuh dengan tombol yang mudah digunakan.
- Sektor sendiri, posisi sendiri, lintasan, korban, waypoint, zona bahaya.
- Mulai/pause/lanjut/selesai penyisiran dengan state yang benar.
- Track lokal tetap direkam saat mesh terputus selama GPS tersedia.
- Status GPS dan status koneksi radio terpisah.
- “Selesai penyisiran” adalah laporan petugas, bukan bukti semua korban ditemukan.
- Catat temuan dengan koordinat, waktu, kategori, catatan, dan status kirim.
- Kategori awal: korban, akses terhalang, bahaya, titik kumpul, dan informasi lapangan.
- Foto tersimpan lokal; transfer gambar menjadi modul tambahan. Jangan otomatis mengirim gambar besar melalui LoRa.
- Tombol SOS petugas memerlukan konfirmasi singkat; status ACK dan upaya kirim ditampilkan.

### Korban

Layar minimal:
- Kirim lokasi dan minta bantuan.
- Pilih kondisi serta jumlah orang bila diketahui.
- Tampilkan lokasi tersedia/tidak tersedia, kapan diambil, dan status penerimaan.
- Jika GPS tidak tersedia, kirim SOS tanpa koordinat bila protokol mengizinkan.
- Tampilkan “lokasi belum tersedia”; jangan menggunakan koordinat node sebagai GPS korban.
- Jangan menyimpulkan korban aman dari tidak adanya pembaruan.

Role selector pada simulator merupakan fasilitas demo. Role pada koneksi nyata harus berasal dari konfigurasi atau otorisasi yang dapat dijelaskan.

## D. Peta dan geofence

Layers yang dapat dihidupkan/dimatikan:
sektor, tim, korban, nodes, tracks, zona bahaya, temuan, waypoints.

Interaksi:
pan/zoom, fokus diri, fokus korban, fokus sektor, pilih marker, legend, dan fit operasi. Jangan otomatis memusatkan ulang peta ketika pengguna sedang menggeser.

Tampilkan lingkaran akurasi bila tersedia. Koordinat bertimestamp. Lokasi yang lama diberi style berbeda.

Geofence:
- Gunakan posisi GPS petugas untuk pemeriksaan batas sektor yang ditugaskan.
- Implementasikan baseline point-in-polygon, kemudian hysteresis spasial dan waktu konfirmasi yang dapat dikonfigurasi.
- Bedakan status inside, outside, pending crossing, uncertain, dan GPS unavailable.
- Jika ketidakpastian lokasi beririsan dengan batas, jelaskan status uncertain; implementasikan aturan yang eksplisit.
- Pending crossing batal jika pengguna kembali sebelum waktu konfirmasi terpenuhi.
- Satu crossing menghasilkan satu event, bukan alarm berulang setiap sampel.
- Evaluasi lokal tidak menunggu ACK radio.
- Kirim event ke posko dengan prioritas yang ditentukan adaptor.
- Bedakan alarm keluar sektor dan masuk zona bahaya.
- Timestamp event, sectorVersion, posisi pemicu, konfigurasi algoritma, dan ID pengguna tersimpan.
- Batas buffer/TTT tidak di-hardcode sebagai parameter optimal. Sediakan profil evaluasi.
- Jika aplikasi di-background dan platform tidak mendukung evaluasi, tampilkan batas kemampuan; jangan mengklaim alarm berjalan.

## E. Navigasi menuju korban

Ketika target dipilih:
- Hitung jarak geodesik dari posisi petugas ke koordinat korban.
- Hitung bearing dengan referensi utara yang konsisten.
- Jika heading kompas tersedia, panah relatif mengikuti selisih bearing dan heading.
- Jika heading tidak tersedia, tampilkan arah numerik pada peta north-up; jangan memutar panah secara palsu.
- Tampilkan timestamp, usia lokasi, sumber koordinat, dan akurasi kedua posisi.
- Jika korban berpindah dan update valid diterima, perbarui target.
- Jika target lama, tampilkan “menuju lokasi terakhir”; petugas memilih lanjut atau meminta update.
- Jika koordinat tidak valid/tidak tersedia, nonaktifkan navigasi dan beri alasan.
- Dukungan waypoint manual berurutan, berikutnya/sebelumnya, serta catatan medan.
- Mendekati radius target berarti “mendekati koordinat”, bukan “korban ditemukan”.
- Petugas mengonfirmasi ditemukan, perlu bantuan tambahan, atau tidak ditemukan.
- Simpan track perjalanan untuk kembali menelusuri lintasan yang direkam. Jangan mengklaim lintasan pasti aman setelah kondisi berubah.

Navigasi rute dan estimasi waktu tempuh merupakan fitur yang diminta. Implementasikan sesuai bagian N: ETA dihitung dari segmen rute dan model gerak yang dinyatakan, bukan jarak garis lurus dibagi kecepatan tetap untuk seluruh medan. Bila data tidak memadai, tampilkan ETA tidak tersedia atau estimasi dengan keterbatasan yang jelas.

## F. Coverage penyisiran

- Coverage dihitung dari union buffer segmen track yang valid, dipotong batas sektor.
- Radius buffer adalah asumsi jangkauan pengamatan, dapat diubah dan ditampilkan.
- Hindari double counting area overlapping.
- Jangan menghubungkan dua titik yang berjeda terlalu lama atau terlalu berjauhan seolah track kontinu.
- GPS buruk, track pause, dan data hilang tidak otomatis menambah cakupan.
- Tampilkan “cakupan geometris berdasarkan lintasan”, bukan “probabilitas korban ditemukan”.
- Jika belum ada track, tampilkan belum ada data.
- Perubahan sektor menghitung ulang coverage terhadap versi sektor yang dipilih.
- Status lapangan “diperiksa” tetap dapat dicatat petugas; pisahkan dari overlay geometris.

## G. Transport BLE–LoRa dan operasi offline

Buat lapisan:
domain → aplikasi/storage → UI; transport menyediakan simulator atau perangkat nyata.

Kontrak minimal:
connect/disconnect, kirim paket, terima paket, status koneksi, capability perangkat, dan delivery update. Petakan ke API firmware yang sebenarnya sebelum mengaktifkan adaptor perangkat.

Pesan domain:
position report, SOS, assignment revision, assignment ACK, geofence event, finding report, status, dan delivery ACK.

Setiap pesan menyertakan:
messageId, operationId, senderId, recipientId/group bila tersedia, type, createdAt, sequence, payload, serta schemaVersion.

Ketentuan:
- Deduplicate messageId.
- ACK radio dan ACK aplikasi/penugasan adalah status berbeda.
- Paket lama tidak boleh menimpa posisi baru.
- Simpan waktu pengambilan GPS dan waktu penerimaan.
- Kebijakan timestamp mencatat clock quality; jangan menghitung delay satu arah seolah jam sinkron jika tidak.
- Status kirim: queued, sent, acknowledged, failed/expired; jangan menampilkan delivered tanpa bukti.
- Retry dibatasi; cegah antrean tak terbatas.
- Prioritaskan SOS dan event penting sesuai kemampuan firmware; tampilkan jika firmware belum mendukung prioritas.
- Lokasi biasa dapat dikoaleskan ke yang terbaru. Event SOS/temuan tidak hilang akibat koalescing.
- Penugasan ditujukan ke tim/pengguna yang jelas.
- Sector revision memiliki aturan penerimaan versi dan konfirmasi.
- Saat reconnect, sinkronkan tanpa menggandakan event.
- Jangan melakukan relay radio ulang lewat aplikasi tanpa aturan protokol yang mencegah loop.

Pelaporan posisi:
mode interval tetap untuk baseline; mode adaptif memakai perubahan jarak/arah atau kejadian, dengan interval minimum dan maximum silence. Semua parameter dapat dikonfigurasi. Ini kebijakan laporan; sampling track lokal bisa lebih rapat.

Offline:
operasi, sektor, track, korban terakhir, catatan, antrean, dan audit tersimpan lokal. Reload/restart tidak menghapusnya.
Peta yang belum diunduh ditandai tidak tersedia; fungsi kompas/koordinat tetap bekerja.
Sektor dan update ringkas dibawa mesh; berkas peta dasar melalui persiapan sebelum operasi.

## H. Model data minimum

| Entitas | Field utama |
|---|---|
| Operation | id, name, basecamp, status, createdAt |
| Sector | id, operationId, polygon, version, label, createdBy |
| Assignment | id, sectorId, sectorVersion, teamId, status, deliveryStatus |
| Person/Team | id, role, teamId, callsign |
| Position | subjectId, lat, lon, capturedAt, receivedAt, accuracyReported, source, sequence |
| Node | id, lastHeard, capabilities, radioMetrics jika tersedia |
| TrackSession | id, operatorId, operationId, state, points, gaps |
| VictimReport | id, personId, position optional, condition, assistanceStatus |
| Finding | id, category, coordinate optional, note, createdAt, deliveryStatus |
| GeofenceEvent | id, actorId, sectorId, version, type, triggeredAt, acknowledgedBy |
| Waypoint | id, operationId, coordinate, order, note |
| Message | id, type, payload, state, attempts, createdAt, ACK metadata |
| Audit | actorId, action, entityId, before/after revision, time |

Gunakan satuan yang eksplisit dan koordinat WGS84. Definisikan serialisasi berbeda bila GeoJSON memakai [longitude, latitude] dan model internal lat/lon.

## I. Layar wajib

1. Daftar/buat operasi.
2. Persiapan lapangan: kesiapan peta, GPS, node, identitas, penyimpanan.
3. Peta komandan dan daftar kebutuhan tindakan.
4. Editor sektor dan penugasan.
5. Daftar tim dan posisi terakhir.
6. Daftar korban dengan status bantuan.
7. Detail korban dan navigasi.
8. Layar penyisiran petugas.
9. Form temuan/SOS.
10. Inbox penugasan dan konfirmasi.
11. Event geofence dan tindak lanjut.
12. Koneksi node dan antrean pengiriman.
13. Riwayat, ekspor, dan konfigurasi evaluasi.

Setiap layar harus menangani loading, empty, error, offline, data lama, dan simulator. UI berbahasa Indonesia. Tombol punya label jelas dan perubahan state terlihat. Gunakan kontras yang terbaca di lapangan; warna bukan satu-satunya penanda.

## J. Simulator untuk pengembangan dan demonstrasi

Buat fixture dengan koordinat nyata yang konsisten, bukan statistik dekoratif:
- Satu operasi, dua sektor, dua tim, empat node, dan beberapa target.
- Jalankan pergerakan terkontrol masuk/keluar sektor.
- Simulasikan jitter dekat batas, GPS unavailable, lokasi lama, pesan duplikat, out-of-order, relay putus, reconnect, assignment revision, dan pembaruan korban.
- Kejadian memiliki log dan hasil yang dapat direproduksi.
- Seluruh mode simulator diberi badge SIMULASI.
- “Test event” hanya berada pada mode evaluasi.
- PDR/false alarm hanya dihitung dengan denominator/ground truth yang tersedia.
- Simulator bukan emulasi RF yang membuktikan jangkauan perangkat.

## K. Ekspor dan metrik TA

Ekspor:
GeoJSON sektor/temuan; GPX track; CSV posisi, event, delivery, dan pengujian; JSON arsip operasi.

Tampilkan konfigurasi dan satuan:
interval laporan, threshold pergerakan, buffer geofence, waktu konfirmasi, firmware version, jumlah sampel, dan kualitas waktu.

Metrik:
PDR paket unik dengan ACK yang sesuai; duplicate count; retry; delay jika waktu sinkron; AoI berdasarkan timestamp yang dapat dipercaya; alarm lokal vs alarm posko; coverage geometris; energi hanya bila sensor pengukuran tersedia.

Jangan menampilkan false positive nol tanpa ground truth, baterai palsu, hop count tebakan, atau “semua node terhubung” berdasarkan satu pesan lama.

## L. Kriteria penerimaan

Buktikan dengan skenario, tes domain yang relevan, dan inspeksi UI:

1. Operasi dan sektor yang dibuat tetap tersedia setelah restart.
2. Sektor dapat digambar/edit, divalidasi, ditugaskan, dan dikonfirmasi penerima.
3. Track petugas bertambah hanya saat sesi aktif dan input GPS valid.
4. Keluar sektor memicu event sesuai hysteresis/TTT; berdiri dekat batas tidak menyebabkan spam.
5. Event lokal tetap muncul saat radio putus dan tersinkron setelah reconnect.
6. Tombol navigasi memakai posisi petugas; jarak/bearing diperiksa dengan pasangan koordinat yang diketahui.
7. Target lama terlihat lama; posisi korban tanpa GPS tidak diganti lokasi node.
8. ACK penugasan tidak disamakan dengan ACK radio.
9. Data duplikat/out-of-order tidak merusak posisi, versi sektor, atau riwayat.
10. Coverage berasal dari track, tidak double count, dan tidak menutup gap.
11. Peta yang telah disiapkan bekerja offline; peta yang belum siap memberi informasi yang jujur.
12. Ekspor dapat dibuka dan field/timestamp cocok dengan operasi.
13. Tampilan mobile dapat dipakai tanpa overflow atau tombol tertutup.
14. Adaptor simulator dan adaptor perangkat terpisah; fitur hardware yang belum diuji dicantumkan.

## M. Tahap pengerjaan dan laporan akhir

Kerjakan berurutan:
1. Audit repo, paket wilayah, model routing, persistence, dan fixture uji.
2. Mesin routing medan sendiri dan kontrak service/API sesuai bagian N.
3. Peta, pilihan target, statistik rute, ETA, dan navigasi aktif.
4. Laporan hambatan, rerouting, serta verifikasi kemampuan offline.
5. Integrasi BLE–LoRa sesuai protokol; OSRM jalan/jalur menjadi provider tambahan dan baseline bila diperlukan.
6. Sektor penugasan, geofence, track penyisiran, ekspor, dan evaluasi improvement.

Jangan berhenti pada tahap mockup dashboard. Jika dependensi hardware belum tersedia, selesaikan fitur yang tidak bergantung padanya dan laporkan blocker spesifik.

Laporan akhir mencakup:
fitur yang bekerja, cara menjalankan, hasil verifikasi, batas platform/hardware, dan daftar modul yang masih simulator. Sertakan petunjuk demonstrasi satu operasi dari dibuat sampai ditutup.

---

## N. Navigasi mandiri: OSRM, elevasi, hambatan, dan medan tanpa jalur

Bagian ini memperluas kebutuhan navigasi. Pertahankan geofence dan alur SAR sebelumnya. Hasil harus membantu petugas membandingkan lintasan menuju korban, melihat jarak dan waktu tempuh, serta memperbarui lintasan ketika hambatan ditemukan.

### N1. Dasar arsitektur dan koreksi pemahaman OSRM

OSRM bukan hanya layanan API publik. Source code mesin routing tersedia, memakai C++, dan profil perilaku rutenya memakai Lua. Data dasarnya dapat disiapkan sendiri dari OpenStreetMap. Profil foot dapat memproses jalur pejalan kaki yang ada di data, bukan hanya jalan raya. Dokumentasi juga menyediakan mekanisme memasukkan elevasi saat memproses segmen. Namun, tanpa representasi konektivitas medan, mesin tersebut tidak otomatis membentuk lintasan yang dapat dilalui di seluruh hutan. [R1–R4]

Bangun mesin routing medan sendiri sebagai deliverable inti. OSRM self-hosted merupakan provider tambahan dan baseline, bukan syarat untuk memulai implementasi inti. Target akhir mendukung routing gabungan dengan tiga mode:

| Mode | Mesin | Output |
|---|---|---|
| Jalan/jalur terpetakan | OSRM yang di-host sendiri, profil foot yang diperiksa | Rute pada jaringan terpetakan dan instruksi langkah |
| Medan tanpa jalur | Graph/grid lokal dari elevasi, tutupan lahan, dan hambatan | Kandidat lintasan berdasarkan model biaya medan |
| Gabungan | Graph jalan/jalur dan graph medan dengan konektor valid | Perjalanan yang berpindah dari jalur terpetakan ke medan |

Pertahankan mode arah langsung sebagai fallback berlabel. Jangan mengubah garis lurus menjadi rute jalan atau lintasan terverifikasi. Kegagalan OSRM bukan bukti bahwa korban tidak dapat dijangkau. Keberhasilan menghitung lintasan medan juga bukan bukti bahwa seluruh lintasannya dapat dilalui.

### N2. Infrastruktur OSRM tambahan untuk jalur terpetakan

Implementasikan konfigurasi layanan sendiri, bukan bergantung pada endpoint demo publik:

1. Ambil ekstrak OSM regional berformat .osm.pbf; crop wilayah operasi dengan margin yang cukup agar rute tidak terpotong di batas data.
2. Pilih profil foot.lua dari versi OSRM yang dipin. Periksa aturan path, footway, track, steps, access, surface, dan crossing sesuai data.
3. Jalankan pipeline MLD: osrm-extract dengan profil → osrm-partition → osrm-customize → osrm-routed --algorithm mld. Gunakan perintah aktual dari dokumentasi versi yang dipin.
4. Sediakan Docker/config, script preparation, manifest tanggal data, dan petunjuk menjalankan di laptop/komputer posko.
5. Minta geometry rute, distance, duration, steps, serta annotations yang diperlukan. Render dan kelola navigasinya dalam aplikasi sendiri.
6. Validasi jarak snapping endpoint. Endpoint yang tersnap jauh dari petugas/korban harus terlihat di peta; segmen akses menuju titik asli tidak boleh diabaikan atau dianggap otomatis dapat dilalui.
7. Label URL tidak mengganti profil hasil preprocessing: mengganti kata driving menjadi walking tidak otomatis mengubah server yang disiapkan untuk mobil.
8. Laporkan NoRoute/NoSegment, timeout, dan server tidak terjangkau secara berbeda.
9. Jika profil mengubah preferensi risiko, bedakan routing weight dari duration; penalti risiko bukan waktu perjalanan fisik.

OSRM di LAN posko bekerja tanpa internet hanya ketika perangkat dapat menjangkau server LAN tersebut. LoRa tidak otomatis menyediakan koneksi HTTP ke server. Petugas yang hanya terhubung BLE–LoRa membutuhkan mesin lokal pada HP atau rute yang telah disiapkan; jangan mengklaim self-hosted OSRM berarti semua HP dapat menghitung ulang offline.

### N3. Sumber data elevasi dan hambatan

Sediakan modul impor paket wilayah. Catat source, resolusi asli, tanggal observasi, tanggal unduh, CRS, datum vertikal, lisensi/atribusi, checksum, dan status validasi.

| Data | Sumber awal | Pemakaian dan batasnya |
|---|---|---|
| Jalan, jalur, sungai, bangunan, crossing | OpenStreetMap/Geofabrik | Konektivitas dan kandidat barrier; kelengkapan berbeda antarwilayah [R5] |
| Elevasi Indonesia | DEMNAS BIG, 0,27 arc-second, datum EGM2008 | Kandidat utama topografi lokal; resolusi bukan jaminan akurasi atau keterbaruan [R6] |
| Elevasi global alternatif | Copernicus DEM GLO-30 | DSM sekitar 30 m yang mencakup vegetasi/bangunan; tidak identik dengan tanah kosong [R7] |
| Tutupan lahan | ESA WorldCover 2021 v200, 10 m | Kelas hutan, semak, air, dan lain-lain untuk biaya lintasan; bukan peta kepadatan semak atau kondisi hari ini [R8] |
| Pemrosesan/ekspor cloud sebelum operasi | Google Earth Engine | Pilih dataset, clip AOI, ekspor raster numerik GeoTIFF untuk dipakai lokal [R9] |
| Hambatan pascabencana | Laporan SAR dengan geometri, waktu, dan tingkat verifikasi | Penutupan akses, longsor, banjir, jembatan putus, dan zona larangan |
| Detail tambahan jika tersedia | Survei drone/LiDAR atau DTM lokal berizin | Memperbaiki representasi medan; kualitas diperiksa sebelum dipakai |

Google Earth untuk inspeksi visual berbeda dari Earth Engine untuk analisis dataset. Jangan menganggap screenshot citra atau tampilan 3D sebagai raster elevasi numerik yang siap dihitung. Google Earth Engine bukan dependency runtime di lapangan: ekspor paket sebelumnya. Tampilkan atribusi dan metadata yang diwajibkan sumber data.

Data satelit tidak otomatis mengidentifikasi akar pohon, lubang kecil, batu, semak rapat, kedalaman banjir, atau runtuhan terbaru. Tampilkan informasi tidak diketahui sebagai unknown. Jangan menyimpulkan clear hanya karena hambatan tidak terpetakan.

Belum ada AOI lokasi pengujian yang dipilih. Jangan mengarang elevasi minimum/maksimum atau jumlah rintangan wilayah tertentu. Buat pemilih AOI/bounding box dan tool statistik; hasil aktual baru dihitung setelah dataset wilayah tersedia. Fixture buatan diberi label sintetis.

### N4. Pipeline persiapan medan

Gunakan GDAL/Rasterio untuk preprocessing dan GRASS GIS r.walk sebagai referensi implementasi/pembanding routing berdasarkan elevasi dan friction. Tidak perlu mengganti stack aplikasi yang ada. [R10–R12]

Tahapan:
1. Clip AOI plus margin, cek NoData dan cakupan data.
2. Reproject untuk perhitungan jarak/lereng ke CRS lokal metrik yang sesuai; jangan menyamakan derajat dengan meter.
3. Selaraskan grid, origin, extent, dan resolusi. Land-cover kategorikal menggunakan resampling kategorikal seperti nearest-neighbor, bukan bilinear.
4. Jangan mengklaim raster 30 m menjadi akurat 5 m hanya karena upsampling.
5. Hitung slope untuk visualisasi/aturan medan; simpan satuan derajat atau persen dengan jelas.
6. Hitung perubahan elevasi arah perjalanan pada setiap edge. Biaya dari A ke B dapat berbeda dari B ke A.
7. Rasterize hambatan dengan pemeriksaan konservatif; barrier tipis tidak boleh hilang hanya karena garis jatuh di sela pusat sel.
8. Bangun graph/grid yang terbatas pada paket wilayah, dengan aturan koneksi diagonal yang tidak memotong sudut hambatan.
9. Buat terrain package berversi dan manifest. Siapkan indeks/chunk untuk pembacaan terbatas di HP.
10. Profilkan ukuran data, penggunaan RAM, durasi preprocessing, dan waktu query sebelum memilih resolusi operasional.

### N5. Model biaya routing medan

Implementasikan Dijkstra sebagai baseline. A* dapat menjadi optimasi dengan heuristic yang merupakan lower bound konsisten dengan model biaya; jika sulit menjaminnya, gunakan heuristic nol. Jangan membuat heuristic jarak saja untuk biaya yang satuannya tidak cocok.

Untuk edge i→j, definisikan:
- d_ij: jarak horizontal dalam meter.
- s_ij = (z_j - z_i) / d_ij: grade arah perjalanan.
- v_ij: estimasi kecepatan positif berdasarkan model gerak, grade, dan tutupan lahan.
- t_ij = d_ij / v_ij: waktu segmen dalam detik jika model menggunakan jarak horizontal.
- c_ij = t_ij + penalti preferensi dengan bobot dan unit yang dinyatakan; hindari menjumlahkan indeks tanpa skala.

Model harus mengikuti konvensi jarak yang sama saat dikalibrasi. Pisahkan estimasi waktu dari penalti pemilihan rute. Sediakan model terdokumentasi dari r.walk atau model walking lain yang diverifikasi; jangan menetapkan koefisien sebagai karakteristik SAR Indonesia tanpa pengujian.

Hard constraint: zona larangan, barrier yang dikonfirmasi tidak bisa dilalui, dan batas kemampuan tim yang disetujui. Jangan memberi biaya tinggi tetapi tetap dapat dilewati untuk zona yang harus dilarang.

Soft constraint: medan sulit, preferensi jalur terpetakan, kondisi yang belum diverifikasi. Penalty tutupan lahan adalah asumsi model, bukan bukti semua hutan memiliki hambatan sama. Gunakan slope limit yang configurable dan terdokumentasi, bukan ambang universal aman.

NoData tidak diperlakukan sebagai elevasi nol. Mode konservatif menolak lintasan melintasi area tanpa data; mode eksplorasi harus menandainya dan tidak mengeluarkan ETA seolah lengkap.

### N6. Routing gabungan dan perubahan hambatan

Jangan cukup mengambil titik akhir OSRM lalu menarik garis lurus ke korban.

- Bentuk kandidat konektor jalan/jalur ke grid yang diperiksa terhadap barrier, jarak, elevasi, dan footprint data.
- MVP dapat membandingkan beberapa kombinasi OSRM + segmen terrain yang valid; beri label kandidat, bukan optimum global.
- Optimasi lanjutan dapat mencari lintasan pada graph gabungan. Biaya kedua graph harus comparable.
- Bedakan sektor penugasan dengan larangan lintas. Keluar sektor dapat memicu alarm tanpa memblokir seluruh navigasi kecuali aturan operasi menyatakan demikian.
- Laporan hambatan memiliki geometri, reporter, observedAt, severity, verificationState, dan expiry/reviewAt.
- Hambatan yang memotong rute membuat rute outdated dan memicu perhitungan ulang bila mesin tersedia.
- Perubahan barrier pada rute OSRM tidak cukup ditampilkan sebagai overlay. Rute harus ditolak/divalidasi ulang, graph diperbarui, atau planner gabungan mencari alternatif. Jangan mengklaim arbitrary polygon exclusion tersedia langsung di OSRM standar.
- Jangan menghaluskan lintasan sampai memotong sungai atau zona terlarang. Periksa ulang tiap segmen geometry setelah simplification.

### N7. Statistik lintasan dan ETA

Panel rute wajib memisahkan:
1. Jarak garis lurus menuju koordinat terakhir korban.
2. Panjang horizontal sepanjang rute.
3. Jarak 3D berbasis DEM bila dihitung, dengan keterangan resolusi/model.
4. Elevasi awal/akhir, min/max, total ascent/descent, dan profil ketinggian sepanjang jarak rute.
5. Grade arah perjalanan dan statistik lereng medan; keduanya bukan metrik yang sama.
6. Proporsi panjang rute per kelas tutupan lahan, unknown fraction, dan panjang di zona belum diverifikasi.
7. Hambatan dekat/memotong lintasan: kategori, jarak sepanjang rute, waktu observasi, dan verifikasi.
8. Waktu bergerak = jumlah t_ij. Pisahkan tambahan berhenti, membawa peralatan/korban, dan durasi penyisiran.
9. Engine, profile, datasetVersion, hazardVersion, dan waktu pembuatan rute.

Ascent/descent dari DEM perlu filtering yang terdokumentasi agar noise tidak diakumulasi sebagai tanjakan. Jangan menyamakan persentase hutan dengan persentase pasti tidak bisa dilalui.

ETA adalah estimasi model, bukan jaminan. Sediakan skenario kecepatan/penalty optimistis, nominal, dan konservatif jika parameter tersedia; label rentang skenario, bukan confidence interval statistik tanpa kalibrasi. Bila target lama atau route data kurang, jelaskan keterbatasan dan tampilkan unknown bila perlu.

### N8. UX navigasi aktif

Alur: pilih korban → pilih profil tim → hitung kandidat → bandingkan → pilih rute → mulai → pantau kemajuan → laporkan hambatan → hitung ulang.

Tampilkan jarak tersisa sepanjang rute, jarak ke waypoint berikutnya, arah, estimasi waktu tersisa, profil elevasi, dan status koneksi/route freshness. Untuk rute medan, gunakan instruksi waypoint/bearing yang sesuai; jangan mengarang nama jalan atau perintah belok presisi.

Lokasi GPS aktual tetap terlihat. Progress tidak boleh tersnap jauh ke jalur sampai menutupi posisi asli. Deteksi keluar rute memakai jarak ke polyline, toleransi GPS, dan waktu konfirmasi. Heading yang hilang tidak diganti data acak.

Recompute target bergerak dengan threshold/time gate agar tidak melakukan query pada setiap jitter GPS. Jika rute baru tidak tersedia, tampilkan rute sebelumnya berlabel outdated; jangan diam-diam menggantinya dengan garis lurus.

### N9. Offline dan perangkat komputasi

Implementasi posko awal: OSRM lokal dan worker terrain pada laptop/komputer, paket wilayah disiapkan sebelum operasi.

Implementasi petugas:
- Route geometry, waypoint, metadata, dan peta wilayah disimpan lokal sehingga navigasi rute terpilih tidak memerlukan internet.
- Agar dapat menghitung ulang saat terpisah dari posko, implementasikan solver terrain lokal pada perangkat untuk AOI terbatas; ukur performanya pada HP target.
- Jika solver lokal belum dibuat, jelaskan bahwa perangkat hanya mengikuti rute tersimpan dan menampilkan bearing. Mode ini tidak memenuhi kriteria offline rerouting.
- LoRa membawa posisi, kejadian, dan rute ringkas yang dibatasi ukuran. Paket rute terfragmentasi memerlukan checksum, ID/version, ACK dan recovery sesuai firmware. Rute penuh yang belum diterima tidak boleh ditampilkan sebagai lengkap.
- Jangan mengirim DEM, imagery, atau seluruh graph melalui LoRa sebagai mekanisme normal.

### N10. Kontrak service dan data tambahan

Buat RoutingProvider dengan implementasi OSRM, Terrain, Hybrid, dan BearingFallback. Kontrak route mengembalikan geometry, steps/waypoints, length, durationEstimate, ascent/descent, dataCoverage, warnings, modelVersion, dan engine. Status: available, unavailable, missingData, noTraversablePath, stale, atau cancelled.

Entitas tambahan:
- TerrainPackage: AOI, CRS, gridSize, sumber, tanggal, license, datasetVersion, checksum.
- Hazard: geometry, observedAt, verification, blockingPolicy, version.
- MovementProfile: model, parameter, batas kemampuan, calibratedOn optional.
- RoutePlan: targetPositionTimestamp, engine/profile, geometry, statistics, packageVersion, hazardVersion.
- NavigationSession: routeId, state, progress, deviations, logs.

Simpan request/result dan versi data untuk reproduksi eksperimen. Input di luar AOI atau target dalam barrier menghasilkan penjelasan, bukan rute palsu.

### N11. Uji dan evaluasi improvement navigasi

Uji fixture deterministik:
- Dataran terbuka tanpa jalan menghasilkan terrain route jika data tersedia.
- Bukit membuat perubahan waktu dan pilihan rute sesuai model; A→B dan B→A diperiksa.
- Sungai terlarang dengan satu crossing valid tidak boleh ditembus di titik lain.
- Barrier menutup seluruh akses menghasilkan noTraversablePath.
- Konektor/snapping jauh, NoData, posisi di luar paket, GPS jitter, target lama, dan server LAN hilang ditangani.
- Perubahan hambatan membatalkan rute terkait.
- Geofence sektor tidak otomatis diperlakukan sebagai barrier.
- Rute hasil simplification tetap memenuhi constraint.
- Airplane mode dengan paket lokal menguji follow route dan reroute sebagai kemampuan terpisah.

Bandingkan baseline OSRM foot, arah langsung sebagai pembanding geometris, terrain berbasis jarak, terrain berbasis waktu/elevasi, dan model dengan hambatan/tutupan lahan. Jangan memaksa baseline NoRoute menjadi angka waktu nol.

Metrik: keberhasilan menemukan kandidat yang valid terhadap constraint, panjang rute, ascent/descent, waktu komputasi, RAM, ukuran paket, pelanggaran barrier, waktu perjalanan aktual, MAE estimasi waktu, dan rasio lintasan yang dikonfirmasi dapat dilalui. Uji lapangan dengan lintasan terkendali dan catatan petugas; DEM bukan ground truth keterlintasan.

Report tanggal data dan perubahan kondisi lapangan. Klaim improvement hanya terhadap baseline dan skenario yang diuji. Tidak adanya hambatan dalam database tidak membuktikan keselamatan.

### N12. Resource resmi untuk implementasi

| ID | Resource | Peran |
|---|---|---|
| R1 | [OSRM backend source](https://github.com/Project-OSRM/osrm-backend) | Mesin C++, Docker, pipeline self-hosted |
| R2 | [OSRM profile docs](https://github.com/Project-OSRM/osrm-backend/blob/master/docs/profiles.md) | Lua, weight/duration, preprocessing, elevasi |
| R3 | [Profil foot](https://github.com/Project-OSRM/osrm-backend/blob/master/profiles/foot.lua) | Dasar routing pejalan kaki |
| R4 | [OSRM API](https://project-osrm.org/docs/) | Geometry, distance, duration, steps dan error |
| R5 | [Ekstrak OSM Indonesia](https://download.geofabrik.de/asia/indonesia.html) | Bahan .osm.pbf, bukan hasil API rute |
| R6 | [DEMNAS BIG](https://www.big.go.id/en/content/product/demnas) | Spesifikasi elevasi Indonesia dan akses resmi |
| R7 | [Copernicus DEM](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM) | Alternatif elevasi global |
| R8 | [ESA WorldCover download](https://esa-worldcover.org/en/data-access), [repo tutorial](https://github.com/ESA-WorldCover/esa-worldcover-datasets) | Tutupan lahan raster |
| R9 | [Earth Engine image export](https://developers.google.com/earth-engine/guides/exporting_images), [WorldCover catalog](https://developers.google.com/earth-engine/datasets/catalog/ESA_WorldCover_v200) | Clip dan ekspor data sebelum operasi |
| R10 | [GDAL slope](https://gdal.org/en/stable/programs/gdal_raster_slope.html) | Turunan lereng dari raster elevasi |
| R11 | [Rasterio reprojection](https://rasterio.readthedocs.io/en/stable/topics/reproject.html), [resampling](https://rasterio.readthedocs.io/en/stable/topics/resampling.html) | Penyelarasan data raster |
| R12 | [GRASS r.walk](https://grass.osgeo.org/grass-stable/manuals/r.walk.html), [r.path](https://grass.osgeo.org/grass-stable/manuals/r.path.html) | Cost routing arah perjalanan dan ekstraksi lintasan |

Resource diperiksa 14 Oktober 2026. Pin versi dependensi dan gunakan dokumentasi versinya. Sumber data tersedia untuk dipilih; dokumen ini belum mengunduh dataset wilayah atau menghitung statistik lapangan aktual.

## Prompt lanjutan 1 — Audit setelah implementasi pertama

Audit aplikasi terhadap spesifikasi NusaSurvey. Jalankan alur komandan, petugas, dan korban. Buat tabel fitur dengan status: berfungsi, parsial, simulasi, atau belum tersedia. Periksa setiap tombol, sumber angka dashboard, persistence, geofence, navigasi, dan ekspor. Perbaiki masalah yang dapat direproduksi. Jangan menyebut fitur selesai hanya karena komponen UI ada.

## Prompt lanjutan 2 — Uji keadaan gagal

Jalankan skenario GPS hilang, lokasi korban lama, mesh putus, paket duplikat/out-of-order, perubahan sektor, dan restart ketika pesan masih antre. Periksa apakah informasi UI konsisten dan data dapat dipulihkan. Tambahkan pengujian domain untuk kasus yang dapat menyebabkan salah lokasi, alarm berulang, atau penugasan salah versi. Laporkan hasil serta keterbatasan.

## Prompt lanjutan 3 — Integrasi NusaNode

Baca spesifikasi BLE dan format paket firmware yang diberikan. Petakan pesan aplikasi ke field firmware, batas payload, fragmentasi, ACK, prioritas, dan identitas pengguna. Implementasikan adaptor tanpa mengubah format firmware secara diam-diam. Jika kemampuan tidak tersedia, buat daftar perubahan firmware yang diperlukan beserta dampaknya. Verifikasi dengan perangkat bila tersedia; pisahkan bukti simulator dan perangkat.

## Prompt lanjutan 4 — Evaluasi improvement TA

Implementasikan konfigurasi baseline dan usulan improvement yang dapat dibandingkan pada input identik. Mulai dari geofence dasar vs hysteresis/TTT, dan pelaporan posisi interval tetap vs adaptif. Ekspor event, denominator, konfigurasi, dan waktu. Pisahkan hasil simulator dari pengujian radio lapangan. Jangan membuat persentase improvement sebelum data diperoleh.

