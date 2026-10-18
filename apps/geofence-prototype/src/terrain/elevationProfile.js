import { distanceM } from '../domain.js';
import { terrainContext } from '../routing/hybrid.js';

// Resample the existing polyline, never move it or smooth its elevations.
// A smaller display interval adds interpolation samples, not source detail.
export function buildElevationProfile(route, pack) {
  if (!route?.geometry?.length || !pack || route.packageId !== pack.id || route.packageVersion !== pack.datasetVersion) return null;
  const context = terrainContext(pack), spacingM = pack.grid.resolutionM / 2, samples = [];
  let travelled = 0;
  function add(point, distance, segmentIndex) {
    const z = context.sample(point)?.elevationM ?? null, previous = samples.at(-1), d = previous ? distance - previous.distanceM : 0;
    samples.push({ index: samples.length, distanceM: distance, coordinate: point, elevationM: z, segmentIndex, kind: route.segments[segmentIndex]?.kind || (route.engine === 'MappedOSM' ? 'osm' : 'terrain'), osmWayId: route.segments[segmentIndex]?.osmWayId ?? null, gradePercent: d > 1e-7 && z !== null && previous.elevationM !== null ? (z - previous.elevationM) / d * 100 : null });
  }
  add(route.geometry[0], 0, 0);
  for (let i = 1; i < route.geometry.length; i++) {
    const a = route.geometry[i - 1], b = route.geometry[i], d = distanceM(a, b), steps = Math.max(1, Math.ceil(d / spacingM));
    if (d < 1e-7) continue;
    for (let j = 1; j <= steps; j++) { const t = j / steps; add([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t], travelled + d * t, i - 1); }
    travelled += d;
  }
  const elevations = samples.filter(s => s.elevationM !== null).map(s => s.elevationM), dem = pack.sources.find(s => s.role === 'dem') || pack.sources[0];
  return { samples, lengthM: travelled, spacingM, gridResolutionM: pack.grid.resolutionM, verticalDatum: pack.verticalDatum, dem, synthetic: pack.synthetic, packageId: pack.id, packageVersion: pack.datasetVersion, generatedAt: route.generatedAt, minM: elevations.length ? Math.min(...elevations) : null, maxM: elevations.length ? Math.max(...elevations) : null, routeGeometry: route.geometry };
}

const csvValue = value => `"${String(value ?? '').replaceAll('"', '""')}"`;
const fixed = (value, decimals) => Number.isFinite(value) ? value.toFixed(decimals) : '';
export function elevationCsv(profile, status = 'READY') {
  const header = ['sample_index', 'distance_m', 'latitude', 'longitude', 'dem_elevation_m', 'grade_percent', 'segment_kind', 'osm_way_id', 'grid_resolution_m', 'max_sample_spacing_m', 'vertical_datum', 'dem_source', 'dem_native_resolution_m_approx', 'package_version', 'route_generated_at', 'route_status'];
  const rows = profile.samples.map(s => [s.index, fixed(s.distanceM, 3), fixed(s.coordinate[0], 8), fixed(s.coordinate[1], 8), fixed(s.elevationM, 3), fixed(s.gradePercent, 3), s.kind, s.osmWayId, profile.gridResolutionM, profile.spacingM, profile.verticalDatum, profile.dem?.name, profile.dem?.nativeResolutionMApprox ?? profile.dem?.nativeResolutionM, profile.packageVersion, profile.generatedAt ? new Date(profile.generatedAt).toISOString() : '', status]);
  return '\uFEFF' + [header, ...rows].map(row => row.map(csvValue).join(',')).join('\r\n');
}

const xml = value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&apos;');
export function elevationKml(profile, status = 'READY') {
  const data = values => `<ExtendedData>${Object.entries(values).map(([key, value]) => `<Data name="${xml(key)}"><value>${xml(value)}</value></Data>`).join('')}</ExtendedData>`;
  const coords = point => `${point[1]},${point[0]},0`;
  // Ground clamping is intentional: Earth supplies its own elevations for
  // comparison instead of rendering our DEM values as authoritative heights.
  return `<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document>
<name>NusaSurvey - rute pembanding elevasi</name>
<description>${xml(`DEM: ${profile.dem?.name || 'Paket wilayah'}; datum ${profile.verticalDatum}; grid ${profile.gridResolutionM} m; status ${status}. Path mengikuti tanah Google Earth; nilai DEM NusaSurvey tersedia di titik sampel dan CSV.`)}</description>
<Style id="route"><LineStyle><color>ffff6325</color><width>4</width></LineStyle></Style>
<Placemark><name>Rute korban - profil tanah Google Earth</name><styleUrl>#route</styleUrl>${data({ package_version: profile.packageVersion, dem_source: profile.dem?.name, dem_vertical_datum: profile.verticalDatum, route_status: status })}<LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>${profile.routeGeometry.map(coords).join(' ')}</coordinates></LineString></Placemark>
<Folder><name>Sampel DEM NusaSurvey (aktifkan untuk inspeksi)</name><visibility>0</visibility>${profile.samples.map(s => `<Placemark><name>Sampel ${s.index} - ${Math.round(s.distanceM)} m</name>${data({ distance_m: fixed(s.distanceM, 3), nusasurvey_dem_elevation_m: fixed(s.elevationM, 3), vertical_datum: profile.verticalDatum, segment_kind: s.kind })}<Point><altitudeMode>clampToGround</altitudeMode><coordinates>${coords(s.coordinate)}</coordinates></Point></Placemark>`).join('')}</Folder>
</Document></kml>`;
}
