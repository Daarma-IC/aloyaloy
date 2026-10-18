import { distanceM } from '../domain.js';
import { routeHitsHazards } from '../terrain/engine.js';

export async function routeOsrm({ baseUrl, from, to, hazards = [], maxSnapM = 50, fetchImpl = fetch, timeoutMs = 15000 }) {
  const fail = (status, message) => ({ status, engine: 'OSRM', message, geometry: [] });
  if (!baseUrl) return fail('unavailable', 'OSRM foot lokal belum dikonfigurasi melalui VITE_OSRM_URL');
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const url = new URL(`${baseUrl.replace(/\/$/, '')}/route/v1/foot/${from[1]},${from[0]};${to[1]},${to[0]}`);
    if (!['http:', 'https:'].includes(url.protocol)) return fail('unavailable', 'URL OSRM harus HTTP/HTTPS');
    url.search = new URLSearchParams({ overview: 'full', geometries: 'geojson', steps: 'true', annotations: 'duration,distance' }).toString();
    const response = await fetchImpl(url.toString(), { signal: controller.signal });
    const data = await response.json();
    if (data.code === 'NoRoute') return fail('noTraversablePath', 'OSRM NoRoute: jaringan jalur tidak terhubung');
    if (data.code === 'NoSegment') return fail('missingData', 'OSRM NoSegment: endpoint tidak dapat di-snap');
    if (!response.ok || data.code !== 'Ok' || !data.routes?.length) return fail('unavailable', `OSRM ${data.code || response.status}`);
    const route = data.routes[0], geometry = route.geometry.coordinates.map(([lon, lat]) => [lat, lon]);
    const snapped = data.waypoints?.map(w => [w.location[1], w.location[0]]);
    if (!snapped || snapped.length < 2) return fail('unavailable', 'OSRM tidak memberi bukti snapping endpoint');
    const snaps = [distanceM(from, snapped[0]), distanceM(to, snapped.at(-1))];
    if (snaps.some(d => d > maxSnapM)) return fail('missingData', `Snapping terlalu jauh: ${snaps.map(d => Math.round(d)).join(' / ')} m (batas ${maxSnapM} m)`);
    if (routeHitsHazards([from, ...geometry, to], hazards).length) return fail('noTraversablePath', 'Geometry OSRM memotong hambatan lokal; cari alternatif terrain');
    return { status: 'available', engine: 'OSRM', geometry, length: route.distance, durationEstimate: route.duration, snaps, steps: route.legs.flatMap(leg => leg.steps).map(step => ({ name: step.name || 'jalur tanpa nama', distance: Math.round(step.distance), maneuver: step.maneuver.type })), warnings: ['Profil foot harus sudah dipakai saat preprocessing server; URL foot tidak mengubah profil mesin.', 'ETA OSRM untuk jaringan terpetakan; konektor endpoint dan kondisi lapangan belum diverifikasi.'] };
  } catch (e) { return fail('unavailable', e.name === 'AbortError' ? 'OSRM timeout' : `Server OSRM tidak terjangkau: ${e.message}`); }
  finally { clearTimeout(timer); }
}
