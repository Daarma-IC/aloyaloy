"""Ambil objek peta dalam kotak Bandung dari ekstrak OSM Geofabrik (data ODbL) untuk dirender sendiri."""
import osmium, pickle, sys, time

W, S, E, N = 107.25, -7.35, 107.95, -6.75          # Kota + Kab. Bandung
CORE = (107.54, -7.03, 107.76, -6.84)               # inti kota s.d. Bojongsoang/Dayeuhkolot: bangunan & zoom 16
M = 0.02                                            # margin supaya objek di tepi tidak terpotong

ROADS = {"motorway","motorway_link","trunk","trunk_link","primary","primary_link","secondary","secondary_link",
         "tertiary","tertiary_link","unclassified","residential","living_street","service","track","path","footway","pedestrian","steps"}
WATERWAYS = {"river","stream","canal","drain"}
LANDUSE = {"residential":"residential","industrial":"industrial","commercial":"commercial","retail":"commercial",
           "forest":"forest","farmland":"farmland","paddy":"farmland","orchard":"orchard","plantation":"orchard",
           "meadow":"grass","grass":"grass","recreation_ground":"park","cemetery":"cemetery","farmyard":"farmland",
           "reservoir":"water","basin":"water"}
NATURAL = {"wood":"forest","scrub":"scrub","grassland":"grass","heath":"scrub","wetland":"wetland","bare_rock":"rock","water":"water"}
LEISURE = {"park":"park","golf_course":"park","pitch":"park","nature_reserve":"forest"}
POI = {"hospital":"H","clinic":"+","doctors":"+","police":"P","fire_station":"F"}

def inside(lon, lat, box=(W, S, E, N), m=M):
    return box[0]-m <= lon <= box[2]+m and box[1]-m <= lat <= box[3]+m

class H(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.roads, self.rail, self.waterways, self.areas, self.buildings, self.places, self.peaks, self.pois = [], [], [], [], [], [], [], []

    def node(self, n):
        if not n.tags or not n.location.valid() or not inside(n.location.lon, n.location.lat):
            return
        t = n.tags
        name = t.get("name")
        if "place" in t and name and t["place"] in ("city","town","suburb","village","hamlet","quarter","neighbourhood"):
            self.places.append((t["place"], name, n.location.lon, n.location.lat))
        elif t.get("natural") in ("peak","volcano") and name:
            self.peaks.append((name, t.get("ele"), n.location.lon, n.location.lat))
        elif t.get("amenity") in POI:
            self.pois.append((POI[t["amenity"]], name, n.location.lon, n.location.lat))

    def way(self, w):
        t = w.tags
        hw, ww, rw = t.get("highway"), t.get("waterway"), t.get("railway")
        if hw not in ROADS and ww not in WATERWAYS and rw != "rail":
            return
        try:
            coords = [(nd.lon, nd.lat) for nd in w.nodes]
        except osmium.InvalidLocationError:
            return
        if not any(inside(x, y) for x, y in coords):
            return
        if hw in ROADS and t.get("area") != "yes":
            self.roads.append((hw, t.get("name"), t.get("tunnel") == "yes", coords))
        elif ww in WATERWAYS:
            self.waterways.append((ww, coords))
        elif rw == "rail":
            self.rail.append(coords)

    def area(self, a):
        t = a.tags
        if "building" in t:
            kind = "building"
        elif t.get("natural") in NATURAL:
            kind = NATURAL[t["natural"]]
        elif t.get("landuse") in LANDUSE:
            kind = LANDUSE[t["landuse"]]
        elif t.get("leisure") in LEISURE:
            kind = LEISURE[t["leisure"]]
        elif t.get("waterway") == "riverbank" or t.get("water"):
            kind = "water"
        elif t.get("amenity") in POI:
            kind = "poi"
        else:
            return
        try:
            polys = []
            for outer in a.outer_rings():
                ring = [(nd.lon, nd.lat) for nd in outer]
                holes = [[(nd.lon, nd.lat) for nd in inner] for inner in a.inner_rings(outer)]
                polys.append((ring, holes))
        except osmium.InvalidLocationError:
            return
        if not polys:
            return
        box = CORE if kind == "building" else (W, S, E, N)
        if not any(inside(x, y, box) for ring, _ in polys for x, y in ring):
            return
        if kind == "building":
            self.buildings.append(polys)
        elif kind == "poi":
            ring = polys[0][0]
            self.pois.append((POI[t["amenity"]], t.get("name"), sum(p[0] for p in ring) / len(ring), sum(p[1] for p in ring) / len(ring)))
        else:
            self.areas.append((kind, polys))

start = time.time()
h = H()
h.apply_file(sys.argv[1], locations=True, idx="flex_mem")
data = {k: getattr(h, k) for k in ("roads","rail","waterways","areas","buildings","places","peaks","pois")}
data["bbox"], data["core"] = (W, S, E, N), CORE
pickle.dump(data, open(sys.argv[2], "wb"))
print({k: len(v) for k, v in data.items() if isinstance(v, list)}, f"{time.time()-start:.0f}s")
