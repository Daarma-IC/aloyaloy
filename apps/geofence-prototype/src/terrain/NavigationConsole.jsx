import React, { useEffect, useRef, useState } from 'react';
import { distanceM } from '../domain.js';
import { routeProgress, validatePackage } from './engine.js';
import { loadTerrainPackage, saveTerrainPackage } from './storage.js';
import './navigation-console.css';
import { demoHybridCoordinates } from '../routing/hybrid.js';
import ElevationProfile from './ElevationProfile.jsx';

const VERSION = 'kaliurang-hybrid-v4';
const formatTime = seconds => `${Math.max(0, Math.ceil(seconds / 60))} menit`;

export default function NavigationConsole({ state, setState, pack, setPack, map, lastTap, selectedVictim, onClearVictim, navigationRequest }) {
  const [error, setError] = useState(''), [loading, setLoading] = useState(!pack || pack.synthetic), [playing, setPlaying] = useState(false);
  const [routingMode, setRoutingMode] = useState('Hybrid');
  const handledRequest = useRef(navigationRequest);
  const worker = useRef(null), alive = useRef(true), stateRef = useRef(state), mapRef = useRef(map);
  stateRef.current = state; mapRef.current = map;
  const result = state.navigation.terrainResult, ready = result?.status === 'available' && ['TERRAIN', 'MAPPED', 'HYBRID'].includes(state.navigation.mode);
  const progress = ready ? routeProgress(result.geometry, state.operator.pos, result.segments) : null;

  function preparePoints(dataset, options = {}) {
    const current = stateRef.current, from = options.from || current.operator.pos, to = options.to || current.victim.pos;
    const timestamp = Date.now();
    worker.current?.terminate(); setPlaying(false); setError(''); setLoading(false); onClearVictim();
    setState(s => ({ ...s, navigationDemoVersion: VERSION, ...(options.reset ? { simClock: timestamp, operation: { ...s.operation, name: 'Demo SAR Kaliurang - data medan nyata' }, terrain: { ...s.terrain, hazards: [], hazardVersion: 0 }, trackSession: { ...s.trackSession, points: [], state: 'IDLE' } } : {}), layers: { ...s.layers, sectors: false, nodes: false, tracks: false, terrain: false, paths: true, navigation: true, teams: true, victims: true }, operator: { ...s.operator, name: 'Petugas SAR (dummy)', pos: from, capturedAt: timestamp, accuracy: 8 }, victim: { ...s.victim, pos: to, capturedAt: timestamp, name: 'Korban 01', source: 'Koordinat dummy untuk demo' }, gpsAvailable: true, navigation: { mode: 'HYBRID', status: 'IDLE', route: [], terrainResult: null, metrics: null, steps: [], waypoints: [], distance: 0, duration: null, requestId: null, warning: 'Pilih titik korban lalu arahkan untuk menghitung rute.' } }));
    mapRef.current?.fitBounds([from, to], { padding: [65, 65], animate: false });
  }

  function compute(dataset, options = {}) {
    const current = stateRef.current, from = options.from || current.operator.pos, to = options.to || current.victim.pos;
    const timestamp = options.to ? Date.now() : current.victim.capturedAt;
    const hazards = options.reset ? [] : current.terrain.hazards;
    const hazardVersion = options.reset ? 0 : current.terrain.hazardVersion;
    const id = `navigation-${Date.now()}`;
    setError(''); setLoading(true); setPlaying(false);
    setState(s => ({ ...s, navigationDemoVersion: VERSION, ...(options.reset ? { simClock: timestamp, operation: { ...s.operation, name: 'Demo SAR Kaliurang - data medan nyata' }, terrain: { ...s.terrain, hazards: [], hazardVersion: 0 }, layers: { ...s.layers, sectors: false, nodes: false, tracks: false, terrain: false, paths: true, navigation: true, teams: true, victims: true }, trackSession: { ...s.trackSession, points: [], state: 'IDLE' } } : {}), operator: { ...s.operator, name: 'Petugas SAR (dummy)', pos: from, capturedAt: Date.now(), accuracy: 8 }, victim: { ...s.victim, pos: to, capturedAt: timestamp, name: 'Korban 01', source: 'Koordinat dummy untuk demo' }, gpsAvailable: true, navigation: { ...s.navigation, userRequested: true, mode: s.navigation.route.length ? s.navigation.mode : routingMode === 'Hybrid' ? 'HYBRID' : routingMode === 'MappedOSM' ? 'MAPPED' : 'TERRAIN', status: 'CALCULATING', requestId: id, ...(options.reset ? { route: [], terrainResult: null, metrics: null } : {}), warning: 'Menghitung rute dari data medan Kaliurang yang sebenarnya.' } }));
    worker.current?.terminate(); const job = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' }); worker.current = job;
    job.onmessage = event => {
      job.terminate(); if (!alive.current) return; setLoading(false);
      const data = event.data.result;
      if (!data || data.status !== 'available') { setError(data?.message || event.data.error || 'Perhitungan rute gagal'); setState(s => s.navigation.requestId === id ? { ...s, navigation: { ...s.navigation, status: s.navigation.route.length ? 'STALE' : 'noTraversablePath', warning: data?.message || 'Perhitungan gagal' } } : s); return; }
      setState(s => {
        if (s.navigation.requestId !== id) return s;
        if (s.terrain.hazardVersion !== hazardVersion || distanceM(s.victim.pos, to) > 1 || distanceM(s.operator.pos, from) > 1) return { ...s, navigation: { ...s.navigation, status: 'STALE', warning: 'Posisi/hambatan berubah saat query. Hitung ulang.' } };
        return { ...s, navigation: { userRequested: true, mode: data.engine === 'Hybrid' ? 'HYBRID' : data.engine === 'MappedOSM' ? 'MAPPED' : 'TERRAIN', status: 'READY', route: data.geometry, terrainResult: data, distance: Math.round(data.length), duration: data.durationEstimate, waypoints: [], steps: [], source: data.engine === 'Hybrid' ? 'Jalan OSM + lintasan medan + transisi tervalidasi' : data.engine === 'MappedOSM' ? 'Ruas OSM asli + DEM untuk ETA' : 'Lintasan medan DEM / tanpa jalur', warning: data.warnings.join(' '), demoIndex: 0, progressM: 0, deviationStartedAt: null } };
      });
      mapRef.current?.fitBounds(data.geometry, { padding: [65, 65], animate: false });
    };
    job.onerror = event => { job.terminate(); if (alive.current) { setLoading(false); setError(event.message || 'Worker routing gagal'); } };
    job.postMessage({ id, request: { engine: routingMode, pack: dataset, from, to, profileId: current.terrain.profileId || 'nominal', hazards, hazardVersion, targetPositionTimestamp: timestamp } });
  }

  useEffect(() => {
    alive.current = true; const abort = new AbortController();
    if (stateRef.current.navigationDemoVersion !== VERSION) setState(s => ({ ...s, navigation: { ...s.navigation, mode: 'TERRAIN', status: 'LOADING', route: [], terrainResult: null, requestId: null } }));
    fetch('/demo/kaliurang-terrain.json', { signal: abort.signal }).then(r => { if (!r.ok) throw Error('Paket data Kaliurang tidak tersedia'); return r.json(); }).catch(async e => { if (e.name === 'AbortError') throw e; const stored = await loadTerrainPackage(); if (stored?.id !== 'kaliurang-real-terrain' || stored.synthetic) throw e; return stored; }).then(async data => {
      validatePackage(data); if (data.synthetic) throw Error('Demo utama wajib menggunakan medan nyata');
      if (!alive.current) return; await saveTerrainPackage(data); if (!alive.current) return; setPack(data);
      const current = stateRef.current;
      const reset = current.navigationDemoVersion !== VERSION;
      const plan = current.navigation.terrainResult;
      const requested = current.navigation.userRequested || current.navigation.mode === 'MAPPED';
      if (!reset && requested && current.navigation.route.length && plan?.packageId === data.id && plan.packageVersion === data.datasetVersion) {
        setLoading(false); setRoutingMode(plan.engine === 'Hybrid' ? 'Hybrid' : plan.engine === 'MappedOSM' ? 'MappedOSM' : 'Terrain');
        if (current.navigation.status === 'ACTIVE') setState(s => ({ ...s, navigation: { ...s.navigation, status: 'PAUSED' } }));
        mapRef.current?.fitBounds(current.navigation.route, { padding: [65, 65], animate: false });
        return;
      }
      const demo = demoHybridCoordinates(data);
      preparePoints(data, { reset, from: reset ? demo.from : current.operator.pos, to: reset ? demo.to : current.victim.pos });
    }).catch(e => { if (alive.current && e.name !== 'AbortError') { setLoading(false); setError(e.message); } });
    return () => { alive.current = false; abort.abort(); worker.current?.terminate(); setState(s => s.navigation.requestId?.startsWith('navigation-') && s.navigation.status === 'CALCULATING' ? { ...s, navigation: { ...s.navigation, status: s.navigation.route.length ? 'STALE' : 'CANCELLED' } } : s); };
  }, []);

  useEffect(() => { if (navigationRequest !== handledRequest.current) { handledRequest.current = navigationRequest; if (selectedVictim && pack && !pack.synthetic) compute(pack); } }, [navigationRequest]);

  useEffect(() => {
    if (!playing || state.navigation.status !== 'ACTIVE' || !ready || !state.gpsAvailable) { if (playing) setPlaying(false); return; }
    const timer = setInterval(() => setState(s => {
      if (s.navigation.status !== 'ACTIVE' || !s.gpsAvailable) return s;
      const route = s.navigation.terrainResult, distance = Math.min(route.length, (s.navigation.progressM || 0) + 12);
      let index = route.segments.findIndex(segment => segment.cumulativeDistanceM >= distance); if (index < 0) index = route.segments.length - 1;
      const start = index ? route.segments[index - 1].cumulativeDistanceM : 0, fraction = route.segments[index].distanceM ? Math.min(1, (distance - start) / route.segments[index].distanceM) : 1;
      const a = route.geometry[index], b = route.geometry[index + 1], pos = [a[0] + (b[0] - a[0]) * fraction, a[1] + (b[1] - a[1]) * fraction];
      return { ...s, simClock: s.simClock + 1000, operator: { ...s.operator, pos, capturedAt: s.simClock + 1000 }, navigation: { ...s.navigation, progressM: distance, status: distance >= route.length ? 'ARRIVED' : 'ACTIVE' } };
    }), 500);
    return () => clearInterval(timer);
  }, [playing, state.navigation.status, state.gpsAvailable, ready]);

  function newTarget() {
    if (!pack) return;
    const options = demoHybridCoordinates(pack).candidates.filter(point => distanceM(state.operator.pos, point) > 650 && distanceM(state.victim.pos, point) > 120);
    if (!options.length) { setError('Tidak ada target dummy lain di paket ini'); return; }
    const target = options[(state.targetGeneration || 0) * 37 % options.length];
    setState(s => ({ ...s, targetGeneration: (s.targetGeneration || 0) + 1 })); preparePoints(pack, { to: target });
  }
  function blockAhead() {
    if (!ready || !progress) return;
    const point = result.geometry[Math.min(result.geometry.length - 2, progress.index + 5)], dy = 35 / 111195, dx = dy / Math.cos(point[0] * Math.PI / 180);
    const hazard = { id: `demo-hazard-${Date.now()}`, label: 'Laporan jalur tertutup', polygon: [[point[0] - dy, point[1] - dx], [point[0] - dy, point[1] + dx], [point[0] + dy, point[1] + dx], [point[0] + dy, point[1] - dx]], observedAt: state.simClock, verification: 'reported', blockingPolicy: 'block', reporter: state.operator.id };
    setPlaying(false); setState(s => ({ ...s, terrain: { ...s.terrain, hazards: [...s.terrain.hazards, hazard], hazardVersion: s.terrain.hazardVersion + 1 }, navigation: { ...s.navigation, status: 'STALE', warning: 'Jalur di depan dilaporkan tertutup. Hitung rute alternatif.' } }));
  }
  return <section className="navigation-console">
    <div className="nav-heading"><span>NAVIGASI SAR</span><h2>{ready ? 'Menuju Korban 01' : 'Pilih tujuan di peta'}</h2><p>Kaliurang · koordinat petugas dan korban dummy</p></div>
    <div className="real-data-label">MEDAN NYATA · DEM + WORLDCOVER + JALUR OSM</div>
    {loading && <p role="status" className="nav-loading">{state.navigation.status === 'CALCULATING' ? 'Menghitung rute menuju korban…' : 'Memuat lokasi petugas dan korban…'}</p>}
    {error && <p role="alert" className="nav-error">{error}</p>}
    {!loading && !ready && !selectedVictim && <p className="nav-loading">Titik biru adalah petugas, titik merah adalah korban. Klik titik korban untuk memilih tujuan.</p>}
    {!loading && !ready && selectedVictim && <div className="selected-victim"><strong>Korban 01 dipilih</strong><p>{state.victim.pos.map(x => x.toFixed(6)).join(', ')}</p><label>Jenis rute<select aria-label="Jenis rute" value={routingMode} onChange={e => setRoutingMode(e.target.value)}><option value="Hybrid">Otomatis: jalan dan medan</option><option value="MappedOSM">Hanya jalur OSM (pembanding)</option><option value="Terrain">Hanya medan DEM (pembanding)</option></select></label><button className="start-navigation" disabled={!pack || pack.synthetic} onClick={() => compute(pack)}>Arahkan ke korban</button><p>Rute dan estimasi waktu dihitung setelah diarahkan.</p></div>}
    {ready && <>
      <p className="route-state">{result.engine === 'Hybrid' ? 'Rute otomatis jalan dan medan' : result.engine === 'MappedOSM' ? 'Rute mengikuti jalur OSM asli' : 'Lintasan medan tanpa jalur (grid DEM)'} · total {Math.round(result.length)} m</p>
      {result.engine === 'Hybrid' && <p className="route-breakdown">Jalur OSM {Math.round(result.lengthsByKind.osm)} m · medan {Math.round(result.lengthsByKind.terrain)} m · transisi {Math.round(result.lengthsByKind.transition)} m</p>}
      <ElevationProfile route={result} pack={pack} map={map} status={state.navigation.status}/>
      <div className="navigation-next"><div className="bearing-arrow" style={{ transform: `rotate(${progress.bearing}deg)` }}>↑</div><div><small>Waypoint berikutnya · arah terhadap utara</small><strong>{Math.round(progress.nextWaypointDistance)} m</strong><span>{Math.round(progress.bearing)}° · ikuti segmen rute berikutnya</span></div></div>
      {['MappedOSM', 'Hybrid'].includes(result.engine) && <p className="nav-explanation">{result.segments[progress.index]?.kind === 'transition' ? 'Transisi antara jalan dan medan; ikuti garis jingga.' : result.segments[progress.index]?.kind === 'terrain' ? 'Di luar jalur terpetakan; ikuti kandidat lintasan ungu.' : `Ikuti ${result.segments[progress.index]?.name || 'jalur pejalan kaki'} menuju titik berikutnya.`}</p>}
      <div className="nav-summary"><div><small>Sisa rute</small><strong>{Math.round(progress.remaining)} m</strong></div><div><small>ETA model</small><strong>{formatTime(progress.remainingSeconds)}</strong></div><div><small>Naik / turun</small><strong>{Math.round(result.ascent)} / {Math.round(result.descent)} m</strong></div></div>
      <p className={`route-state ${state.navigation.status === 'STALE' ? 'stale' : ''}`}>{({ READY: 'Rute siap — mulai navigasi', ACTIVE: playing ? 'Navigasi aktif · koordinat bergerak dalam demo' : 'Navigasi aktif', PAUSED: 'Navigasi dijeda', STALE: 'Rute perlu diperbarui', ARRIVED: 'Tiba di koordinat korban — konfirmasi temuan' })[state.navigation.status] || state.navigation.status}</p>
      <button className="start-navigation" disabled={loading || !state.gpsAvailable || !['READY', 'PAUSED'].includes(state.navigation.status)} onClick={() => { setState(s => ({ ...s, navigation: { ...s.navigation, status: 'ACTIVE' } })); setPlaying(true); }}>Mulai navigasi ke korban</button>
      {state.navigation.status === 'ACTIVE' && <button className="nav-secondary" onClick={() => { setPlaying(false); setState(s => ({ ...s, navigation: { ...s.navigation, status: 'PAUSED' } })); }}>Jeda navigasi</button>}
      <p className="nav-explanation">Hanya posisi orang yang disimulasikan. Gerakan demo dipercepat 24 m/detik untuk melihat panduan berubah; ETA tetap dari model berjalan.</p>
    </>}
    <div className="person-coordinate"><span>● Petugas (dummy)</span><b>{state.operator.pos.map(x => x.toFixed(6)).join(', ')}</b><span>● Korban 01 (dummy)</span><b>{state.victim.pos.map(x => x.toFixed(6)).join(', ')}</b></div>
    <div className="nav-tools">{ready && <button disabled={loading || !pack || pack.synthetic} onClick={() => compute(pack)}>Hitung ulang dari posisi sekarang</button>}<button disabled={loading} onClick={newTarget}>Generate target dummy lain</button><button disabled={loading || !lastTap || !pack} onClick={() => preparePoints(pack, { to: lastTap })}>Titik peta menjadi target</button>{ready && <button disabled={loading} onClick={blockAhead}>Laporkan jalur di depan tertutup</button>}<button disabled={loading || !pack} onClick={() => { const demo = demoHybridCoordinates(pack); setRoutingMode('Hybrid'); preparePoints(pack, { reset: true, from: demo.from, to: demo.to }); }}>Reset perjalanan demo</button></div>
    <details className="dataset-details"><summary>Sumber data dan batas rute</summary><p>Rute otomatis menggabungkan geometri OSM asli, medan grid 35 m, dan transisi yang diperiksa terhadap sel serta hambatan. Air tidak dilintasi tanpa ruas jembatan/tunnel yang terpetakan. Bangunan OSM berupa way tertutup menjadi hambatan; bangunan multipolygon dan hambatan yang belum dipetakan belum tercakup. ETA masih model asumsi, belum kalibrasi lapangan atau LiDAR STRIDE.</p><p>{pack?.sources?.map(s => s.name).join(' · ')}</p><p>Biru: ruas OSM. Ungu putus-putus: medan tanpa jalur. Jingga: transisi. Garis rute muncul setelah korban dipilih dan diarahkan.</p></details>
  </section>;
}
