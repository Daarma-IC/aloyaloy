"""Render data OSM (ODbL) wilayah Bandung menjadi MBTiles raster PNG untuk impor di NusaMesh."""
import io, math, multiprocessing as mp, os, pickle, sqlite3, sys, time
from collections import defaultdict
from PIL import Image, ImageDraw, ImageFont

SS = 2                      # supersampling: gambar 512 px lalu diperkecil → garis halus
T = 256 * SS
MINZ, MAXZ, CORE_Z = 10, 16, 16
FONT = "/System/Library/Fonts/Supplemental/Arial.ttf"
FONT_B = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"

def px(lon, lat, z):
    n = 256 * SS * (1 << z)
    lat = max(min(lat, 85.0), -85.0)
    return (lon + 180) / 360 * n, (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n

def tile_range(box, z, pad_px=0):
    x0, y0 = px(box[0], box[3], z)
    x1, y1 = px(box[2], box[1], z)
    p = pad_px * SS
    return int((x0 - p) // T), int((y0 - p) // T), int((x1 + p) // T), int((y1 + p) // T)

# ---- gaya ---------------------------------------------------------------------------------------
BG = (242, 239, 233)
AREA = {  # urutan gambar: yang belakangan di atas
    "farmland": (238, 240, 213), "orchard": (174, 223, 163), "grass": (205, 235, 176), "park": (200, 250, 204),
    "scrub": (200, 215, 171), "forest": (173, 209, 158), "residential": (224, 223, 223), "commercial": (242, 218, 217),
    "industrial": (235, 219, 232), "cemetery": (170, 203, 175), "wetland": (210, 230, 220), "rock": (225, 220, 210),
    "water": (170, 211, 223),
}
AREA_MINZ = {"residential": 11, "commercial": 13, "industrial": 12, "cemetery": 14, "park": 13}
WATER = (170, 211, 223)
ROAD = {  # kelas: (warna isi, warna tepi, lebar z16 px, zoom min)
    "motorway": ((232, 146, 162), (220, 46, 102), 11, 10), "trunk": ((249, 178, 156), (200, 90, 60), 10, 10),
    "primary": ((252, 214, 164), (160, 120, 40), 9, 10), "secondary": ((247, 250, 191), (130, 140, 40), 8, 11),
    "tertiary": ((255, 255, 255), (150, 150, 150), 7, 12), "unclassified": ((255, 255, 255), (170, 170, 170), 5.5, 13),
    "residential": ((255, 255, 255), (170, 170, 170), 5.5, 13), "living_street": ((237, 237, 237), (170, 170, 170), 5, 14),
    "pedestrian": ((221, 221, 232), (170, 170, 170), 4, 15), "service": ((255, 255, 255), (180, 180, 180), 3, 14),
}
LINKS = {"motorway_link": "motorway", "trunk_link": "trunk", "primary_link": "primary", "secondary_link": "secondary", "tertiary_link": "tertiary"}
DASHED = {"track": ((153, 102, 51), 2.2, 14), "path": ((200, 70, 70), 1.6, 14), "footway": ((250, 128, 114), 1.6, 15), "steps": ((250, 128, 114), 2.2, 15)}
ROAD_ORDER = ["service", "pedestrian", "living_street", "unclassified", "residential", "tertiary", "secondary", "primary", "trunk", "motorway"]
WATERWAY = {"river": (6, 10), "canal": (4, 12), "stream": (2, 13), "drain": (1.5, 15)}   # (lebar z16, zoom min)
SCALE = {16: 1.0, 15: 0.8, 14: 0.62, 13: 0.48, 12: 0.38, 11: 0.3, 10: 0.25}
PLACE = {"city": (10, 13, 15, True), "town": (11, 14, 13, True), "suburb": (12, 15, 12, False), "quarter": (14, 16, 11, False),
         "village": (13, 16, 11, False), "neighbourhood": (15, 16, 10, False), "hamlet": (15, 16, 10, False)}
PRIORITY = ["city", "town", "suburb", "peak", "village", "quarter", "poi", "neighbourhood", "hamlet"]

def width(base, z):
    return max(0.9, base * SCALE[z]) * SS

def to_px(coords, z, ox, oy):
    return [(px(lon, lat, z)[0] - ox, px(lon, lat, z)[1] - oy) for lon, lat in coords]

def dashed(draw, pts, color, w, on, off):
    carry, drawing = 0.0, True
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        seg = math.hypot(x1 - x0, y1 - y0)
        pos = 0.0
        while pos < seg:
            step = min((on if drawing else off) - carry, seg - pos)
            if drawing:
                a, b = pos / seg, (pos + step) / seg
                draw.line([(x0 + (x1 - x0) * a, y0 + (y1 - y0) * a), (x0 + (x1 - x0) * b, y0 + (y1 - y0) * b)], fill=color, width=max(1, round(w)))
            pos += step
            carry += step
            if carry >= (on if drawing else off) - 1e-6:
                carry, drawing = 0.0, not drawing

DATA = None
INDEX = None
LABELS = None
FONTS = {}

def font(size, bold=False):
    key = (size, bold)
    if key not in FONTS:
        FONTS[key] = ImageFont.truetype(FONT_B if bold else FONT, size)
    return FONTS[key]

def build_index():
    """Daftar fitur per ubin per zoom (dihitung sekali), supaya tiap ubin cukup menggambar isinya."""
    index = defaultdict(lambda: defaultdict(list))
    def bbox(coords):
        xs = [c[0] for c in coords]; ys = [c[1] for c in coords]
        return min(xs), min(ys), max(xs), max(ys)
    layers = {
        "areas": [(a, bbox(a[1][0][0])) for a in DATA["areas"]],
        "buildings": [(b, bbox(b[0][0])) for b in DATA["buildings"]],
        "waterways": [(w, bbox(w[1])) for w in DATA["waterways"]],
        "roads": [(r, bbox(r[3])) for r in DATA["roads"]],
        "rail": [(r, bbox(r)) for r in DATA["rail"]],
    }
    for z in range(MINZ, MAXZ + 1):
        for layer, items in layers.items():
            if layer == "buildings" and z < 15:
                continue
            for i, (_, box) in enumerate(items):
                x0, y0, x1, y1 = tile_range(box, z, pad_px=8)
                if (x1 - x0 + 1) * (y1 - y0 + 1) > 4000:  # fitur raksasa (mis. hutan lindung): tetap, tapi batasi pencarian
                    pass
                for tx in range(x0, x1 + 1):
                    for ty in range(y0, y1 + 1):
                        index[(z, tx, ty)][layer].append(i)
    return index, layers

def place_labels():
    """Penempatan label global per zoom (prioritas + tabrakan), supaya tidak terpotong di batas ubin."""
    out = defaultdict(list)
    cand = []
    for kind, name, lon, lat in DATA["places"]:
        if kind in PLACE:
            cand.append((kind, name, lon, lat, None))
    for name, ele, lon, lat in DATA["peaks"]:
        label = name + (f" ({ele.split('.')[0]} m)" if ele and ele.replace('.', '').isdigit() else "")
        cand.append(("peak", label, lon, lat, None))
    for sym, name, lon, lat in DATA["pois"]:
        cand.append(("poi", name or "", lon, lat, sym))
    cand.sort(key=lambda c: PRIORITY.index(c[0]))
    for z in range(MINZ, MAXZ + 1):
        taken = []
        for kind, name, lon, lat, sym in cand:
            if kind in PLACE:
                zmin, zmax, size, bold = PLACE[kind]
                if not (zmin <= z <= zmax):
                    continue
            elif kind == "peak":
                if z < 11: continue
                size, bold = 11, False
            else:
                if z < 14: continue
                size, bold = 10, False
            x, y = px(lon, lat, z)
            f = font(size * SS, bold)
            text = name if kind != "poi" else (name if z >= 15 else "")
            w = f.getlength(text) if text else 0
            h = size * SS
            box = (x - w / 2 - 4, y - h - 4, x + w / 2 + 4, y + h + 4)
            if any(not (box[2] < b[0] or box[0] > b[2] or box[3] < b[1] or box[1] > b[3]) for b in taken):
                continue
            taken.append(box)
            out[z].append((kind, text, x, y, size, bold, sym))
    return out

def render(task):
    z, tx, ty = task
    ox, oy = tx * T, ty * T
    img = Image.new("RGB", (T, T), BG)
    d = ImageDraw.Draw(img)
    items = INDEX.get((z, tx, ty), {})
    A = DATA

    def poly(polys, color, outline=None):
        for ring, holes in polys:
            pts = to_px(ring, z, ox, oy)
            if len(pts) < 3: continue
            if holes:
                mask = Image.new("L", (T, T), 0)
                md = ImageDraw.Draw(mask)
                md.polygon(pts, fill=255)
                for hole in holes:
                    if len(hole) >= 3: md.polygon(to_px(hole, z, ox, oy), fill=0)
                img.paste(color, (0, 0, T, T), mask)
            else:
                d.polygon(pts, fill=color, outline=outline)

    order = list(AREA.keys())
    for i in sorted(items.get("areas", []), key=lambda i: order.index(A["areas"][i][0])):
        kind, polys = A["areas"][i]
        if z < AREA_MINZ.get(kind, 10): continue
        poly(polys, AREA[kind])
    for i in items.get("waterways", []):
        kind, coords = A["waterways"][i]
        base, zmin = WATERWAY[kind]
        if z >= zmin: d.line(to_px(coords, z, ox, oy), fill=WATER, width=max(1, round(width(base, z))), joint="curve")
    if z >= 15:
        for i in items.get("buildings", []):
            poly(A["buildings"][i], (217, 208, 201), outline=(196, 182, 171))
    roads = [A["roads"][i] for i in items.get("roads", [])]
    for cls, _, _, coords in roads:
        if cls in DASHED and z >= DASHED[cls][2]:
            color, base, _ = DASHED[cls]
            dashed(d, to_px(coords, z, ox, oy), color, width(base, z), 6 * SS, 4 * SS)
    solid = [(LINKS.get(c, c), coords, tunnel) for c, _, tunnel, coords in roads if LINKS.get(c, c) in ROAD and z >= ROAD[LINKS.get(c, c)][3]]
    solid.sort(key=lambda r: ROAD_ORDER.index(r[0]))
    for cls, coords, tunnel in solid:          # tepi dulu, isi kemudian → persimpangan rapi
        fill, edge, base, _ = ROAD[cls]
        d.line(to_px(coords, z, ox, oy), fill=edge, width=max(1, round(width(base, z) + 1.6 * SS)), joint="curve")
    for cls, coords, tunnel in solid:
        fill, edge, base, _ = ROAD[cls]
        d.line(to_px(coords, z, ox, oy), fill=fill, width=max(1, round(width(base, z))), joint="curve")
    for i in items.get("rail", []):
        pts = to_px(A["rail"][i], z, ox, oy)
        d.line(pts, fill=(112, 112, 112), width=max(1, round(width(3, z))))
        dashed(d, pts, (255, 255, 255), width(1.5, z), 8 * SS, 8 * SS)

    for kind, text, x, y, size, bold, sym in LABELS.get(z, []):
        x -= ox; y -= oy
        if not (-300 * SS < x < T + 300 * SS and -60 * SS < y < T + 60 * SS): continue
        f = font(size * SS, bold)
        if kind == "peak":
            d.polygon([(x, y - 5 * SS), (x - 5 * SS, y + 4 * SS), (x + 5 * SS, y + 4 * SS)], fill=(140, 90, 50))
            d.text((x, y - 7 * SS), text, font=f, fill=(90, 60, 30), anchor="ms", stroke_width=2 * SS, stroke_fill=(255, 255, 255))
        elif kind == "poi":
            r = 7 * SS
            d.ellipse([x - r, y - r, x + r, y + r], fill=(200, 30, 30) if sym in "H+" else (40, 80, 160), outline=(255, 255, 255), width=SS)
            d.text((x, y), sym, font=font(9 * SS, True), fill=(255, 255, 255), anchor="mm")
            if text: d.text((x, y + r + 2 * SS), text, font=f, fill=(120, 30, 30), anchor="mt", stroke_width=2 * SS, stroke_fill=(255, 255, 255))
        else:
            d.text((x, y), text, font=f, fill=(40, 40, 40), anchor="mm", stroke_width=3 * SS, stroke_fill=(255, 255, 255))

    img = img.resize((256, 256), Image.LANCZOS).quantize(colors=96, method=Image.Quantize.MEDIANCUT)
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return z, tx, ty, buf.getvalue()

def tasks():
    out = []
    for z in range(MINZ, MAXZ + 1):
        box = DATA["core"] if z >= CORE_Z else DATA["bbox"]
        x0, y0, x1, y1 = tile_range(box, z)
        out += [(z, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]
    return out

if __name__ == "__main__":
    start = time.time()
    DATA = pickle.load(open(sys.argv[1], "rb"))
    INDEX, _ = build_index()
    INDEX = {k: dict(v) for k, v in INDEX.items()}
    LABELS = place_labels()
    todo = tasks()
    print(f"{len(todo)} ubin, indeks {time.time()-start:.0f}s", flush=True)
    if os.path.exists(sys.argv[2]): os.remove(sys.argv[2])
    db = sqlite3.connect(sys.argv[2])
    db.executescript("CREATE TABLE metadata (name text, value text); CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob);"
                     "CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);")
    W, S, E, N = DATA["bbox"]
    meta = {"name": "Bandung Raya (OSM)", "format": "png", "type": "baselayer", "version": "1",
            "bounds": f"{W},{S},{E},{N}", "center": "107.6191,-6.9175,13", "minzoom": str(MINZ), "maxzoom": str(MAXZ),
            "attribution": "© OpenStreetMap contributors (ODbL)",
            "description": f"Kota & Kab. Bandung, zoom {MINZ}-15; zoom 16 untuk inti kota s.d. Bojongsoang/Dayeuhkolot. Dirender NusaMesh dari data Geofabrik."}
    db.executemany("INSERT INTO metadata VALUES (?,?)", meta.items())
    done = 0
    with mp.get_context("fork").Pool(max(1, os.cpu_count() - 1)) as pool:
        for z, x, y, png in pool.imap_unordered(render, todo, chunksize=8):
            db.execute("INSERT INTO tiles VALUES (?,?,?,?)", (z, x, (1 << z) - 1 - y, png))
            done += 1
            if done % 500 == 0:
                db.commit()
                print(f"{done}/{len(todo)} ({time.time()-start:.0f}s)", flush=True)
    db.commit()
    db.close()
    print(f"selesai {done} ubin, {os.path.getsize(sys.argv[2])/1e6:.1f} MB, {time.time()-start:.0f}s")
