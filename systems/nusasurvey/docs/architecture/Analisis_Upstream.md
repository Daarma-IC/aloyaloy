# Analisis upstream untuk NusaSurvey

Tanggal: 14 Oktober 2026. Dokumen ini adalah catatan tahap audit awal: saat itu baru pembacaan source terpilih dan dokumen resmi, sebelum importer/solver/integrasi. Implementasi berikutnya sudah membuat paket raster Kaliurang, solver lokal, graph Hybrid, navigasi, dan profil elevasi. Status aktual, hasil pengujian, dan batas fitur ada di [dokumentasi pengembangan](../Dokumentasi_Pengembangan_NusaSurvey.md). Pernyataan “belum” pada uraian audit di bawah merekam keadaan tahap awal, bukan status runtime terbaru.

## 1. OSRM: profil, graph jalur, dan API

Source yang dibaca: `research/source/osrm/profiles/foot.lua`, `profiles/lib/way_handlers.lua`, `src/engine/plugins/viaroute.cpp`, `include/engine/api/route_parameters.hpp`, `docs/profiles.md`, dan `docs/areas.md`.

Profil foot mempunyai hierarchy akses `foot` lalu `access`, barrier, surface speed, dan aturan one-way khusus pejalan kaki. Ini lebih lengkap dibanding filter kelas jalan pada prototype. Plugin route memvalidasi koordinat, mencari phantom nodes untuk snapping, memilih pencarian rute, dan membedakan `NoSegment` dari `NoRoute`.

Temuan tambahan: snapshot upstream memuat meshing area pedestrian eksperimental melalui `visgraph+dijkstra`, default nonaktif. Karena itu OSRM tidak boleh disederhanakan menjadi hanya garis jalan. Namun fitur area ini belum membuktikan routing DEM, land-cover, dan hambatan kebencanaan yang diminta NusaSurvey.

Pemetaan: provider jaringan jalur, kontrak route/error, validasi snapping, serta baseline profil foot. Release runtime perlu dipilih dan diuji terpisah.

Sumber: [profil foot](https://github.com/Project-OSRM/osrm-backend/blob/f2147ead8d9c6a2c724120113d9c56ab85963262/profiles/foot.lua), [route plugin](https://github.com/Project-OSRM/osrm-backend/blob/f2147ead8d9c6a2c724120113d9c56ab85963262/src/engine/plugins/viaroute.cpp), [area meshing](https://github.com/Project-OSRM/osrm-backend/blob/f2147ead8d9c6a2c724120113d9c56ab85963262/docs/areas.md).

## 2. GRASS: acuan solver medan

Source yang dibaca: `research/source/grass/raster/r.walk/main.c`, `heap.c`, `r.walk.md`, dan `raster/r.path/main.c`.

`r.walk` memakai elevasi dan friction raster, min-heap, relaxation biaya tetangga, serta arah untuk rekonstruksi lintasan. Per-edge, grade menentukan koefisien uphill/downhill; friction kedua sel dirata-ratakan lalu dikalikan jarak dan lambda. Biaya A→B dapat berbeda dari B→A. Sel NULL secara default dikecualikan. `r.path` menjadi acuan ekstraksi lintasan dari output arah.

Pemetaan: baseline Dijkstra berarah, model waktu bersatuan, predecessor, NoData, serta pembanding hasil solver lokal. Larangan memotong sudut barrier dan hard constraints NusaSurvey perlu dibuat eksplisit; tidak diasumsikan otomatis setara dengan perilaku upstream.

Koefisien default bukan hasil kalibrasi tim SAR Indonesia. GRASS disimpan bersama lisensi GPL; keputusan penggunaan binary/library atau adaptasi source perlu dicatat sebelum distribusi.

Sumber: [implementasi r.walk](https://github.com/OSGeo/grass/blob/ba898e78d725dce81c6218dac1ceffee7f93e29f/raster/r.walk/main.c), [model r.walk](https://grass.osgeo.org/grass-stable/manuals/r.walk.html).

## 3. GDAL: persiapan paket wilayah

Source yang dibaca: `research/source/gdal/apps/gdalwarp_lib.cpp`, `apps/gdaldem_lib.cpp`, `apps/gdal_rasterize_lib.cpp`, `alg/gdalwarper.cpp`, dan `alg/gdalrasterize.cpp`.

Warp menyediakan reprojection, resampling, dan pengelolaan raster. DEM processing memuat perhitungan gradient/slope, termasuk Horn, dengan skala sumbu. Rasterize meneruskan opsi `-at` menjadi `ALL_TOUCHED=TRUE`.

Pemetaan: clip AOI dengan margin, reprojection ke CRS metrik, alignment grid, slope layer, dan rasterisasi hazard konservatif. Land-cover kategorikal memakai nearest-neighbor. DEM tidak diganti nol ketika NoData; nilai unknown tetap dipertahankan. ALL_TOUCHED membantu menangkap barrier tipis, tetapi koneksi diagonal/segmen rute tetap harus diuji di solver.

Sumber: [GDAL source](https://github.com/OSGeo/gdal/tree/13310f750da0a09bbc944067b7d7fa8b8b27b110), [slope docs](https://gdal.org/en/stable/programs/gdal_raster_slope.html).

## 4. DEMNAS dan Copernicus: sumber elevasi

DEMNAS BIG menjelaskan resolusi 0,27 arc-second dan datum vertikal EGM2008. Copernicus menjelaskan produk DSM, GLO-30/GLO-90, GeoTIFF/DTED, grid alignment, datum, serta quality layers. Informasi ini merupakan spesifikasi dataset, bukan source solver yang bisa di-clone.

Pemetaan: adaptor import elevasi dengan CRS horizontal, datum vertikal, unit, resolusi asli, alignment pixel, NoData, tanggal, lisensi, dan checksum. PixelIsPoint pada Copernicus perlu ditangani saat alignment dengan raster kategorikal. Dataset berbeda tidak dicampur tanpa pemeriksaan georeferensi/datum. Raster nyata dan kualitas AOI belum diperiksa.

Sumber: [DEMNAS BIG](https://www.big.go.id/en/content/product/demnas), [Copernicus DEM](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM).

## 5. WorldCover: import dan kelas medan

Source yang dibaca: `research/source/worldcover/scripts/download.py`, `notebooks/utils/labels.py`, `notebooks/utils/stac.py`, dan README.

Downloader memilih tile berdasarkan intersection AOI lalu mengambil COG dari bucket publik. Kode label mendefinisikan NoData=0, tree=10, shrub=20, grass=30, crop=40, built=50, bare=60, snow=70, water=80, wetland=90, mangrove=95, dan lichens/mosses=100. Kode tersebut tidak menyediakan koefisien kecepatan SAR.

Pemetaan: import land-cover dan pemetaan kelas ke friction/profile yang dinyatakan sebagai asumsi. Air dapat menjadi hard constraint sesuai kebijakan operasi; hutan bukan otomatis barrier. Downloader contoh perlu diperkuat dengan timeout, HTTP validation, checksum, dan unduhan streaming sebelum dipakai sebagai importer produksi. Jangan jalankan tanpa AOI karena default dapat mengambil seluruh tile.

Sumber: [repo tutorial](https://github.com/ESA-WorldCover/esa-worldcover-datasets/tree/82bdd1d342685de9ff1782dff96ad875844a142f), [data dan atribusi](https://esa-worldcover.org/en/data-access).

## 6. Earth Engine: persiapan sebelum operasi

Source yang dibaca: `research/source/earthengine/python/ee/batch.py`, `image.py`, `data.py`, dan `tests/batch_test.py`.

Client mengonfigurasi image/export task; `toDrive`/`toCloudStorage` mendukung region, CRS, transform, GeoTIFF, dan opsi NoData/cloud-optimized. Eksekusi dataset/komputasi berada di server Earth Engine; client bukan mesin cloud yang dapat dijalankan lokal.

Pemetaan: alat persiapan opsional sebelum operasi untuk clip/export raster numerik. Runtime lapangan membaca hasil ekspor lokal. Proyek/otorisasi Earth Engine belum digunakan dan tidak dibutuhkan untuk mulai importer file lokal.

Sumber: [client source](https://github.com/google/earthengine-api/tree/9ec29fe12a009862f25c6613a5fc8648b89aacaf), [export docs](https://developers.google.com/earth-engine/guides/exporting_images).

## 7. Susunan implementasi yang diturunkan dari pembacaan

```text
DEMNAS / Copernicus + WorldCover + hazard + OSM
              ↓ import file / export Earth Engine opsional
GDAL preprocessing → terrain package + manifest versi
              ↓
Solver medan lokal: Dijkstra berarah + constraint + predecessor
              ↓
RoutingProvider → statistik/ETA per edge → navigasi dan rerouting
              ↑
OSRM foot: provider graph jalur terpetakan dan baseline tambahan
```

Tahap berikutnya: kontrak paket wilayah; importer raster; baseline solver yang dibandingkan dengan r.walk; barrier dan NoData; statistik rute; integrasi ke prototype. Pengujian awal harus mencakup dataran tanpa jalan, uphill/downhill, sungai dengan crossing, diagonal barrier, akses tertutup, dan perubahan hazard. AOI nyata belum dipilih; lokasi fixture prototype tidak dianggap pilihan pengguna.

Perubahan tahap ini hanya pengumpulan/analisis source serta organisasi dokumen. Belum ada integrasi engine, instalasi binary, pengambilan raster wilayah, atau perubahan aplikasi NusaMesh.
