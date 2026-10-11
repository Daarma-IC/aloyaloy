# Benchmark AI super-resolution untuk gambar LoRa NusaMesh

Membandingkan model AI pembesar gambar (×4) untuk gambar yang dikirim lewat LoRa: foto diperkecil ke
**128 px** dan dikompres **WebP ≤ 1.200 byte** persis seperti `LoraImageCodec` di app, lalu tiap metode
memperbesar ke **512 px** dan dibandingkan dengan foto aslinya.

## Cara pakai

```sh
cd apps/nusamesh/tools/sr-benchmark
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
./download.sh                 # semua model (±85 MB) + BSD100 & Urban100 (±60 MB)
python benchmark.py           # ±15–25 menit di laptop; --limit 10 untuk coba cepat
```

Foto lapangan sendiri (paling relevan untuk TA): taruh di satu folder lalu
`python benchmark.py --datasets data/foto_lapangan`.

Opsi lain: `--no-lpips` (tanpa torch, lebih cepat), `--no-webp` (uji SR tanpa kompresi LoRa),
`--samples 5` (jumlah panel perbandingan visual), `--threads 8`.

## Hasil (folder `results/`)

| File | Isi |
|---|---|
| `summary.md` / `summary.csv` | Rata-rata per metode per dataset: PSNR, SSIM, LPIPS, waktu, ukuran model |
| `per_image.csv` | Nilai tiap foto — untuk uji statistik / boxplot |
| `sample_*.jpg` | Panel visual: asli, input LoRa, dan semua metode berdampingan |

- **PSNR / SSIM** (kanal Y, ↑ lebih baik): kemiripan piksel dengan foto asli.
- **LPIPS** (↓ lebih baik): kemiripan menurut persepsi manusia (jaringan AlexNet).
- **ms/gambar**: CPU laptop ini — **bukan** waktu di HP. Ukur ulang di HP setelah model dipasang.

## Model yang diuji

| Model | Sumber | Lisensi |
|---|---|---|
| XLSR, QuickSRNet Small/Medium/Large, SESR-M5, Real-ESRGAN General x4v3 (float & int8) | [Qualcomm AI Hub](https://huggingface.co/qualcomm) — TFLite 128×128 → 512×512 | BSD-3 |
| ESRGAN (pembanding kelas berat, 67 MB) | Qualcomm AI Hub | Apache-2.0 |
| FSRCNN, ESPCN, LapSRN ×4 | Model pralatih OpenCV `dnn_superres` (GitHub Saafke / fannymonori) | lihat repo masing-masing |
| Bicubic, Lanczos | Pillow | baseline non-AI |

Set foto: **BSD100** (alam/luar ruang) dan **Urban100** (bangunan) — set benchmark SR standar,
penggunaan riset. Semua TFLite berjalan di Android mana pun (CPU/GPU); NPU khusus chip Qualcomm.

## Catatan untuk analisis TA

- Kompresi 1,2 KB menghapus detail halus; model **tidak bisa mengembalikan informasi yang hilang**,
  hanya menebak. LPIPS bagus tapi PSNR turun = model "mempercantik" dengan detail karangan.
- Untuk penggunaan SAR, tampilkan label "diperjelas AI" dan sediakan gambar asli.
- `models/`, `data/`, `results/` tidak disimpan di git (unduh ulang dengan `download.sh`).
