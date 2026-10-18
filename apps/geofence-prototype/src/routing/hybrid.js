import { distanceM, bearingDeg } from '../domain.js';
import { validatePackage, edgeTime, PROFILES, MinHeap, inPolygon, segmentHitsPolygon } from '../terrain/engine.js';
import { graphForPackage, demoMappedCoordinates } from './mapped.js';

export const HYBRID_MODEL = 'osm-terrain-langmuir-v1';
const validPoint = p => Array.isArray(p) && p.length === 2 && p.every(Number.isFinite) && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180;
const interpolate = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];

// Work in grid coordinates so every crossed raster cell is checked, including
// both sides of boundaries and all four cells at a diagonal corner.
export function terrainContext(pack, hazards = [], profile = PROFILES.nominal) {
  const { rows, cols } = pack.grid, o = pack.coordinates[0], c = pack.coordinates[1], r = pack.coordinates[cols];
  const cx = c[1] - o[1], cy = c[0] - o[0], rx = r[1] - o[1], ry = r[0] - o[0], det = cx * ry - cy * rx;
  if (Math.abs(det) < 1e-20) throw Error('Basis grid tidak valid');
  const gridPoint = p => { const x = p[1] - o[1], y = p[0] - o[0]; return [(x * ry - y * rx) / det, (cx * y - cy * x) / det]; };
  const obstacles = [...hazards.filter(h => h.blockingPolicy === 'block'), ...(pack.obstacles || [])].map(h => {
    if (!Array.isArray(h.polygon) || h.polygon.length < 3 || !h.polygon.every(validPoint)) throw Error('Polygon hambatan tidak valid');
    return { ...h, bounds: [Math.min(...h.polygon.map(p => p[0])), Math.min(...h.polygon.map(p => p[1])), Math.max(...h.polygon.map(p => p[0])), Math.max(...h.polygon.map(p => p[1]))] };
  });
  const obstacleBins = new Map();
  function gridBox(a, b) {
    const corners = [[a[0], a[1]], [a[0], b[1]], [b[0], a[1]], [b[0], b[1]]].map(gridPoint);
    return [Math.max(0, Math.floor(Math.min(...corners.map(g => g[0])) - .5)), Math.max(0, Math.floor(Math.min(...corners.map(g => g[1])) - .5)), Math.min(cols - 1, Math.ceil(Math.max(...corners.map(g => g[0])) + .5)), Math.min(rows - 1, Math.ceil(Math.max(...corners.map(g => g[1])) + .5))];
  }
  obstacles.forEach((h, i) => {
    const [x0, y0, x1, y1] = gridBox([h.bounds[0], h.bounds[1]], [h.bounds[2], h.bounds[3]]);
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) { const key = y * cols + x; if (!obstacleBins.has(key)) obstacleBins.set(key, []); obstacleBins.get(key).push(i); }
  });
  function obstacleHit(a, b, road) {
    const loY = Math.min(a[0], b[0]), hiY = Math.max(a[0], b[0]), loX = Math.min(a[1], b[1]), hiX = Math.max(a[1], b[1]);
    const [x0, y0, x1, y1] = gridBox(a, b), candidates = new Set();
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) for (const i of obstacleBins.get(y * cols + x) || []) candidates.add(i);
    return [...candidates].some(i => { const h = obstacles[i]; return !(road && h.kind === 'building' && road.tags.tunnel && road.tags.tunnel !== 'no') && h.bounds[0] <= hiY && h.bounds[2] >= loY && h.bounds[1] <= hiX && h.bounds[3] >= loX && segmentHitsPolygon(a, b, h.polygon); });
  }
  function cellsAt(x, y) {
    const xs = [Math.floor(x + .5)], ys = [Math.floor(y + .5)];
    if (Math.abs(x + .5 - Math.round(x + .5)) < 1e-8) xs.push(xs[0] - 1);
    if (Math.abs(y + .5 - Math.round(y + .5)) < 1e-8) ys.push(ys[0] - 1);
    return ys.flatMap(row => xs.map(col => row >= 0 && row < rows && col >= 0 && col < cols ? row * cols + col : -1));
  }
  function sample(p) {
    const [x, y] = gridPoint(p);
    if (!inPolygon(p, pack.footprint) || x < -.5 - 1e-8 || y < -.5 - 1e-8 || x > cols - .5 + 1e-8 || y > rows - .5 + 1e-8) return null;
    const col = Math.max(0, Math.min(cols - 1, x)), row = Math.max(0, Math.min(rows - 1, y));
    const c0 = Math.floor(col), r0 = Math.floor(row), c1 = Math.min(cols - 1, c0 + 1), r1 = Math.min(rows - 1, r0 + 1), dx = col - c0, dy = row - r0;
    const ids = [r0 * cols + c0, r0 * cols + c1, r1 * cols + c0, r1 * cols + c1], weights = [(1 - dx) * (1 - dy), dx * (1 - dy), (1 - dx) * dy, dx * dy];
    if (ids.some((id, i) => weights[i] > 1e-10 && pack.elevationM[id] === null)) return null;
    return { elevationM: ids.reduce((z, id, i) => z + (pack.elevationM[id] ?? 0) * weights[i], 0), landCover: pack.landCover[Math.round(row) * cols + Math.round(col)] };
  }
  function inspect(a, b, road = null) {
    if (!sample(a) || !sample(b) || obstacleHit(a, b, road)) return null;
    const ga = gridPoint(a), gb = gridPoint(b), times = [0, 1];
    for (let axis = 0; axis < 2; axis++) {
      const delta = gb[axis] - ga[axis]; if (Math.abs(delta) < 1e-12) continue;
      const min = Math.min(ga[axis], gb[axis]), max = Math.max(ga[axis], gb[axis]);
      for (let k = Math.ceil(min - .5); k + .5 < max; k++) { const t = (k + .5 - ga[axis]) / delta; if (t > 1e-10 && t < 1 - 1e-10) times.push(t); }
      // Elevation interpolation changes slope at cell centres as well as at
      // boundaries. Sampling both avoids averaging a steep peak into a flat leg.
      for (let k = Math.ceil(min); k < max; k++) { const t = (k - ga[axis]) / delta; if (t > 1e-10 && t < 1 - 1e-10) times.push(t); }
    }
    times.sort((x, y) => x - y);
    const ts = times.filter((t, i) => !i || t - times[i - 1] > 1e-10), crossed = new Set();
    for (let i = 0; i < ts.length; i++) for (const t of [ts[i], ...(i ? [(ts[i - 1] + ts[i]) / 2] : [])]) {
      const g = [ga[0] + (gb[0] - ga[0]) * t, ga[1] + (gb[1] - ga[1]) * t]; cellsAt(...g).forEach(id => crossed.add(id));
    }
    const elevated = road && ((road.tags.bridge && road.tags.bridge !== 'no') || (road.tags.tunnel && road.tags.tunnel !== 'no'));
    for (const id of crossed) {
      if (id < 0 || pack.blocked[id] || pack.elevationM[id] === null || ![10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 100].includes(pack.landCover[id])) return null;
      if (pack.landCover[id] === 80 && !elevated) return null;
      if (!road && pack.landCover[id] !== 80 && edgeTime(1, 0, pack.landCover[id], pack.landCover[id], profile) === null) return null;
    }
    let seconds = 0, ascent = 0, descent = 0, length3d = 0;
    for (let i = 1; i < ts.length; i++) {
      const p = interpolate(a, b, ts[i - 1]), q = interpolate(a, b, ts[i]), sp = sample(p), sq = sample(q), mid = sample(interpolate(p, q, .5));
      if (!sp || !sq || !mid) return null;
      const d = distanceM(p, q), dz = sq.elevationM - sp.elevationM;
      const t = d < 1e-7 ? 0 : edgeTime(d, dz, road ? 60 : mid.landCover, road ? 60 : mid.landCover, profile);
      if (t === null) return null;
      seconds += road ? t * road.seconds / (road.meters * .72) : t;
      ascent += Math.max(0, dz); descent += Math.max(0, -dz); length3d += Math.hypot(d, dz);
    }
    return { seconds, distanceM: distanceM(a, b), ascent, descent, length3d, crossedCells: [...crossed] };
  }
  return { gridPoint, sample, inspect, cellsAt };
}

function projection(a, b, p) {
  const cos = Math.cos(p[0] * Math.PI / 180), ax = (a[1] - p[1]) * cos, ay = a[0] - p[0], dx = (b[1] - a[1]) * cos, dy = b[0] - a[0], l = dx * dx + dy * dy;
  const fraction = l ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l)) : 0;
  const point = interpolate(a, b, fraction); return { point, fraction, distance: distanceM(p, point) };
}

export function demoHybridCoordinates(pack) {
  const context = terrainContext(pack), graph = graphForPackage(pack);
  const base = graph.edges.length ? demoMappedCoordinates(pack) : { from: pack.demoStart, to: pack.demoTarget };
  const candidates = pack.coordinates.filter(p => distanceM(p, pack.demoTarget) < 300 && context.inspect(p, p)).map(point => ({ point, distance: graph.edges.reduce((best, e) => Math.min(best, projection(graph.nodes[e.a].point, graph.nodes[e.b].point, point).distance), Infinity) })).filter(p => p.distance >= 60 && p.distance < 200).sort((a, b) => distanceM(a.point, pack.demoTarget) - distanceM(b.point, pack.demoTarget));
  return { from: base.from, to: candidates[0]?.point || base.to, candidates: candidates.map(c => c.point) };
}

export function routeHybrid({ pack, from, to, profileId = 'nominal', hazards = [], hazardVersion = 0, targetPositionTimestamp = null, terrainPenaltySecondsPerM = 3 }) {
  const began = performance.now(), fail = (status, message) => ({ status, message, engine: 'Hybrid', geometry: [], warnings: [message] });
  try {
    validatePackage(pack);
    const profile = PROFILES[profileId];
    if (!profile || !validPoint(from) || !validPoint(to) || !Number.isFinite(terrainPenaltySecondsPerM) || terrainPenaltySecondsPerM < 0) return fail('unavailable', 'Posisi, profil, atau penalti medan tidak valid');
    const context = terrainContext(pack, hazards, profile), graph = graphForPackage(pack), { rows, cols } = pack.grid;
    if (!context.sample(from) || !context.sample(to)) return fail('missingData', 'Endpoint di luar paket atau pada elevasi NoData');
    const n = pack.coordinates.length, points = [...pack.coordinates, ...graph.nodes.map(node => node.point)], adjacency = points.map(() => []);
    const addPoint = p => { points.push(p); adjacency.push([]); return points.length - 1; };
    function connect(a, b, kind, edgeIndex = null) {
      const road = kind === 'osm' ? graph.edges[edgeIndex] : null, info = context.inspect(points[a], points[b], road);
      if (!info) return false;
      adjacency[a].push({ to: b, kind, edgeIndex, ...(road ? { osmWayId: road.osmWayId, name: road.name || 'Jalur OSM' } : { name: kind === 'transition' ? 'Transisi jalan–medan' : 'Lintasan medan' }), ...info, weight: info.seconds + (road ? 0 : info.distanceM * terrainPenaltySecondsPerM) }); return true;
    }
    // Index road segments by raster-cell bounding boxes. Projections are never
    // road geometry replacements: each anchor splits the original OSM edge.
    const bins = new Map(), anchors = graph.edges.map(edge => [{ fraction: 0, index: n + edge.a }, { fraction: 1, index: n + edge.b }]);
    graph.edges.forEach((edge, ei) => {
      const a = context.gridPoint(points[n + edge.a]), b = context.gridPoint(points[n + edge.b]);
      for (let row = Math.max(0, Math.floor(Math.min(a[1], b[1]) - 1)); row <= Math.min(rows - 1, Math.ceil(Math.max(a[1], b[1]) + 1)); row++)
        for (let col = Math.max(0, Math.floor(Math.min(a[0], b[0]) - 1)); col <= Math.min(cols - 1, Math.ceil(Math.max(a[0], b[0]) + 1)); col++) { const key = row * cols + col; if (!bins.has(key)) bins.set(key, []); bins.get(key).push(ei); }
    });
    function nearby(p) {
      const [x, y] = context.gridPoint(p), col = Math.max(0, Math.min(cols - 1, Math.round(x))), row = Math.max(0, Math.min(rows - 1, Math.round(y)));
      return (bins.get(row * cols + col) || []).map(ei => ({ ei, ...projection(points[n + graph.edges[ei].a], points[n + graph.edges[ei].b], p) })).filter(s => s.distance <= pack.grid.resolutionM * .8).sort((a, b) => a.distance - b.distance);
    }
    function anchor(s) {
      const found = anchors[s.ei].find(a => Math.abs(a.fraction - s.fraction) < 1e-9); if (found) return found.index;
      const index = addPoint(s.point); anchors[s.ei].push({ index, fraction: s.fraction }); return index;
    }
    function canLeaveRoad(s) {
      const edge = graph.edges[s.ei], elevated = e => e.tags.bridge && e.tags.bridge !== 'no' || e.tags.tunnel && e.tags.tunnel !== 'no';
      if (!elevated(edge)) return true;
      // A crossing in XY is not a junction in 3D. Only allow entry/exit of
      // elevated/underground ways at a shared node with a surface road.
      const node = s.fraction < 1e-9 ? edge.a : s.fraction > 1 - 1e-9 ? edge.b : null;
      return node !== null && graph.edges.some(e => (e.a === node || e.b === node) && !elevated(e));
    }
    for (let a = 0; a < n; a++) {
      if (!context.inspect(points[a], points[a])) continue;
      const row = Math.floor(a / cols), col = a % cols;
      for (let dr = -1; dr <= 1; dr++) for (let dc = -1; dc <= 1; dc++) {
        if ((!dr && !dc) || row + dr < 0 || row + dr >= rows || col + dc < 0 || col + dc >= cols) continue;
        connect(a, (row + dr) * cols + col + dc, 'terrain');
      }
      // Connect every nearby segment that passes checks, rather than attaching
      // to only the nearest road across an obstacle or disconnected component.
      for (const s of nearby(points[a])) {
        if (!canLeaveRoad(s) || !context.inspect(points[a], s.point)) continue;
        const b = anchor(s); connect(a, b, 'transition'); connect(b, a, 'transition');
      }
    }
    function endpoint(p) {
      const index = addPoint(p), near = nearby(p), exact = near.filter(s => s.distance < 1e-5);
      if (exact.length) { for (const s of exact) { const roadIndex = anchor(s); connect(index, roadIndex, 'osm', s.ei); connect(roadIndex, index, 'osm', s.ei); } return index; }
      const [x, y] = context.gridPoint(p);
      for (let row = Math.max(0, Math.floor(y) - 1); row <= Math.min(rows - 1, Math.ceil(y) + 1); row++)
        for (let col = Math.max(0, Math.floor(x) - 1); col <= Math.min(cols - 1, Math.ceil(x) + 1); col++) { const cell = row * cols + col; connect(index, cell, 'terrain'); connect(cell, index, 'terrain'); }
      for (const s of near) if (canLeaveRoad(s) && context.inspect(p, s.point)) { const roadIndex = anchor(s); connect(index, roadIndex, 'transition'); connect(roadIndex, index, 'transition'); }
      return index;
    }
    const start = endpoint(from), goal = endpoint(to);
    anchors.forEach((list, ei) => { list.sort((a, b) => a.fraction - b.fraction); for (let i = 1; i < list.length; i++) { if (graph.edges[ei].forward) connect(list[i - 1].index, list[i].index, 'osm', ei); if (graph.edges[ei].backward) connect(list[i].index, list[i - 1].index, 'osm', ei); } });
    const costs = new Float64Array(points.length); costs.fill(Infinity); costs[start] = 0;
    const parent = new Int32Array(points.length); parent.fill(-1); const via = [], heap = new MinHeap(); heap.push(start, 0); let explored = 0;
    while (heap.items.length) {
      const item = heap.pop(); if (item.cost !== costs[item.index]) continue; explored++; if (item.index === goal) break;
      for (const link of adjacency[item.index]) { const cost = item.cost + link.weight; if (cost < costs[link.to]) { costs[link.to] = cost; parent[link.to] = item.index; via[link.to] = link; heap.push(link.to, cost); } }
    }
    if (!Number.isFinite(costs[goal])) return fail('noTraversablePath', 'Tidak ada lintasan jalan–medan yang memenuhi constraint data, hambatan, dan profil tim.');
    const indices = []; for (let i = goal; i !== -1; i = parent[i]) indices.push(i); indices.reverse();
    const geometry = [from], segments = [], elevationProfile = [{ distanceM: 0, elevationM: context.sample(from).elevationM }], lengthsByKind = { osm: 0, terrain: 0, transition: 0 };
    let length = 0, length3d = 0, durationEstimate = 0, ascent = 0, descent = 0;
    for (let i = 1; i < indices.length; i++) {
      const link = via[indices[i]], p = points[indices[i]]; if (link.distanceM < 1e-7) continue;
      const previous = geometry.at(-1); geometry.push(p); length += link.distanceM; length3d += link.length3d; durationEstimate += link.seconds; ascent += link.ascent; descent += link.descent; lengthsByKind[link.kind] += link.distanceM;
      const { to: _, crossedCells, ...segment } = link;
      segments.push({ ...segment, bearingDeg: bearingDeg(previous, p), cumulativeDistanceM: length, cumulativeSeconds: durationEstimate });
      elevationProfile.push({ distanceM: length, elevationM: context.sample(p).elevationM });
    }
    if (geometry.length === 1) { geometry.push(to); segments.push({ kind: 'terrain', name: 'Sudah di koordinat tujuan', distanceM: 0, seconds: 0, cumulativeDistanceM: 0, cumulativeSeconds: 0, bearingDeg: 0 }); }
    return { status: 'available', engine: 'Hybrid', geometry, segments, length, length3d, durationEstimate, ascent, descent, lengthsByKind, elevationProfile, routingWeight: costs[goal], terrainPenaltySecondsPerM, explored, computeMs: performance.now() - began, packageId: pack.id, packageVersion: pack.datasetVersion, modelVersion: HYBRID_MODEL, profileId, hazardVersion, targetPositionTimestamp, generatedAt: Date.now(), warnings: [pack.synthetic ? 'Data uji sintetis.' : 'Data OSM dan raster nyata; keterlintasan belum diverifikasi lapangan.', 'ETA Langmuir + friction asumsi; penalti preferensi medan tidak termasuk ETA.', 'Detail subsel dan perubahan kondisi lapangan dapat tidak terekam; bukan model LiDAR STRIDE.'] };
  } catch (e) { return fail('missingData', e.message); }
}
