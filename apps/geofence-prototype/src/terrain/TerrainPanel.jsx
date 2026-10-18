import React, { useEffect, useRef, useState } from 'react';
import { distanceM } from '../domain.js';
import { LAND_CLASSES, MODEL_VERSION, PROFILES, routeProgress, validatePackage } from './engine.js';
import { createSyntheticPackage } from './fixture.js';
import { downloadJson, loadTerrainPackage, saveTerrainPackage } from './storage.js';

const duration = seconds => `${Math.round(seconds / 60)} menit`;
function boxAt(point, radiusM) { const dy = radiusM / 111195, dx = dy / Math.cos(point[0] * Math.PI / 180); return [[point[0] - dy, point[1] - dx], [point[0] - dy, point[1] + dx], [point[0] + dy, point[1] + dx], [point[0] + dy, point[1] - dx]]; }

export default function TerrainPanel({ state, setState, pack, setPack, map, lastTap }) {
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [ioBusy, setIoBusy] = useState(false);
  const workerRef = useRef(null), mounted = useRef(true);
  const terrain = state.terrain || { profileId: 'nominal', hazards: [], hazardVersion: 0 };
  const nav = state.navigation, result = nav.mode === 'TERRAIN' ? nav.terrainResult : null;
  const progress = result?.geometry?.length ? routeProgress(result.geometry, state.operator.pos, result.segments) : null;
  useEffect(() => {
    mounted.current = true;
    if (!pack) loadTerrainPackage().then(p => { if (p && mounted.current) { validatePackage(p); setPack(p); } }).catch(e => mounted.current && setError(e.message));
    return () => {
      mounted.current = false; workerRef.current?.terminate();
      setState(s => s.navigation.requestId?.startsWith('terrain-') && s.navigation.status === 'CALCULATING' ? { ...s, navigation: { ...s.navigation, status: s.navigation.route.length ? 'STALE' : 'CANCELLED', warning: 'Perhitungan dibatalkan ketika panel ditutup.' } } : s);
    };
  }, []);

  function changeTerrain(fn) { setState(s => ({ ...s, terrain: fn(s.terrain || terrain) })); }
  async function install(next, demo = false) {
    setError(''); setIoBusy(true);
    try {
      validatePackage(next); await saveTerrainPackage(next); setPack(next);
      setState(s => ({ ...s, ...(demo ? { operator: { ...s.operator, pos: next.demoStart, capturedAt: s.simClock, accuracy: 8 }, victim: { ...s.victim, pos: next.demoTarget, capturedAt: s.simClock, source: 'Target fixture sintetis' }, gpsAvailable: true, trackSession: { ...s.trackSession, state: 'IDLE', points: [] } } : {}), terrain: { ...terrain, hazards: [], hazardVersion: 0 }, navigation: { ...s.navigation, mode: 'TERRAIN', status: 'READY', route: [], steps: [], metrics: null, terrainResult: null, duration: null, distance: 0, warning: 'Paket siap. Pilih profil lalu hitung kandidat medan.' } }));
      map?.fitBounds(next.footprint, { padding: [25, 25] });
    } catch (e) { setError(`Paket tidak dipasang: ${e.message}`); } finally { setIoBusy(false); }
  }
  async function importFile(event) {
    const file = event.target.files?.[0]; event.target.value = ''; if (!file) return;
    if (file.size > 32 * 1024 * 1024) { setError('Paket maksimum 32 MB untuk prototype browser.'); return; }
    try { await install(JSON.parse(await file.text())); } catch (e) { setError(e.message); }
  }
  function calculate(objective = 'time') {
    if (!pack || !state.gpsAvailable) { setError('Paket medan dan GPS tersedia diperlukan.'); return; }
    setBusy(true); setError('');
    workerRef.current?.terminate(); const worker = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' }); workerRef.current = worker;
    const id = `terrain-${Date.now()}`;
    const request = { pack, from: state.operator.pos, to: state.victim.pos, profileId: terrain.profileId, hazards: terrain.hazards, hazardVersion: terrain.hazardVersion, targetPositionTimestamp: state.victim.capturedAt, objective };
    setState(s => ({ ...s, navigation: { ...s.navigation, mode: 'TERRAIN', status: 'CALCULATING', requestId: id, warning: 'Dijkstra lokal sedang menghitung kandidat medan…' } }));
    const finish = (data, problem) => {
      worker.terminate(); if (!mounted.current) return; setBusy(false);
      if (problem) { setError(problem); data = { status: 'unavailable', message: problem }; }
      setState(s => {
        if (s.navigation.requestId !== id) return s;
        if (s.terrain.hazardVersion !== request.hazardVersion || s.victim.capturedAt !== request.targetPositionTimestamp || distanceM(s.operator.pos, request.from) > 1) return { ...s, navigation: { ...s.navigation, status: 'STALE', warning: 'Input berubah saat perhitungan; hitung ulang.' } };
        if (data.status !== 'available') return { ...s, navigation: { ...s.navigation, status: s.navigation.route.length ? 'STALE' : data.status, lastRoutingStatus: data.status, warning: `${data.message}. ${s.navigation.route.length ? 'Rute sebelumnya dipertahankan sebagai outdated.' : 'Tidak ada rute; arah langsung harus dipilih sendiri.'}` } };
        const events = [{ id, type: 'ROUTING', title: 'Kandidat medan dihitung lokal', detail: `${Math.round(data.length)} m · ${data.explored} sel · ${data.computeMs.toFixed(1)} ms · ${pack.datasetVersion}`, time: s.simClock }, ...s.events].slice(0, 40);
        return { ...s, events, navigation: { mode: 'TERRAIN', status: 'READY', route: data.geometry, steps: [], waypoints: [], metrics: null, distance: Math.round(data.length), duration: data.durationEstimate, source: 'Terrain / Dijkstra lokal', terrainResult: data, requestSummary: { from: request.from, to: request.to, profileId: request.profileId, objective, packageId: pack.id, packageVersion: pack.datasetVersion, hazardVersion: request.hazardVersion, targetPositionTimestamp: request.targetPositionTimestamp }, demoIndex: 0, deviationStartedAt: null, warning: data.warnings.join(' ') } };
      });
      if (data?.status === 'available') map?.fitBounds(data.geometry, { padding: [30, 30] });
    };
    worker.onmessage = e => finish(e.data.result, e.data.error);
    worker.onerror = e => finish(null, e.message || 'Worker routing gagal');
    worker.postMessage({ id, request });
  }

  function addHazard(point, label = 'Hambatan lapangan', radius = 45) {
    if (!point) { setError('Ketuk peta untuk memilih lokasi hambatan.'); return; }
    setError('');
    const hazard = { id: `HZ-${Date.now()}`, polygon: boxAt(point, radius), observedAt: state.simClock, reporter: state.operator.id, verification: 'reported', blockingPolicy: 'block', severity: 'impassable', label, reviewAt: state.simClock + 3600000 };
    setState(s => ({ ...s, terrain: { ...s.terrain, hazards: [...s.terrain.hazards, hazard], hazardVersion: s.terrain.hazardVersion + 1 }, navigation: { ...s.navigation, status: s.navigation.route.length ? 'STALE' : s.navigation.status, warning: 'Versi hambatan berubah; rute outdated. Hitung ulang sebelum melanjutkan.' }, events: [{ id: hazard.id, type: 'FINDING', title: label, detail: 'Zona larangan lokal · laporan belum diverifikasi lapangan', time: s.simClock }, ...s.events].slice(0, 40) }));
  }
  function blockBridge() { if (pack?.bridgeCell != null) addHazard(pack.coordinates[pack.bridgeCell], 'Crossing fixture ditutup', pack.grid.resolutionM * 0.7); }
  function stepAlongRoute() {
    if (!result || nav.status !== 'ACTIVE' || !state.gpsAvailable) return;
    setState(s => {
      const route = s.navigation.terrainResult.geometry, index = Math.min((s.navigation.demoIndex || 0) + 1, route.length - 1), dt = Math.max(1000, Math.round(s.navigation.terrainResult.segments[index - 1].seconds * 1000)), time = s.simClock + dt;
      const point = { coords: route[index], accuracy: 8, valid: true, capturedAt: time, gapMs: dt };
      return { ...s, simClock: time, operator: { ...s.operator, pos: point.coords, capturedAt: time, accuracy: 8 }, trackSession: { ...s.trackSession, points: s.trackSession.state === 'ACTIVE' ? [...s.trackSession.points, point] : s.trackSession.points }, navigation: { ...s.navigation, demoIndex: index, status: index === route.length - 1 ? 'ARRIVED' : 'ACTIVE' } };
    });
  }
  const path = result?.elevationProfile || [], min = result?.elevationMin || 0, max = result?.elevationMax || min + 1;
  const svgPoints = path.map(p => `${10 + p.distanceM / Math.max(result.length, 1) * 300},${85 - (p.elevationM - min) / Math.max(max - min, 1) * 65}`).join(' ');
  return <section className="terrain-panel">
    <h3>Mesin medan NusaSurvey</h3>
    <div className="terrain-badge">{pack ? pack.synthetic ? 'DATA SINTETIS · DEMO' : 'PAKET RASTER IMPOR' : 'PAKET BELUM TERSEDIA'}</div>
    <p>{pack ? `${pack.id} · ${pack.datasetVersion} · ${pack.grid.rows} × ${pack.grid.cols} sel · ${pack.grid.resolutionM} m` : 'Impor paket hasil preprocessing atau muat fixture tanpa jalan.'}</p>
    <div className="terrain-actions">
      <button disabled={busy || ioBusy} onClick={() => install(createSyntheticPackage(), true)}>Muat demo medan</button>
      <label className="file-button">Impor paket JSON<input type="file" accept=".json,application/json" disabled={busy || ioBusy} onChange={importFile} /></label>
      {pack && <button disabled={busy} onClick={() => downloadJson(`${pack.id}.json`, pack)}>Ekspor paket</button>}
    </div>
    {pack && <>
      <p className="note">{pack.sources.map(s => s.name).join(' · ')}<br/>CRS: {pack.crs} · datum: {pack.verticalDatum}. Peta OSM di bawahnya bukan sumber elevasi fixture.</p>
      <label>Profil tim<select disabled={busy} value={terrain.profileId} onChange={e => { changeTerrain(t => ({ ...t, profileId: e.target.value })); setState(s => ({ ...s, navigation: { ...s.navigation, status: s.navigation.route.length ? 'STALE' : s.navigation.status } })); }}>{Object.values(PROFILES).map(p => <option key={p.id} value={p.id}>{p.label} · batas grade {p.maxSlopeDeg}°</option>)}</select></label>
      <div className="terrain-actions"><button className="primary" disabled={busy || ioBusy || !state.gpsAvailable} onClick={() => calculate()}>Hitung / reroute medan</button><button disabled={busy || !state.gpsAvailable} onClick={() => calculate('distance')}>Baseline jarak</button></div>
      <div className="terrain-actions"><button disabled={busy || !lastTap} onClick={() => setState(s => ({ ...s, gpsAvailable: true, operator: { ...s.operator, pos: lastTap, capturedAt: s.simClock, gpsAvailable: true }, navigation: { ...s.navigation, status: s.navigation.route.length ? 'STALE' : s.navigation.status, warning: 'Posisi petugas diganti dari titik peta dalam simulator. Hitung ulang.' } }))}>Titik peta → petugas (simulasi)</button><button disabled={busy || !lastTap} onClick={() => setState(s => ({ ...s, victim: { ...s.victim, pos: lastTap, capturedAt: s.simClock, source: 'Target dipilih pada peta simulator' }, navigation: { ...s.navigation, status: s.navigation.route.length ? 'STALE' : s.navigation.status, warning: 'Target diganti dari titik peta. Hitung ulang.' } }))}>Titik peta → target (simulasi)</button></div>
      <div className="terrain-actions"><button disabled={busy} onClick={() => addHazard(lastTap)}>Blokir titik peta (±45 m)</button>{pack.bridgeCell != null && <button disabled={busy} onClick={blockBridge}>Tutup crossing demo</button>}<button disabled={busy || !terrain.hazards.length} onClick={() => setState(s => ({ ...s, terrain: { ...s.terrain, hazards: [], hazardVersion: s.terrain.hazardVersion + 1 }, navigation: { ...s.navigation, status: s.navigation.route.length ? 'STALE' : s.navigation.status, warning: 'Hambatan dihapus. Hitung ulang dengan versi terbaru.' } }))}>Hapus hambatan</button></div>
      <p className="note">{terrain.hazards.length} hambatan · versi {terrain.hazardVersion}. Sektor penugasan tidak dijadikan barrier.</p>
      <ul className="hazard-list">{terrain.hazards.map(h => <li key={h.id}>{h.label} · {h.verification}</li>)}</ul>
    </>}
    {busy && <p role="status">Menghitung di Web Worker lokal…</p>}{error && <p className="terrain-error" role="alert">{error}</p>}
    {result && <>
      <div className="terrain-stats"><span>Horizontal<b>{Math.round(result.length)} m</b></span><span>3D berbasis grid<b>{Math.round(result.length3d)} m</b></span><span>ETA model<b>{duration(result.durationEstimate)}</b></span><span>Naik / turun<b>{Math.round(result.ascent)} / {Math.round(result.descent)} m</b></span><span>Elevasi min / maks<b>{Math.round(result.elevationMin)} / {Math.round(result.elevationMax)} m</b></span><span>Query<b>{result.computeMs.toFixed(1)} ms · {result.explored} sel</b></span></div>
      <svg viewBox="0 0 320 100" className="elevation-chart" role="img" aria-label="Profil elevasi sepanjang kandidat rute"><line x1="10" y1="85" x2="310" y2="85" stroke="#a8b9ae"/><polyline points={svgPoints} fill="none" stroke="#15803d" strokeWidth="2"/><text x="10" y="98">0 m</text><text x="240" y="98">{Math.round(result.length)} m</text></svg>
      <p className="note">{Object.entries(result.coverLengths).map(([key, length]) => `${LAND_CLASSES[key] || 'Unknown'} ${Math.round(length / Math.max(result.length, 1) * 100)}%`).join(' · ')}<br/>Model {MODEL_VERSION} · {result.objective} · paket {result.packageVersion} · hazard {result.hazardVersion}. Kandidat belum diverifikasi lapangan.</p>
      <div className="terrain-actions"><button disabled={!['READY', 'PAUSED'].includes(nav.status) || !state.gpsAvailable} onClick={() => setState(s => ({ ...s, navigation: { ...s.navigation, status: 'ACTIVE' } }))}>Mulai / lanjut navigasi</button><button disabled={nav.status !== 'ACTIVE'} onClick={() => setState(s => ({ ...s, navigation: { ...s.navigation, status: 'PAUSED' } }))}>Jeda</button><button disabled={nav.status !== 'ACTIVE' || !state.gpsAvailable} onClick={stepAlongRoute}>Langkah GPS di rute (simulasi)</button></div>
      {progress && <p className="waypoint-guide">Waypoint {progress.index + 2}: {Math.round(progress.nextWaypointDistance)} m · bearing {Math.round(progress.bearing)}° utara<br/>Sisa sepanjang rute {Math.round(progress.remaining)} m · {duration(progress.remainingSeconds)}{nav.status === 'ARRIVED' && <strong> Mendekati koordinat; korban belum dikonfirmasi ditemukan.</strong>}</p>}
      <button onClick={() => downloadJson('nusasurvey-route-experiment.json', { request: nav.requestSummary, result, hazards: terrain.hazards, profile: PROFILES[result.profileId], package: { id: pack?.id, sources: pack?.sources, synthetic: pack?.synthetic }, exportedAt: new Date().toISOString() })}>Ekspor request / hasil rute</button>
    </>}
  </section>;
}
