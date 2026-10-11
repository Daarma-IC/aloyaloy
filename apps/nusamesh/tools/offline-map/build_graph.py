"""
Graf jalan offline untuk router NusaMesh (A* di HP) dari ekstrak OSM Geofabrik (ODbL).

Simpul graf = persimpangan & ujung jalan (dari ID node OSM, bukan kebetulan koordinat sama);
ruas = potongan jalan di antaranya beserta titik bentuknya. Format biner little-endian "NMRG" v1:
  header: "NMRG", u8 versi, 3 byte cadangan, u32 simpul, u32 ruas, u32 titik bentuk, 4×f32 bbox (W,S,E,N)
  simpul: i32 lat×1e6 [n], i32 lon×1e6 [n]
  ruas:   u32 a, u32 b, u32 panjang (desimeter), u8 kelas, u8 flag, u32 awal bentuk, u16 jumlah bentuk  (tiap kolom berurutan)
          flag bit0 = satu arah a→b (kendaraan), bit1 = terlarang pejalan kaki, bit2 = terlarang kendaraan
  bentuk: i32 lat×1e6 [m], i32 lon×1e6 [m]   (titik antara, urutan a→b)
Kelas = indeks di CLASSES (harus sama dengan RoadClass di app).

  python build_graph.py java-latest.osm.pbf ../../composeApp/offline-maps/routing/bandung.nmrg
"""
import math, struct, sys, time
from array import array
import osmium

W, S, E, N = 107.25, -7.35, 107.95, -6.75
M = 0.02
CLASSES = ["motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
           "living_street", "service", "track", "path", "footway", "pedestrian", "steps", "cycleway", "bridleway"]
LINKS = {"motorway_link": "motorway", "trunk_link": "trunk", "primary_link": "primary",
         "secondary_link": "secondary", "tertiary_link": "tertiary", "road": "unclassified"}
NON_VEHICLE = {"path", "footway", "pedestrian", "steps", "cycleway", "bridleway"}

def inside(lon, lat):
    return W - M <= lon <= E + M and S - M <= lat <= N + M

class Ways(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.ways = []          # (refs, coords, cls, flags)
    def way(self, w):
        t = w.tags
        hw = LINKS.get(t.get("highway"), t.get("highway"))
        if hw not in CLASSES or t.get("area") == "yes" or t.get("access") in ("no", "private"):
            return
        try:
            refs = [nd.ref for nd in w.nodes]
            coords = [(nd.lon, nd.lat) for nd in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(refs) < 2 or not any(inside(x, y) for x, y in coords):
            return
        flags = 0
        oneway = t.get("oneway")
        if oneway in ("yes", "1", "true") or hw == "motorway" or t.get("junction") == "roundabout":
            flags |= 1
        elif oneway == "-1":       # satu arah berlawanan: balik urutan
            refs.reverse(); coords.reverse(); flags |= 1
        if t.get("foot") == "no" or hw == "motorway":
            flags |= 2
        if hw in NON_VEHICLE or t.get("motor_vehicle") == "no" or t.get("vehicle") == "no":
            flags |= 4
        self.ways.append((refs, coords, CLASSES.index(hw), flags))

def meters(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((b[0] - a[0]) * 111320 * math.cos(lat), (b[1] - a[1]) * 110540)

start = time.time()
h = Ways()
h.apply_file(sys.argv[1], locations=True, idx="flex_mem")
print(f"{len(h.ways)} jalan ({time.time()-start:.0f}s)", flush=True)

uses = {}
for refs, _, _, _ in h.ways:
    for r in refs:
        uses[r] = uses.get(r, 0) + 1
node_index, node_lat, node_lon = {}, array("i"), array("i")
def node(ref, c):
    i = node_index.get(ref)
    if i is None:
        i = node_index[ref] = len(node_lat)
        node_lat.append(round(c[1] * 1e6)); node_lon.append(round(c[0] * 1e6))
    return i

sa, sb, slen, scls, sflag, sstart, scount = array("I"), array("I"), array("I"), array("B"), array("B"), array("I"), array("H")
shape_lat, shape_lon = array("i"), array("i")
for refs, coords, cls, flags in h.ways:
    seg_start = 0
    for k in range(1, len(refs)):
        if k == len(refs) - 1 or uses[refs[k]] > 1:
            a = node(refs[seg_start], coords[seg_start]); b = node(refs[k], coords[k])
            length = sum(meters(coords[j], coords[j + 1]) for j in range(seg_start, k))
            inner = coords[seg_start + 1:k][:65535]
            if a != b or inner:
                sa.append(a); sb.append(b); slen.append(max(1, round(length * 10))); scls.append(cls); sflag.append(flags)
                sstart.append(len(shape_lat)); scount.append(len(inner))
                for x, y in inner:
                    shape_lat.append(round(y * 1e6)); shape_lon.append(round(x * 1e6))
            seg_start = k

arrays = (node_lat, node_lon, sa, sb, slen, scls, sflag, sstart, scount, shape_lat, shape_lon)
assert sys.byteorder == "little"
with open(sys.argv[2], "wb") as f:
    f.write(b"NMRG" + bytes([1, 0, 0, 0]))
    f.write(struct.pack("<III4f", len(node_lat), len(sa), len(shape_lat), W, S, E, N))
    for arr in arrays:
        f.write(arr.tobytes())
size = 36 + sum(a.itemsize * len(a) for a in arrays)
print(f"simpul {len(node_lat)}, ruas {len(sa)}, titik bentuk {len(shape_lat)}, {size/1e6:.1f} MB, {time.time()-start:.0f}s")
