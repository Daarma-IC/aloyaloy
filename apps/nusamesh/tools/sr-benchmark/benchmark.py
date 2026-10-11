"""
Benchmark super-resolution ×4 untuk gambar LoRa NusaMesh.

Meniru LoraImageCodec di app: foto → 128 px → WebP ≤1200 byte (cari kualitas 5..90) → tiap metode
memperbesar ke 512 px → dibandingkan dengan foto asli 512 px. Jadi yang diukur adalah kemampuan
memulihkan gambar yang BENAR-BENAR dikirim lewat LoRa, bukan gambar kecil yang bersih.

Metrik: PSNR & SSIM (kanal Y, makin tinggi makin mirip), LPIPS (makin rendah makin mirip secara
persepsi), waktu inferensi (CPU mesin ini — BUKAN waktu di HP), ukuran model.

Contoh:
  python benchmark.py                                  # BSD100 + Urban100, semua metode
  python benchmark.py --datasets data/foto_lapangan    # foto sendiri (folder berisi jpg/png)
  python benchmark.py --limit 20 --no-lpips            # cepat
  python benchmark.py --no-webp                        # tanpa kompresi (uji SR murni)
"""
import argparse, csv, glob, io, os, time
import numpy as np
from PIL import Image

HR, LR = 512, 128
MAX_BYTES, QMIN, QMAX = 1200, 5, 90


def lora_webp(img128: Image.Image):
    """Sama dengan LoraImageCodec.encode: kualitas tertinggi yang muat ≤1200 byte."""
    lo, hi, best = QMIN, QMAX, None
    while lo <= hi:
        mid = (lo + hi) // 2
        buf = io.BytesIO()
        img128.save(buf, "WEBP", quality=mid)
        if buf.tell() <= MAX_BYTES:
            best, lo = buf.getvalue(), mid + 1
        else:
            hi = mid - 1
    if best is None:
        buf = io.BytesIO(); img128.save(buf, "WEBP", quality=QMIN); best = buf.getvalue()
    return Image.open(io.BytesIO(best)).convert("RGB"), len(best)


def prepare(path, webp=True):
    img = Image.open(path).convert("RGB")
    s = min(img.size)
    left, top = (img.width - s) // 2, (img.height - s) // 2
    hr = img.crop((left, top, left + s, top + s)).resize((HR, HR), Image.LANCZOS)
    lr = hr.resize((LR, LR), Image.BICUBIC)
    nbytes = None
    if webp:
        lr, nbytes = lora_webp(lr)
    return np.asarray(hr), np.asarray(lr), nbytes


# ---------------------------------------------------------------- metode
class Pil:
    def __init__(self, name, filt): self.name, self.filt, self.size_mb = name, filt, 0.0
    def run(self, lr): return np.asarray(Image.fromarray(lr).resize((HR, HR), self.filt))


class OpenCvSr:
    def __init__(self, path, algo):
        import cv2
        self.cv2 = cv2
        self.name = f"{algo}_x4 (OpenCV)"
        self.size_mb = os.path.getsize(path) / 1e6
        self.sr = cv2.dnn_superres.DnnSuperResImpl_create()
        self.sr.readModel(path)
        self.sr.setModel(algo.lower(), 4)
    def run(self, lr):
        bgr = self.sr.upsample(self.cv2.cvtColor(lr, self.cv2.COLOR_RGB2BGR))
        return self.cv2.cvtColor(bgr, self.cv2.COLOR_BGR2RGB)


class Tflite:
    def __init__(self, path, name, threads):
        from ai_edge_litert.interpreter import Interpreter
        self.name = name
        self.size_mb = os.path.getsize(path) / 1e6
        self.it = Interpreter(model_path=path, num_threads=threads)
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]
        self.out = self.it.get_output_details()[0]
    def run(self, lr):
        x = lr.astype(np.float32) / 255.0                     # model Qualcomm: RGB 0..1
        if self.inp["dtype"] == np.uint8:
            scale, zero = self.inp["quantization"]
            x = np.clip(np.round(x / scale + zero), 0, 255).astype(np.uint8)
        self.it.set_tensor(self.inp["index"], x[None])
        self.it.invoke()
        y = self.it.get_tensor(self.out["index"])[0]
        if self.out["dtype"] == np.uint8:
            scale, zero = self.out["quantization"]
            y = (y.astype(np.float32) - zero) * scale
        return np.clip(y * 255.0 + 0.5, 0, 255).astype(np.uint8)


def load_methods(models_dir, threads):
    methods = [Pil("Bicubic (baseline)", Image.BICUBIC), Pil("Lanczos", Image.LANCZOS)]
    for algo in ("FSRCNN", "ESPCN", "LapSRN"):
        p = os.path.join(models_dir, f"{algo}_x4.pb")
        if os.path.exists(p):
            methods.append(OpenCvSr(p, algo))
    for p in sorted(glob.glob(os.path.join(models_dir, "**", "*.tflite"), recursive=True)):
        folder = os.path.relpath(p, models_dir).split(os.sep)[0]          # mis. xlsr-w8a8
        methods.append(Tflite(p, folder.replace("-tflite", ""), threads))
    return methods


# ---------------------------------------------------------------- metrik
def y_channel(rgb):
    rgb = rgb.astype(np.float64)
    return 16 + (65.481 * rgb[..., 0] + 128.553 * rgb[..., 1] + 24.966 * rgb[..., 2]) / 255.0


def psnr_ssim(ref, out, border=4):
    from skimage.metrics import peak_signal_noise_ratio, structural_similarity
    a, b = y_channel(ref)[border:-border, border:-border], y_channel(out)[border:-border, border:-border]
    return peak_signal_noise_ratio(a, b, data_range=255), structural_similarity(a, b, data_range=255)


class Lpips:
    def __init__(self):
        import lpips, torch
        self.torch = torch
        self.fn = lpips.LPIPS(net="alex", verbose=False)
    def __call__(self, ref, out):
        t = lambda a: self.torch.from_numpy(a).permute(2, 0, 1)[None].float() / 127.5 - 1
        with self.torch.no_grad():
            return float(self.fn(t(ref), t(out)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--datasets", nargs="+", default=["data/BSD100", "data/Urban100"])
    ap.add_argument("--models", default="models")
    ap.add_argument("--limit", type=int, default=0, help="maks foto per dataset (0 = semua)")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--no-lpips", action="store_true")
    ap.add_argument("--no-webp", action="store_true", help="tanpa kompresi WebP LoRa")
    ap.add_argument("--samples", type=int, default=3, help="jumlah gambar perbandingan visual per dataset")
    ap.add_argument("--out", default="results")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    methods = load_methods(args.models, args.threads)
    lp = None
    if not args.no_lpips:
        try:
            lp = Lpips()
        except Exception as e:
            print(f"LPIPS dilewati ({e}). Pasang torch & lpips untuk metrik persepsi.")
    print("Metode:", ", ".join(m.name for m in methods))

    rows = []
    for ds in args.datasets:
        files = sorted(f for f in glob.glob(os.path.join(ds, "**", "*"), recursive=True)
                       if f.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".bmp")))
        if args.limit:
            files = files[:args.limit]
        name = os.path.basename(os.path.normpath(ds))
        print(f"\n{name}: {len(files)} foto")
        for n, f in enumerate(files):
            hr, lr, nbytes = prepare(f, webp=not args.no_webp)
            outs = []
            for m in methods:
                t0 = time.perf_counter()
                out = m.run(lr)
                ms = (time.perf_counter() - t0) * 1000
                p, s = psnr_ssim(hr, out)
                l = lp(hr, out) if lp else None
                rows.append({"dataset": name, "image": os.path.basename(f), "method": m.name, "psnr": p, "ssim": s,
                             "lpips": l, "ms": ms, "model_mb": m.size_mb, "lora_bytes": nbytes})
                outs.append((m.name, out))
            if n < args.samples:   # panel visual: asli | input LoRa | tiap metode
                tiles = [("Asli 512 px", hr), (f"Input LoRa {nbytes or ''} B", np.asarray(Image.fromarray(lr).resize((HR, HR), Image.NEAREST)))] + outs
                grid = Image.new("RGB", (HR * 4, HR * ((len(tiles) + 3) // 4)), "white")
                from PIL import ImageDraw
                for i, (title, im) in enumerate(tiles):
                    tile = Image.fromarray(im).copy()
                    ImageDraw.Draw(tile).rectangle([0, 0, HR, 22], fill="white")
                    ImageDraw.Draw(tile).text((6, 5), title, fill="black")
                    grid.paste(tile, ((i % 4) * HR, (i // 4) * HR))
                grid.save(os.path.join(args.out, f"sample_{name}_{n + 1}.jpg"), quality=90)
            print(f"  {n + 1}/{len(files)}", end="\r", flush=True)

    with open(os.path.join(args.out, "per_image.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=rows[0].keys()); w.writeheader(); w.writerows(rows)

    summary = []
    for ds in sorted({r["dataset"] for r in rows}):
        for m in methods:
            rs = [r for r in rows if r["dataset"] == ds and r["method"] == m.name]
            mean = lambda k: (float(np.mean([r[k] for r in rs])) if rs and rs[0][k] is not None else None)
            summary.append({"dataset": ds, "method": m.name, "psnr_db": mean("psnr"), "ssim": mean("ssim"),
                            "lpips": mean("lpips"), "ms_per_image_cpu": float(np.median([r["ms"] for r in rs])),
                            "model_mb": m.size_mb, "images": len(rs)})
    with open(os.path.join(args.out, "summary.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=summary[0].keys()); w.writeheader(); w.writerows(summary)

    fmt = lambda v, d: "-" if v is None else f"{v:.{d}f}"
    lines = ["| Dataset | Metode | PSNR (dB) ↑ | SSIM ↑ | LPIPS ↓ | ms/gambar (CPU) | Model (MB) |", "|---|---|---|---|---|---|---|"]
    for s in sorted(summary, key=lambda s: (s["dataset"], -(s["psnr_db"] or 0))):
        lines.append(f"| {s['dataset']} | {s['method']} | {fmt(s['psnr_db'], 2)} | {fmt(s['ssim'], 4)} | {fmt(s['lpips'], 4)} | "
                     f"{fmt(s['ms_per_image_cpu'], 1)} | {fmt(s['model_mb'], 2)} |")
    table = "\n".join(lines)
    open(os.path.join(args.out, "summary.md"), "w").write(table + "\n")
    print("\n\n" + table + f"\n\nHasil: {args.out}/summary.csv, per_image.csv, summary.md, sample_*.jpg")


if __name__ == "__main__":
    main()
