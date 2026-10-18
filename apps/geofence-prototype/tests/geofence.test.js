import test from 'node:test';
import assert from 'node:assert/strict';
import { geofenceStep } from '../src/domain.js';
const polygon = [[0, 0], [0, 0.01], [0.01, 0.01], [0.01, 0]], profile = { id: 'test', bufferM: 20, confirmMs: 5000 };
const outside = { coords: [-0.001, 0.005], accuracy: 5 }, inside = { coords: [0.005, 0.005], accuracy: 5 };
test('one crossing emits only one event, subsequent samples clear event', () => {
  let s = geofenceStep({ status: 'INSIDE' }, outside, polygon, profile, 0);
  s = geofenceStep(s, outside, polygon, profile, 6000); assert.equal(s.event.type, 'EXIT_SECTOR');
  s = geofenceStep(s, outside, polygon, profile, 12000); assert.equal(s.event, null);
});
test('uncertainty is boundary overlap, and pending crossing cancels on return', () => {
  const pending = geofenceStep({ status: 'INSIDE' }, outside, polygon, profile, 0);
  assert.equal(geofenceStep(pending, inside, polygon, profile, 6000).pending, null);
  const uncertain = geofenceStep({ status: 'INSIDE' }, { coords: [-0.00001, 0.005], accuracy: 5 }, polygon, profile, 0);
  assert.equal(uncertain.status, 'UNCERTAIN'); assert.equal(uncertain.event, null);
  assert.equal(geofenceStep({ status: 'INSIDE' }, { ...inside, accuracy: 50 }, polygon, profile, 0).status, 'INSIDE');
});
test('baseline transitions immediately; unavailable GPS clears pending', () => {
  const s = geofenceStep({ status: 'INSIDE' }, outside, polygon, { ...profile, bufferM: 0, confirmMs: 0 }, 0);
  assert.equal(s.event.type, 'EXIT_SECTOR');
  assert.equal(geofenceStep(s, null, polygon, profile, 100).pending, null);
});
