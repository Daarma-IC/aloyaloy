import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { routeTerrain, routeProgress } from '../src/terrain/engine.js';
const raw = readFileSync(new URL('../public/demo/kaliurang-terrain.json', import.meta.url));
const pack = JSON.parse(raw);

test('bundled Kaliurang demo contains real raster provenance, OSM paths and valid checksum', () => {
  const manifest = JSON.parse(readFileSync(new URL('../public/demo/sources.json', import.meta.url)));
  assert.equal(pack.synthetic, false);
  assert.equal(createHash('sha256').update(raw).digest('hex'), manifest.packageSha256);
  assert.equal(pack.sources.length, 3); assert.ok(pack.sources[0].url.includes('copernicus-dem')); assert.ok(pack.sources[1].url.includes('esa-worldcover'));
  assert.ok(pack.mappedPaths.length > 100); assert.ok(pack.elevationM.every(z => z !== null));
});
test('default generated people have a real terrain route with a nonzero next waypoint', () => {
  const result = routeTerrain({ pack, from: pack.demoStart, to: pack.demoTarget });
  assert.equal(result.status, 'available'); assert.ok(result.length > 1500);
  const progress = routeProgress(result.geometry, pack.demoStart, result.segments);
  assert.ok(progress.nextWaypointDistance > 30);
  assert.ok(result.routingWeight >= result.durationEstimate - 1e-6);
});
