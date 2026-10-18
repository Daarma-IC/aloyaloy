import React, { useEffect, useMemo, useState } from 'react';
import L from 'leaflet';
import { buildElevationProfile, elevationCsv, elevationKml } from './elevationProfile.js';
import { downloadText } from './storage.js';
import './elevation-profile.css';

export default function ElevationProfile({ route, pack, map, status }) {
  const profile = useMemo(() => buildElevationProfile(route, pack), [route, pack]);
  const [index, setIndex] = useState(0);
  useEffect(() => setIndex(0), [profile]);
  const selected = profile?.samples[Math.min(index, profile.samples.length - 1)];
  useEffect(() => {
    if (!map || !selected) return;
    const marker = L.circleMarker(selected.coordinate, { radius: 7, color: '#ffffff', weight: 2, fillColor: '#0f766e', fillOpacity: 1, interactive: false, className: 'elevation-sample-marker' }).addTo(map);
    return () => marker.remove();
  }, [map, selected]);
  if (!profile) return <p className="nav-error">Profil elevasi belum tersedia: versi paket tidak cocok dengan rute. Hitung ulang rute.</p>;
  const W = 440, H = 220, left = 55, right = 16, top = 20, bottom = 40;
  const min = profile.minM ?? 0, max = profile.maxM ?? min, pad = Math.max(5, (max - min) * .12), lo = Math.floor((min - pad) / 10) * 10, hi = Math.ceil((max + pad) / 10) * 10;
  const x = d => left + d / Math.max(profile.lengthM, 1) * (W - left - right), y = z => H - bottom - (z - lo) / Math.max(hi - lo, 1) * (H - top - bottom);
  const valid = profile.samples.filter(s => s.elevationM !== null), stale = status === 'STALE' || status === 'CALCULATING';
  function inspect(event) {
    const bounds = event.currentTarget.getBoundingClientRect(), px = (event.clientX - bounds.left) / bounds.width * W, distance = Math.max(0, Math.min(profile.lengthM, (px - left) / (W - left - right) * profile.lengthM));
    let nearest = 0; profile.samples.forEach((s, i) => { if (Math.abs(s.distanceM - distance) < Math.abs(profile.samples[nearest].distanceM - distance)) nearest = i; }); setIndex(nearest);
  }
  return <section className="route-elevation" aria-label="Profil elevasi rute">
    <h3>Profil elevasi rute</h3>
    <p className="elevation-subtitle">Elevasi DEM (m) terhadap jarak sepanjang rute (m)</p>
    {stale && <p className="elevation-stale">Profil rute sebelumnya; hitung ulang sebelum memakai rute ini.</p>}
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Grafik elevasi DEM sepanjang rute ke korban" onPointerMove={inspect} onClick={inspect}>
      <title>Profil elevasi DEM dari petugas menuju korban</title>
      {[0, 1, 2, 3, 4].map(i => { const value = lo + (hi - lo) * i / 4; return <g key={i}><line x1={left} y1={y(value)} x2={W - right} y2={y(value)} stroke="#dce5eb"/><text x={left - 7} y={y(value) + 4} textAnchor="end">{Math.round(value)}</text></g>; })}
      {[0, .25, .5, .75, 1].map(t => <g key={t}><line x1={x(profile.lengthM * t)} y1={H - bottom} x2={x(profile.lengthM * t)} y2={H - bottom + 4} stroke="#64748b"/><text x={x(profile.lengthM * t)} y={H - bottom + 19} textAnchor="middle">{Math.round(profile.lengthM * t)}</text></g>)}
      <text x={left} y={12}>Elevasi (m)</text><text x={W - right} y={H - 3} textAnchor="end">Jarak rute (m)</text>
      {profile.samples.slice(1).map((s, i) => { const a = profile.samples[i]; return a.elevationM === null || s.elevationM === null ? null : <line key={i} x1={x(a.distanceM)} y1={y(a.elevationM)} x2={x(s.distanceM)} y2={y(s.elevationM)} stroke={s.kind === 'osm' ? '#2563eb' : s.kind === 'transition' ? '#d97706' : '#9333ea'} strokeWidth="2.5"/>; })}
      {selected?.elevationM !== null && <g><line x1={x(selected.distanceM)} x2={x(selected.distanceM)} y1={top} y2={H - bottom} stroke="#0f766e" strokeDasharray="4 3"/><circle cx={x(selected.distanceM)} cy={y(selected.elevationM)} r="4" fill="#0f766e" stroke="white" strokeWidth="1.5"/></g>}
    </svg>
    <label className="elevation-slider">Pilih sampel elevasi<input type="range" min="0" max={profile.samples.length - 1} value={Math.min(index, profile.samples.length - 1)} onChange={e => setIndex(Number(e.target.value))}/></label>
    <div className="elevation-readout" aria-live="polite"><strong>{selected.elevationM === null ? 'NoData' : `${selected.elevationM.toFixed(1)} m`}</strong><span>Jarak {selected.distanceM.toFixed(1)} m · {({ osm: 'jalur OSM', terrain: 'medan', transition: 'transisi' })[selected.kind]}</span><code>{selected.coordinate.map(v => v.toFixed(6)).join(', ')}</code><span>{selected.gradePercent === null ? 'Awal rute' : `Grade antar sampel ${selected.gradePercent.toFixed(1)}%`}</span></div>
    <div className="elevation-extrema"><span>Minimum <b>{min.toFixed(1)} m</b></span><span>Maksimum <b>{max.toFixed(1)} m</b></span></div>
    <p className="elevation-source">{profile.dem?.name || 'DEM paket wilayah'} · datum {profile.verticalDatum}<br/>Grid {profile.gridResolutionM} m · interval sampel ≤ {profile.spacingM} m (interpolasi bilinear, tanpa smoothing).{profile.dem?.nativeResolutionMApprox && <> Resolusi sumber sekitar {Math.round(profile.dem.nativeResolutionMApprox)} m.</>}</p>
    <p className="elevation-note">Sampel rapat tidak menambah detail sumber. Elevasi ini berasal dari DEM, bukan pengukuran GPS atau validasi lapangan.{valid.length !== profile.samples.length && ' Sebagian sampel NoData; grafik terputus pada data hilang.'}</p>
    <div className="elevation-export"><button onClick={() => downloadText('nusasurvey-route-google-earth.kml', elevationKml(profile, status), 'application/vnd.google-earth.kml+xml')}>Unduh rute KML</button><button onClick={() => downloadText('nusasurvey-elevation.csv', elevationCsv(profile, status), 'text/csv;charset=utf-8')}>Unduh elevasi CSV</button></div>
    <details><summary>Bandingkan dengan Google Earth</summary><p>Buka KML di Google Earth Pro, pilih path “Rute korban - profil tanah Google Earth”, lalu Show Elevation Profile. KML mengikuti terrain Google Earth; angka DEM NusaSurvey tidak dipaksakan sebagai altitude path. Gunakan koordinat dan jarak sampel CSV untuk membandingkan titik yang sama.</p><p>Perbedaan sumber, resolusi, datum, vegetasi, dan bangunan dapat memengaruhi hasil. Kecocokan dengan Google Earth belum membuktikan keterlintasan jurang di lapangan.</p><a href="https://support.google.com/earth/answer/148134?hl=en" target="_blank" rel="noreferrer">Panduan profil elevasi Google Earth</a></details>
  </section>;
}
