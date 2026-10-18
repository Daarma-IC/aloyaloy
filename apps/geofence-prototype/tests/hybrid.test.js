import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { routeHybrid, terrainContext, demoHybridCoordinates } from '../src/routing/hybrid.js';
import { graphForPackage } from '../src/routing/mapped.js';
import { createSyntheticPackage } from '../src/terrain/fixture.js';
import { distanceM } from '../src/domain.js';
import { routeHitsHazards, routeProgress } from '../src/terrain/engine.js';

function flat(rows = 7, cols = 9) {
  const coordinates = Array.from({ length: rows * cols }, (_, i) => [Math.floor(i / cols) * .00054, i % cols * .00054]);
  return { ...createSyntheticPackage(), id: 'hybrid-flat', grid: { rows, cols, resolutionM: 60 }, footprint: [[-.00027, -.00027], [-.00027, (cols - .5) * .00054], [(rows - .5) * .00054, (cols - .5) * .00054], [(rows - .5) * .00054, -.00027]], coordinates, elevationM: coordinates.map(() => 100), landCover: coordinates.map(() => 60), blocked: coordinates.map(() => false), mappedPaths: [], obstacles: [] };
}
function road(pack, row, first = 0, last = pack.grid.cols - 1, tags = {}) {
  const points = pack.coordinates.slice(row * pack.grid.cols + first, row * pack.grid.cols + last + 1), id = pack.mappedPaths.length + 1;
  pack.mappedPaths.push({ id, points, nodeIds: points.map((_, i) => id * 100 + i), tags: { highway: 'residential', name: `Test road ${id}`, ...tags } });
}
const query = (pack, from, to, extra = {}) => routeHybrid({ pack, from, to, ...extra });

test('no OSM network still yields an exact-endpoint terrain route and physical ETA', () => {
  const pack = flat(), from = [.0001, .0001], to = [.0031, .0041], r = query(pack, from, to);
  assert.equal(r.status, 'available'); assert.deepEqual(r.geometry[0], from); assert.deepEqual(r.geometry.at(-1), to);
  assert.equal(r.lengthsByKind.osm, 0); assert.ok(Math.abs(r.durationEstimate - r.length * .72) < 1e-7);
  assert.ok(Math.abs(r.routingWeight - r.durationEstimate - r.length * 3) < 1e-6);
  assert.ok(routeProgress(r.geometry, from, r.segments).remainingSeconds > 0);
});
test('one query joins a road to an off-road victim; road segments remain on original geometry', () => {
  const pack = flat(); road(pack, 3); pack.mappedPaths[0].points = pack.mappedPaths[0].points.map(p => [p[0] + .0001, p[1]]); const r = query(pack, pack.mappedPaths[0].points[0], pack.coordinates[8]);
  assert.equal(r.status, 'available'); assert.ok(r.lengthsByKind.osm > 200); assert.ok(r.lengthsByKind.terrain > 0); assert.ok(r.lengthsByKind.transition > 0);
  const graph = graphForPackage(pack);
  r.segments.forEach((s, i) => { if (s.kind !== 'osm') return; const edge = graph.edges[s.edgeIndex], a = graph.nodes[edge.a].point, b = graph.nodes[edge.b].point;
    for (const p of [r.geometry[i], r.geometry[i + 1]]) assert.ok(Math.abs(distanceM(a, p) + distanceM(p, b) - edge.meters) < .001);
    assert.equal(s.osmWayId, 1);
  });
});
test('disconnected road components can be joined via valid terrain and re-entered', () => {
  const pack = flat(); road(pack, 3, 0, 2); road(pack, 3, 6, 8);
  const r = query(pack, pack.coordinates[27], pack.coordinates[35]); assert.equal(r.status, 'available');
  const kinds = r.segments.map(s => s.kind); assert.ok(kinds.includes('terrain') || kinds.includes('transition'));
  assert.ok(r.segments.some(s => s.osmWayId === 1)); assert.ok(r.segments.some(s => s.osmWayId === 2));
});
test('water wall rejects transitions and long endpoint connectors; mapped bridge alone enables crossing', () => {
  const pack = flat(5, 9); for (let row = 0; row < 5; row++) pack.landCover[row * 9 + 4] = 80;
  const from = pack.coordinates[18], to = pack.coordinates[26];
  assert.equal(query(pack, from, to).status, 'noTraversablePath');
  assert.equal(terrainContext(pack).inspect(pack.coordinates[21], pack.coordinates[23]), null);
  const withBridge = structuredClone(pack); road(withBridge, 2, 0, 8, { bridge: 'yes' }); const r = query(withBridge, from, to); assert.equal(r.status, 'available');
  assert.ok(r.segments.every(s => s.kind === 'osm')); assert.ok(r.lengthsByKind.osm > 400);
  assert.equal(query(withBridge, from, pack.coordinates[22]).status, 'available'); // destination on mapped bridge
  const waterPoint = [pack.coordinates[22][0] + .0001, pack.coordinates[22][1]];
  assert.equal(query(withBridge, from, waterPoint).status, 'noTraversablePath'); // cannot step off bridge into water
});
test('a road without bridge tags cannot bypass a water wall', () => {
  const pack = flat(5, 9); road(pack, 2); for (let row = 0; row < 5; row++) pack.landCover[row * 9 + 4] = 80;
  assert.equal(query(pack, pack.coordinates[18], pack.coordinates[26]).status, 'noTraversablePath');
});
test('supercover checks diagonal corners and cells between endpoint samples', () => {
  const pack = flat(3, 4); pack.blocked[1] = true; pack.blocked[4] = true;
  const context = terrainContext(pack); assert.equal(context.inspect(pack.coordinates[0], pack.coordinates[5]), null);
  assert.equal(context.inspect(pack.coordinates[0], pack.coordinates[3]), null);
});
test('a subcell polygon blocks every crossing, even when no grid centre lies inside', () => {
  const pack = flat(), polygon = [[-.0003, .00133], [-.0003, .00137], [.004, .00137], [.004, .00133]];
  const hazard = { blockingPolicy: 'block', polygon };
  road(pack, 3); assert.equal(query(pack, pack.coordinates[27], pack.coordinates[35], { hazards: [hazard] }).status, 'noTraversablePath');
});
test('OSM building footprints block target and road–terrain connectors', () => {
  const pack = flat(); road(pack, 3); const p = pack.coordinates[8];
  pack.obstacles = [{ kind: 'building', polygon: [[p[0] - .0001, p[1] - .0001], [p[0] - .0001, p[1] + .0001], [p[0] + .0001, p[1] + .0001], [p[0] + .0001, p[1] - .0001]] }];
  assert.equal(query(pack, pack.coordinates[27], p).status, 'noTraversablePath');
});
test('NoData, unknown class and excessive grade cannot be hidden by road or transition edges', () => {
  const pack = flat(5, 9); road(pack, 2); for (let row = 0; row < 5; row++) pack.elevationM[row * 9 + 4] = null;
  assert.equal(query(pack, pack.coordinates[18], pack.coordinates[26]).status, 'noTraversablePath');
  pack.elevationM.fill(100); for (let row = 0; row < 5; row++) pack.landCover[row * 9 + 4] = 999;
  assert.equal(query(pack, pack.coordinates[18], pack.coordinates[26]).status, 'noTraversablePath');
  pack.landCover.fill(60); for (let row = 0; row < 5; row++) for (let col = 4; col < 9; col++) pack.elevationM[row * 9 + col] = 1000;
  assert.equal(query(pack, pack.coordinates[18], pack.coordinates[26]).status, 'noTraversablePath');
});
test('off-road endpoints on raster edges stay exact, invalid input has explicit status', () => {
  const pack = flat(); assert.equal(query(pack, [0, -.0002], [.0001, .0001]).status, 'available');
  assert.equal(query(pack, [40, 50], pack.coordinates[0]).status, 'missingData');
  assert.equal(query(pack, [NaN, 0], pack.coordinates[0]).status, 'unavailable');
});
test('flat terrain Dijkstra matches the known shortest diagonal and equipment adjusts physical ETA', () => {
  const pack = flat(6, 6), from = pack.coordinates[0], to = pack.coordinates.at(-1), r = query(pack, from, to, { terrainPenaltySecondsPerM: 0 });
  assert.equal(r.status, 'available'); assert.ok(Math.abs(r.length - distanceM(from, to)) < .001);
  assert.ok(Math.abs(r.routingWeight - r.durationEstimate) < 1e-7);
  const loaded = query(pack, from, to, { profileId: 'equipment', terrainPenaltySecondsPerM: 0 });
  assert.ok(Math.abs(loaded.durationEstimate * .7 - r.durationEstimate) < 1e-7);
  const hill = structuredClone(pack); hill.elevationM = hill.coordinates.map(p => 100 + p[0] * 5000);
  assert.ok(query(hill, from, to).durationEstimate > query(hill, to, from).durationEstimate);
});
test('OSM foot oneway is retained when roads are split into transition anchors', () => {
  const pack = flat(); road(pack, 3, 0, 8, { 'oneway:foot': 'yes' });
  const reverse = query(pack, pack.coordinates[35], pack.coordinates[27]); assert.equal(reverse.status, 'available');
  reverse.segments.forEach((s, i) => { if (s.kind === 'osm') assert.ok(reverse.geometry[i + 1][1] >= reverse.geometry[i][1] - 1e-12); });
  // Terrain may legitimately bypass the oneway; it must be disclosed as terrain.
  assert.ok(reverse.lengthsByKind.terrain > 0);
});
test('a bridge over land does not create a false mid-span transition to the ground', () => {
  const pack = flat(); road(pack, 3, 0, 8, { bridge: 'yes' });
  const r = query(pack, pack.coordinates[27], pack.coordinates[8]);
  assert.equal(r.status, 'noTraversablePath'); // no mapped surface junction at bridge ends
});
test('rerouting after a new hazard changes route, versions and remaining-time accounting', () => {
  const pack = flat(); road(pack, 3); const from = pack.coordinates[27], to = pack.coordinates[8], initial = query(pack, from, to), p = pack.coordinates[31];
  const hazard = { blockingPolicy: 'block', polygon: [[p[0] - .00012, p[1] - .00012], [p[0] - .00012, p[1] + .00012], [p[0] + .00012, p[1] + .00012], [p[0] + .00012, p[1] - .00012]] };
  const r = query(pack, from, to, { hazards: [hazard], hazardVersion: 1 }); assert.equal(r.status, 'available'); assert.notDeepEqual(r.geometry, initial.geometry);
  assert.equal(r.hazardVersion, 1); assert.equal(routeHitsHazards(r.geometry, [hazard]).length, 0);
  assert.ok(Math.abs(routeProgress(r.geometry, from, r.segments).remainingSeconds - r.durationEstimate) < 1e-7);
});
test('real Kaliurang demo reaches off-network victim, retains OSM geometry and avoids building footprints', () => {
  const pack = JSON.parse(readFileSync(new URL('../public/demo/kaliurang-terrain.json', import.meta.url))), demo = demoHybridCoordinates(pack), r = query(pack, demo.from, demo.to);
  assert.equal(r.status, 'available'); assert.deepEqual(r.geometry.at(-1), demo.to); assert.ok(pack.obstacles.length > 1000);
  assert.ok(r.lengthsByKind.osm > 1000); assert.ok(r.lengthsByKind.terrain + r.lengthsByKind.transition > 60);
  const context = terrainContext(pack), graph = graphForPackage(pack);
  r.segments.forEach((s, i) => {
    const a = r.geometry[i], b = r.geometry[i + 1]; assert.ok(context.inspect(a, b, s.kind === 'osm' ? graph.edges[s.edgeIndex] : null));
    if (s.kind === 'osm') { const edge = graph.edges[s.edgeIndex], u = graph.nodes[edge.a].point, v = graph.nodes[edge.b].point; assert.ok(Math.abs(distanceM(u, a) + distanceM(a, v) - edge.meters) < .001); assert.ok(Math.abs(distanceM(u, b) + distanceM(b, v) - edge.meters) < .001); }
  });
  assert.ok(Math.abs(Object.values(r.lengthsByKind).reduce((a, b) => a + b, 0) - r.length) < 1e-6);
});
