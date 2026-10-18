"""Prepare real offline SAR packages for Indonesian operation areas; optional HTTP service."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import sys
import threading
from urllib.parse import parse_qs, urlparse, urlencode
from urllib.request import Request, urlopen

import rasterio
from rasterio.windows import from_bounds
from rasterio.merge import merge

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / "apps/geofence-prototype/tools"))
from prepare_terrain import prepare, sha256
from cog import selected_cog

CACHE = ROOT / "systems/nusasurvey/data/navigation"
PRESETS = json.loads((HERE / "regions.json").read_text(encoding="utf-8"))["regions"]
LOCK = threading.Lock()


def checked_bbox(values):
    if len(values) != 4 or not all(math.isfinite(v) for v in values):
        raise ValueError("bbox must contain four finite numbers: west,south,east,north")
    w, s, e, n = values
    if not (94 <= w < e <= 142 and -12 <= s < n <= 7):
        raise ValueError("Operation area must be within the Indonesia bounding envelope")
    if e - w > .090001 or n - s > .090001:
        raise ValueError("Maximum operation-area width/height is 0.09 degrees (~10 km); use a smaller area")
    return [round(v, 6) for v in values]


def source_urls(role, bbox):
    w, s, e, n = bbox
    step = 1 if role == "dem" else 3
    result = []
    for lat in range(math.floor(s / step) * step, math.floor(n / step) * step + 1, step):
        for lon in range(math.floor(w / step) * step, math.floor(e / step) * step + 1, step):
            ns, ew = ("N" if lat >= 0 else "S"), ("E" if lon >= 0 else "W")
            if role == "dem":
                tile = f"Copernicus_DSM_COG_10_{ns}{abs(lat):02}_00_{ew}{abs(lon):03}_00_DEM"
                result.append(f"https://copernicus-dem-30m.s3.amazonaws.com/{tile}/{tile}.tif")
            else:
                result.append(f"https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_{ns}{abs(lat):02}{ew}{abs(lon):03}_Map.tif")
    return result


def raster_window(role, bbox, folder):
    output = folder / f"{role}.tif"
    expanded = [bbox[0] - .001, bbox[1] - .001, bbox[2] + .001, bbox[3] + .001]
    urls = source_urls(role, expanded)
    if output.exists():
        return output, urls
    crops = []
    with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif", GDAL_HTTP_TIMEOUT="30", GDAL_HTTP_MAX_RETRY="1"):
        for i, url in enumerate(urls):
            crop = folder / f"{role}-{i}.tif"
            if not crop.exists():
                print(f"Read {role}: {url}", flush=True)
                source = selected_cog(url, folder / f"{role}-source-{i}.tif", expanded)
                with rasterio.open(source) as src:
                    bounds = [max(src.bounds.left, expanded[0]), max(src.bounds.bottom, expanded[1]), min(src.bounds.right, expanded[2]), min(src.bounds.top, expanded[3])]
                    if bounds[0] >= bounds[2] or bounds[1] >= bounds[3]:
                        continue
                    window = from_bounds(*bounds, src.transform).round_offsets().round_lengths()
                    data = src.read(1, window=window)
                    profile = src.profile.copy()
                    profile.update(driver="GTiff", width=data.shape[1], height=data.shape[0], transform=src.window_transform(window))
                    temporary = crop.with_suffix(".tmp.tif")
                    with rasterio.open(temporary, "w", **profile) as dst:
                        dst.write(data, 1)
                    temporary.replace(crop)
            crops.append(crop)
    if not crops:
        raise ValueError("No real raster coverage for this area")
    readers = [rasterio.open(p) for p in crops]
    try:
        array, affine = merge(readers)
        profile = readers[0].profile.copy()
        profile.update(driver="GTiff", height=array.shape[1], width=array.shape[2], transform=affine)
        with rasterio.open(output.with_suffix(".tmp.tif"), "w", **profile) as dst:
            dst.write(array)
        output.with_suffix(".tmp.tif").replace(output)
    finally:
        for reader in readers:
            reader.close()
    return output, urls


def osm_snapshot(bbox, folder):
    output = folder / "osm.json"
    if output.exists():
        return json.loads(output.read_text(encoding="utf-8"))
    w, s, e, n = bbox
    box = f"{s-.001},{w-.001},{n+.001},{e+.001}"
    query = f'[out:json][timeout:90];(way["highway"]({box});way["building"]({box}););(._;>;);out body;'
    failures = []
    for endpoint in ("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"):
        try:
            req = Request(endpoint, data=urlencode({"data": query}).encode(), headers={"User-Agent": "NusaSurvey-package-builder/1.0"})
            with urlopen(req, timeout=120) as response:
                raw = response.read(50_000_001)
            if len(raw) > 50_000_000:
                raise ValueError("OSM response too large; crop the operation area")
            data = json.loads(raw)
            if data.get("remark") or not isinstance(data.get("elements"), list):
                raise ValueError(data.get("remark", "Invalid OSM response"))
            output.with_suffix(".tmp").write_bytes(raw)
            output.with_suffix(".tmp").replace(output)
            return data
        except Exception as error:
            failures.append(str(error))
    raise RuntimeError("OSM snapshot unavailable: " + "; ".join(failures))


def build_package(bbox, package_id=None, name=None):
    bbox = checked_bbox(bbox)
    key = hashlib.sha256(json.dumps(bbox).encode()).hexdigest()[:16]
    package_id = package_id or f"indonesia-{key}"
    folder = CACHE / package_id
    folder.mkdir(parents=True, exist_ok=True)
    result = folder / "package.json"
    if result.exists():
        pack = json.loads(result.read_text(encoding="utf-8"))
        if pack["aoiWgs84"] != bbox:
            raise ValueError("Package ID already has a different operation area")
        return result
    with ThreadPoolExecutor(max_workers=3) as pool:
        dem_job = pool.submit(raster_window, "dem", bbox, folder)
        cover_job = pool.submit(raster_window, "landCover", bbox, folder)
        osm_job = pool.submit(osm_snapshot, bbox, folder)
        dem, dem_urls = dem_job.result()
        cover, cover_urls = cover_job.result()
        osm = osm_job.result()
    now = datetime.now(timezone.utc).isoformat()
    metadata = {"id": package_id, "datasetVersion": f"copdem-worldcover2021-osm-{now[:10]}", "verticalDatum": "EGM2008", "synthetic": False,
                "dem": {"name": "Copernicus DEM GLO-30 Public", "observedAt": "TanDEM-X 2011–2015; public GLO-30 product", "downloadedAt": now, "license": "Copernicus DEM public licence", "urls": dem_urls},
                "landCover": {"name": "ESA WorldCover 2021 v200", "observedAt": "2021", "downloadedAt": now, "license": "CC BY 4.0; © ESA WorldCover project 2021", "urls": cover_urls}}
    pack = prepare(dem, cover, bbox, metadata, resolution=35, margin=0)
    pack.pop("cellCorners", None)  # no prototype cell inspector in the mobile app
    pack["name"] = name or "Wilayah operasi Indonesia"
    nodes = {item["id"]: [item["lat"], item["lon"]] for item in osm["elements"] if item["type"] == "node"}
    paths, obstacles = [], []
    allowed = {"path", "footway", "track", "steps", "pedestrian", "service", "unclassified", "residential", "tertiary", "secondary"}
    for way in osm["elements"]:
        if way["type"] != "way":
            continue
        refs, tags = way.get("nodes", []), way.get("tags", {})
        if not all(ref in nodes for ref in refs):
            continue  # never connect across missing OSM nodes
        points = [nodes[ref] for ref in refs]
        if tags.get("building") not in (None, "no") and len(refs) >= 4 and refs[0] == refs[-1]:
            obstacles.append({"id": f"osm-building-{way['id']}", "kind": "building", "polygon": points[:-1], "source": "OpenStreetMap"})
        if tags.get("highway") in allowed and tags.get("foot", tags.get("access", "yes")) not in {"no", "private", "agricultural", "forestry", "use_sidepath"} and len(points) >= 2:
            paths.append({"id": str(way["id"]), "name": tags.get("name", ""), "highway": tags["highway"], "points": points, "nodeIds": refs, "tags": tags})
    pack.update(mappedPaths=paths, obstacles=obstacles, trailPreferenceSecondsPerM=3, obstacleCoverage="Closed OSM building ways only; relations/unmapped hazards not included")
    pack["sources"].append({"name": "OpenStreetMap", "observedAt": osm.get("osm3s", {}).get("timestamp_osm_base", now), "downloadedAt": now, "license": "ODbL; © OpenStreetMap contributors", "sha256": sha256(folder / "osm.json")})
    result.with_suffix(".tmp").write_text(json.dumps(pack, separators=(",", ":"), allow_nan=False), encoding="utf-8")
    result.with_suffix(".tmp").replace(result)
    print(json.dumps({"file": str(result), "name": pack["name"], "grid": pack["grid"], "paths": len(paths), "statistics": pack["statistics"]}), flush=True)
    return result


class Handler(BaseHTTPRequestHandler):
    def send(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/catalog":
            self.send(200, json.dumps({"regions": PRESETS, "maxSpanDegrees": .09}).encode())
            return
        if url.path != "/package":
            self.send(404, b'{"error":"Not found"}')
            return
        try:
            bbox = checked_bbox([float(v) for v in parse_qs(url.query).get("bbox", [""])[0].split(",")])
            if not LOCK.acquire(blocking=False):
                self.send(503, b'{"error":"Package builder busy; retry after current download"}')
                return
            try:
                preset = next((p for p in PRESETS if p["bbox"] == bbox), None)
                result = build_package(bbox, preset["id"] if preset else None, preset["name"] if preset else None)
            finally:
                LOCK.release()
            self.send(200, result.read_bytes())
        except ValueError as error:
            self.send(400, json.dumps({"error": str(error)}).encode())
        except Exception as error:
            self.send(502, json.dumps({"error": str(error)}).encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jabar", action="store_true")
    parser.add_argument("--bbox", nargs=4, type=float)
    parser.add_argument("--serve", action="store_true")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", default=8083, type=int)
    args = parser.parse_args()
    if args.jabar:
        for preset in PRESETS:
            build_package(preset["bbox"], preset["id"], preset["name"])
    if args.bbox:
        print(build_package(args.bbox))
    if args.serve:
        print(f"Package service http://{args.bind}:{args.port}; routing remains on device", flush=True)
        ThreadingHTTPServer((args.bind, args.port), Handler).serve_forever()
    if not (args.jabar or args.bbox or args.serve):
        parser.error("Choose --jabar, --bbox or --serve")


if __name__ == "__main__":
    main()
