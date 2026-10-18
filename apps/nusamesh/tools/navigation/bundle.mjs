import { build } from '../../../geofence-prototype/node_modules/vite/dist/node/index.js';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const resources = fileURLToPath(new URL('../../composeApp/src/commonMain/composeResources/files/navigation/', import.meta.url));
await mkdir(resources, { recursive: true });
await build({ configFile: false, build: { lib: { entry: fileURLToPath(new URL('./entry.js', import.meta.url)), name: 'NusaSurvey', formats: ['iife'], fileName: () => 'solver.js' }, outDir: resources, emptyOutDir: false, target: 'es2020', minify: true } });
const data = await readFile(new URL('../../../geofence-prototype/public/demo/kaliurang-terrain.json', import.meta.url));
await writeFile(resources + '/kaliurang-terrain.json', data);
const engine = await readFile(resources + '/solver.js');
const presets = JSON.parse(await readFile(new URL('./regions.json', import.meta.url))).regions;
// Cell-corner display geometry belongs to the prototype inspector; the route solver uses centres/footprint.
const compact = pack => { const { cellCorners, demoStart, demoTarget, ...navigation } = pack; return navigation; };
const packages = [compact(JSON.parse(data))];
for (const preset of presets) {
  const raw = await readFile(new URL('../../../../systems/nusasurvey/data/navigation/' + preset.id + '/package.json', import.meta.url));
  packages.push(compact(JSON.parse(raw)));
}
await writeFile(resources + '/regions.json', JSON.stringify({ schemaVersion: 1, packages, catalog: presets }));
await writeFile(resources + '/regions-manifest.json', JSON.stringify({ packages: packages.map(pack => ({ id: pack.id, name: pack.name || 'Kaliurang', bbox: pack.aoiWgs84, version: pack.datasetVersion, sha256: createHash('sha256').update(JSON.stringify(pack)).digest('hex') })), generatedAt: new Date().toISOString() }, null, 2));
await writeFile(resources + '/manifest.json', JSON.stringify({ model: 'osm-terrain-langmuir-v1', package: JSON.parse(data).datasetVersion, packageSha256: createHash('sha256').update(data).digest('hex'), solverSha256: createHash('sha256').update(engine).digest('hex'), source: 'apps/geofence-prototype/src/routing/hybrid.js', generatedAt: new Date().toISOString() }, null, 2));
