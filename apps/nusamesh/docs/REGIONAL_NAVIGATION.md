# Paket navigasi Indonesia dan prioritas Jawa Barat

Implementasi 15 Oktober 2026 memperluas integrasi navigasi NusaMesh menjadi beberapa paket wilayah operasi. Awal dan tujuan harus tercakup **satu paket**. Perhitungan tetap berjalan di perangkat melalui worker Hybrid OSM + medan. Fencing/editor polygon tidak termasuk tahap ini.

## Cakupan yang tersedia

| Paket bawaan | Batas W,S,E,N (derajat WGS84) |
|---|---|
| Gede–Cibodas, Jawa Barat | 106.971, -6.766, 107.021, -6.716 |
| Tangkuban Parahu, Jawa Barat | 107.585, -6.785, 107.635, -6.735 |
| Papandayan, Jawa Barat | 107.708, -7.337, 107.758, -7.287 |
| Kaliurang, paket sebelumnya | 110.425, -7.601, 110.452, -7.569 |

Nama kawasan bukan klaim seluruh gunung/kabupaten tercakup: batas koordinat pada tabel adalah cakupan sebenarnya. Paket Gede–Cibodas belum mencakup puncak Gede. Paket tambahan dapat dibuat untuk area lain di Jawa Barat maupun Indonesia dalam bounding envelope 94–142 BT dan 12 LS–7 LU; envelope ini bukan polygon administrasi nasional.

Setiap paket memakai grid 35 m, Copernicus GLO-30 Public, ESA WorldCover 2021 v200, snapshot OSM serta footprint bangunan OSM tertutup. Raster NoData tetap ditolak. Tidak ada elevasi buatan atau koordinat orang dummy yang dimasukkan dalam aplikasi.

## Alur aplikasi (koreksi rendering peta, 17 Oktober 2026)

1. Peta utama dikembalikan ke rendering sebelum perapihan navbar: tile OpenStreetMap online, peta wilayah Natural Earth sebagai fallback, serta MBTiles dan cache Android yang sudah ada. Layer warna medan/kontur percobaan ditarik dari runtime karena hanya mencakup area kecil dan bukan pengganti basemap lengkap.
2. Empat wilayah routing di atas tetap dibundel. Pilih unit/titik lalu Arahkan; pemilihan wilayah dan solver Hybrid berlangsung lokal tanpa server routing atau IndexedDB. Awal dan tujuan harus berada dalam satu footprint bawaan.
3. Tombol/panel Paket navigasi tetap dihapus. Navbar/panel navigasi ringkas tetap dipertahankan.
4. Pencarian kota bawaan tersedia offline; pencarian lainnya kembali memakai geocoding online seperti semula.

**Batas offline:** data routing bukan peta detail nasional. Peta detail di luar MBTiles/cache memerlukan internet; saat offline tampak peta wilayah kasar. Klaim bahwa seluruh peta sudah tersedia offline pada perubahan sebelumnya keliru. Aset MBTiles detail belum tersedia di workspace ini. Tidak ada angka ASL/AGL baru yang dikarang; profil DEM rute tetap tersedia pada Detail.

Verifikasi rendering memakai `node tools/navigation/check-live-map.mjs` pada aplikasi localhost aktual tanpa fixture posisi/rute atau respons tile palsu. Pemeriksaan mencakup pemuatan tile nyata di Bandung, Jakarta, dan Kaliurang. `check-map.mjs` dan `check-package-storage.mjs` memeriksa routing lokal saat tile tidak tersedia.

## Tooling pengembang untuk menyiapkan wilayah tambahan

Dari root repo, pakai virtual environment GIS yang tersedia atau buat environment sendiri dengan `tools/navigation/requirements.txt`:

```powershell
apps/geofence-prototype/.venv/Scripts/python.exe -m pip install -r apps/nusamesh/tools/navigation/requirements.txt
apps/geofence-prototype/.venv/Scripts/python.exe apps/nusamesh/tools/navigation/packages.py --jabar
apps/geofence-prototype/.venv/Scripts/python.exe apps/nusamesh/tools/navigation/packages.py --serve
```

Layanan pengembangan: `http://127.0.0.1:8083`. Layanan ini hanya untuk persiapan data; aplikasi tidak mengaksesnya. Endpoint `GET /catalog` menjelaskan preset dan batas paket; `GET /package?bbox=WEST,SOUTH,EAST,NORTH` menyiapkan/mengambil paket nyata. Default hanya bind localhost.

Untuk membuat paket tambahan lewat CLI:

```powershell
apps/geofence-prototype/.venv/Scripts/python.exe apps/nusamesh/tools/navigation/packages.py --bbox 107.1 -6.85 107.15 -6.8
```

Cache sumber dan paket berada rapi di `systems/nusasurvey/data/navigation/`. Raster COG sparse adalah cache preprocessing yang hanya berisi blok terkait area; **bukan raster sumber lengkap yang boleh disebarkan sebagai dataset**. Hash sumber dalam metadata merujuk GeoTIFF crop lengkap dan snapshot OSM, bukan bagian kosong cache sparse. Pembacaan blok HTTP memeriksa Content-Range dan panjang respons sebelum raster dibaca. Pemilihan tile DEM 1° dan WorldCover 3° menangani batas tile; mosaik dilakukan sebelum reproyeksi grid metric UTM dan resampling.

`packages.py` tidak menghitung route di server. Ia mengambil raster publik dan Overpass, melakukan preprocessing, dan menyimpan paket secara atomik. Permintaan yang menghasilkan error/timeout/OSM remark tidak dipublikasikan sebagai paket lengkap. Satu job pembuatan aktif di layanan pengembangan; request bersamaan mendapat 503. Paket dengan bbox sama memakai cache. API pengembangan belum merupakan layanan publik production dengan auth, kuota, refresh data terjadwal atau job queue.

## Build dan verifikasi

```powershell
cd apps/nusamesh
node tools/navigation/bundle.mjs
node tools/navigation/check-regions.mjs
node tools/navigation/check-map.mjs
node tools/navigation/check-package-storage.mjs
./gradlew.bat :composeApp:testDebugUnitTest :composeApp:assembleDebug :composeApp:wasmJsBrowserDevelopmentWebpack --offline --console=plain
```

Bundler memasukkan empat paket nyata dalam `regions.json`; `regions-manifest.json` mencatat versi/bbox/hash tiap paket. Data demo endpoint dan geometry cell-corner untuk inspector prototype tidak dibundel dalam paket regional aplikasi. Paket Kaliurang lama dipertahankan sebagai asset terpisah untuk pemeriksaan regresi. Test browser unduhan memakai respons fixture paket **nyata** pada layanan mock, terpisah dari runtime; test layanan HTTP nyata dilakukan tersendiri.

## Catatan verifikasi tahap sebelumnya

Catatan unduhan/panel berikut mencatat implementasi 15 Oktober sebelum panel dihapus. Perilaku runtime terbaru dijelaskan di Alur aplikasi. Pemeriksaan `check-package-storage.mjs` sekarang menguji rute bawaan tanpa IndexedDB, reload dan tanpa unduhan routing; permintaan tile basemap dipisahkan.

## Batas penting

Verifikasi paket nyata: ketiga area Jawa Barat memiliki masing-masing 25.600 sel elevasi valid tanpa NoData pada grid hasil preprocessing. Jumlah jalur OSM: Cibodas 412, Tangkuban Parahu 219, Papandayan 85. Rute uji pada graph nyata berhasil dengan panjang sekitar 6.032 m, 6.809 m dan 5.560 m; endpoint uji hanya ada di tooling, tidak menjadi koordinat orang dalam aplikasi. [Hasil routing terukur](previews/jabar-routing-check.json).

117 tes Kotlin lulus. Pemeriksaan browser meliputi regresi rute Kaliurang, pemilihan paket berdasarkan footprint, validasi input, unduhan, IndexedDB, reload, hitung rute offline dari paket Jawa Barat tersimpan, serta penghapusan paket. Endpoint layanan lokal diuji mengembalikan paket nyata dan menolak bbox di luar envelope.

Build Android debug dan webpack Wasm development berhasil. Pemeriksaan aplikasi Compose yang benar-benar dikompilasi lulus untuk rute Kaliurang dan Papandayan: restore PAUSED, geometry tetap, tidak ada permintaan route otomatis, seluruh rute masuk viewport, panel empat paket dan reload. Uji unduhan/penyimpanan/routing offline juga lulus dengan layanan HTTP lokal nyata menggunakan `NUSA_PACKAGE_SERVICE=http://127.0.0.1:8083`, selain fixture mock. Assets `regions.json`, `solver.js` dan `regions-manifest.json` dalam APK sesuai hasil bundling; APK 33.760.688 byte pada run verifikasi ini.

- Paket operasi maksimum lebar/tinggi 0,09° (~10 km) dan 100.000 sel. Ini belum mesin routing jarak jauh seluruh provinsi/nasional atau stitching antar-paket.
- Jaringan OSM hanya sesuai jalur yang terpetakan dan tag akses; bangunan relation/multipolygon serta hambatan yang belum dipetakan belum lengkap.
- Elevasi GLO-30 merupakan DSM dengan sampling ~30 m. Grid 35 m tidak membuatnya mampu mendeteksi seluruh tebing/jurang kecil; ETA masih model belum kalibrasi lapangan.
- Belum ada AI route, detektor jurang, buffer tebing, konfirmasi kondisi jalur lapangan atau integrasi zona bahaya routing.
- Build Android/browser dan test software tidak menggantikan uji GPS/BLE/perjalanan di ponsel. Build iOS belum diverifikasi di Windows.

## Sumber primer

- [Copernicus DEM, AWS Open Data](https://registry.opendata.aws/copernicus-dem/): COG GLO-30 Public dengan pembacaan window.
- [ESA WorldCover, akses data resmi](https://esa-worldcover.org/en/data-access): WorldCover 2021 v200, COG 3° dan CC BY 4.0.
- [Overpass API](https://overpass-api.de/): snapshot ways/nodes highway dan bangunan OSM.
- [OpenStreetMap copyright dan ODbL](https://www.openstreetmap.org/copyright): atribusi dan lisensi data jalan/bangunan.
- [Rasterio, windowed reading](https://rasterio.readthedocs.io/en/stable/topics/windowed-rw.html) dan [reprojection](https://rasterio.readthedocs.io/en/stable/topics/reproject.html): crop, mosaik, reproyeksi dan resampling nyata.
- [Dokumentasi integrasi navigasi](SAR_NAVIGATION.md) serta [referensi gabungan CD](../../../systems/nusasurvey/docs/Referensi_Gabungan_Navigasi_NusaSurvey_CD.md): model routing, ETA, teori dan riwayat tahap sebelumnya.
