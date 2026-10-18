import { test, expect } from '@playwright/test';
const state = page => page.evaluate(() => JSON.parse(localStorage.getItem('nusasurvey.prototype.v3')));
async function openTerrain(page) { await page.goto('/'); await page.getByRole('button', { name: 'MESIN', exact: true }).click(); }
async function demo(page) { await openTerrain(page); await page.getByRole('button', { name: 'Muat demo medan' }).click(); await expect(page.getByText('DATA SINTETIS · DEMO', { exact: true })).toBeVisible(); }
async function calculate(page) { await page.getByRole('button', { name: 'Hitung / reroute medan' }).click(); await expect.poll(async () => (await state(page)).navigation.status).toBe('READY'); }

test('terrain routing, navigation, hazards, reload and offline rerouting', async ({ page, context }) => {
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await demo(page); await calculate(page);
  const initial = await state(page); expect(initial.navigation.terrainResult.status).toBe('available');
  expect(initial.navigation.terrainResult.length).toBeGreaterThan(2500);
  await page.getByRole('button', { name: 'Mulai / lanjut navigasi' }).click();
  await page.getByRole('button', { name: 'Langkah GPS di rute (simulasi)' }).click();
  expect((await state(page)).navigation.status).toBe('ACTIVE');
  await page.getByRole('button', { name: 'Jeda', exact: true }).click();
  await page.getByRole('button', { name: 'Hitung / reroute medan' }).click();
  await expect.poll(async () => (await state(page)).navigation.status).toBe('READY');
  const terrainBeforeReload = (await state(page)).navigation.terrainResult;
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await context.setOffline(true);
  await page.reload();
  await page.getByRole('button', { name: 'MESIN', exact: true }).click();
  await expect(page.getByText('DATA SINTETIS · DEMO', { exact: true })).toBeVisible();
  expect((await state(page)).navigation.terrainResult.geometry).toEqual(terrainBeforeReload.geometry);
  await calculate(page);
  await page.getByRole('button', { name: 'Tutup crossing demo' }).click();
  expect((await state(page)).navigation.status).toBe('STALE');
  await page.getByRole('button', { name: 'Hitung / reroute medan' }).click();
  await expect.poll(async () => (await state(page)).navigation.lastRoutingStatus).toBe('noTraversablePath');
  const failed = await state(page); expect(failed.navigation.status).toBe('STALE'); expect(failed.navigation.route.length).toBeGreaterThan(2);
  await expect(page.getByRole('button', { name: 'Mulai / lanjut navigasi' })).toBeDisabled();
  await page.getByRole('button', { name: 'Hapus hambatan', exact: true }).click(); await calculate(page);
  expect(errors).toEqual([]);
});

test('invalid package import preserves previous package; target updates invalidate route', async ({ page }) => {
  await demo(page); await calculate(page);
  await page.locator('.terrain-panel input[type=file]').setInputFiles({ name: 'broken.json', mimeType: 'application/json', buffer: Buffer.from('{"schemaVersion":1}') });
  await expect(page.getByRole('alert')).toContainText('Paket tidak dipasang');
  await page.getByRole('button', { name: 'Simulasi update korban' }).click();
  expect((await state(page)).navigation.status).toBe('STALE');
  await page.reload(); await page.getByRole('button', { name: 'MESIN', exact: true }).click();
  await expect(page.getByText('DATA SINTETIS · DEMO', { exact: true })).toBeVisible();
});

test('mobile terrain controls fit viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await demo(page); await calculate(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('GPS outage pauses active navigation and a moved target requires a new route', async ({ page }) => {
  await demo(page); await calculate(page);
  await page.getByRole('button', { name: 'Mulai / lanjut navigasi' }).click();
  await page.getByRole('button', { name: 'PETUGAS', exact: true }).click();
  await page.getByRole('button', { name: 'Simulasi GPS hilang', exact: true }).click();
  await expect.poll(async () => (await state(page)).navigation.status).toBe('PAUSED');
  await page.getByRole('button', { name: 'MESIN', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Mulai / lanjut navigasi' })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Hitung / reroute medan' })).toBeDisabled();
});
