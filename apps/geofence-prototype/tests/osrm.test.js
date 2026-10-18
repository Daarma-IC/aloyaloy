import test from 'node:test';
import assert from 'node:assert/strict';
import { routeOsrm } from '../src/routing/osrm.js';
const from = [0, 0], to = [0, 0.001];
const fetchImpl = async () => ({ ok: true, json: async () => ({ code: 'Ok', waypoints: [{ location: [0, 0] }, { location: [0.001, 0] }], routes: [{ distance: 111, duration: 90, geometry: { coordinates: [[0, 0], [0.001, 0]] }, legs: [{ steps: [] }] }] }) });
test('OSRM is optional and never silently uses the public driving endpoint', async () => { assert.equal((await routeOsrm({ from, to })).status, 'unavailable'); });
test('OSRM validates geometry against local hazards', async () => {
  const args = { baseUrl: 'http://localhost:5000', from, to, fetchImpl };
  assert.equal((await routeOsrm(args)).status, 'available');
  args.hazards = [{ blockingPolicy: 'block', polygon: [[-0.001, 0.0004], [0.001, 0.0004], [0.001, 0.0006], [-0.001, 0.0006]] }];
  assert.equal((await routeOsrm(args)).status, 'noTraversablePath');
});
test('NoRoute, NoSegment and server failure remain different', async () => {
  for (const [code, status] of [['NoRoute', 'noTraversablePath'], ['NoSegment', 'missingData']]) {
    assert.equal((await routeOsrm({ baseUrl: 'http://localhost:5000', from, to, fetchImpl: async () => ({ ok: false, json: async () => ({ code }) }) })).status, status);
  }
  assert.equal((await routeOsrm({ baseUrl: 'http://localhost:5000', from, to, fetchImpl: async () => { throw Error('offline'); } })).status, 'unavailable');
});
test('far endpoint snap rejects route rather than fabricating an access leg', async () => {
  assert.equal((await routeOsrm({ baseUrl: 'http://localhost:5000', from: [0.1, 0], to, fetchImpl })).status, 'missingData');
});
