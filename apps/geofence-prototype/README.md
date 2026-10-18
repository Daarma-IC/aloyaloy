# NusaSurvey SAR Prototype

Prototype React + Leaflet untuk menguji mesin routing medan dan navigasi SAR sebelum integrasi ke aplikasi NusaMesh. Aplikasi `apps/nusamesh` belum diubah.

[Dokumentasi pengembangan lengkap](../../systems/nusasurvey/docs/Dokumentasi_Pengembangan_NusaSurvey.md) mencatat pekerjaan, sumber/teknologi, arsitektur aktual, asumsi model, status fitur, reproduksi, dan jurnal fondasi. [Daftar pustaka dan BibTeX](../../systems/nusasurvey/docs/references/Landasan_Ilmiah.md) tersedia terpisah.

## Demo utama: koordinat dummy, medan nyata

Buka aplikasi dan pilih **NAVIGASI**. Paket Kaliurang sudah disertakan; awalnya hanya posisi petugas dan korban yang digenerate. Klik titik merah **Korban 01**, lalu tekan **Arahkan ke korban** pada popup. Baru setelah itu mesin menghitung dan menampilkan garis rute, jarak, serta estimasi waktu. Memilih korban saja belum membuat rute. Tidak perlu mengimpor paket atau memuat fixture dahulu.

- Data elevasi: Copernicus DEM GLO-30 Public, release 2021, melalui [sumber resmi AWS](https://registry.opendata.aws/copernicus-dem/).
- Tutupan lahan: ESA WorldCover 2021 v200 melalui [sumber resmi AWS](https://registry.opendata.aws/esa-worldcover-vito/).
- Jalur: OpenStreetMap snapshot Kaliurang, 14 Oktober 2026; 209 ways dengan akses pejalan kaki yang sesuai filter importer.
- Paket lokal: [public/demo/kaliurang-terrain.json](public/demo/kaliurang-terrain.json), grid UTM 35 m, 8.772 sel valid; hash dan provenance ada di [sources.json](public/demo/sources.json).

**Mulai navigasi ke korban** menjalankan posisi dummy sepanjang rute yang sudah dihitung; marker, waypoint, bearing, jarak tersisa, dan ETA ikut berubah. Gerak demo dipercepat 24 m/detik, sedangkan ETA tetap estimasi model berjalan. **Generate target dummy lain** membuat tujuan baru dan menghapus rute lama; tujuan baru harus dipilih dan diarahkan kembali. **Laporkan jalur di depan tertutup** membuat rute outdated; **Hitung ulang dari posisi sekarang** mencari alternatif jalan–medan yang memenuhi constraint atau melaporkan tidak ada akses.

Rute yang sudah diminta pengguna tetap ada saat berpindah tab atau reload. Navigasi aktif dijeda setelah panel dimuat kembali. Rute dikosongkan ketika target diganti/reset; tidak dihapus hanya karena panel navigasi dibuka ulang.

Mode default **Otomatis: jalan dan medan** mencari rute pada graph gabungan: ruas OSM asli, sel medan, dan transisi tervalidasi. Korban demo sengaja berada di luar jalan. Bagian jalan mempertahankan geometri ruas OSM; bagian medan memeriksa seluruh sel yang dilintasi, lereng arah perjalanan, serta poligon hambatan. Paket menyertakan 1.168 footprint bangunan OSM. Air hanya dapat dilewati pada ruas bridge/tunnel yang terpetakan; transisi dari struktur tersebut dibatasi ke junction permukaan yang diketahui.

Biru menunjukkan jalan OSM, ungu putus-putus medan tanpa jalur, dan jingga transisi. ETA memakai waktu fisik; penalti preferensi medan 3 detik/meter hanya menentukan pemilihan rute. Provider **Hanya jalur OSM** dan **Hanya medan DEM** tetap tersedia sebagai pembanding. Dataset historis dan koefisien ETA belum dikalibrasi lapangan. Bangunan multipolygon dan hambatan yang belum dipetakan belum tercakup. Lihat [kontrak routing gabungan](docs/HYBRID.md).

Setelah **Arahkan ke korban**, **Profil elevasi rute** menampilkan elevasi terhadap jarak rute. Pointer/slider memilih sampel dan menandai koordinat yang sama pada peta. **Unduh rute KML** membuka rute di Google Earth dengan elevasi tanah Google Earth; **Unduh elevasi CSV** menyediakan angka DEM prototype untuk pembandingan titik yang sama. Lihat [sumber, sampling, dan cara membandingkan](docs/ELEVATION.md).

## Fixture dan impor untuk pengujian mesin

Di tab **MESIN**, buka panel **Mesin medan NusaSurvey**. Fixture sintetis hanya untuk pengujian deterministik:

1. Tekan **Muat demo medan**. Ini memuat grid sintetis 60 m dengan bukit, hutan, dan sungai yang memiliki satu crossing. Data ini bukan DEM/tutupan lahan Merapi nyata.
2. Pilih profil tim, lalu **Hitung / reroute medan**. Dijkstra berjalan di Web Worker browser, tanpa jaringan jalan atau API eksternal.
3. Periksa jarak horizontal/3D, ETA model, naik/turun, profil elevasi, dan distribusi tutupan lahan. **Baseline jarak** memilih biaya jarak dengan constraint yang sama untuk pembanding.
4. Tekan **Mulai / lanjut navigasi**, lalu **Langkah GPS di rute (simulasi)** untuk melihat progress dan bearing waypoint. GPS tetap fixture simulator.
5. Ketuk peta dan **Blokir titik peta** untuk menambahkan zona larangan. Rute menjadi outdated; hitung ulang untuk mencari alternatif.
6. **Tutup crossing demo** menutup satu-satunya akses melintasi sungai. Query berikutnya menghasilkan `noTraversablePath`; rute lama dipertahankan dengan label outdated, bukan diganti arah langsung.
7. Ekspor paket atau request/hasil rute untuk reproduksi. Paket tersimpan di IndexedDB; hazard, rute, dan state navigasi dipulihkan setelah reload.

Titik peta dapat dijadikan posisi petugas atau target melalui tombol simulator. Posisi di luar paket atau di sel terlarang menghasilkan penjelasan. Sektor penugasan tidak dijadikan zona larangan otomatis.

Model waktu memakai formula Langmuir yang didokumentasikan oleh GRASS `r.walk`, ditambah friction tutupan lahan dalam detik/meter. Koefisien profil merupakan asumsi; belum dikalibrasi untuk SAR. NoData dan air ditolak, batas grade bersifat configurable melalui profil, dan diagonal tidak boleh memotong sudut sel terlarang. Tidak ada smoothing geometry yang dapat memotong barrier. Lihat [detail paket dan model](docs/TERRAIN.md).

## Data wilayah nyata

Importer [tools/prepare_terrain.py](tools/prepare_terrain.py) menggunakan Rasterio/GDAL untuk mengubah DEM dan WorldCover GeoTIFF lokal menjadi grid UTM sejajar serta paket JSON. Land-cover memakai nearest-neighbor; elevasi memakai bilinear; NoData dipertahankan. AOI dan provenance wajib diberikan. Importer menolak upsampling dan membatasi grid untuk prototype browser.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r tools/requirements.txt
```

Isi metadata dari [contoh](tools/metadata.example.json) berdasarkan berkas sebenarnya. Perintah dan kontrak paket ada di [TERRAIN.md](docs/TERRAIN.md). Raster Copernicus dan WorldCover Kaliurang sudah disiapkan dalam demo utama. DEMNAS belum digunakan. Untuk AOI lain, siapkan paket tambahan dari berkas wilayah yang sesuai.

## Verifikasi

```powershell
npm test
npm run build
npx playwright install chromium
npm run test:browser
.\.venv\Scripts\python.exe tools/test_prepare_terrain.py
```

Uji browser memakai hasil build/preview. Bundle aplikasi dan routing worker diprecache oleh service worker; setelah persiapan online, reload dan rerouting medan telah diuji dengan koneksi browser offline. Tile basemap tetap terbatas pada yang telah tercache; wilayah yang belum siap tidak otomatis tersedia. Mode dev tidak mendaftarkan service worker baru.

Hasil verifikasi dan daftar fitur yang belum lengkap ada di [VALIDATION.md](docs/VALIDATION.md).

## Menjalankan

```powershell
cd D:\TAA\aloyaloy\apps\geofence-prototype
npm install
npm run dev -- --open
```

Build produksi:

```powershell
npm run build
npm run preview -- --open
```

## Peta

- Renderer: Leaflet 1.9.4.
- Basemap detail: tile OpenStreetMap nyata, bukan SVG ilustrasi.
- Tile yang pernah dibuka disimpan oleh service worker untuk pengujian offline terbatas.
- Tidak ada bulk download tile OSM.
- Toolbar Leaflet Draw di kiri digunakan untuk membuat, mengedit, dan menghapus polygon sektor.
- Sektor, petugas, korban, node, track, dan temuan adalah layer Leaflet terpisah.

Offline penuh untuk wilayah operasi tetap membutuhkan paket tile yang disiapkan sebelum operasi. Pada aplikasi produksi, target adaptornya mengikuti pola MBTiles yang sudah tersedia di NusaMesh.

## Alur demonstrasi

1. Di tab **COMMAND**, pilih sektor atau gambar polygon baru dari toolbar Leaflet.
2. Edit polygon untuk membuat revision dan membuat assignment perlu dikonfirmasi ulang.
3. Di tab **PETUGAS**, mulai penyisiran lalu tekan **Langkah GPS berikutnya**.
4. Ubah profil geofence untuk membandingkan baseline dengan hysteresis/time confirmation.
5. Putuskan radio dari tab **TRANSPORT**, lalu lanjutkan track, temuan, atau SOS.
6. Reconnect untuk menyinkronkan antrean lokal tanpa mengganti `messageId`.
7. Periksa coverage, jumlah sampel, dan ACK di tab **EVALUASI**.

## Navigasi korban

Mode navigasi legacy berikut dipertahankan untuk pembanding/panel lama; demo utama **NAVIGASI/KORBAN** memakai console Hybrid yang dijelaskan di awal README. Ketersediaan kontrol legacy mengikuti panel operasi yang aktif:

- **Rute gunung offline**: A\* berjalan sepenuhnya di browser terhadap graph jalur kaki OSM yang sudah disimpan di IndexedDB. Biaya ruas memperhitungkan kelas jalur, surface, smoothness, dan `sac_scale` bila tersedia.
- **Rute jalan OSRM**: provider tambahan ke server sendiri melalui `VITE_OSRM_URL` pada `.env.local`. Server wajib disiapkan dengan profil foot; mengganti label URL tidak mengubah profil preprocessing. Tidak ada pemanggilan endpoint demo driving otomatis. Snapping di atas 50 m dan geometry yang memotong hazard lokal ditolak. Server OSRM belum dijalankan atau diuji dengan PBF nyata.
- **Arah langsung**: jarak geodesik dan bearing north-up dari GPS petugas ke koordinat terakhir korban. Tetap bekerja tanpa graph.
- **Waypoint manual**: petugas/komandan mengetuk peta untuk menyusun lintasan berdasarkan penilaian medan di lapangan.

Alur uji rute gunung:

1. Buat atau sesuaikan polygon area operasi di peta.
2. Buka tab **KORBAN**, lalu tekan **Siapkan graph area operasi** saat masih memiliki jaringan. Prototype mengambil jalan setapak/track OSM untuk bounding box sektor dan menyimpannya di IndexedDB.
3. Tekan **Rute gunung offline**. Posisi petugas dan korban di-snap ke graph, lalu A\* memilih lintasan dengan estimasi waktu terendah.
4. Panel menampilkan jarak total, sisa jarak, ETA total/tersisa, deviasi dari rute, connector di luar graph, dan petunjuk per segmen.
5. Setelah graph tersedia, matikan jaringan dan ulangi perhitungan untuk menguji operasi offline.

Mode graph OSM lama masih memiliki fallback arah langsung. Provider Terrain dan OSRM mempertahankan hasil gagal secara eksplisit; pengguna memilih arah langsung sendiri. Radius “mendekati koordinat” memakai nilai terbesar antara 20 meter dan gabungan ketidakpastian GPS petugas/korban. Status ini tidak menyatakan korban telah ditemukan.

Server Vite meneruskan persiapan data ke OSM Map API dan memakai Overpass sebagai fallback. Karena itu jalankan prototype melalui `npm run dev` atau `npm run preview`, bukan dengan membuka `dist/index.html` langsung. Layanan publik tersebut dapat lambat atau timeout. Implementasi produksi sebaiknya membangun graph dari ekstrak OSM/PBF sebelum operasi dan memasukkannya ke paket peta wilayah, sehingga tidak bergantung pada layanan publik di lapangan.

## Batas prototype

- BLE/LoRa, GPS, RSSI/SNR, dan ACK masih fixture simulator.
- Service worker cache bukan pengganti paket MBTiles wilayah operasi.
- Coverage merupakan cakupan geometris berdasarkan track dan buffer, bukan probabilitas korban ditemukan.
- ETA graph jalur OSM lama belum memakai DEM. Provider Terrain baru menggunakan elevasi, kelas tutupan lahan, dan hazard lokal; cuaca, stamina, serta keterlintasan nyata belum diukur.
- Petunjuk rute tidak boleh dianggap sebagai bukti medan aman. Hybrid dapat menghitung kandidat pada medan tak terpetakan, tetapi keterlintasan aktual tetap membutuhkan penilaian/verifikasi lapangan.
- State operasi disimpan di `localStorage`; graph OSM dan paket medan di IndexedDB. Kegagalan storage ditampilkan. Transport ACK, RSSI, posisi, serta koneksi BLE masih simulator.
- Coverage penyisiran lama masih pendekatan panjang track x lebar buffer; belum union buffer GIS. Metrik itu belum memenuhi kriteria coverage final.
