"""Download a public COG in checked byte ranges, retaining partial downloads."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import subprocess
import json


def download(url, output, length, selected=None):
    output = Path(output)
    folder = output.parent / (output.name + ".parts")
    folder.mkdir(parents=True, exist_ok=True)
    block = 262144
    ranges = list(range(0, length, block)) if selected is None else sorted({start for offset, count in selected for start in range(offset // block * block, offset + count, block)})
    def part(start):
        end = min(length - 1, start + block - 1)
        target = folder / f"{start:012d}"
        if target.exists() and target.stat().st_size == end - start + 1:
            return target
        for attempt in range(4):
            result = subprocess.run(["curl.exe", "-sS", "-L", "--fail", "--connect-timeout", "10", "--max-time", "35", "--range", f"{start}-{end}", url, "-o", str(target)], capture_output=True)
            if result.returncode == 0 and target.stat().st_size == end - start + 1:
                return target
        raise RuntimeError(f"Range failed: {start}-{end}: {result.stderr.decode(errors='replace')}")
    with ThreadPoolExecutor(max_workers=6) as pool:
        parts = []
        for i, path in enumerate(pool.map(part, ranges)):
            parts.append(path)
            if i % 20 == 0:
                print(f"{output.name}: {i + 1}/{len(ranges)} chunks", flush=True)
    with output.open("wb" if selected is None else "r+b") as dst:
        for start, path in zip(ranges, parts):
            dst.seek(start)
            dst.write(path.read_bytes())
    if output.stat().st_size != length:
        raise RuntimeError("Raster length mismatch")
    print(f"Saved {'full raster' if selected is None else 'selected COG blocks'} {output}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url")
    parser.add_argument("output")
    parser.add_argument("length", type=int)
    parser.add_argument("--ranges")
    args = parser.parse_args()
    download(args.url, args.output, args.length, json.loads(Path(args.ranges).read_text()) if args.ranges else None)
