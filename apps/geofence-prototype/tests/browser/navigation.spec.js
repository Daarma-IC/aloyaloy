import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
const state = page => page.evaluate(() => JSON.parse(localStorage.getItem('nusasurvey.prototype.v3')));
const routeLine = page => page.locator('.leaflet-overlay-pane path[stroke="#ffffff"][stroke-width="10"]');
async function loaded(page) { await expect.poll(async () => (await state(page))?.navigation.status).toBe('IDLE'); }
async function directToVictim(page) {
  await page.getByRole('button', { name: 'Koordinat Korban 01', exact: true }).click();
  await page.locator('.leaflet-popup-content').getByRole('button', { name: 'Arahkan ke korban', exact: true }).click();
  await expect.poll(async () => (await state(page)).navigation.status).toBe('READY');
}

test('two dots first; explicit directions displays mixed OSM/terrain route, ETA and works offline', async ({ page, context }) => {
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto('/'); await loaded(page);
  await expect(page.getByRole('heading', { name: 'Pilih tujuan di peta' })).toBeVisible();
  await expect(routeLine(page)).toHaveCount(0); await expect(page.locator('.nav-summary')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Profil elevasi rute', exact: true })).toHaveCount(0);
  expect((await state(page)).navigation.terrainResult).toBeNull();
  await page.getByRole('button', { name: 'Koordinat Korban 01', exact: true }).click();
  await expect(page.locator('.leaflet-popup-content').getByRole('button', { name: 'Arahkan ke korban' })).toBeVisible();
  await expect(routeLine(page)).toHaveCount(0); await expect(page.locator('.nav-summary')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Profil elevasi rute', exact: true })).toHaveCount(0);
  await page.locator('.leaflet-popup-content').getByRole('button', { name: 'Arahkan ke korban' }).click();
  await expect.poll(async () => (await state(page)).navigation.status).toBe('READY');
  const initial = await state(page), route = initial.navigation.terrainResult;
  expect(route.engine).toBe('Hybrid'); expect(route.geometry.length).toBeGreaterThan(100);
  expect(route.lengthsByKind.osm).toBeGreaterThan(1000); expect(route.lengthsByKind.terrain).toBeGreaterThan(0); expect(route.lengthsByKind.transition).toBeGreaterThan(0);
  expect(route.geometry.at(-1)).toEqual(initial.victim.pos);
  await expect(page.locator('.leaflet-overlay-pane path[stroke="#2563eb"][stroke-width="6"]').first()).toBeVisible();
  // SVG vertical strokes have zero-width bounding boxes despite being painted.
  for (const color of ['#9333ea', '#d97706']) {
    const paths = page.locator(`.leaflet-overlay-pane path[stroke="${color}"][stroke-width="6"]`);
    expect(await paths.count()).toBeGreaterThan(0);
    expect(await paths.evaluateAll(elements => elements.some(p => p.getTotalLength() > 0 && getComputedStyle(p).display !== 'none'))).toBeTruthy();
  }
  await expect(page.locator('.route-breakdown')).toContainText('medan');
  await expect(page.getByRole('heading', { name: 'Profil elevasi rute', exact: true })).toBeVisible();
  await page.screenshot({ path: 'docs/previews/hybrid-navigation.png', fullPage: true });
  await expect(routeLine(page)).toHaveCount(1); await expect(page.locator('.nav-summary')).toBeVisible();
  await page.getByRole('button', { name: 'Mulai navigasi ke korban' }).click();
  await expect.poll(async () => (await state(page)).navigation.progressM).toBeGreaterThan(24);
  expect((await state(page)).operator.pos).not.toEqual(initial.operator.pos);
  await page.getByRole('button', { name: 'Jeda navigasi', exact: true }).click();
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await context.setOffline(true); await page.reload();
  await expect(page.getByRole('heading', { name: 'Menuju Korban 01' })).toBeVisible();
  await expect(routeLine(page)).toHaveCount(1);
  await page.getByRole('button', { name: 'Hitung ulang dari posisi sekarang' }).click();
  await expect.poll(async () => (await state(page)).navigation.status).toBe('READY');
  expect((await state(page)).navigation.terrainResult.engine).toBe('Hybrid');
  expect(errors).toEqual([]);
});

test('requested route survives switching to field view and returning to navigation', async ({ page }) => {
  await page.goto('/'); await loaded(page); await directToVictim(page);
  const before = (await state(page)).navigation.route;
  await page.getByRole('button', { name: 'PETUGAS', exact: true }).click();
  const packetLoaded = page.waitForResponse(r => r.url().endsWith('/demo/kaliurang-terrain.json'));
  await page.getByRole('button', { name: 'NAVIGASI', exact: true }).click(); await packetLoaded;
  await expect(page.getByRole('heading', { name: 'Menuju Korban 01' })).toBeVisible();
  await expect(routeLine(page)).toHaveCount(1);
  expect((await state(page)).navigation.route).toEqual(before);
});

test('new blockage recalculates hybrid route while keeping segment provenance and target', async ({ page }) => {
  await page.goto('/'); await loaded(page); await directToVictim(page);
  const before = await state(page);
  await page.getByRole('button', { name: 'Laporkan jalur di depan tertutup' }).click();
  expect((await state(page)).navigation.status).toBe('STALE');
  await page.getByRole('button', { name: 'Hitung ulang dari posisi sekarang' }).click();
  await expect.poll(async () => (await state(page)).navigation.status).not.toBe('CALCULATING');
  const after = await state(page); expect(after.victim.pos).toEqual(before.victim.pos);
  if (after.navigation.status === 'READY') { expect(after.navigation.route).not.toEqual(before.navigation.route); expect(after.navigation.terrainResult.engine).toBe('Hybrid'); expect(after.navigation.terrainResult.hazardVersion).toBe(before.terrain.hazardVersion + 1); expect(after.navigation.terrainResult.segments.every(s => ['osm', 'terrain', 'transition'].includes(s.kind))).toBeTruthy(); }
  else { expect(after.navigation.status).toBe('STALE'); await expect(page.getByRole('button', { name: 'Mulai navigasi ke korban' })).toBeDisabled(); }
});

test('new dummy victim removes old route and waits for victim click and directions', async ({ page }) => {
  await page.goto('/'); await loaded(page); await directToVictim(page);
  const before = await state(page);
  await page.getByRole('button', { name: 'Generate target dummy lain' }).click(); await loaded(page);
  const after = await state(page); expect(after.victim.pos).not.toEqual(before.victim.pos);
  expect(after.navigation.route).toEqual([]); await expect(routeLine(page)).toHaveCount(0);
  await directToVictim(page); expect((await state(page)).navigation.terrainResult.engine).toBe('Hybrid');
});

test('road-only comparator explicitly rejects the off-network victim', async ({ page }) => {
  await page.goto('/'); await loaded(page);
  await page.getByRole('button', { name: 'Koordinat Korban 01', exact: true }).click();
  await page.getByLabel('Jenis rute').selectOption('MappedOSM');
  await page.locator('.selected-victim').getByRole('button', { name: 'Arahkan ke korban', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('di luar jalur OSM');
  expect((await state(page)).navigation.route).toEqual([]);
  await expect(routeLine(page)).toHaveCount(0);
});

test('elevation chart samples map coordinates and exports an independent Google Earth path plus DEM CSV', async ({ page }, testInfo) => {
  await page.goto('/'); await loaded(page); await directToVictim(page);
  const route = (await state(page)).navigation.terrainResult;
  const panel = page.getByRole('region', { name: 'Profil elevasi rute', exact: true });
  await expect(panel.getByRole('img', { name: 'Grafik elevasi DEM sepanjang rute ke korban' })).toBeVisible();
  await expect(panel.locator('.elevation-readout strong')).toHaveText(`${route.elevationProfile[0].elevationM.toFixed(1)} m`);
  const slider = panel.getByRole('slider', { name: 'Pilih sampel elevasi' }), last = Number(await slider.getAttribute('max'));
  expect(last).toBeGreaterThan(route.geometry.length - 1);
  await slider.press('End');
  await expect(panel.locator('.elevation-readout code')).toHaveText(route.geometry.at(-1).map(n => n.toFixed(6)).join(', '));
  await expect(panel.locator('.elevation-readout strong')).toHaveText(`${route.elevationProfile.at(-1).elevationM.toFixed(1)} m`);
  await expect(page.locator('.elevation-sample-marker')).toHaveCount(1);
  const csvDownload = page.waitForEvent('download'); await panel.getByRole('button', { name: 'Unduh elevasi CSV' }).click();
  const csv = await csvDownload; await csv.saveAs(testInfo.outputPath('elevation.csv'));
  const text = readFileSync(testInfo.outputPath('elevation.csv'), 'utf8'), lines = text.split('\r\n');
  expect(lines.length).toBe(last + 2); expect(text).toContain('"dem_elevation_m"'); expect(text).toContain('"EGM2008"'); expect(text).toContain('"Copernicus DEM');
  const kmlDownload = page.waitForEvent('download'); await panel.getByRole('button', { name: 'Unduh rute KML' }).click();
  const kml = await kmlDownload; await kml.saveAs(testInfo.outputPath('route.kml'));
  const content = readFileSync(testInfo.outputPath('route.kml'), 'utf8');
  const parsed = await page.evaluate(xml => { const doc = new DOMParser().parseFromString(xml, 'application/xml'); return { errors: doc.querySelectorAll('parsererror').length, coordinates: doc.querySelector('LineString coordinates').textContent.trim().split(/\s+/).map(v => v.split(',').map(Number)), mode: doc.querySelector('LineString altitudeMode').textContent }; }, content);
  expect(parsed.errors).toBe(0); expect(parsed.mode).toBe('clampToGround'); expect(parsed.coordinates).toEqual(route.geometry.map(p => [p[1], p[0], 0]));
  await page.setViewportSize({ width: 390, height: 844 });
  await panel.scrollIntoViewIfNeeded();
  expect(await panel.evaluate(el => el.getBoundingClientRect().width)).toBeLessThanOrEqual(390);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: 'docs/previews/elevation-mobile.png', fullPage: true });
});
