# Kontrak terrain package dan mesin routing

Navigasi utama memakai provider `Hybrid` yang menggabungkan ruas OSM asli, sel medan, dan transisi tervalidasi. Lihat [kontrak Hybrid](HYBRID.md). Provider `MappedOSM` dan `Terrain` tetap menjadi pembanding. Route MappedOSM menjaga geometry jalan/jalur OSM, memakai interpolation elevasi bilinear untuk model grade, menolak snap lebih dari 3 m, serta memvalidasi polygon hazard pada setiap edge. Bagian di bawah menjelaskan provider Terrain. Rute baru dihitung setelah pengguna memilih titik korban dan menekan Arahkan; memuat paket wilayah tidak otomatis membuat route.

## Pipeline

GeoTIFF DEM (DEMNAS/Copernicus atau sumber berizin) + GeoTIFF WorldCover -> Rasterio/GDAL clip/reproject -> grid UTM -> JSON lokal -> IndexedDB -> Dijkstra di Web Worker -> route plan -> navigasi.

Earth Engine boleh digunakan untuk menyiapkan ekspor GeoTIFF sebelum operasi; bukan dependency runtime browser. Tidak ada kode server Earth Engine yang di-port. Source GDAL/GRASS upstream dipakai sebagai bahan pembacaan; binary GDAL digunakan melalui Rasterio, sedangkan solver JavaScript merupakan implementasi lokal dengan model yang terdokumentasi. GRASS binary belum dijalankan untuk perbandingan hasil.

## Persiapan GeoTIFF

```powershell
.\.venv\Scripts\python.exe tools/prepare_terrain.py --dem data/dem.tif --land-cover data/worldcover.tif --aoi WEST SOUTH EAST NORTH --metadata data/metadata.json --resolution 30 --margin 300 --out data/terrain-v1.json
```

Ganti WEST SOUTH EAST NORTH dengan angka AOI sebenarnya dalam WGS84. Contoh metadata tersedia di `tools/metadata.example.json`; nama sumber, tanggal pengamatan/unduh, lisensi dan datum harus sesuai data. CLI menerima satu raster DEM dan satu raster land-cover; mosaic beberapa tile dilakukan sebelumnya. Output baru tidak menimpa paket yang sudah ada.

Grid diproyeksikan ke UTM berdasarkan pusat AOI, dibatasi 100.000 sel dan rentang AOI maksimal 0,2 derajat per sumbu. Semua jarak solver dihitung dalam meter secara geodesik antara koordinat sel; derajat tidak dijumlahkan sebagai meter. Elevasi harus sudah dalam meter. Datum vertikal dipertahankan, tidak diubah otomatis. Dataset DSM tidak dianggap ground DTM.

Importer menolak resolusi grid yang lebih halus dari perkiraan resolusi metrik sumber paling kasar. Elevasi bilinear dan kategori nearest-neighbor mengikuti fungsi [Rasterio reprojection](https://rasterio.readthedocs.io/en/stable/topics/reproject.html) dan [resampling](https://rasterio.readthedocs.io/en/stable/topics/resampling.html). Mask NoData dipertahankan sebagai null. Footprint, cell corners, transform, sumber, checksum file input, statistik AOI, dan kebijakan resampling disimpan dalam paket.

## Schema version 1

```text
schemaVersion: 1
id, datasetVersion, synthetic, crs, verticalDatum, sources[], preparedAt
grid: { rows, cols, resolutionM, transform? }
footprint: polygon koordinat internal [latitude, longitude]
coordinates: pusat sel row-major, [latitude, longitude][]
cellCorners?: polygon setiap sel (hasil importer GeoTIFF)
elevationM: number | null per sel
landCover: kode WorldCover | null per sel
blocked: boolean per sel
```

Semua array raster berukuran rows x cols. Penerimaan paket memeriksa schema, field metadata, koordinat, ukuran array, nilai elevasi/kategori, serta jarak tetangga. Tidak menerima file di atas 32 MB dari UI. JSON ini merupakan intermediate package untuk AOI kecil, belum format chunk produksi HP.

Land-cover 80 (air), mask blocked, dan polygon hazard blockingPolicy=block merupakan larangan. Kategori unknown/NoData ditolak secara konservatif. Geometry hazard diperiksa terhadap edge dan konektor endpoint, sehingga barrier tipis di antara pusat sel tetap tidak boleh dilintasi. Diagonal membutuhkan dua sel orthogonal valid. Pada fixture sungai, satu sel crossing sudah diberi kategori traversable; keputusan ini sintetis, bukan deteksi jembatan otomatis dari satelit.

## Model waktu

Acuan formula: [GRASS r.walk](https://grass.osgeo.org/grass-stable/manuals/r.walk.html). Implementasi membaca grade arah perjalanan per edge; bukan hanya slope visualisasi.

```text
grade = (z_to - z_from) / distance_horizontal_m
coefficient = 6 (naik), 1.9998 (turun moderat), -1.9998 (turun grade < -0.2125)
walking_seconds = 0.72 * distance_horizontal_m + coefficient * elevation_delta_m
seconds = (walking_seconds + average_friction_seconds_per_m * distance_horizontal_m) / speedFactor
```

Friction nominal (detik/meter): hutan 0,65; semak 0,9; rumput 0,1; pertanian/terbangun 0,2; terbuka 0; salju 1,2; wetland 1,5; mangrove 1,8; lumut 0,3. Angka ini asumsi pengembangan, bukan hasil pengukuran. Profil nominal speedFactor=1 dan batas grade 35 derajat; peralatan speedFactor=0,7 dan 25 derajat. Batas ini constraint model, bukan ambang universal keselamatan.

Objective time meminimalkan seconds ditambah penalti preferensi bila paket menyediakannya. Paket Kaliurang memakai mask koridor jalur OSM yang dirasterisasi all-touched dan penalti 3 detik ekuivalen/meter di luar koridor. Penalti ini hanya masuk routingWeight, bukan durationEstimate. Objective distance mengabaikan penalti preferensi dan menjadi baseline jarak dengan hard constraints sama; ETA model tetap dihitung. Solver memakai Dijkstra/heuristic nol.

Konektor posisi asli ke pusat sel dibatasi sekitar setengah diagonal sel (maksimum 0,8 x resolusi). Elevasi konektor diasumsikan sama dengan pusat sel; bukan survei subsel. Tidak ada smoothing lintasan. Ascent/descent menggunakan sampel grid tanpa filtering, diberi peringatan noise DEM. Statistik 3D memakai hypot(jarak horizontal, delta elevasi), bukan pengukuran permukaan detail.

## Navigasi dan perubahan data

Progress diproyeksikan ke segmen polyline; ETA tersisa menjumlahkan waktu segmen tersisa. Bearing waypoint menggunakan utara, tidak ada heading kompas palsu. Deviasi terkonfirmasi 5 detik di atas max(30 m, 2 x akurasi GPS) membuat rute outdated. GPS hilang menjeda navigasi. Query worker dibatalkan ketika panel ditutup; hasil lama tidak boleh mengganti target/versi hazard yang lebih baru.

Hambatan, profil, target, atau paket baru membatalkan kesiapan rute. Rerouting menggunakan tombol eksplisit. Kegagalan query mempertahankan rute lama dengan status STALE; navigasi tidak bisa dilanjutkan sampai rute baru tersedia. Rerouting otomatis target bergerak dengan threshold/time gate, routing hybrid optimum, provider GPS nyata, dan transfer route terfragmentasi melalui mesh belum diimplementasikan.

## Hasil query

`routeTerrain` menerima pack, from, to, profileId, objective, hazards, hazardVersion, dan targetPositionTimestamp. Hasil available berisi geometry, segments, length, length3d, durationEstimate, ascent/descent, elevationProfile, coverLengths, dataCoverage, warnings, modelVersion, packageVersion, hazardVersion, waktu query, dan jumlah sel explored. Gagal dibedakan menjadi missingData, noTraversablePath, atau unavailable. Pembatalan/stale dikelola lapisan UI.

Ekspor request/result menyertakan versi input dan profil; paket JSON diekspor terpisah untuk reproduksi. Metrik computeMs hanya runtime query, bukan latency radio. Memory HP, perbandingan GRASS binary, dataset nyata dan waktu perjalanan lapangan belum diukur.
