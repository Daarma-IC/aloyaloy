import { distanceM } from "./domain.js";

const DB_NAME = "nusasurvey-routing";
const STORE = "graphs";
const GRAPH_KEY = "active-foot-graph";
const ALLOWED =
  /^(path|footway|track|steps|pedestrian|service|unclassified|residential|tertiary|secondary)$/;

const speedFor = (tags) => {
  const highway = tags.highway || "path";
  let speed =
    {
      steps: 0.45,
      path: 0.75,
      track: 0.9,
      footway: 1.05,
      pedestrian: 1.1,
      service: 1.15,
      residential: 1.2,
      unclassified: 1.15,
      tertiary: 1.2,
      secondary: 1.15,
    }[highway] || 0.8;
  if (["mud", "sand", "ground", "unpaved"].includes(tags.surface))
    speed *= 0.72;
  if (["demanding_mountain_hiking", "alpine_hiking"].includes(tags.sac_scale))
    speed *= 0.55;
  if (
    tags.smoothness &&
    ["bad", "very_bad", "horrible"].includes(tags.smoothness)
  )
    speed *= 0.7;
  return speed;
};

function openDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
export async function saveTrailGraph(graph) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put(graph, GRAPH_KEY);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}
export async function loadTrailGraph() {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const req = db.transaction(STORE).objectStore(STORE).get(GRAPH_KEY);
    req.onsuccess = () => resolve(req.result || null);
    req.onerror = () => reject(req.error);
  });
}

export async function downloadTrailGraph(bounds, onProgress = () => {}) {
  const [south, west, north, east] = bounds;
  const query = `[out:json][timeout:60];way["highway"~"${ALLOWED.source}"](${south},${west},${north},${east});(._;>;);out body;`;
  const endpoints = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
  ];
  let lastError;
  try {
    onProgress("Mengambil data wilayah dari OSM Map API…");
    const data = await downloadOsmMap(bounds);
    const graph = buildTrailGraph(data, bounds);
    if (graph.nodes.length < 2 || graph.edges.length < 1)
      throw new Error("Tidak ada jalur kaki pada area ini");
    graph.source = "OpenStreetMap Map API";
    await saveTrailGraph(graph);
    return graph;
  } catch (error) {
    lastError = error;
    onProgress("OSM Map API gagal; mencoba Overpass…");
  }
  for (const endpoint of endpoints) {
    let timer;
    try {
      onProgress(`Mengunduh graph dari ${new URL(endpoint).hostname}…`);
      const controller = new AbortController();
      timer = setTimeout(() => controller.abort(), 20000);
      const response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        },
        body: `data=${encodeURIComponent(query)}`,
        signal: controller.signal,
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      const graph = buildTrailGraph(data, bounds);
      if (graph.nodes.length < 2 || graph.edges.length < 1)
        throw new Error("Tidak ada jalur kaki pada area ini");
      await saveTrailGraph(graph);
      return graph;
    } catch (error) {
      lastError = error;
    } finally {
      clearTimeout(timer);
    }
  }
  throw new Error(
    `Semua sumber graph gagal: ${lastError?.message || "unknown"}`,
  );
}

async function downloadOsmMap(bounds) {
  const [south, west, north, east] = bounds;
  const url = new URL("/osm-api/api/0.6/map", window.location.origin);
  url.searchParams.set("bbox", `${west},${south},${east},${north}`);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 45000);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) throw new Error(`OSM Map API HTTP ${response.status}`);
    const xml = new DOMParser().parseFromString(
      await response.text(),
      "text/xml",
    );
    const apiError = xml.querySelector("parsererror");
    if (apiError) throw new Error("Respons XML OSM tidak valid");
    const elements = [];
    xml.querySelectorAll("node").forEach((node) =>
      elements.push({
        type: "node",
        id: Number(node.getAttribute("id")),
        lat: Number(node.getAttribute("lat")),
        lon: Number(node.getAttribute("lon")),
      }),
    );
    xml.querySelectorAll("way").forEach((way) => {
      const tags = {};
      way.querySelectorAll(":scope > tag").forEach((tag) => {
        tags[tag.getAttribute("k")] = tag.getAttribute("v");
      });
      if (!ALLOWED.test(tags.highway || "")) return;
      elements.push({
        type: "way",
        id: Number(way.getAttribute("id")),
        nodes: [...way.querySelectorAll(":scope > nd")].map((node) =>
          Number(node.getAttribute("ref")),
        ),
        tags,
      });
    });
    return { elements };
  } finally {
    clearTimeout(timer);
  }
}

export function buildTrailGraph(overpass, bounds) {
  const rawNodes = new Map(
    overpass.elements
      .filter((e) => e.type === "node")
      .map((n) => [n.id, [n.lat, n.lon]]),
  );
  const nodeIndex = new Map();
  const nodes = [];
  const edges = [];
  const indexOf = (id) => {
    if (nodeIndex.has(id)) return nodeIndex.get(id);
    const point = rawNodes.get(id);
    if (!point) return -1;
    const index = nodes.length;
    nodeIndex.set(id, index);
    nodes.push({ id, point, edges: [] });
    return index;
  };
  for (const way of overpass.elements.filter(
    (e) => e.type === "way" && ALLOWED.test(e.tags?.highway || ""),
  )) {
    const tags = way.tags || {};
    if (["no", "private", "agricultural", "forestry", "use_sidepath"].includes(tags.foot || tags.access)) continue;
    const speed = speedFor(tags);
    const ids = way.nodes || [];
    for (let i = 1; i < ids.length; i++) {
      const a = indexOf(ids[i - 1]),
        b = indexOf(ids[i]);
      if (a < 0 || b < 0 || a === b) continue;
      const meters = distanceM(nodes[a].point, nodes[b].point);
      if (!Number.isFinite(meters) || meters <= 0) continue;
      const edge = {
        a,
        b,
        meters,
        seconds: meters / speed,
        highway: tags.highway,
        name: tags.name || "",
        surface: tags.surface || "unknown",
        sacScale: tags.sac_scale || null,
        osmWayId: way.id,
        tags,
        forward: !["-1", "reverse"].includes(tags["oneway:foot"] || tags.oneway_foot),
        backward: !["yes", "1", "true"].includes(tags["oneway:foot"] || tags.oneway_foot),
      };
      const edgeIndex = edges.length;
      edges.push(edge);
      if (edge.forward) nodes[a].edges.push({ edge: edgeIndex, to: b });
      if (edge.backward)
        nodes[b].edges.push({ edge: edgeIndex, to: a });
    }
  }
  return {
    version: 1,
    profile: "SAR_FOOT",
    bounds,
    nodes,
    edges,
    preparedAt: Date.now(),
    source: "OpenStreetMap via Overpass",
    attribution: "© OpenStreetMap contributors (ODbL)",
  };
}

function nearest(graph, point, maxMeters = 1500) {
  let best = -1,
    bestDistance = Infinity;
  graph.nodes.forEach((node, index) => {
    const d = distanceM(point, node.point);
    if (d < bestDistance) {
      best = index;
      bestDistance = d;
    }
  });
  return bestDistance <= maxMeters
    ? { index: best, distance: bestDistance }
    : null;
}

class MinHeap {
  constructor() {
    this.items = [];
  }
  push(node, score) {
    const item = { node, score };
    let i = this.items.length;
    this.items.push(item);
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (this.items[p].score <= score) break;
      this.items[i] = this.items[p];
      i = p;
      this.items[p] = item;
    }
  }
  pop() {
    const root = this.items[0],
      last = this.items.pop();
    if (this.items.length && last) {
      this.items[0] = last;
      let i = 0;
      while (true) {
        let c = i * 2 + 1;
        if (c >= this.items.length) break;
        if (
          c + 1 < this.items.length &&
          this.items[c + 1].score < this.items[c].score
        )
          c++;
        if (this.items[c].score >= this.items[i].score) break;
        [this.items[i], this.items[c]] = [this.items[c], this.items[i]];
        i = c;
      }
    }
    return root?.node;
  }
  get size() {
    return this.items.length;
  }
}

export function routeTrail(graph, from, to) {
  const start = nearest(graph, from),
    goal = nearest(graph, to);
  if (!start || !goal)
    return {
      code: "OUTSIDE_GRAPH",
      message: "Asal atau target terlalu jauh dari graph jalur",
      startSnap: start?.distance ?? null,
      targetSnap: goal?.distance ?? null,
    };
  const count = graph.nodes.length,
    g = new Float64Array(count);
  g.fill(Infinity);
  const viaNode = new Int32Array(count);
  viaNode.fill(-1);
  const viaEdge = new Int32Array(count);
  viaEdge.fill(-1);
  const closed = new Uint8Array(count);
  const heap = new MinHeap();
  g[start.index] = 0;
  heap.push(
    start.index,
    distanceM(graph.nodes[start.index].point, graph.nodes[goal.index].point) /
      1.2,
  );
  let explored = 0;
  while (heap.size) {
    const current = heap.pop();
    if (closed[current]) continue;
    if (current === goal.index) break;
    closed[current] = 1;
    explored++;
    for (const link of graph.nodes[current].edges) {
      if (closed[link.to]) continue;
      const edge = graph.edges[link.edge];
      const next = g[current] + edge.seconds;
      if (next < g[link.to]) {
        g[link.to] = next;
        viaNode[link.to] = current;
        viaEdge[link.to] = link.edge;
        heap.push(
          link.to,
          next +
            distanceM(
              graph.nodes[link.to].point,
              graph.nodes[goal.index].point,
            ) /
              1.2,
        );
      }
    }
  }
  if (!Number.isFinite(g[goal.index]))
    return {
      code: "NO_ROUTE",
      message: "Tidak ada komponen jalur yang menghubungkan asal dan target",
      startSnap: start.distance,
      targetSnap: goal.distance,
      explored,
    };
  const nodePath = [];
  let cursor = goal.index;
  while (cursor >= 0) {
    nodePath.push(cursor);
    if (cursor === start.index) break;
    cursor = viaNode[cursor];
  }
  nodePath.reverse();
  let networkDistance = 0;
  const used = [];
  for (let i = 1; i < nodePath.length; i++) {
    const edge = graph.edges[viaEdge[nodePath[i]]];
    networkDistance += edge.meters;
    used.push(edge);
  }
  const segments = [];
  for (const edge of used) {
    const key = `${edge.name}|${edge.highway}|${edge.surface}`;
    const current = segments[segments.length - 1];
    if (current?.key === key) {
      current.distance += edge.meters;
      current.seconds += edge.seconds;
    } else
      segments.push({
        key,
        name: edge.name || edge.highway,
        highway: edge.highway,
        surface: edge.surface,
        distance: edge.meters,
        seconds: edge.seconds,
        maneuver: segments.length ? "lanjutkan" : "mulai",
      });
  }
  const offGraph = start.distance + goal.distance;
  const seconds = g[goal.index] + offGraph / 0.65;
  const surfaces = [...new Set(used.map((e) => e.surface))];
  const classes = [...new Set(used.map((e) => e.highway))];
  return {
    code: "OK",
    points: [from, ...nodePath.map((i) => graph.nodes[i].point), to],
    distance: networkDistance + offGraph,
    networkDistance,
    offGraph,
    durationSeconds: seconds,
    startSnap: start.distance,
    targetSnap: goal.distance,
    explored,
    nodeCount: nodePath.length,
    surfaces,
    classes,
    segments,
  };
}

export function graphBoundsFromPolygons(polygons, padding = 0.012) {
  const points = polygons.flat();
  const lats = points.map((p) => p[0]),
    lons = points.map((p) => p[1]);
  return [
    Math.min(...lats) - padding,
    Math.min(...lons) - padding,
    Math.max(...lats) + padding,
    Math.max(...lons) + padding,
  ];
}
