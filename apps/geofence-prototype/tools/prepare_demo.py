"""Download real Kaliurang rasters as COG windows and build the bundled demo."""
import json
from pathlib import Path
from datetime import datetime, timezone
import rasterio
import xml.etree.ElementTree as ET
import numpy as np
from rasterio.features import rasterize
from rasterio import Affine
from rasterio.windows import from_bounds
from rasterio.warp import transform_bounds, transform
from prepare_terrain import prepare, sha256

ROOT = Path(__file__).resolve().parents[1]
AOI = [110.425, -7.601, 110.452, -7.569]
SOURCES = {
    "dem": "https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_S08_00_E110_00_DEM/Copernicus_DSM_COG_10_S08_00_E110_00_DEM.tif",
    "landCover": "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_S09E108_Map.tif",
}


def main():
    folder = ROOT / "data" / "kaliurang"
    folder.mkdir(parents=True, exist_ok=True)
    for role, url in SOURCES.items():
        output = folder / f"{role}.tif"
        if output.exists():
            print(f"Reuse {role}: {output}", flush=True)
            continue
        print(f"Read real {role} COG window", flush=True)
        with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif", GDAL_HTTP_TIMEOUT="40", GDAL_HTTP_MAX_RETRY="2"):
            source = folder / f"{role}-source.tif"
            if not source.exists() and (folder / f"{role}-window-source.tif").exists():
                source = folder / f"{role}-window-source.tif"
            with rasterio.open(source if source.exists() else url) as src:
                bbox = transform_bounds("EPSG:4326", src.crs, AOI[0] - .003, AOI[1] - .003, AOI[2] + .003, AOI[3] + .003)
                window = from_bounds(*bbox, src.transform).round_offsets().round_lengths()
                data = src.read(1, window=window)
                profile = src.profile.copy()
                profile.update(width=data.shape[1], height=data.shape[0], transform=src.window_transform(window), driver="GTiff")
                with rasterio.open(output, "w", **profile) as dst:
                    dst.write(data, 1)
        print(f"Saved {role}: {output.stat().st_size} bytes", flush=True)
    now = datetime.now(timezone.utc).isoformat()
    metadata = {
        "id": "kaliurang-real-terrain", "datasetVersion": "copdem2021-worldcover2021-osm-obstacles-v2", "verticalDatum": "EGM2008", "synthetic": False,
        "dem": {"name": "Copernicus DEM GLO-30 Public (2021 release)", "observedAt": "TanDEM-X acquisition 2011-2015; product release 2021", "downloadedAt": now, "license": "Copernicus DEM GLO-30 public licence; https://registry.opendata.aws/copernicus-dem/", "url": SOURCES["dem"]},
        "landCover": {"name": "ESA WorldCover 2021 v200", "observedAt": "2021", "downloadedAt": now, "license": "CC BY 4.0; © ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium", "url": SOURCES["landCover"]},
    }
    pack = prepare(folder / "dem.tif", folder / "landCover.tif", AOI, metadata, resolution=35, margin=0)
    xml = ET.parse(folder / "osm.xml").getroot()
    nodes = {int(node.attrib["id"]): [float(node.attrib["lat"]), float(node.attrib["lon"])] for node in xml.findall("node")}
    paths = []
    obstacles = []
    for way in xml.findall("way"):
        tags = {t.attrib["k"]: t.attrib["v"] for t in way.findall("tag")}
        refs = [int(n.attrib["ref"]) for n in way.findall("nd")]
        # Closed OSM building footprints are physical obstacles, separate from
        # the WorldCover built-up class (which also includes walkable streets).
        if tags.get("building") not in {None, "no"} and len(refs) >= 4 and refs[0] == refs[-1] and all(ref in nodes for ref in refs):
            obstacles.append({"id": f"osm-building-{way.attrib['id']}", "kind": "building", "polygon": [nodes[ref] for ref in refs[:-1]], "source": "OpenStreetMap", "osmWayId": int(way.attrib["id"])})
        if tags.get("highway") not in {"path", "footway", "track", "steps", "pedestrian", "service", "residential"}:
            continue
        access = tags.get("foot", tags.get("access", "yes"))
        if access in {"no", "private", "agricultural", "forestry"} or tags.get("highway") == "construction":
            continue
        points = [nodes[int(n.attrib["ref"])] for n in way.findall("nd") if int(n.attrib["ref"]) in nodes]
        if len(points) >= 2:
            paths.append({"id": way.attrib["id"], "name": tags.get("name", ""), "highway": tags["highway"], "points": points, "nodeIds": [int(n.attrib["ref"]) for n in way.findall("nd") if int(n.attrib["ref"]) in nodes], "tags": tags})
    shapes = []
    for path in paths:
        points = path["points"]
        xs, ys = transform("EPSG:4326", pack["crs"], [p[1] for p in points], [p[0] for p in points])
        shapes.append(({"type": "LineString", "coordinates": list(zip(xs, ys))}, 1))
    grid = pack["grid"]
    mask = rasterize(shapes, out_shape=(grid["rows"], grid["cols"]), transform=Affine(*grid["transform"]), all_touched=True, dtype="uint8")
    pack["trailMask"] = [bool(x) for x in mask.ravel()]
    pack["mappedPaths"] = paths
    pack["obstacles"] = obstacles
    pack["obstacleCoverage"] = "Closed OSM building ways in snapshot; multipolygon relations and unmapped obstacles not included"
    pack["trailPreferenceSecondsPerM"] = 3
    pack["sources"].append({"name": "OpenStreetMap foot-accessible paths, Kaliurang", "observedAt": "OSM snapshot 2026-10-14", "downloadedAt": now, "license": "ODbL; © OpenStreetMap contributors", "url": "https://api.openstreetmap.org/api/0.6/map?bbox=110.425,-7.601,110.452,-7.569", "sha256": sha256(folder / "osm.xml")})
    # These are simulated people, located on cells near mapped trails in the real dataset.
    def endpoint(seed):
        options = [i for i, p in enumerate(pack["coordinates"]) if pack["trailMask"][i] and pack["elevationM"][i] is not None and pack["landCover"][i] not in {None, 80}]
        i = min(options, key=lambda i: (pack["coordinates"][i][0] - seed[0]) ** 2 + (pack["coordinates"][i][1] - seed[1]) ** 2)
        return pack["coordinates"][i]
    pack["demoStart"] = endpoint([-7.5955, 110.4308])
    pack["demoTarget"] = endpoint([-7.5823, 110.4338])
    output = ROOT / "public" / "demo" / "kaliurang-terrain.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(pack, allow_nan=False), encoding="utf-8")
    (output.parent / "sources.json").write_text(json.dumps({"packageSha256": sha256(output), "sources": pack["sources"], "aoi": AOI, "statistics": pack["statistics"]}, indent=2), encoding="utf-8")
    print(json.dumps({"file": str(output), "bytes": output.stat().st_size, "grid": pack["grid"], "statistics": pack["statistics"]}), flush=True)


if __name__ == "__main__":
    main()
