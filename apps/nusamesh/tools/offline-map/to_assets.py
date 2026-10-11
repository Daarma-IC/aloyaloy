"""MBTiles → aset APK (offline-tiles/z/x/y.png + metadata.json) supaya peta bawaan langsung aktif tanpa impor."""
import json, os, shutil, sqlite3, sys

src, out = sys.argv[1], sys.argv[2]
target = os.path.join(out, "offline-tiles")
if os.path.exists(target):
    shutil.rmtree(target)
db = sqlite3.connect(src)
meta = dict(db.execute("SELECT name, value FROM metadata"))
count = 0
for z, x, row, data in db.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles"):
    y = (1 << z) - 1 - row                     # MBTiles TMS → XYZ seperti diminta Leaflet
    path = os.path.join(target, str(z), str(x))
    os.makedirs(path, exist_ok=True)
    with open(os.path.join(path, f"{y}.png"), "wb") as f:
        f.write(data)
    count += 1
minz, maxz = db.execute("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles").fetchone()
json.dump({"name": meta.get("name", "Peta bawaan"), "format": meta.get("format", "png"), "minzoom": minz, "maxzoom": maxz,
           "bounds": meta.get("bounds"), "attribution": meta.get("attribution", ""), "tiles": count},
          open(os.path.join(target, "metadata.json"), "w"), ensure_ascii=False)
print(f"{count} ubin → {target}")
