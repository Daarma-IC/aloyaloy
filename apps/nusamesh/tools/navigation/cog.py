"""Read selected real COG blocks using checked HTTP ranges (avoids truncated GDAL curl reads)."""
from concurrent.futures import ThreadPoolExecutor
import math
from pathlib import Path
import re
import shutil
import subprocess
import rasterio
from rasterio.windows import from_bounds
import tifffile


def selected_cog(url, path, bbox):
    path = Path(path)
    curl = shutil.which("curl.exe") or shutil.which("curl")
    if not curl:
        raise RuntimeError("curl is required for verified COG range downloads")
    head = subprocess.run([curl, "-sSI", "--fail", "--max-time", "30", url], capture_output=True, check=True).stdout.decode()
    lengths = re.findall(r"(?im)^content-length:\s*(\d+)", head)
    if not lengths:
        raise RuntimeError("Raster source has no content length")
    length = int(lengths[-1])
    folder = path.parent / (path.name + ".parts")
    folder.mkdir(parents=True, exist_ok=True)
    block = 262144

    def chunk(start):
        end = min(length - 1, start + block - 1)
        target = folder / str(start)
        if target.exists() and target.stat().st_size == end-start+1:
            return start, target
        headers = folder / (str(start) + ".headers")
        for attempt in range(4):
            result = subprocess.run([curl, "-sS", "-L", "--fail", "--connect-timeout", "10", "--max-time", "45", "--range", f"{start}-{end}", "--dump-header", str(headers), url, "-o", str(target)], capture_output=True)
            ranges = re.findall(r"(?im)^content-range:\s*bytes\s+(\d+)-(\d+)/(\d+)", headers.read_text() if headers.exists() else "")
            if result.returncode == 0 and target.exists() and target.stat().st_size == end-start+1 and ranges and tuple(map(int, ranges[-1])) == (start,end,length):
                return start, target
        raise RuntimeError(f"Incomplete/incorrect raster range {start}-{end}: {result.stderr.decode(errors='replace')}")

    if not path.exists() or path.stat().st_size != length:
        with path.open("wb") as dst:
            dst.truncate(length)
    def fetch(starts):
        with ThreadPoolExecutor(max_workers=6) as pool:
            for start, target in pool.map(chunk, sorted(set(starts))):
                with path.open("r+b") as dst:
                    dst.seek(start)
                    dst.write(target.read_bytes())
    fetch([0])
    with tifffile.TiffFile(path) as tif:
        page = tif.pages[0]
        offsets, counts = page.dataoffsets, page.databytecounts
        tile_width, tile_height = page.tilewidth, page.tilelength
        width = page.imagewidth
    with rasterio.open(path) as src:
        clipped = [max(src.bounds.left,bbox[0]),max(src.bounds.bottom,bbox[1]),min(src.bounds.right,bbox[2]),min(src.bounds.top,bbox[3])]
        if clipped[0] >= clipped[2] or clipped[1] >= clipped[3]:
            raise ValueError("Raster tile does not cover requested bounds")
        win = from_bounds(*clipped, src.transform)
    tiles_per_row = math.ceil(width / tile_width)
    ids = [row*tiles_per_row+col for row in range(math.floor(win.row_off/tile_height),math.ceil((win.row_off+win.height)/tile_height)) for col in range(math.floor(win.col_off/tile_width),math.ceil((win.col_off+win.width)/tile_width))]
    starts = [start for index in ids for start in range(offsets[index]//block*block, offsets[index]+counts[index], block)]
    fetch(starts)
    return path
