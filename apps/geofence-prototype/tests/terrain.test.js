import test from 'node:test';
import assert from 'node:assert/strict';
import { edgeTime, PROFILES, routeTerrain, routeProgress, routeHitsHazards, validatePackage } from '../src/terrain/engine.js';
import { createSyntheticPackage } from '../src/terrain/fixture.js';

function flat(rows = 7, cols = 7) {
  const fixture = createSyntheticPackage();
  const coordinates = [];
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) coordinates.push([r * 0.00054, c * 0.00054]);
  const n = rows * cols;
  return { ...fixture, id: 'flat-test', grid: { rows, cols, resolutionM: 60 }, footprint: [[-0.00027, -0.00027], [-0.00027, cols * 0.00054 - 0.00027], [rows * 0.00054 - 0.00027, cols * 0.00054 - 0.00027], [rows * 0.00054 - 0.00027, -0.00027]], coordinates, elevationM: Array(n).fill(100), landCover: Array(n).fill(60), blocked: Array(n).fill(false) };
}
function route(pack, extra = {}) { return routeTerrain({ pack, from: pack.coordinates[0], to: pack.coordinates.at(-1), ...extra }); }

test('open terrain routes without any OSM network; reproducible geometry and ETA', () => {
  const pack = flat(), a = route(pack), b = route(pack);
  assert.equal(a.status, 'available'); assert.deepEqual(a.geometry, b.geometry);
  assert.ok(a.length > 400); assert.ok(Math.abs(a.durationEstimate - a.length * 0.72) < 1e-8);
  assert.equal(a.ascent, 0); assert.equal(a.descent, 0);
});
test('directional time and equipment profile change ETA, slope constraints are hard', () => {
  assert.ok(edgeTime(100, 10, 60, 60) > edgeTime(100, -10, 60, 60));
  assert.ok(edgeTime(100, 10, 60, 60, PROFILES.equipment) > edgeTime(100, 10, 60, 60));
  assert.equal(edgeTime(10, 100, 60, 60), null);
  const pack = flat(); pack.elevationM = pack.coordinates.map(p => 100 + p[0] * 20000);
  assert.ok(route(pack).durationEstimate > route(pack, { from: pack.coordinates.at(-1), to: pack.coordinates[0] }).durationEstimate);
});
test('river is crossed only at a traversable opening', () => {
  const pack = flat(); for (let r = 0; r < 7; r++) if (r !== 3) pack.landCover[r * 7 + 3] = 80;
  const result = route(pack); assert.equal(result.status, 'available'); assert.ok(result.cells.includes(24));
  assert.ok(result.cells.every(i => pack.landCover[i] !== 80));
  pack.landCover[24] = 80; assert.equal(route(pack).status, 'noTraversablePath');
});
test('diagonal corner cutting cannot pass blocked orthogonal neighbours', () => {
  const pack = flat(2, 2); pack.blocked[1] = true; pack.blocked[2] = true;
  assert.equal(route(pack).status, 'noTraversablePath');
});
test('NoData and outside AOI never yield fake routes', () => {
  const pack = flat(); pack.elevationM[0] = null; assert.equal(route(pack).status, 'missingData');
  assert.equal(route(pack, { from: [10, 10] }).status, 'missingData');
  assert.equal(route(pack, { to: [NaN, 1] }).status, 'unavailable');
});
test('thin polygon barrier between centres is checked against edges', () => {
  const pack = flat(); const hazard = { blockingPolicy: 'block', polygon: [[-0.001, 0.0013], [0.005, 0.0013], [0.005, 0.0014], [-0.001, 0.0014]] };
  assert.equal(route(pack, { hazards: [hazard] }).status, 'noTraversablePath');
  assert.equal(routeHitsHazards(route(pack).geometry, [hazard]).length, 1);
});
test('partial new hazard reroutes; complete barrier returns noTraversablePath', () => {
  const pack = flat(), initial = route(pack);
  const hazard = { blockingPolicy: 'block', polygon: [[0.001, 0.001], [0.002, 0.001], [0.002, 0.002], [0.001, 0.002]] };
  const updated = route(pack, { hazards: [hazard], hazardVersion: 1 });
  assert.equal(updated.status, 'available'); assert.notDeepEqual(initial.cells, updated.cells);
  assert.equal(routeHitsHazards(updated.geometry, [hazard]).length, 0); assert.equal(updated.hazardVersion, 1);
});
test('endpoint connector through a hazard is rejected', () => {
  const pack = flat(); const from = [-0.0002, 0];
  const hazard = { blockingPolicy: 'block', polygon: [[-0.00015, -0.0001], [-0.00005, -0.0001], [-0.00005, 0.0001], [-0.00015, 0.0001]] };
  assert.equal(route(pack, { from, hazards: [hazard] }).status, 'noTraversablePath');
});
test('land-cover friction changes route choice compared with distance baseline', () => {
  const pack = flat(9, 9); for (let r = 2; r < 7; r++) for (let c = 2; c < 7; c++) pack.landCover[r * 9 + c] = 20;
  const time = route(pack), distance = route(pack, { objective: 'distance' });
  assert.equal(time.status, 'available'); assert.notDeepEqual(time.cells, distance.cells);
  assert.ok(time.durationEstimate < distance.durationEstimate); assert.ok(time.length > distance.length);
});
test('progress projects to segments and sums remaining segment time', () => {
  const points = [[0, 0], [0, 0.001], [0, 0.002]];
  const p = routeProgress(points, [0, 0.0005], [{ seconds: 100 }, { seconds: 200 }]);
  assert.ok(p.offRoute < 1e-8); assert.ok(Math.abs(p.remainingSeconds - 250) < 1e-8);
  assert.ok(p.remaining > 160 && p.remaining < 170);
});
test('synthetic scenario routes through its bridge then closing crossing prevents access', () => {
  const pack = createSyntheticPackage(); const initial = route(pack, { from: pack.demoStart, to: pack.demoTarget });
  assert.equal(initial.status, 'available'); assert.ok(initial.cells.includes(pack.bridgeCell));
  pack.blocked[pack.bridgeCell] = true; assert.equal(route(pack, { from: pack.demoStart, to: pack.demoTarget }).status, 'noTraversablePath');
});
test('malformed package and unrecognised profile are explicit failures', () => {
  const pack = flat(); assert.throws(() => validatePackage({ ...pack, elevationM: [] }));
  assert.equal(route(pack, { profileId: 'made-up' }).status, 'unavailable');
  assert.equal(route({ ...pack, landCover: [] }).status, 'missingData');
});
