# Peta offline bawaan

Membuat peta raster dari **data mentah OpenStreetMap** (ekstrak Geofabrik, lisensi ODbL) — bukan menyedot
ubin dari tile.openstreetmap.org (dilarang kebijakan pemakaiannya). Hasilnya ikut tertanam di APK sehingga
peta langsung tersedia offline tanpa impor.

```sh
python3 -m venv .venv && .venv/bin/pip install osmium pillow
curl -LO https://download.geofabrik.de/asia/indonesia/java-latest.osm.pbf     # ±900 MB
.venv/bin/python extract.py java-latest.osm.pbf bandung.pkl                   # ±13 menit
.venv/bin/python render.py bandung.pkl bandung-raya.mbtiles                   # ±1 menit, ±97 MB
.venv/bin/python to_assets.py bandung-raya.mbtiles ../../composeApp/offline-maps
```

- Wilayah & zoom diatur di bagian atas `extract.py` (`W,S,E,N`, `CORE`) dan `render.py` (`MINZ`, `MAXZ`).
- `composeApp/offline-maps/` di-*gitignore* (±97 MB). Tanpa folder itu APK tetap bisa dibuat, hanya tanpa peta bawaan.
- File `.mbtiles` yang sama juga bisa diimpor manual di app (Peta → Peta offline → Impor MBTiles).
- Wajib mencantumkan atribusi "© OpenStreetMap contributors (ODbL)" (sudah ada di metadata & peta).
