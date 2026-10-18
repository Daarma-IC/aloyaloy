import unittest
from packages import checked_bbox, source_urls


class PackageTests(unittest.TestCase):
    def test_jabar_cross_tile_and_southern_latitude_names(self):
        urls = source_urls("dem", [106.99, -6.76, 107.01, -6.71])
        self.assertEqual(2, len(urls))
        self.assertTrue(all("S07_00" in url for url in urls))
        self.assertIn("E106_00", urls[0])
        self.assertIn("E107_00", urls[1])
        self.assertIn("S09E105", source_urls("landCover", [107.5,-6.8,107.6,-6.7])[0])

    def test_bounds_reject_oversize_and_nonfinite(self):
        self.assertEqual([107.5,-6.8,107.55,-6.75], checked_bbox([107.5,-6.8,107.55,-6.75]))
        for box in ([107,-7,108,-6],[0,0,1,1],[107,-7,float('nan'),-6.99],[107,-7,107,-6.99]):
            with self.assertRaises(ValueError):
                checked_bbox(box)


if __name__ == "__main__":
    unittest.main()
