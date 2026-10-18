import test from 'node:test';
import assert from 'node:assert/strict';
import { buildElevationProfile, elevationCsv, elevationKml } from '../src/terrain/elevationProfile.js';
import { createSyntheticPackage } from '../src/terrain/fixture.js';
import { distanceM } from '../src/domain.js';

function plane() {
  const pack = createSyntheticPackage(), rows = 3, cols = 5;
  const coordinates = Array.from({ length: rows * cols }, (_, i) => [Math.floor(i / cols) * .00054, i % cols * .00054]);
  return { ...pack, grid: { rows, cols, resolutionM: 60 }, footprint: [[-.00027, -.00027], [-.00027, .00243], [.00135, .00243], [.00135, -.00027]], coordinates, elevationM: coordinates.map((_, i) => 100 + i % cols * 10 + Math.floor(i / cols) * 20), landCover: coordinates.map(() => 60), blocked: coordinates.map(() => false), mappedPaths: [], obstacles: [], sources: [{ role: 'dem', name: 'Plane DEM & <test>', nativeResolutionM: 60 }] };
}
function plan(pack, geometry) { return { status: 'available', engine: 'Hybrid', packageId: pack.id, packageVersion: pack.datasetVersion, generatedAt: 1700000000000, geometry, segments: geometry.slice(1).map((_, i) => ({ kind: i ? 'terrain' : 'osm', osmWayId: i ? null : 42 })) }; }

test('profile matches an analytical elevation plane, preserves vertices and cumulative route distance', () => {
  const pack = plane(), geometry = [[.00027, .00027], [.00054, .00108], [.00081, .00189]], route = plan(pack, geometry), before = structuredClone(route), p = buildElevationProfile(route, pack);
  assert.deepEqual(route, before); assert.equal(p.samples[0].elevationM, 115); assert.equal(p.samples.at(-1).elevationM, 165);
  for (const s of p.samples) assert.ok(Math.abs(s.elevationM - (100 + s.coordinate[1] / .00054 * 10 + s.coordinate[0] / .00054 * 20)) < 1e-8);
  for (const vertex of geometry) assert.ok(p.samples.some(s => distanceM(s.coordinate, vertex) < 1e-7));
  assert.ok(Math.abs(p.lengthM - distanceM(geometry[0], geometry[1]) - distanceM(geometry[1], geometry[2])) < 1e-7);
  p.samples.slice(1).forEach((s, i) => { assert.ok(s.distanceM > p.samples[i].distanceM); assert.ok(s.distanceM - p.samples[i].distanceM <= p.spacingM + 1e-7); });
  assert.equal(p.minM, 115); assert.equal(p.maxM, 165);
});
test('profile marks missing elevation, handles a flat zero-length route and rejects mismatched datasets', () => {
  const pack = plane(), from = pack.coordinates[0], to = pack.coordinates[4]; pack.elevationM[2] = null;
  const p = buildElevationProfile(plan(pack, [from, to]), pack); assert.ok(p.samples.some(s => s.elevationM === null));
  assert.equal(buildElevationProfile({ ...plan(pack, [from, to]), packageVersion: 'wrong' }, pack), null);
  const flat = buildElevationProfile(plan(pack, [from, from]), pack); assert.equal(flat.samples.length, 1); assert.equal(flat.lengthM, 0); assert.equal(flat.minM, 100);
});
test('comparison exports use longitude/latitude ground geometry and separately labelled DEM elevations', () => {
  const pack = plane(), p = buildElevationProfile(plan(pack, [pack.coordinates[0], pack.coordinates[4]]), pack), csv = elevationCsv(p, 'STALE'), kml = elevationKml(p, 'STALE');
  assert.equal(csv.split('\r\n').length, p.samples.length + 1); assert.ok(csv.includes('"dem_elevation_m"')); assert.ok(csv.includes('"synthetic"')); assert.ok(csv.includes('"STALE"'));
  assert.ok(kml.includes('<altitudeMode>clampToGround</altitudeMode>')); assert.ok(!kml.includes('<altitudeMode>absolute</altitudeMode>'));
  assert.ok(kml.includes('<coordinates>0,0,0 0.00216,0,0</coordinates>')); assert.ok(kml.includes('Plane DEM &amp; &lt;test&gt;')); assert.ok(kml.includes('nusasurvey_dem_elevation_m'));
  assert.equal((kml.match(/<Point>/g) || []).length, p.samples.length);
});
