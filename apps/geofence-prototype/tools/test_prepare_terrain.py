import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import numpy as np
import rasterio
from rasterio.transform import from_origin
from rasterio.warp import transform_bounds

from prepare_terrain import prepare


class PrepareTerrainTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.crs = "EPSG:32749"
        tx = from_origin(450000, 9160000, 20, 20)
        dem = np.arange(400, dtype=np.float32).reshape(20, 20) * 0.1 + 100
        dem[5:8, 5:8] = -9999
        cover = np.full((20, 20), 30, dtype=np.uint8)
        cover[:, 10:] = 10
        for filename, data, nodata in (("dem.tif", dem, -9999), ("cover.tif", cover, 0)):
            with rasterio.open(self.root / filename, "w", driver="GTiff", width=20, height=20, count=1, dtype=data.dtype, crs=self.crs, transform=tx, nodata=nodata) as ds:
                ds.write(data, 1)
        self.aoi = transform_bounds(self.crs, "EPSG:4326", 450000, 9159600, 450400, 9160000)
        source = {"name": "Synthetic test GeoTIFF", "observedAt": "fixture", "downloadedAt": "fixture", "license": "test"}
        self.metadata = {"id": "raster-test", "datasetVersion": "test-v1", "synthetic": True, "verticalDatum": "synthetic", "dem": source, "landCover": source}

    def tearDown(self):
        self.temp.cleanup()

    def test_metric_reprojection_and_browser_package_compatibility(self):
        pack = prepare(self.root / "dem.tif", self.root / "cover.tif", self.aoi, self.metadata, resolution=21, margin=0)
        self.assertEqual(pack["crs"], self.crs)
        self.assertGreater(pack["statistics"]["validElevationCells"], 300)
        self.assertIn(None, pack["elevationM"])
        self.assertEqual(set(pack["landCover"]) - {None}, {10, 30})
        self.assertEqual(len(pack["cellCorners"]), len(pack["coordinates"]))
        result = subprocess.run(["node", "--input-type=module", "-e", "import {readFileSync} from 'node:fs'; import {validatePackage} from './src/terrain/engine.js'; validatePackage(JSON.parse(readFileSync(0,'utf8'))); console.log('validated');"], input=json.dumps(pack), capture_output=True, text=True, cwd=Path(__file__).resolve().parents[1])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("validated", result.stdout)

    def test_upsampling_and_missing_provenance_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "upsampling"):
            prepare(self.root / "dem.tif", self.root / "cover.tif", self.aoi, self.metadata, resolution=5)
        with self.assertRaisesRegex(ValueError, "Missing metadata"):
            prepare(self.root / "dem.tif", self.root / "cover.tif", self.aoi, {})


if __name__ == "__main__":
    unittest.main()
