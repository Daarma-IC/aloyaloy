# Verifikasi tahap routing medan

Tanggal: 14 Oktober 2026. Lingkungan: Windows, Node 24, Vite 5, Chromium headless melalui Playwright, Python 3.13, Rasterio 1.4.4. Aplikasi NusaMesh tidak diubah.

## Hasil

| Pemeriksaan | Hasil |
|---|---|
| `npm run build` | Lulus; worker menjadi asset terpisah |
| `npm test` | 43 uji lulus |
| `npm run test:browser` | 10 alur browser lulus, termasuk profil/ekspor elevasi, reload dan rerouting offline |
| `.venv/Scripts/python.exe tools/test_prepare_terrain.py` | 2 uji importer lulus |

Uji domain: medan tanpa jaringan jalan; determinisme; waktu naik/turun; profil peralatan; slope hard constraint; sungai dengan crossing; diagonal corner cutting; endpoint NoData/di luar AOI; barrier tipis antar pusat sel; perubahan hazard; konektor endpoint; perbedaan objective jarak/waktu; progress polyline dan sisa waktu per segmen; crossing ditutup; paket malformed; OSRM opsional/error/snapping/hazard; geofence satu event per crossing, ketidakpastian batas, pembatalan pending, dan baseline segera.

Uji browser: halaman utama memuat dua titik tanpa route/ETA; klik korban membuka tindakan Arahkan tanpa menghitung otomatis; menekan Arahkan menghasilkan route campuran jalan–medan, tiga warna segmen, pembagian jarak, dan ETA. Posisi dummy bergerak saat navigasi aktif; rute bertahan saat pindah tab dan reload offline; rerouting worker lokal tetap berjalan offline. Target baru menghapus rute lama sampai diarahkan kembali. Laporan penutupan menghasilkan alternatif tervalidasi atau kegagalan eksplisit. Pembanding road-only menolak korban di luar jaringan. Empat alur panel mesin memeriksa fixture, closure crossing, import malformed, GPS hilang, dan layout mobile 390 px.

Empat uji khusus graph memeriksa bahwa setiap segmen geometry berada pada edge OSM asli dengan ID way yang sesuai, menolak target di luar jaringan, menghitung ulang dari tengah ruas, dan menghormati polygon hambatan tanpa mengarang konektor akses.

Lima belas uji Hybrid menambahkan perjalanan tanpa jaringan, jalan ke korban luar jalan, sambungan antar komponen jalan, sungai/jembatan, larangan keluar struktur ke tanah pada titik tanpa junction, seluruh sel di sepanjang konektor, sudut diagonal, polygon subsel, footprint bangunan, NoData/kelas unknown/grade, endpoint exact, pembanding shortest diagonal analitik, profil beban/arah, oneway kaki, hazard-version rerouting, dan geometry/provenance dataset Kaliurang.

Tiga uji profil elevasi memeriksa interpolasi terhadap bidang analitik, geometri/vertex dan jarak kumulatif, interval sampling, NoData/versi paket, serta CSV/KML dengan altitude clampToGround untuk pembandingan independen. [Kontrak dan cara pembandingan](ELEVATION.md) mencatat perbedaan dataset dan batas validasi.

Uji importer memakai GeoTIFF sintetis dengan CRS UTM, elevasi numerik, lubang NoData, dan kategori 10/30. Hasil reprojection dicek terhadap validator JavaScript browser. Upsampling dan provenance kosong ditolak. Ini menguji pipeline file, bukan kualitas DEMNAS/WorldCover wilayah tertentu.

Demo utama memakai Kaliurang nyata: Copernicus DEM GLO-30 Public (release 2021), WorldCover 2021 v200, 209 ways OSM akses kaki, dan 1.168 footprint bangunan tertutup OSM. Graph jalur menyimpan 2.824 node OSM dengan identitas aslinya. Paket elevasi memiliki 8.772 sel valid pada grid UTM 35 m. Korban dummy default berada lebih dari 60 m dari jaringan OSM. Query Hybrid setelah Arahkan menghasilkan sekitar 2.487 m, 243 titik geometry, dan ETA model 5.203 detik (87 menit): 2.207 m ruas OSM, 120 m medan, dan 160 m transisi. Angka ini hasil dataset/model, bukan waktu perjalanan lapangan. Checksum paket dan sumber disimpan pada `public/demo/sources.json`. Screenshot: [hybrid-navigation.png](previews/hybrid-navigation.png).

## Batas yang masih ada

- Raster Kaliurang sudah diimpor dan dipakai dalam solver; kualitas keterlintasan saat ini dan ETA lapangan belum diuji.
- Solver GRASS binary belum dijalankan sebagai baseline numerik; model mengacu dokumentasi/source yang dibaca.
- Server OSRM self-hosted dengan PBF foot belum disiapkan; provider diuji melalui respons mock.
- Hybrid graph sudah menggabungkan jalan–medan–transisi dengan Dijkstra; optimalitas hanya pada graph diskret dan bobot yang dinyatakan. D* Lite, alternatif Pareto risiko, STRIDE/LiDAR, dan kalibrasi ETA belum dibuat.
- Footprint bangunan hanya closed ways; multipolygon, pagar, area privat, dan hambatan yang belum dipetakan belum menjadi layer constraint lengkap. Ketinggian deck jembatan/tunnel tidak tersedia pada DSM.
- GPS/BLE/LoRa/ACK tetap simulator, belum uji perangkat atau GPS background.
- Tidak ada reroute otomatis saat target bergerak; input berubah membuat rute outdated dan pengguna menghitung ulang.
- Peta dasar offline hanya tile yang tercache; belum paket basemap seluruh AOI.
- Geofence inti sudah diperbaiki, tetapi assignment dan transport simulator lama belum memenuhi seluruh kontrak produksi.
- Coverage lama belum union buffer GIS; pelaporan adaptif dan evaluasi radio belum dibuat.
- Paket JSON dan penyimpanan localStorage adalah pendekatan prototype AOI kecil; format chunk, RAM, dan performa HP produksi masih perlu dirancang/diukur.
