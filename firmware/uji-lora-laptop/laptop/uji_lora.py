"""Uji kirim gambar dan voice note lewat LoRa, dikendalikan dari laptop.

Pasangan dari firmware UjiLoraLaptop. Gambar dikompres persis seperti app
NusaMesh (LoraImageCodec), voice note memakai format NC2 app (Codec2 1200).
Data dipecah menjadi frame 255 byte (header 12 + isi 243, sama dengan
NusaNode), dikirim dengan batas duty cycle, lalu disusun ulang di penerima.

Perintah:
    port         daftar port serial
    uji          kirim dan terima dari satu laptop (dua board)
    kirim        hanya mengirim (laptop pengirim)
    terima       hanya menerima (laptop penerima)
    ambil-suara  salin voice note dari HP (APK debug) lewat adb

Contoh:
    python uji_lora.py port
    python uji_lora.py uji --tx COM5 --rx COM6 --gambar foto --suara suara
    python uji_lora.py uji --simulasi --gambar foto --suara-sintetis 10
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import random
import struct
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------------------
# Format frame di udara (12 byte header, sama besar dengan header NusaNode)
#   [0..1] "UL"   [2] versi   [3] jenis   [4..7] id pesan
#   [8..9] indeks frame       [10..11] jumlah frame
# ---------------------------------------------------------------------------
MAGIC = b"UL"
FRAME_VERSION = 1
HEADER_BYTES = 12
MAX_FRAME_BYTES = 255
PAYLOAD_BYTES = MAX_FRAME_BYTES - HEADER_BYTES  # 243

KIND_IMAGE = 1
KIND_VOICE = 2
KIND_NAMES = {KIND_IMAGE: "gambar", KIND_VOICE: "suara"}
KIND_EXT = {KIND_IMAGE: ".webp", KIND_VOICE: ".nc2"}

# Preset gambar app: (sisi terpanjang px, batas byte). Sama dengan
# LoraImageQuality di ChatMediaActions.kt.
IMAGE_PRESETS = {
    "cepat": (160, 2000),
    "seimbang": (256, 4000),
    "detail": (384, 8000),
}

# Voice note app: "NC2" + versi + jumlah sampel (u32) + jumlah frame (u32),
# lalu frame Codec2. Versi 2 = 1200 bps: 320 sampel (40 ms) = 6 byte/frame.
NC2_HEADER = 12
NC2_VERSION_1200 = 2
NC2_SAMPLE_RATE = 8000
NC2_SAMPLES_PER_FRAME = 320
NC2_BYTES_PER_FRAME = 6

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
APP_PACKAGE = "id.nusamesh.app"


# ---------------------------------------------------------------------------
# Data yang dikirim
# ---------------------------------------------------------------------------
@dataclass
class Item:
    kind: int
    name: str
    label: str          # preset gambar atau sumber suara
    data: bytes
    info: dict = field(default_factory=dict)


def android_downscale(src, target):
    """inSampleSize pangkat 2 lalu resize bilinear, sama dengan app."""
    from PIL import Image

    sample = 1
    while max(src.size) // (sample * 2) >= target * 2:
        sample *= 2
    img = src.reduce(sample) if sample > 1 else src
    scale = target / max(img.size)
    size = (
        max(1, int(img.width * scale + 0.5)),
        max(1, int(img.height * scale + 0.5)),
    )
    return img.resize(size, Image.BILINEAR)


def webp_bytes(img, quality):
    buf = io.BytesIO()
    img.save(buf, "WEBP", quality=quality)
    return buf.getvalue()


def compress_like_app(path, max_side, max_bytes):
    """Kualitas WebP tertinggi yang muat batas byte; sisi dikecilkan 15%
    bila kualitas 5 pun tidak muat. Mengikuti LoraImageCodec.kt."""
    from PIL import Image, ImageOps

    src = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    side = max_side
    while True:
        small = android_downscale(src, side)
        lo, hi, best, best_q = 5, 90, None, None
        while lo <= hi:
            mid = (lo + hi) // 2
            data = webp_bytes(small, mid)
            if len(data) <= max_bytes:
                best, best_q, lo = data, mid, mid + 1
            else:
                hi = mid - 1
        if best is not None or side <= 64:
            if best is None:
                best, best_q = webp_bytes(small, 5), 5
            info = {"lebar": small.width, "tinggi": small.height,
                    "kualitas_webp": best_q}
            return best, info
        side = max(64, int(side * 0.85))


def parse_presets(text):
    presets = []
    for part in text.split(","):
        part = part.strip().lower()
        if part in IMAGE_PRESETS:
            presets.append((part, *IMAGE_PRESETS[part]))
        elif ":" in part:  # contoh 512:16000
            side, size = part.split(":")
            presets.append((f"{side}px-{size}B", int(side), int(size)))
        else:
            raise SystemExit(f"preset tidak dikenal: {part}")
    return presets


def nc2_info(data):
    if len(data) < NC2_HEADER or data[:3] != b"NC2":
        return None
    samples, frames = struct.unpack(">II", data[4:12])
    return {"versi_nc2": data[3], "detik": round(samples / NC2_SAMPLE_RATE, 2),
            "frame_codec2": frames}


def synthetic_voice(seconds, seed):
    """Voice note NC2 berukuran sama dengan rekaman app. Isinya acak,
    jadi hanya untuk menguji pengiriman, bukan untuk didengar."""
    samples = int(seconds * NC2_SAMPLE_RATE)
    frames = math.ceil(samples / NC2_SAMPLES_PER_FRAME)
    rng = random.Random(seed)
    body = bytes(rng.getrandbits(8) for _ in range(frames * NC2_BYTES_PER_FRAME))
    head = b"NC2" + bytes([NC2_VERSION_1200]) + struct.pack(">II", samples, frames)
    return head + body


def build_items(args):
    items = []
    if args.gambar:
        files = sorted(p for p in Path(args.gambar).iterdir()
                       if p.suffix.lower() in IMAGE_SUFFIXES)
        if not files:
            raise SystemExit(f"tidak ada gambar di {args.gambar}")
        for path in files:
            for label, side, size in parse_presets(args.preset):
                data, info = compress_like_app(path, side, size)
                items.append(Item(KIND_IMAGE, path.stem, label, data, info))
    if args.suara:
        files = sorted(p for p in Path(args.suara).iterdir() if p.is_file())
        for path in files:
            data = path.read_bytes()
            info = nc2_info(data)
            if info is None:
                print(f"lewati {path.name}: bukan voice note NC2 app")
                continue
            items.append(Item(KIND_VOICE, path.stem, "rekaman-app", data, info))
    for i, seconds in enumerate(args.suara_sintetis or []):
        data = synthetic_voice(seconds, seed=i)
        items.append(Item(KIND_VOICE, f"sintetis-{seconds:g}s", "sintetis",
                          data, nc2_info(data)))
    if not items:
        raise SystemExit("tidak ada yang dikirim: isi --gambar, --suara "
                         "atau --suara-sintetis")
    return items * args.ulang


# ---------------------------------------------------------------------------
# Frame dan airtime
# ---------------------------------------------------------------------------
def split_frames(msg_id, kind, data):
    total = max(1, math.ceil(len(data) / PAYLOAD_BYTES))
    if total > 0xFFFF:
        raise ValueError("data terlalu besar")
    frames = []
    for index in range(total):
        chunk = data[index * PAYLOAD_BYTES:(index + 1) * PAYLOAD_BYTES]
        head = MAGIC + bytes([FRAME_VERSION, kind])
        head += struct.pack(">IHH", msg_id, index, total)
        frames.append(head + chunk)
    return frames


def parse_frame(raw):
    if len(raw) < HEADER_BYTES or raw[:2] != MAGIC or raw[2] != FRAME_VERSION:
        return None
    msg_id, index, total = struct.unpack(">IHH", raw[4:12])
    if total == 0 or index >= total:
        return None
    return raw[3], msg_id, index, total, raw[HEADER_BYTES:]


def airtime_s(n_bytes, sf, bw_khz, cr, preamble=8):
    """Waktu siar LoRa (header eksplisit, CRC aktif), rumus Semtech AN1200.13."""
    t_sym = (2 ** sf) / (bw_khz * 1000.0)
    ldro = 1 if t_sym > 0.016 else 0
    num = 8 * n_bytes - 4 * sf + 28 + 16
    payload_sym = 8 + max(math.ceil(num / (4 * (sf - 2 * ldro))) * cr, 0)
    return (preamble + 4.25 + payload_sym) * t_sym


# ---------------------------------------------------------------------------
# Jam: nyata, atau virtual untuk simulasi tanpa board
# ---------------------------------------------------------------------------
class RealClock:
    def now(self):
        return time.monotonic()

    def sleep(self, seconds):
        if seconds > 0:
            time.sleep(seconds)


class VirtualClock:
    def __init__(self):
        self.t = 0.0

    def now(self):
        return self.t

    def sleep(self, seconds):
        self.t += max(seconds, 0.0)


# ---------------------------------------------------------------------------
# Penerima: menyusun ulang frame menjadi pesan
# ---------------------------------------------------------------------------
@dataclass
class Message:
    kind: int
    total: int
    parts: dict = field(default_factory=dict)
    rssi: list = field(default_factory=list)
    snr: list = field(default_factory=list)
    duplicates: int = 0
    first_at: float = 0.0
    done_at: float | None = None


class Collector:
    def __init__(self, clock, on_complete=None):
        self.clock = clock
        self.on_complete = on_complete
        self.messages: dict[int, Message] = {}
        self.foreign = 0
        self.crc_errors = 0
        self.cond = threading.Condition()

    def on_frame(self, raw, rssi, snr):
        parsed = parse_frame(raw)
        with self.cond:
            if parsed is None:
                self.foreign += 1
                return
            kind, msg_id, index, total, payload = parsed
            now = self.clock.now()
            msg = self.messages.get(msg_id)
            if msg is None:
                msg = Message(kind, total, first_at=now)
                self.messages[msg_id] = msg
            if index in msg.parts:
                msg.duplicates += 1
            else:
                msg.parts[index] = payload
            msg.rssi.append(rssi)
            msg.snr.append(snr)
            completed = msg.done_at is None and len(msg.parts) == msg.total
            if completed:
                msg.done_at = now
            self.cond.notify_all()
        if completed and self.on_complete:
            self.on_complete(msg_id, msg)

    def on_crc_error(self):
        with self.cond:
            self.crc_errors += 1

    def missing(self, msg_id, total):
        with self.cond:
            msg = self.messages.get(msg_id)
            have = msg.parts if msg else {}
            return [i for i in range(total) if i not in have]

    def wait_done(self, msg_id, timeout):
        deadline = time.monotonic() + timeout
        with self.cond:
            while True:
                msg = self.messages.get(msg_id)
                if msg and msg.done_at is not None:
                    return True
                left = deadline - time.monotonic()
                if left <= 0:
                    return False
                self.cond.wait(left)

    def assembled(self, msg_id):
        with self.cond:
            msg = self.messages.get(msg_id)
            if not msg or len(msg.parts) != msg.total:
                return None
            return b"".join(msg.parts[i] for i in range(msg.total))


# ---------------------------------------------------------------------------
# Board: lewat serial, atau tiruan untuk simulasi
# ---------------------------------------------------------------------------
class SerialBoard:
    def __init__(self, port, baud, name):
        import serial

        self.name = name
        self.serial = serial.Serial(port, baud, timeout=0.2)
        self.responses: list[str] = []
        self.cond = threading.Condition()
        self.collector: Collector | None = None
        self.running = True
        self.thread = threading.Thread(target=self._read_loop, daemon=True)
        self.thread.start()
        self._wait_ready()

    def _read_loop(self):
        buffer = b""
        while self.running:
            chunk = self.serial.read(512)
            if not chunk:
                continue
            buffer += chunk
            while b"\n" in buffer:
                raw, buffer = buffer.split(b"\n", 1)
                self._handle(raw.decode("ascii", "replace").strip())

    def _handle(self, text):
        if not text:
            return
        if text.startswith("RX "):
            parts = text.split()
            if self.collector and len(parts) == 4:
                try:
                    frame = bytes.fromhex(parts[1])
                    self.collector.on_frame(frame, float(parts[2]),
                                            float(parts[3]))
                except ValueError:
                    pass
            return
        if text.startswith("RXERR"):
            if self.collector and "crc" in text:
                self.collector.on_crc_error()
            return
        with self.cond:
            self.responses.append(text)
            self.cond.notify_all()

    def _wait_for(self, prefixes, timeout):
        deadline = time.monotonic() + timeout
        with self.cond:
            while True:
                for i, text in enumerate(self.responses):
                    if text.startswith(prefixes):
                        del self.responses[: i + 1]
                        return text
                left = deadline - time.monotonic()
                if left <= 0:
                    return None
                self.cond.wait(left)

    def _send(self, text):
        self.serial.write((text + "\n").encode("ascii"))
        self.serial.flush()

    def _wait_ready(self):
        # Membuka port biasanya me-reset ESP32: tunggu READY, lalu PING.
        self._wait_for(("READY", "ERR RADIO"), 4.0)
        for _ in range(5):
            self._send("PING")
            reply = self._wait_for(("PONG",), 1.5)
            if reply:
                print(f"[{self.name}] {reply}")
                return
        raise SystemExit(f"[{self.name}] board tidak menjawab PING. "
                         "Cek port dan firmware UjiLoraLaptop.")

    def configure(self, freq, sf, bw, cr, power):
        self._send(f"CFG {freq} {sf} {bw} {cr} {power}")
        reply = self._wait_for(("OK CFG", "ERR CFG"), 3.0)
        if not reply or reply.startswith("ERR"):
            raise SystemExit(f"[{self.name}] konfigurasi gagal: {reply}")
        print(f"[{self.name}] {reply}")

    def transmit(self, frame, expected_airtime):
        self._send("TX " + frame.hex())
        reply = self._wait_for(("TXOK", "ERR TX"), expected_airtime * 3 + 3)
        if not reply or reply.startswith("ERR"):
            raise RuntimeError(f"[{self.name}] TX gagal: {reply}")
        measured_us = int(reply.split()[2])
        return measured_us / 1e6

    def close(self):
        self.running = False
        self.thread.join(timeout=1)
        self.serial.close()


class SimulatedLink:
    """Pasangan TX/RX tiruan: frame hilang acak sesuai --hilang."""

    def __init__(self, clock, loss, seed, radio):
        self.clock = clock
        self.loss = loss
        self.rng = random.Random(seed)
        self.radio = radio
        self.collector: Collector | None = None

    def configure(self, *_):
        print(f"[simulasi] frame hilang {self.loss:.0%}")

    def transmit(self, frame, _expected):
        air = airtime_s(len(frame), *self.radio)
        self.clock.sleep(air)
        if self.collector and self.rng.random() >= self.loss:
            rssi = self.rng.uniform(-100, -60)
            snr = self.rng.uniform(-5, 10)
            self.collector.on_frame(frame, rssi, snr)
        return air

    def close(self):
        pass


# ---------------------------------------------------------------------------
# Pengiriman
# ---------------------------------------------------------------------------
def pace(clock, air, duty):
    """Diam setelah memancar supaya rata-rata waktu siar <= duty."""
    if duty < 1.0:
        clock.sleep(air * (1.0 / duty - 1.0))


def mean(values):
    return round(sum(values) / len(values), 2) if values else ""


def send_item(item, tx, collector, clock, args, radio):
    msg_id = random.getrandbits(32)
    frames = split_frames(msg_id, item.kind, item.data)
    total = len(frames)
    started = clock.now()
    sent = 0
    air_total = 0.0
    lost_first = None
    rounds = 0
    pending = list(range(total))
    grace = 0.3 if isinstance(clock, VirtualClock) else 2.0

    for rounds in range(1, args.perbaiki + 2):
        for index in pending:
            expected = airtime_s(len(frames[index]), *radio)
            air = tx.transmit(frames[index], expected)
            sent += 1
            air_total += air
            pace(clock, air, args.duty)
        if collector is None:
            break
        collector.wait_done(msg_id, grace)
        pending = collector.missing(msg_id, total)
        if lost_first is None:
            lost_first = len(pending)
        if not pending:
            break

    row = {
        "jenis": KIND_NAMES[item.kind],
        "berkas": item.name,
        "preset": item.label,
        "byte": len(item.data),
        "frame": total,
        "frame_dikirim": sent,
        "airtime_s": round(air_total, 2),
        "id_pesan": f"{msg_id:08x}",
        "sha256": hashlib.sha256(item.data).hexdigest(),
    }
    row.update(item.info)
    if collector is None:
        row["waktu_kirim_s"] = round(clock.now() - started, 1)
        return row, None

    received = collector.assembled(msg_id)
    msg = collector.messages.get(msg_id)
    done_at = msg.done_at if msg else None
    row.update({
        "hilang_putaran1": lost_first,
        "putaran": rounds,
        "lengkap": received is not None,
        "isi_sama": received == item.data,
        "waktu_sampai_s": round(done_at - started, 1) if done_at else "",
        "rssi_rata": mean(msg.rssi) if msg else "",
        "rssi_min": round(min(msg.rssi), 1) if msg and msg.rssi else "",
        "snr_rata": mean(msg.snr) if msg else "",
        "snr_min": round(min(msg.snr), 2) if msg and msg.snr else "",
    })
    return row, received


def estimate(items, args, radio):
    frames = sum(math.ceil(len(i.data) / PAYLOAD_BYTES) for i in items)
    air = frames * airtime_s(MAX_FRAME_BYTES, *radio)
    wall = air / args.duty if args.duty < 1 else air
    print(f"{len(items)} kiriman, {frames} frame, airtime sekitar {air:.0f} s, "
          f"perkiraan waktu {wall / 60:.1f} menit tanpa kirim ulang "
          f"(duty {args.duty:.0%})")


def write_csv(path, rows):
    keys = []
    for row in rows:
        keys += [k for k in row if k not in keys]
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=keys)
        writer.writeheader()
        writer.writerows(rows)


def summarize(rows):
    groups = {}
    for row in rows:
        groups.setdefault((row["jenis"], row["preset"]), []).append(row)
    print("\nRingkasan")
    print(f"{'jenis':<8}{'preset':<14}{'n':>4}{'utuh':>8}"
          f"{'hilang1':>9}{'detik':>8}{'byte':>7}")
    for (kind, label), group in groups.items():
        ok = sum(1 for r in group if r.get("isi_sama"))
        frames = sum(r["frame"] for r in group)
        lost = sum(r.get("hilang_putaran1") or 0 for r in group)
        times = [r["waktu_sampai_s"] for r in group if r.get("waktu_sampai_s")]
        size = sum(r["byte"] for r in group) / len(group)
        print(f"{kind:<8}{label:<14}{len(group):>4}"
              f"{ok / len(group):>8.0%}{lost / frames:>9.1%}"
              f"{(sum(times) / len(times) if times else 0):>8.1f}"
              f"{size:>7.0f}")


def radio_tuple(args):
    return (args.sf, args.bw, args.cr)


def open_out(args):
    out = Path(args.keluar) / time.strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    return out


def run_test(args, receive):
    radio = radio_tuple(args)
    items = build_items(args)
    estimate(items, args, radio)
    out = open_out(args)

    if args.simulasi:
        clock = VirtualClock()
        tx = SimulatedLink(clock, args.hilang, seed=1, radio=radio)
        rx = None
    else:
        clock = RealClock()
        tx = SerialBoard(args.tx, args.baud, "TX")
        rx = SerialBoard(args.rx, args.baud, "RX") if receive else None

    collector = Collector(clock) if receive else None
    try:
        for board in (tx, rx):
            if board is None:
                continue
            board.configure(args.freq, args.sf, args.bw, args.cr, args.daya)
        if args.simulasi and receive:
            tx.collector = collector
        elif rx is not None:
            rx.collector = collector

        rows = []
        for no, item in enumerate(items, 1):
            row, received = send_item(item, tx, collector, clock, args, radio)
            row = {"no": no, **row}
            rows.append(row)
            stem = f"{no:03d}_{item.name}_{item.label}"
            ext = KIND_EXT[item.kind]
            (out / f"{stem}_kirim{ext}").write_bytes(item.data)
            if received is not None:
                (out / f"{stem}_terima{ext}").write_bytes(received)
            status = ("utuh" if row.get("isi_sama") else
                      "TIDAK UTUH" if receive else "terkirim")
            print(f"[{no}/{len(items)}] {row['jenis']} {item.name} "
                  f"{item.label}: {row['byte']} B, {row['frame']} frame, "
                  f"{status}")
            write_csv(out / "hasil.csv", rows)
    except KeyboardInterrupt:
        print("\ndihentikan, hasil sejauh ini tetap disimpan")
    finally:
        for board in (tx, rx):
            if board is not None:
                board.close()

    if collector is not None:
        summarize(rows)
        extra = {"frame_crc_rusak": collector.crc_errors,
                 "frame_asing": collector.foreign}
        print(f"frame CRC rusak: {extra['frame_crc_rusak']}, "
              f"frame asing: {extra['frame_asing']}")
        (out / "ringkasan.json").write_text(json.dumps(extra, indent=2))
    print(f"\nHasil: {out}")


def run_receive_only(args):
    out = open_out(args)
    clock = RealClock()
    rows = []

    def save(msg_id, msg):
        data = b"".join(msg.parts[i] for i in range(msg.total))
        name = f"{msg_id:08x}{KIND_EXT.get(msg.kind, '.bin')}"
        (out / name).write_bytes(data)
        rows.append({
            "id_pesan": f"{msg_id:08x}",
            "jenis": KIND_NAMES.get(msg.kind, "?"),
            "byte": len(data),
            "frame": msg.total,
            "duplikat": msg.duplicates,
            "waktu_susun_s": round(msg.done_at - msg.first_at, 1),
            "rssi_rata": mean(msg.rssi),
            "snr_rata": mean(msg.snr),
            "sha256": hashlib.sha256(data).hexdigest(),
        })
        write_csv(out / "diterima.csv", rows)
        print(f"diterima {name}: {len(data)} B dari {msg.total} frame")

    collector = Collector(clock, on_complete=save)
    rx = SerialBoard(args.rx, args.baud, "RX")
    rx.configure(args.freq, args.sf, args.bw, args.cr, args.daya)
    rx.collector = collector
    print("menunggu kiriman, Ctrl+C untuk berhenti")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        rx.close()
    print(f"\n{len(rows)} pesan utuh, frame CRC rusak {collector.crc_errors}. "
          f"Cocokkan sha256 dengan hasil.csv di laptop pengirim. Hasil: {out}")


def list_ports(_args):
    from serial.tools import list_ports as lp

    ports = list(lp.comports())
    if not ports:
        print("tidak ada port serial")
    for port in ports:
        print(f"{port.device:<8} {port.description}")


def pull_voice(args):
    """Salin voice note (lampiran berawalan NC2) dari riwayat chat app.
    Hanya bisa untuk APK debug (adb run-as)."""
    out = Path(args.keluar)
    out.mkdir(parents=True, exist_ok=True)
    base = "no_backup/chat-history"
    listing = subprocess.run(
        ["adb", "shell", "run-as", APP_PACKAGE, "ls", base],
        capture_output=True, text=True,
    )
    if listing.returncode != 0:
        raise SystemExit("adb gagal: " + listing.stderr.strip())
    names = [n for n in listing.stdout.split() if n.startswith("a-")]
    saved = 0
    for name in names:
        data = subprocess.run(
            ["adb", "exec-out", "run-as", APP_PACKAGE, "cat", f"{base}/{name}"],
            capture_output=True,
        ).stdout
        info = nc2_info(data)
        if info:
            (out / f"{name}.nc2").write_bytes(data)
            saved += 1
            print(f"{name}.nc2: {len(data)} B, {info['detik']} detik")
    print(f"{saved} voice note disalin ke {out}")


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Uji kirim gambar dan suara lewat LoRa dari laptop.")
    sub = parser.add_subparsers(dest="cmd", required=True)

    def radio_args(p):
        p.add_argument("--baud", type=int, default=115200)
        p.add_argument("--freq", type=float, default=921.0, help="MHz")
        p.add_argument("--sf", type=int, default=7)
        p.add_argument("--bw", type=float, default=125.0, help="kHz")
        p.add_argument("--cr", type=int, default=5, help="5..8 = 4/5..4/8")
        p.add_argument("--daya", type=int, default=2, help="dBm, 2..14")
        p.add_argument("--keluar", default="hasil")

    def send_args(p):
        p.add_argument("--gambar", help="folder foto sumber")
        p.add_argument("--preset", default="cepat,seimbang,detail",
                       help="cepat,seimbang,detail atau SISI:BYTE")
        p.add_argument("--suara", help="folder voice note NC2 dari app")
        p.add_argument("--suara-sintetis", type=float, nargs="*",
                       help="durasi detik, contoh: 5 10 30")
        p.add_argument("--ulang", type=int, default=1,
                       help="ulangi seluruh daftar N kali")
        p.add_argument("--duty", type=float, default=0.05,
                       help="batas duty cycle, 0.05 = 5%%")

    p = sub.add_parser("port", help="daftar port serial")
    p.set_defaults(func=list_ports)

    p = sub.add_parser("uji", help="kirim dan terima dari satu laptop")
    radio_args(p)
    send_args(p)
    p.add_argument("--tx", help="port board pengirim, contoh COM5")
    p.add_argument("--rx", help="port board penerima, contoh COM6")
    p.add_argument("--perbaiki", type=int, default=2,
                   help="putaran kirim ulang frame yang hilang")
    p.add_argument("--simulasi", action="store_true",
                   help="tanpa board, frame hilang acak")
    p.add_argument("--hilang", type=float, default=0.05,
                   help="peluang frame hilang saat simulasi")
    p.set_defaults(func=lambda a: run_test(a, receive=True))

    p = sub.add_parser("kirim", help="hanya mengirim")
    radio_args(p)
    send_args(p)
    p.add_argument("--tx", required=True)
    p.set_defaults(func=lambda a: run_test(a, receive=False),
                   perbaiki=0, simulasi=False, rx=None)

    p = sub.add_parser("terima", help="hanya menerima")
    radio_args(p)
    p.add_argument("--rx", required=True)
    p.set_defaults(func=run_receive_only)

    p = sub.add_parser("ambil-suara", help="salin voice note dari HP")
    p.add_argument("--keluar", default="suara")
    p.set_defaults(func=pull_voice)

    args = parser.parse_args(argv)
    if args.cmd == "uji" and not args.simulasi and not (args.tx and args.rx):
        parser.error("uji butuh --tx dan --rx, atau --simulasi")
    if getattr(args, "duty", 0.05) <= 0 or getattr(args, "duty", 0.05) > 1:
        parser.error("--duty harus di antara 0 dan 1")
    args.func(args)


if __name__ == "__main__":
    sys.exit(main())
