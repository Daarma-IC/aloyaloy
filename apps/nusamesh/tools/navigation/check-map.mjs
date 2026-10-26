import { chromium } from '../../../geofence-prototype/node_modules/@playwright/test/index.mjs';
import { readFile, mkdir } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { demoHybridCoordinates, routeHybrid } from '../../../geofence-prototype/src/routing/hybrid.js';
const resources = new URL('../../composeApp/src/commonMain/composeResources/files/', import.meta.url);
const kotlin = await readFile(new URL('../../composeApp/src/commonMain/kotlin/id/nusamesh/app/ui/LeafletMap.kt', import.meta.url), 'utf8');
let html = kotlin.split('internal val leafletHtml = """')[1].split('""".trimIndent()')[0];
const solver = await readFile(new URL('navigation/solver.js', resources), 'utf8');
const terrain = await readFile(new URL('navigation/kaliurang-terrain.json', resources), 'utf8');
const regions = await readFile(new URL('navigation/regions.json', resources), 'utf8');
const pack = JSON.parse(terrain), demo = demoHybridCoordinates(pack), reference = routeHybrid({ pack, from: demo.from, to: demo.to });
html = html.replace('<!--LEAFLET_CSS-->', '<style>' + await readFile(new URL('leaflet/leaflet.css', resources), 'utf8') + '</style>')
  .replace('<!--LEAFLET_JS-->', '<script>' + await readFile(new URL('leaflet/leaflet.js', resources), 'utf8') + '</script>')
  .replace('/*OFFLINE_REGION*/', await readFile(new URL('maps/natural-earth-se-asia.geojson', resources), 'utf8'))
  .replace('/*MAP_DATA*/', 'null').replace('/*NAV_SOLVER*/', JSON.stringify(solver)).replace('/*NAV_TERRAIN*/', JSON.stringify(regions));
const browser = await chromium.launch({ headless: true, channel: 'chromium' });
try {
  const context = await browser.newContext({ viewport: { width: 430, height: 660 }, offline: true });
  const page = await context.newPage(), errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto('about:blank'); await page.evaluate(() => { window.messages = []; window.taps = []; window.NusaBridge = { tap(lat, lon) { window.taps.push([lat, lon]); }, navigation(text) { window.messages.push(JSON.parse(text)); } }; });
  await page.setContent(html);
  assert.equal(await page.locator('#region-button, #region-panel').count(),0);
  await page.waitForFunction(() => typeof window.setNusaData === 'function');
  const data = { units: [{ peerId: 'sar', name: 'Petugas uji', latitude: demo.from[0], longitude: demo.from[1], own: true }, { peerId: 'victim', name: 'Korban uji', latitude: demo.to[0], longitude: demo.to[1], own: false }], routes: [], waypoints: [] };
  await page.evaluate(data => window.setNusaData(data), data);
  assert.equal(await page.locator('path[stroke="#2563eb"]').count(), 0);
  await page.evaluate(() => { window.testUnitMarker = window.nusaMarkers.getLayers()[1]; });
  await page.locator('path.leaflet-interactive').nth(1).click();
  assert.deepEqual(await page.evaluate(() => window.taps), [], 'A marker click must not also select an arbitrary map point');
  await page.evaluate(data => {
    for (let i = 0; i < 10; i++) window.setNusaData({ ...data, units: data.units.map(u => ({ ...u, latitude: u.latitude + i * 0.000001, battery: 80 - i })) });
  }, data);
  assert.ok(await page.evaluate(() => window.nusaMarkers.getLayers()[1] === window.testUnitMarker && window.testUnitMarker.isPopupOpen()), 'GPS updates must keep the marker and its popup alive');
  await page.getByRole('button', { name: 'Arahkan ke korban/unit' }).click();
  assert.deepEqual(await page.evaluate(() => window.messages[0]), { action: 'select', kind: 'unit', targetId: 'victim' });
  const wp = { id: 'victim-point', type: 'korban', typeLabel: 'Korban', label: 'Titik GPS', owner: 'Tim', point: demo.to };
  await page.evaluate(({data, wp}) => { window.setNusaData({...data, waypoints:[wp]}); window.testWaypointMarker = window.nusaWaypoints.getLayers()[0]; }, {data, wp});
  await page.locator('.leaflet-marker-icon.leaflet-interactive').click();
  await page.evaluate(({data, wp}) => window.setNusaData({...data, waypoints:[{...wp, label:'Posisi diperbarui'}]}), {data, wp});
  assert.ok(await page.evaluate(() => window.nusaWaypoints.getLayers()[0] === window.testWaypointMarker && window.testWaypointMarker.isPopupOpen()));
  await page.getByRole('button', { name: 'Arahkan ke titik', exact: true }).click();
  assert.deepEqual(await page.evaluate(() => window.messages.at(-1)), { action:'select', kind:'waypoint', targetId:wp.id });
  assert.deepEqual(await page.evaluate(() => window.taps), []);
  await page.evaluate(data => window.setNusaData({...data, units:[data.units[0]]}), data);
  assert.equal(await page.evaluate(() => window.nusaMarkers.getLayers().length), 1);
  assert.equal(await page.evaluate(() => window.nusaWaypoints.getLayers().length), 0);
  await page.locator('#query').fill('Bandung');
  await page.locator('#query').press('Enter');
  await page.getByRole('button', { name: 'Arahkan ke titik peta', exact:true }).click();
  assert.deepEqual(await page.evaluate(() => window.messages.at(-1)), {action:'select', kind:'point', latitude:-6.9175, longitude:107.6191});
  await page.evaluate(data => { window.setNusaData(data); window.nusaMap.closePopup(); }, data);
  const request = { id: 'offline-test', from: demo.from, to: demo.to };
  await page.evaluate(data => window.setNusaData(data), { ...data, navigationRequest: request });
  await page.waitForFunction(() => window.messages.some(m => m.id === 'offline-test'), { timeout: 30000 });
  const result = await page.evaluate(() => window.messages.find(m => m.id === 'offline-test').result);
  assert.equal(result.status, 'available'); assert.deepEqual(result.geometry, reference.geometry); assert.ok(Math.abs(result.durationEstimate - reference.durationEstimate) < 1e-6);
  await page.evaluate(data => window.setNusaData(data), { ...data, navigationPlan: { id: 'offline-test', status: 'READY', geometry: result.geometry, kinds: result.segments.map(s => s.kind) } });
  for (const color of ['#2563eb', '#9333ea', '#d97706']) assert.ok(await page.locator(`path[stroke="${color}"]`).count() > 0);
  await mkdir(new URL('../../docs/previews/', import.meta.url), { recursive: true });
  await page.screenshot({ path: fileURLToPath(new URL('../../docs/previews/sar-navigation-map.png', import.meta.url)) });
  await page.evaluate(data => window.setNusaData(data), { ...data, navigationRequest: { id: 'outside', from: [0, 0], to: demo.to } });
  await page.waitForFunction(() => window.messages.some(m => m.id === 'outside'));
  const outside = await page.evaluate(() => window.messages.find(m => m.id === 'outside').result);
  assert.equal(outside.status, 'missingData'); assert.deepEqual(outside.geometry, []);
  // Superseded jobs cannot overwrite the newest query.
  await page.evaluate(({data, request}) => { window.setNusaData({...data,navigationRequest:{...request,id:'cancelled'}}); window.setNusaData({...data,navigationRequest:{id:'newest',from:[0,0],to:request.to}}); }, {data,request});
  await page.waitForFunction(() => window.messages.some(m => m.id === 'newest'));
  assert.equal(await page.evaluate(() => window.messages.filter(m => m.id === 'cancelled').length), 0);
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({ status: 'PASS', tests: ['marker click does not select map point', 'unit popup survives repeated GPS updates', 'waypoint popup survives updates', 'removed markers are cleared', 'search result directions action', 'marker directions action', 'offline worker parity with prototype', 'mixed route colors', 'outside coverage failure', 'superseded query cancellation'], length: result.length, eta: result.durationEstimate }));
} finally { await browser.close(); }
