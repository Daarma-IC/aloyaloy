import { buildTrailGraph } from '../trailRouter.js';
import { bearingDeg, distanceM } from '../domain.js';
import { edgeTime, MinHeap, PROFILES, segmentHitsPolygon, inPolygon } from '../terrain/engine.js';

const cache = new WeakMap();
export function graphForPackage(pack) {
  if (cache.has(pack)) return cache.get(pack);
  const nodes = new Map(), ways = [];
  for (const path of pack.mappedPaths || []) {
    if (!path.nodeIds || path.nodeIds.length !== path.points.length) throw Error('Identitas node OSM belum tersedia pada paket jalur');
    path.points.forEach((point, i) => nodes.set(path.nodeIds[i], { type: 'node', id: path.nodeIds[i], lat: point[0], lon: point[1] }));
    ways.push({ type: 'way', id: Number(path.id), nodes: path.nodeIds, tags: path.tags || { highway: path.highway, name: path.name } });
  }
  const graph = buildTrailGraph({ elements: [...nodes.values(), ...ways] }, null);
  cache.set(pack, graph); return graph;
}

export function sampleTerrain(pack, point) {
  if (!inPolygon(point, pack.footprint)) return null;
  const origin = pack.coordinates[0], colPoint = pack.coordinates[1], rowPoint = pack.coordinates[pack.grid.cols];
  const x = point[1] - origin[1], y = point[0] - origin[0], cx = colPoint[1] - origin[1], cy = colPoint[0] - origin[0], rx = rowPoint[1] - origin[1], ry = rowPoint[0] - origin[0], det = cx * ry - cy * rx;
  const col = (x * ry - y * rx) / det, row = (cx * y - cy * x) / det;
  if (row < 0 || col < 0 || row > pack.grid.rows - 1 || col > pack.grid.cols - 1) return null;
  const r0 = Math.floor(row), c0 = Math.floor(col), r1 = Math.min(r0 + 1, pack.grid.rows - 1), c1 = Math.min(c0 + 1, pack.grid.cols - 1), dr = row - r0, dc = col - c0;
  const ids = [r0 * pack.grid.cols + c0, r0 * pack.grid.cols + c1, r1 * pack.grid.cols + c0, r1 * pack.grid.cols + c1];
  if (ids.some(i => pack.elevationM[i] === null)) return null;
  const weights = [(1 - dr) * (1 - dc), (1 - dr) * dc, dr * (1 - dc), dr * dc];
  const nearest = Math.round(row) * pack.grid.cols + Math.round(col);
  return { elevationM: ids.reduce((z, i, j) => z + pack.elevationM[i] * weights[j], 0), landCover: pack.landCover[nearest], blocked: pack.blocked[nearest] };
}

export function demoMappedCoordinates(pack) {
  const graph = graphForPackage(pack), valid = graph.nodes.map(n => !!sampleTerrain(pack, n.point));
  const seen = new Set(), components = [];
  graph.nodes.forEach((_, i) => {
    if (!valid[i] || seen.has(i)) return;
    const queue = [i]; seen.add(i);
    for (let k = 0; k < queue.length; k++) for (const link of graph.nodes[queue[k]].edges) if (valid[link.to] && !seen.has(link.to)) { seen.add(link.to); queue.push(link.to); }
    components.push(queue);
  });
  components.sort((a, b) => b.length - a.length);
  const candidates = components[0]; if (!candidates?.length) throw Error('Tidak ada jaringan jalur OSM yang tercakup DEM');
  const closest = seed => candidates.reduce((best, i) => distanceM(graph.nodes[i].point, seed) < distanceM(graph.nodes[best].point, seed) ? i : best, candidates[0]);
  return { from: graph.nodes[closest(pack.demoStart)].point, to: graph.nodes[closest(pack.demoTarget)].point, candidates: candidates.map(i => graph.nodes[i].point) };
}

function snap(graph, position) {
  let best = null;
  const cos = Math.cos(position[0] * Math.PI / 180), scale = Math.PI / 180 * 6371000;
  graph.edges.forEach((edge, index) => {
    const a = graph.nodes[edge.a].point, b = graph.nodes[edge.b].point;
    const ax = (a[1] - position[1]) * cos * scale, ay = (a[0] - position[0]) * scale, dx = (b[1] - a[1]) * cos * scale, dy = (b[0] - a[0]) * scale;
    const length2 = dx * dx + dy * dy; if (!length2) return;
    const fraction = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / length2)), point = [a[0] + fraction * (b[0] - a[0]), a[1] + fraction * (b[1] - a[1])], distance = distanceM(position, point);
    if (!best || distance < best.distance) best = { edgeIndex: index, fraction, point, distance };
  });
  return best;
}

export function routeMapped({ pack, from, to, profileId = 'nominal', hazards = [], hazardVersion = 0, targetPositionTimestamp = null }) {
  const began = performance.now(), fail = (status, message) => ({ status, message, engine: 'MappedOSM', geometry: [] });
  let graph; try { graph = graphForPackage(pack); } catch (e) { return fail('missingData', e.message); }
  const profile = PROFILES[profileId]; if (!profile) return fail('unavailable', 'Profil tidak dikenal');
  const start = snap(graph, from), goal = snap(graph, to);
  // Do not fabricate a straight access leg between an off-network person and a road.
  if (!start || !goal || start.distance > 3 || goal.distance > 3) return fail('missingData', 'Posisi berada di luar jalur OSM. Pilih lintasan medan secara terpisah; akses ke jalur tidak diasumsikan dapat dilalui.');
  const points = [...graph.nodes.map(n => n.point), start.point, goal.point], n = graph.nodes.length, goalIndex = n + 1;
  const adjacency = points.map(() => []), terrain = points.map(p => sampleTerrain(pack, p));
  function connect(a, b, edgeIndex) {
    const edge = graph.edges[edgeIndex], d = distanceM(points[a], points[b]);
    if (!terrain[a] || !terrain[b] || terrain[a].blocked || terrain[b].blocked) return;
    if (hazards.some(h => h.blockingPolicy === 'block' && segmentHitsPolygon(points[a], points[b], h.polygon))) return;
    const dz = terrain[b].elevationM - terrain[a].elevationM;
    const walk = d < 1e-6 ? 0 : edgeTime(d, dz, 60, 60, profile);
    if (walk === null) return;
    const seconds = walk * edge.seconds / (edge.meters * 0.72);
    adjacency[a].push({ to: b, distanceM: d, seconds, edgeIndex });
  }
  graph.nodes.forEach((node, a) => node.edges.forEach(link => connect(a, link.to, link.edge)));
  const sEdge = graph.edges[start.edgeIndex], gEdge = graph.edges[goal.edgeIndex];
  if (sEdge.backward || start.fraction < 1e-8) connect(n, sEdge.a, start.edgeIndex);
  if (sEdge.forward || start.fraction > 1 - 1e-8) connect(n, sEdge.b, start.edgeIndex);
  if (gEdge.forward || goal.fraction < 1e-8) connect(gEdge.a, goalIndex, goal.edgeIndex);
  if (gEdge.backward || goal.fraction > 1 - 1e-8) connect(gEdge.b, goalIndex, goal.edgeIndex);
  if (start.edgeIndex === goal.edgeIndex && ((start.fraction <= goal.fraction && sEdge.forward) || (start.fraction >= goal.fraction && sEdge.backward))) connect(n, goalIndex, start.edgeIndex);
  const costs = new Float64Array(points.length); costs.fill(Infinity); costs[n] = 0;
  const parents = new Int32Array(points.length); parents.fill(-1); const via = [], heap = new MinHeap(); heap.push(n, 0); let explored = 0;
  while (heap.items.length) {
    const item = heap.pop(); if (item.cost !== costs[item.index]) continue; explored++; if (item.index === goalIndex) break;
    for (const link of adjacency[item.index]) { const cost = item.cost + link.seconds; if (cost < costs[link.to]) { costs[link.to] = cost; parents[link.to] = item.index; via[link.to] = link; heap.push(link.to, cost); } }
  }
  if (!Number.isFinite(costs[goalIndex])) return fail('noTraversablePath', 'Jaringan jalur tidak terhubung atau semua akses memenuhi hambatan/batas profil. Tidak dibuat jalan pintas melewati medan.');
  const indices = []; for (let i = goalIndex; i !== -1; i = parents[i]) indices.push(i); indices.reverse();
  const geometry = indices.map(i => points[i]), segments = [], elevationProfile = [{ distanceM: 0, elevationM: terrain[n].elevationM }];
  let length = 0, length3d = 0, durationEstimate = 0, ascent = 0, descent = 0;
  for (let i = 1; i < indices.length; i++) {
    const a = indices[i - 1], b = indices[i], link = via[b], dz = terrain[b].elevationM - terrain[a].elevationM;
    length += link.distanceM; durationEstimate += link.seconds; length3d += Math.hypot(link.distanceM, dz); ascent += Math.max(dz, 0); descent += Math.max(-dz, 0);
    segments.push({ ...link, osmWayId: graph.edges[link.edgeIndex].osmWayId, name: graph.edges[link.edgeIndex].name || 'Jalur pejalan kaki', bearingDeg: bearingDeg(points[a], points[b]), cumulativeDistanceM: length, cumulativeSeconds: durationEstimate });
    elevationProfile.push({ distanceM: length, elevationM: terrain[b].elevationM });
  }
  return { status: 'available', engine: 'MappedOSM', geometry, segments, length, length3d, durationEstimate, ascent, descent, elevationProfile, explored, computeMs: performance.now() - began, packageId: pack.id, packageVersion: pack.datasetVersion, hazardVersion, profileId, targetPositionTimestamp, modelVersion: 'osm-foot-surface-dem-v1', generatedAt: Date.now(), startSnapM: start.distance, targetSnapM: goal.distance, warnings: ['Geometry mengikuti ruas dan node OSM asli; tidak disederhanakan ke grid DEM.', 'ETA memakai kelas/surface jalur dan grade DEM; belum dikalibrasi lapangan.'] };
}
