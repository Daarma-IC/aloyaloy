# Bahan riset upstream

Source inti telah diimpor sebagai file terpilih dari commit upstream, bukan clone lengkap atau instalasi runtime. `manifest.json` di setiap direktori `source/` mencatat repository, commit, URL file, status pengambilan, dan SHA-256. File lisensi upstream ikut disimpan; file asli tidak diedit.

| Direktori | Repository resmi | Isi yang diambil |
|---|---|---|
| `source/osrm` | [Project-OSRM/osrm-backend](https://github.com/Project-OSRM/osrm-backend) | Profil foot, handlers, kontrak API, plugin route, dokumentasi area |
| `source/gdal` | [OSGeo/gdal](https://github.com/OSGeo/gdal) | Warp, rasterize, slope/DEM processing |
| `source/grass` | [OSGeo/grass](https://github.com/OSGeo/grass) | r.walk, heap, r.path, dokumentasi model biaya |
| `source/earthengine` | [google/earthengine-api](https://github.com/google/earthengine-api) | Client API, image, export task, dan test export |
| `source/worldcover` | [ESA-WorldCover/esa-worldcover-datasets](https://github.com/ESA-WorldCover/esa-worldcover-datasets) | Downloader, STAC helper, dan kode kelas tutupan lahan |

DEMNAS, Copernicus DEM, dan WorldCover adalah produk data. Snapshot dokumentasi resminya berada di `snapshots/`. Pada tahap audit awal raster belum disiapkan; implementasi berikutnya sudah menyiapkan crop Copernicus dan WorldCover Kaliurang serta data OSM dalam paket demo prototype. DEMNAS belum diimpor. Earth Engine client tidak menyertakan implementasi server cloud. Status aktual ada di [inventaris sumber](../docs/Sumber_dan_Teknologi.md) dan [dokumentasi pengembangan](../docs/Dokumentasi_Pengembangan_NusaSurvey.md).

Pengambilan ulang dari root workspace:

```powershell
& ./systems/nusasurvey/research/import-source.ps1 -Name osrm
```

Pilihan `-Name`: `osrm`, `gdal`, `grass`, `earthengine`, `worldcover`. Tidak ada installer atau proses build yang dijalankan oleh script. `AGENTS.md` yang tidak tersedia upstream ditandai sebagai gagal di manifest awal Earth Engine; itu bukan kegagalan pengambilan source inti.

Clone dan ZIP penuh terputus/reset/timeout. Sisa unduhannya disimpan di `incomplete-downloads/`, tidak digunakan untuk analisis atau build. Snapshot diambil 14 Oktober 2026; commit yang dicatat adalah bahan audit, belum pilihan release produksi.
