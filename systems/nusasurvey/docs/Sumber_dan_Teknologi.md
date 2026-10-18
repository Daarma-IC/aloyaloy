# Inventaris sumber, teknologi, dan peran pada NusaSurvey

Pembaruan: 14 Oktober 2026. Inventaris ini membedakan dependency runtime, data yang benar-benar digunakan, source yang dibaca, serta teknologi yang baru menjadi opsi. Detail kebutuhan ada pada [spesifikasi](specs/Prompt_Implementasi_NusaSurvey_SAR.md); realisasi ada pada [dokumentasi pengembangan](Dokumentasi_Pengembangan_NusaSurvey.md).

## 1. Teknologi aplikasi dan pipeline yang digunakan

| Komponen | Penggunaan aktual | Source/dokumentasi resmi dan bukti lokal |
|---|---|---|
| React 18 | UI navigasi, state dan panel evaluasi | [React](https://react.dev/); `apps/geofence-prototype/package.json` dan lockfile |
| Vite 5 | Dev server, build aplikasi/worker | [Vite](https://vite.dev/); package/lockfile dan build |
| Leaflet 1.9 | Peta, polyline, marker, popup dan layer | [Leaflet reference](https://leafletjs.com/reference.html); `src/main.jsx` |
| Leaflet Draw 1.0 | Alat gambar poligon pada panel operasi | [Repository](https://github.com/Leaflet/Leaflet.draw); fitur legacy tetap dipertahankan |
| JavaScript / Web Worker | Solver lokal dan pemrosesan routing di luar UI thread | [Web Workers](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API); `src/terrain/worker.js` |
| Python 3.13 / NumPy 2.2.6 | Pipeline persiapan raster/array grid | [NumPy](https://numpy.org/doc/); `tools/requirements.txt` |
| Rasterio 1.4.4 / GDAL | Membaca GeoTIFF, crop, reprojection/resampling, rasterize | [Rasterio reprojection](https://rasterio.readthedocs.io/en/stable/topics/reproject.html), [resampling](https://rasterio.readthedocs.io/en/stable/topics/resampling.html); `tools/prepare_terrain.py` dan `prepare_demo.py` |
| IndexedDB / localStorage | Paket wilayah, state operasi dan route plan | `src/terrain/storage.js`, state aplikasi; uji reload |
| Service Worker / Cache API | Precache aplikasi, worker dan demo; tile yang pernah dimuat | `public/` serta konfigurasi build; uji offline browser |
| Node test runner / Playwright Chromium | Pengujian domain dan UI/export/offline | [Node test runner](https://nodejs.org/api/test.html), [Playwright](https://playwright.dev/); `tests/` |
| SVG React | Grafik elevasi interaktif tanpa smoothing | `ElevationProfile.jsx`; bukan grafik random atau image raster |
| CSV / KML | Ekspor data sampel dan geometry pembanding | [KML altitude modes](https://developers.google.com/kml/documentation/altitudemode); `elevationProfile.js` |

Versi npm aktual mengikuti lockfile; rentang di package.json bukan identitas source upstream GIS. Binary GDAL digunakan melalui Rasterio; source GDAL yang disimpan untuk audit bukan hasil build yang dijalankan prototype.

## 2. Data yang dipakai dalam demo

| Dataset | Peran | Versi, sifat, dan batas |
|---|---|---|
| [Copernicus DEM](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM) GLO-30 | Elevasi, grade, ETA dan profil | Demo memakai release 2021; DSM global sekitar 30 m, dapat mencakup vegetasi/bangunan; bukan DTM langkah manusia |
| [ESA WorldCover](https://esa-worldcover.org/en/data-access) | Kategori land-cover untuk friction asumsi | 2021 v200, 10 m, 11 kelas; tidak memberikan kecepatan SAR atau kepadatan semak bawah tajuk |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) | Ruas/node jalan asli, akses kaki, bridge/tunnel, closed building ways | Snapshot Kaliurang 14 Oktober 2026; 209 ways jalan dan 1.168 bangunan; multipolygon dan fitur yang belum dipetakan belum lengkap |
| Poligon laporan lokal | Menolak segmen terdampak hambatan | Dibuat simulator; sumber dan verifikasi laporan dicatat, bukan observasi sensor nyata |
| Fixture sintetis | Pengujian hill/forest/river/crossing deterministik | `synthetic=true`; hanya panel pengujian, bukan terrain demo utama |
| Koordinat orang dummy | Menguji route input, pemilihan korban dan gerakan | Dataset terrain tetap nyata; posisi bukan korban/petugas nyata |

Sumber raw untuk crop demo:

- [Copernicus tile S08 E110](https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_S08_00_E110_00_DEM/Copernicus_DSM_COG_10_S08_00_E110_00_DEM.tif).
- [WorldCover tile S09 E108](https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_S09E108_Map.tif).
- [OSM Map API AOI demo](https://api.openstreetmap.org/api/0.6/map?bbox=110.425,-7.601,110.452,-7.569). Memanggil URL sekarang tidak menjamin snapshot identik; pakai berkas/hash yang dicatat untuk reproduksi.

Provenance raw crop dan hash paket berada di [sources.json](../../../apps/geofence-prototype/public/demo/sources.json). WorldCover dicatat CC BY 4.0; OSM ODbL dan atribusi contributor; Copernicus memakai lisensi produk publik yang ditautkan pada metadata. Atribusi sumber harus mengikuti ketentuan dataset. Tidak menyebut seluruh dataset sebagai source code aplikasi.

## 3. Source upstream yang diimpor dan dibaca

Lokasi: `systems/nusasurvey/research/source/`. Tiap direktori memuat manifest commit/URL/checksum dan lisensi upstream. Jumlah file mencakup source, dokumentasi, dan lisensi yang berhasil diambil.

| Upstream | Commit yang diaudit | File berhasil | Pengaruh pada rancangan | Status runtime |
|---|---|---:|---|---|
| [OSRM](https://github.com/Project-OSRM/osrm-backend) | `f2147ead8d9c6a2c724120113d9c56ab85963262` | 10 | Profil foot/access/surface, snapping, route/error API, meshing area eksperimental | Provider tambahan tersedia; server belum dijalankan |
| [GDAL](https://github.com/OSGeo/gdal) | `13310f750da0a09bbc944067b7d7fa8b8b27b110` | 8 | Warp, resampling, DEM/slope, rasterize | Dipakai melalui Rasterio, bukan build commit ini |
| [GRASS](https://github.com/OSGeo/grass) | `ba898e78d725dce81c6218dac1ceffee7f93e29f` | 7 | r.walk/r.path, heap/relaxation, model biaya arah dan NoData | Solver JS lokal; binary GRASS belum dibandingkan |
| [Earth Engine API](https://github.com/google/earthengine-api) | `9ec29fe12a009862f25c6613a5fc8648b89aacaf` | 6 | Client image/export task dan metadata | Opsional preprocessing; tidak dijalankan |
| [WorldCover tutorial](https://github.com/ESA-WorldCover/esa-worldcover-datasets) | `82bdd1d342685de9ff1782dff96ad875844a142f` | 5 | Downloader/STAC/class labels | Pipeline lokal memakai raster resmi; repo bukan solver SAR |

Pengambilan file terpilih dilakukan karena clone/ZIP penuh terputus. `research/incomplete-downloads/` menyimpan hasil tidak lengkap dan tidak menjadi dependency build. Kegiatan ini merupakan pembacaan/analisis source, bukan klaim seluruh upstream sudah diinstal atau seluruh kode sudah di-port. [Analisis detail](architecture/Analisis_Upstream.md).

## 4. Sumber yang dipelajari tetapi belum dipakai runtime

- [DEMNAS BIG](https://www.big.go.id/en/content/product/demnas): opsi data Indonesia, 0,27 arc-second, EGM2008. Raster DEMNAS belum diimpor ke demo; grid yang ada tetap Copernicus.
- [GRASS r.slope.aspect](https://grass.osgeo.org/grass-stable/manuals/r.slope.aspect.html): acuan analisis permukaan untuk dugaan tebing; layer sekitar rute belum dibuat.
- STRIDE/LiDAR, STEP/CVaR, D* Lite, dan model berbasis kamera: landasan/tahap lanjutan, bukan dependency saat ini. [Pemetaan pustaka](references/Landasan_Ilmiah.md).
- Google Earth: alat pembandingan manual melalui KML/CSV, bukan sumber elevasi runtime atau API yang diam-diam dipanggil.
- CalTopo/SARTopo, ATAK, QField, Meshtastic–TAK, SARCOP dan produk lain pada referensi awal: pembanding alur kerja SAR/GIS/mesh, bukan sistem yang seluruh source-nya diimpor atau fitur yang otomatis tersedia di prototype.
- MapLibre/Turf/Meshtastic protobuf: kandidat/adaptor pembanding pada spesifikasi; UI prototype memakai Leaflet dan fungsi geometri lokal. Firmware NusaNode tidak otomatis kompatibel dengan protokol pihak lain.

## 5. Pemisahan sumber dan kontribusi implementasi

Dataset memberikan pengamatan/kategori; library menyediakan renderer dan pemrosesan GIS; literatur memberikan model/algoritma serta bukti eksperimen. Implementasi NusaSurvey menggabungkannya melalui paket lokal, graph Hybrid, validasi transisi, state navigasi, pengelolaan versi hambatan, dan ekspor evaluasi. Nilai friction, speedFactor, batas grade, dan penalti preferensi adalah asumsi prototype yang dicatat, bukan angka yang diklaim berasal dari jurnal/dataset.
