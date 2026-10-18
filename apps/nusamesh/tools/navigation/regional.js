import { validatePackage, inPolygon } from '../../../geofence-prototype/src/terrain/engine.js';

export function checkedRegionalPackage(pack) {
  validatePackage(pack);
  if (pack.synthetic !== false) throw Error('Paket navigasi harus memakai data nyata');
  if (!/^(kaliurang-real-terrain|jabar-[a-z0-9-]+|indonesia-[a-f0-9]{16})$/.test(pack.id)) throw Error('Identitas paket wilayah tidak dikenal');
  if (!Array.isArray(pack.aoiWgs84) || pack.aoiWgs84.length !== 4 || !pack.aoiWgs84.every(Number.isFinite)) throw Error('Batas wilayah paket tidak valid');
  const [w,s,e,n] = pack.aoiWgs84;
  if (!(w >= 94 && e <= 142 && s >= -12 && n <= 7 && w < e && s < n)) throw Error('Paket di luar cakupan Indonesia');
  if (e-w > .090001 || n-s > .090001) throw Error('Paket melewati batas ukuran wilayah operasi');
  if (pack.coordinates.some(p => p[1] < w-.001 || p[1] > e+.001 || p[0] < s-.001 || p[0] > n+.001) || pack.footprint.some(p => p[1] < w-.001 || p[1] > e+.001 || p[0] < s-.001 || p[0] > n+.001)) throw Error('Geometry grid tidak sesuai batas wilayah paket');
  if (pack.name != null && (typeof pack.name !== 'string' || pack.name.length > 200)) throw Error('Nama paket tidak valid');
  if (typeof pack.datasetVersion !== 'string' || pack.datasetVersion.length > 200) throw Error('Versi paket tidak valid');
  if (!Array.isArray(pack.mappedPaths) || pack.mappedPaths.length > 50000 || (pack.obstacles || []).length > 50000) throw Error('Data jalur/hambatan terlalu besar');
  const point = p => Array.isArray(p) && p.length === 2 && p.every(Number.isFinite) && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180;
  for (const way of pack.mappedPaths) {
    if (!Array.isArray(way.points) || way.points.length < 2 || way.points.length > 20000 || !way.points.every(point) || !Array.isArray(way.nodeIds) || way.nodeIds.length !== way.points.length || !way.nodeIds.every(Number.isSafeInteger)) throw Error('Geometry/node OSM tidak valid');
  }
  for (const obstacle of pack.obstacles || []) if (!Array.isArray(obstacle.polygon) || obstacle.polygon.length < 3 || !obstacle.polygon.every(point)) throw Error('Geometry hambatan tidak valid');
  return pack;
}

export function selectRegionalPackage(packages, from, to) {
  return packages.filter(pack => inPolygon(from, pack.footprint) && inPolygon(to, pack.footprint))
    .sort((a,b) => a.grid.resolutionM - b.grid.resolutionM || a.coordinates.length - b.coordinates.length)[0] || null;
}

export function operationBounds(from, to = from) {
  if (![from,to].every(p => Array.isArray(p) && p.length === 2 && p.every(Number.isFinite))) throw Error('Koordinat wilayah tidak valid');
  const bbox = [Math.min(from[1],to[1])-.025, Math.min(from[0],to[0])-.025, Math.max(from[1],to[1])+.025, Math.max(from[0],to[0])+.025].map(v => Math.round(v*1e6)/1e6);
  if (bbox[2]-bbox[0] > .090001 || bbox[3]-bbox[1] > .090001) throw Error('Awal dan tujuan terlalu jauh untuk satu paket operasi (~10 km). Routing lintas paket belum tersedia.');
  if (bbox[0] < 94 || bbox[2] > 142 || bbox[1] < -12 || bbox[3] > 7) throw Error('Wilayah berada di luar cakupan Indonesia');
  return bbox;
}

export async function createRegionalStore(bundled) {
  const packs = new Map(bundled.map(pack => [pack.id, checkedRegionalPackage(pack)]));
  let db = null, storageError = '';
  try {
    db = await new Promise((resolve,reject) => {
      const open = indexedDB.open('nusamesh-navigation-packages',1);
      open.onupgradeneeded = () => open.result.createObjectStore('packages',{keyPath:'id'});
      open.onsuccess = () => resolve(open.result); open.onerror = () => reject(open.error);
    });
    const saved = await new Promise((resolve,reject) => {
      const request = db.transaction('packages').objectStore('packages').getAll();
      request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    for (const pack of saved) { try { packs.set(pack.id,checkedRegionalPackage(pack)); } catch { /* invalid cache never replaces usable bundled data */ } }
  } catch { storageError = 'Penyimpanan offline tidak tersedia; paket unduhan hanya berlaku selama peta dibuka.'; }
  const commit = (method,value) => new Promise((resolve,reject) => {
    if (!db) { reject(Error(storageError)); return; }
    const tx = db.transaction('packages','readwrite'); tx.objectStore('packages')[method](value);
    tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || Error('Penyimpanan paket dibatalkan'));
  });
  return {
    all: () => [...packs.values()], select: (from,to) => selectRegionalPackage([...packs.values()],from,to), storageError,
    async install(pack) { checkedRegionalPackage(pack); await commit('put',pack); packs.set(pack.id,pack); },
    async remove(id) { if (bundled.some(pack => pack.id === id)) throw Error('Paket bawaan tidak dapat dihapus'); await commit('delete',id); packs.delete(id); },
    isBundled: id => bundled.some(pack => pack.id === id),
  };
}
