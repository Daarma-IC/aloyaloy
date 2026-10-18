import { bearingDeg, distanceM, pointInPolygon } from '../domain.js';

export const MODEL_VERSION = 'langmuir-friction-v1';
export const PROFILES = {
  nominal: { id: 'nominal', label: 'Tim jalan kaki · nominal', speedFactor: 1, maxSlopeDeg: 35 },
  equipment: { id: 'equipment', label: 'Membawa peralatan · konservatif', speedFactor: 0.7, maxSlopeDeg: 25 },
};
export const LAND_CLASSES = { 10: 'Hutan', 20: 'Semak', 30: 'Rumput', 40: 'Pertanian', 50: 'Terbangun', 60: 'Terbuka', 70: 'Salju', 80: 'Air', 90: 'Lahan basah', 95: 'Mangrove', 100: 'Lumut' };
// Assumed additional seconds/metre, not calibrated SAR measurements.
const FRICTION = { 10: 0.65, 20: 0.9, 30: 0.1, 40: 0.2, 50: 0.2, 60: 0, 70: 1.2, 90: 1.5, 95: 1.8, 100: 0.3 };
const validPoint = p => Array.isArray(p) && p.length === 2 && Number.isFinite(p[0]) && Number.isFinite(p[1]) && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180;

export function validatePackage(pack) {
  if (!pack || pack.schemaVersion !== 1 || !pack.id || !pack.datasetVersion) throw new Error('Paket harus memiliki schemaVersion=1, id, dan datasetVersion');
  const { rows, cols, resolutionM } = pack.grid || {};
  if (!Number.isInteger(rows) || !Number.isInteger(cols) || rows < 2 || cols < 2 || rows * cols > 100000 || !(resolutionM >= 1)) throw new Error('Grid tidak valid atau melebihi 100.000 sel');
  const n = rows * cols;
  for (const key of ['coordinates', 'elevationM', 'landCover', 'blocked']) if (!Array.isArray(pack[key]) || pack[key].length !== n) throw new Error(`Jumlah ${key} tidak cocok dengan grid`);
  if (pack.coordinates.some(p => !validPoint(p))) throw new Error('Koordinat grid tidak valid');
  if (pack.elevationM.some(z => z !== null && !Number.isFinite(z))) throw new Error('Elevasi harus numerik atau null (NoData)');
  if (pack.landCover.some(c => c !== null && !Number.isInteger(c))) throw new Error('Tutupan lahan harus kategori integer atau null');
  if (pack.blocked.some(b => typeof b !== 'boolean')) throw new Error('Mask blocked harus boolean');
  if (pack.trailMask && (pack.trailMask.length !== n || pack.trailMask.some(x => typeof x !== 'boolean'))) throw new Error('Mask jalur OSM tidak valid');
  if (pack.trailPreferenceSecondsPerM != null && (!Number.isFinite(pack.trailPreferenceSecondsPerM) || pack.trailPreferenceSecondsPerM < 0)) throw new Error('Penalti preferensi jalur tidak valid');
  if (!Array.isArray(pack.footprint) || pack.footprint.length < 3 || pack.footprint.some(p => !validPoint(p))) throw new Error('Footprint wilayah tidak valid');
  if (!pack.sources?.length || !pack.crs || !pack.verticalDatum || typeof pack.synthetic !== 'boolean') throw new Error('Metadata sumber, CRS, datum, dan synthetic wajib tersedia');
  // Reject torn grids, duplicate centres and accidental latitude/longitude swaps in neighbour spacing.
  for (let i = 0; i < n; i++) {
    for (const j of [i % cols < cols - 1 ? i + 1 : -1, i + cols < n ? i + cols : -1]) {
      if (j < 0) continue;
      const d = distanceM(pack.coordinates[i], pack.coordinates[j]);
      if (d < resolutionM * 0.5 || d > resolutionM * 1.5) throw new Error('Jarak pusat sel tidak konsisten dengan resolutionM');
    }
  }
  return pack;
}

function orientation(a, b, c) { return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]); }
function onSegment(p, a, b) { return Math.abs(orientation(a, b, p)) < 1e-14 && p[0] >= Math.min(a[0], b[0]) - 1e-12 && p[0] <= Math.max(a[0], b[0]) + 1e-12 && p[1] >= Math.min(a[1], b[1]) - 1e-12 && p[1] <= Math.max(a[1], b[1]) + 1e-12; }
function intersects(a, b, c, d) {
  const x = orientation(a, b, c), y = orientation(a, b, d), z = orientation(c, d, a), w = orientation(c, d, b);
  return (x * y < 0 && z * w < 0) || onSegment(c, a, b) || onSegment(d, a, b) || onSegment(a, c, d) || onSegment(b, c, d);
}
export function inPolygon(p, polygon) { return pointInPolygon(p, polygon) || polygon.some((a, i) => onSegment(p, a, polygon[(i + 1) % polygon.length])); }
export function segmentHitsPolygon(a, b, polygon) { return inPolygon(a, polygon) || inPolygon(b, polygon) || polygon.some((p, i) => intersects(a, b, p, polygon[(i + 1) % polygon.length])); }
export function routeHitsHazards(points, hazards) { return hazards.filter(h => h.blockingPolicy === 'block' && points.slice(1).some((b, i) => segmentHitsPolygon(points[i], b, h.polygon))); }

export function edgeTime(d, dz, fromClass, toClass, profile = PROFILES.nominal) {
  const grade = dz / d;
  if (!(d > 0) || Math.abs(Math.atan(grade) * 180 / Math.PI) > profile.maxSlopeDeg || FRICTION[fromClass] == null || FRICTION[toClass] == null) return null;
  const coefficient = grade >= 0 ? 6 : grade < -0.2125 ? -1.9998 : 1.9998;
  const walking = 0.72 * d + coefficient * dz;
  const seconds = (walking + d * (FRICTION[fromClass] + FRICTION[toClass]) / 2) / profile.speedFactor;
  return seconds > 0 && Number.isFinite(seconds) ? seconds : null;
}

class Heap {
  items = [];
  push(index, cost) {
    const item = { index, cost }; let i = this.items.length; this.items.push(item);
    while (i > 0) { const p = (i - 1) >> 1; if (this.items[p].cost <= cost) break; this.items[i] = this.items[p]; i = p; } this.items[i] = item;
  }
  pop() {
    const root = this.items[0], last = this.items.pop(); if (this.items.length) { let i = 0; this.items[0] = last;
      while (true) { let c = i * 2 + 1; if (c >= this.items.length) break; if (c + 1 < this.items.length && this.items[c + 1].cost < this.items[c].cost) c++; if (this.items[c].cost >= last.cost) break; this.items[i] = this.items[c]; i = c; this.items[i] = last; }
    } return root;
  }
}
export { Heap as MinHeap };

export function routeTerrain({ pack, from, to, profileId = 'nominal', hazards = [], hazardVersion = 0, targetPositionTimestamp = null, objective = 'time' }) {
  const started = performance.now();
  const fail = (status, message) => ({ status, message, engine: 'Terrain', geometry: [], warnings: [message] });
  try { validatePackage(pack); } catch (e) { return fail('missingData', e.message); }
  if (!validPoint(from) || !validPoint(to)) return fail('unavailable', 'Posisi awal atau target tidak valid');
  const profile = PROFILES[profileId];
  if (!profile || !['time', 'distance'].includes(objective)) return fail('unavailable', 'Profil atau objective tidak dikenal');
  if (!inPolygon(from, pack.footprint) || !inPolygon(to, pack.footprint)) return fail('missingData', 'Posisi berada di luar paket wilayah');
  const n = pack.coordinates.length, cols = pack.grid.cols;
  const blocked = pack.blocked.map((b, i) => b || pack.landCover[i] === 80 || hazards.some(h => h.blockingPolicy === 'block' && inPolygon(pack.coordinates[i], h.polygon)));
  const missing = i => pack.elevationM[i] === null || FRICTION[pack.landCover[i]] == null && pack.landCover[i] !== 80;
  function nearest(p) { let best = -1, dist = Infinity; pack.coordinates.forEach((q, i) => { const d = distanceM(p, q); if (d < dist) { dist = d; best = i; } }); return { index: best, distance: dist }; }
  const start = nearest(from), goal = nearest(to);
  if (start.distance > pack.grid.resolutionM * 0.8 || goal.distance > pack.grid.resolutionM * 0.8) return fail('missingData', 'Endpoint tidak tercakup pusat grid');
  if (missing(start.index) || missing(goal.index)) return fail('missingData', 'Endpoint berada pada elevasi/tutupan lahan NoData');
  if (blocked[start.index] || blocked[goal.index]) return fail('noTraversablePath', 'Endpoint berada pada sel yang dilarang');
  // Endpoint connectors stay in their own cell, but are still checked against every hazard.
  if (hazards.some(h => h.blockingPolicy === 'block' && (segmentHitsPolygon(from, pack.coordinates[start.index], h.polygon) || segmentHitsPolygon(to, pack.coordinates[goal.index], h.polygon)))) return fail('noTraversablePath', 'Konektor endpoint memotong zona larangan');
  const costs = new Float64Array(n); costs.fill(Infinity); const parent = new Int32Array(n); parent.fill(-1); const heap = new Heap(); costs[start.index] = 0; heap.push(start.index, 0);
  let explored = 0;
  while (heap.items.length) {
    const current = heap.pop(); if (current.cost !== costs[current.index]) continue;
    const a = current.index; explored++; if (a === goal.index) break;
    const row = Math.floor(a / cols), col = a % cols;
    for (let dr = -1; dr <= 1; dr++) for (let dc = -1; dc <= 1; dc++) {
      if (!dr && !dc || row + dr < 0 || row + dr >= pack.grid.rows || col + dc < 0 || col + dc >= cols) continue;
      const b = (row + dr) * cols + col + dc;
      if (blocked[b] || missing(b)) continue;
      if (dr && dc && [a + dc, a + dr * cols].some(i => blocked[i] || missing(i))) continue;
      if (hazards.some(h => h.blockingPolicy === 'block' && segmentHitsPolygon(pack.coordinates[a], pack.coordinates[b], h.polygon))) continue;
      const d = distanceM(pack.coordinates[a], pack.coordinates[b]), seconds = edgeTime(d, pack.elevationM[b] - pack.elevationM[a], pack.landCover[a], pack.landCover[b], profile);
      if (seconds === null) continue;
      const preference = pack.trailMask && !(pack.trailMask[a] && pack.trailMask[b]) ? d * (pack.trailPreferenceSecondsPerM || 0) : 0;
      const candidate = current.cost + (objective === 'distance' ? d : seconds + preference);
      if (candidate < costs[b]) { costs[b] = candidate; parent[b] = a; heap.push(b, candidate); }
    }
  }
  if (!Number.isFinite(costs[goal.index])) return fail('noTraversablePath', 'Tidak ada lintasan yang memenuhi constraint; sel NoData ditolak');
  const cells = []; for (let i = goal.index; i !== -1; i = parent[i]) cells.push(i); cells.reverse();
  const geometry = [from, ...cells.map(i => pack.coordinates[i]), to];
  const elevations = [pack.elevationM[start.index], ...cells.map(i => pack.elevationM[i]), pack.elevationM[goal.index]];
  const classes = [pack.landCover[start.index], ...cells.map(i => pack.landCover[i]), pack.landCover[goal.index]];
  const segments = [], elevationProfile = [{ distanceM: 0, elevationM: elevations[0] }], coverLengths = {};
  let length = 0, length3d = 0, duration = 0, ascent = 0, descent = 0, maxGrade = 0;
  for (let i = 1; i < geometry.length; i++) {
    const d = distanceM(geometry[i - 1], geometry[i]), dz = elevations[i] - elevations[i - 1];
    const t = d < 1e-6 ? 0 : edgeTime(d, dz, classes[i - 1], classes[i], profile);
    if (t === null) return fail('noTraversablePath', 'Segmen akhir tidak memenuhi profil');
    length += d; length3d += Math.hypot(d, dz); duration += t; ascent += Math.max(0, dz); descent += Math.max(0, -dz); maxGrade = Math.max(maxGrade, d > 0 ? Math.abs(dz / d) : 0);
    coverLengths[classes[i]] = (coverLengths[classes[i]] || 0) + d;
    segments.push({ distanceM: d, seconds: t, bearingDeg: bearingDeg(geometry[i - 1], geometry[i]), cumulativeDistanceM: length, cumulativeSeconds: duration });
    elevationProfile.push({ distanceM: length, elevationM: elevations[i] });
  }
  return { status: 'available', engine: 'Terrain', geometry, cells, segments, length, length3d, durationEstimate: duration, routingWeight: costs[goal.index], trailPreferenceSecondsPerM: pack.trailPreferenceSecondsPerM || 0, ascent, descent, elevationProfile, coverLengths, elevationMin: Math.min(...elevations), elevationMax: Math.max(...elevations), maxGrade, dataCoverage: 1, unknownFraction: 0, modelVersion: MODEL_VERSION, profileId, objective, packageVersion: pack.datasetVersion, packageId: pack.id, hazardVersion, targetPositionTimestamp, generatedAt: Date.now(), explored, computeMs: performance.now() - started,
    warnings: [pack.synthetic ? 'Data medan SINTETIS; bukan elevasi/tutupan lahan Merapi nyata.' : 'Data raster impor; keterlintasan belum diverifikasi lapangan.', 'ETA model Langmuir + friction asumsi; belum dikalibrasi SAR. Konektor endpoint menggunakan elevasi sel, bukan survei subsel.', 'Ascent/descent memakai sampel grid tanpa smoothing; noise DEM dapat menambah total.'] };
}

export function routeProgress(points, position, segments = []) {
  if (points.length < 2) return null;
  const rad = Math.PI / 180, scale = 6371000 * rad, cos = Math.cos(position[0] * rad);
  const xy = p => [(p[1] - position[1]) * scale * cos, (p[0] - position[0]) * scale];
  let best = { offRoute: Infinity, index: 0, fraction: 0 };
  points.slice(1).forEach((b, index) => { const a = xy(points[index]), q = xy(b), vx = q[0] - a[0], vy = q[1] - a[1], l = vx * vx + vy * vy; if (l < 1e-12) return; const fraction = Math.max(0, Math.min(1, -(a[0] * vx + a[1] * vy) / l)); const d = Math.hypot(a[0] + fraction * vx, a[1] + fraction * vy); if (d < best.offRoute) best = { offRoute: d, index, fraction }; });
  let remaining = 0, remainingSeconds = segments.length === points.length - 1 ? 0 : null;
  for (let i = best.index; i < points.length - 1; i++) { const fraction = i === best.index ? 1 - best.fraction : 1; remaining += distanceM(points[i], points[i + 1]) * fraction; if (remainingSeconds !== null) remainingSeconds += segments[i].seconds * fraction; }
  return { ...best, remaining, remainingSeconds, nextWaypoint: points[best.index + 1], nextWaypointDistance: distanceM(position, points[best.index + 1]), bearing: bearingDeg(position, points[best.index + 1]) };
}
