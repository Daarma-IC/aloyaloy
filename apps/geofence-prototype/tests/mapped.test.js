import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { graphForPackage, demoMappedCoordinates, routeMapped } from '../src/routing/mapped.js';
import { routeHitsHazards } from '../src/terrain/engine.js';
const pack = JSON.parse(readFileSync(new URL('../public/demo/kaliurang-terrain.json', import.meta.url)));
const demo = demoMappedCoordinates(pack);

test('every navigational segment lies on its exact original OSM edge', () => {
  const graph = graphForPackage(pack), result = routeMapped({ pack, from: demo.from, to: demo.to });
  assert.equal(result.status, 'available'); assert.ok(result.geometry.length > 100);
  const onEdge = (p, a, b) => Math.abs((p[0] - a[0]) * (b[1] - a[1]) - (p[1] - a[1]) * (b[0] - a[0])) < 1e-12;
  result.segments.forEach((segment, i) => {
    const edge = graph.edges[segment.edgeIndex], a = graph.nodes[edge.a].point, b = graph.nodes[edge.b].point;
    assert.ok(onEdge(result.geometry[i], a, b)); assert.ok(onEdge(result.geometry[i + 1], a, b));
    assert.ok(pack.mappedPaths.some(path => Number(path.id) === segment.osmWayId));
  });
});
test('off-network target is rejected, not connected by a fabricated straight route', () => {
  const result = routeMapped({ pack, from: demo.from, to: [-7.59, 110.429] });
  assert.equal(result.status, 'missingData'); assert.deepEqual(result.geometry, []);
});
test('rerouting mid-edge preserves that original OSM segment', () => {
  const initial = routeMapped({ pack, from: demo.from, to: demo.to });
  const a = initial.geometry[10], b = initial.geometry[11], from = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
  const reroute = routeMapped({ pack, from, to: demo.to });
  assert.equal(reroute.status, 'available'); assert.ok(reroute.startSnapM < 0.01);
});
test('blocked OSM path returns valid graph alternative or explicit failure', () => {
  const initial = routeMapped({ pack, from: demo.from, to: demo.to }), p = initial.geometry[8], dy = 0.00012;
  const hazard = { blockingPolicy: 'block', polygon: [[p[0] - dy, p[1] - dy], [p[0] - dy, p[1] + dy], [p[0] + dy, p[1] - dy]] };
  const result = routeMapped({ pack, from: demo.from, to: demo.to, hazards: [hazard] });
  if (result.status === 'available') assert.equal(routeHitsHazards(result.geometry, [hazard]).length, 0);
  else assert.equal(result.status, 'noTraversablePath');
});
