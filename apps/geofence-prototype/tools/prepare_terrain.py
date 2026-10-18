"""Prepare a small metric terrain grid from local DEM and WorldCover GeoTIFFs."""
import argparse
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import rasterio
from rasterio.transform import from_origin
from rasterio.warp import Resampling, calculate_default_transform, reproject, transform, transform_bounds


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prepare(dem_path, cover_path, aoi, metadata, resolution=None, margin=300):
    west, south, east, north = aoi
    if not (-180 <= west < east <= 180 and -80 <= south < north <= 84):
        raise ValueError("AOI must be west south east north within UTM latitude coverage")
    if east - west > 0.2 or north - south > 0.2:
        raise ValueError("Prototype AOI maximum width/height is 0.2 degrees")
    if not math.isfinite(margin) or margin < 0 or margin > 2000:
        raise ValueError("Margin must be between 0 and 2000 metres")
    for name in ("id", "datasetVersion", "verticalDatum", "dem", "landCover"):
        if not metadata.get(name):
            raise ValueError(f"Missing metadata: {name}")
    for role in ("dem", "landCover"):
        for field in ("name", "observedAt", "downloadedAt", "license"):
            if not metadata[role].get(field):
                raise ValueError(f"Missing metadata: {role}.{field}")
    if metadata.get("elevationUnit", "m") != "m":
        raise ValueError("DEM must be converted to metres before import")
    zone = min(60, int(((west + east) / 2 + 180) / 6) + 1)
    crs = f"EPSG:{(32600 if (south + north) / 2 >= 0 else 32700) + zone}"
    with rasterio.open(dem_path) as dem, rasterio.open(cover_path) as cover:
        if not dem.crs or not cover.crs:
            raise ValueError("Both GeoTIFFs require a CRS")
        native = []
        for source in (dem, cover):
            tx, _, _ = calculate_default_transform(source.crs, crs, source.width, source.height, *source.bounds)
            native.append(max(abs(tx.a), abs(tx.e)))
        minimum = max(native)
        resolution = math.ceil(minimum) if resolution is None else resolution
        if not math.isfinite(resolution) or resolution < max(1, minimum * 0.99):
            raise ValueError(f"Resolution must be >= native coarsest grid ({minimum:.2f} m); upsampling is rejected")
        left, bottom, right, top = transform_bounds("EPSG:4326", crs, west, south, east, north)
        left = math.floor((left - margin) / resolution) * resolution
        bottom = math.floor((bottom - margin) / resolution) * resolution
        right = math.ceil((right + margin) / resolution) * resolution
        top = math.ceil((top + margin) / resolution) * resolution
        cols, rows = round((right - left) / resolution), round((top - bottom) / resolution)
        if rows < 2 or cols < 2 or rows * cols > 100000:
            raise ValueError("Grid must contain 2+ rows/columns and at most 100,000 cells; crop AOI or increase resolution")
        grid_transform = from_origin(left, top, resolution, resolution)
        elevation = np.full((rows, cols), np.nan, dtype=np.float64)
        classes = np.zeros((rows, cols), dtype=np.uint16)
        reproject(rasterio.band(dem, 1), elevation, src_transform=dem.transform, src_crs=dem.crs, src_nodata=dem.nodata, dst_transform=grid_transform, dst_crs=crs, dst_nodata=np.nan, resampling=Resampling.bilinear)
        reproject(rasterio.band(cover, 1), classes, src_transform=cover.transform, src_crs=cover.crs, src_nodata=cover.nodata, dst_transform=grid_transform, dst_crs=crs, dst_nodata=0, resampling=Resampling.nearest)
        rr, cc = np.indices((rows, cols))
        xs = left + (cc.ravel() + 0.5) * resolution
        ys = top - (rr.ravel() + 0.5) * resolution
        lons, lats = transform(crs, "EPSG:4326", xs.tolist(), ys.tolist())
        coordinates = list(map(list, zip(lats, lons)))
        # Vertices are kept so the map represents the metric grid's true cell footprints.
        vr, vc = np.indices((rows + 1, cols + 1))
        vlons, vlats = transform(crs, "EPSG:4326", (left + vc.ravel() * resolution).tolist(), (top - vr.ravel() * resolution).tolist())
        vertices = list(map(list, zip(vlats, vlons)))
        corners = []
        for r in range(rows):
            for c in range(cols):
                a = r * (cols + 1) + c
                corners.append([vertices[a], vertices[a + 1], vertices[a + cols + 2], vertices[a + cols + 1]])
        sources = []
        for role, source, path, native_resolution in zip(("dem", "landCover"), (dem, cover), (dem_path, cover_path), native):
            sources.append({**metadata[role], "role": role, "filename": Path(path).name, "sha256": sha256(path), "sourceCRS": str(source.crs), "nativeResolutionMApprox": native_resolution, "sourceTransform": list(source.transform)[:6], "sourceNoData": str(source.nodata)})
        return {
            "schemaVersion": 1, "id": metadata["id"], "datasetVersion": metadata["datasetVersion"], "synthetic": bool(metadata.get("synthetic", False)),
            "crs": crs, "verticalDatum": metadata["verticalDatum"], "grid": {"rows": rows, "cols": cols, "resolutionM": resolution, "transform": list(grid_transform)[:6]},
            "footprint": [vertices[0], vertices[cols], vertices[-1], vertices[rows * (cols + 1)]], "aoiWgs84": aoi,
            "coordinates": coordinates, "cellCorners": corners, "elevationM": [round(float(z), 3) if np.isfinite(z) else None for z in elevation.ravel()],
            "landCover": [int(c) if c != 0 else None for c in classes.ravel()], "blocked": [False] * (rows * cols), "sources": sources,
            "preparedAt": datetime.now(timezone.utc).isoformat(), "preprocessing": {"demResampling": "bilinear", "landCoverResampling": "nearest", "marginM": margin, "noDataPolicy": "reject", "verticalTransform": "none; input datum retained"},
            "statistics": {"validElevationCells": int(np.isfinite(elevation).sum()), "noDataCells": int((~np.isfinite(elevation) | (classes == 0)).sum()), "elevationMinM": float(np.nanmin(elevation)) if np.isfinite(elevation).any() else None, "elevationMaxM": float(np.nanmax(elevation)) if np.isfinite(elevation).any() else None},
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dem", required=True)
    parser.add_argument("--land-cover", required=True)
    parser.add_argument("--aoi", nargs=4, type=float, required=True, metavar=("WEST", "SOUTH", "EAST", "NORTH"))
    parser.add_argument("--metadata", required=True)
    parser.add_argument("--resolution", type=float)
    parser.add_argument("--margin", type=float, default=300)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    metadata = json.loads(Path(args.metadata).read_text(encoding="utf-8-sig"))
    pack = prepare(args.dem, args.land_cover, args.aoi, metadata, args.resolution, args.margin)
    output = Path(args.out)
    if output.exists():
        parser.error("Output exists; choose a new filename to retain package versions")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(pack, allow_nan=False), encoding="utf-8")
    print(json.dumps({"file": str(output), "bytes": output.stat().st_size, "sha256": sha256(output), "grid": pack["grid"], "statistics": pack["statistics"]}, indent=2))


if __name__ == "__main__":
    main()
