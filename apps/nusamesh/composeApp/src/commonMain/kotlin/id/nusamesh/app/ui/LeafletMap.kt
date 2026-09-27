package id.nusamesh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Peta Leaflet hidup; marker ditambahkan hanya jika data koordinat tersedia. */
@Composable
expect fun LeafletMap(modifier: Modifier)

internal val leafletHtml = """
<!doctype html>
<html lang="id">
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"
        integrity="sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=" crossorigin="">
  <style>
    html, body, #map { width:100%; height:100%; margin:0; padding:0; background:#e7fafa; }
    .leaflet-control-attribution { font:10px sans-serif !important; }
    .leaflet-top { top:94px; }
    .leaflet-control-zoom { border:1px solid #e2e8f0 !important; border-radius:8px !important; overflow:hidden; box-shadow:0 3px 12px #16203322 !important; }
    #search { position:absolute; z-index:1000; top:40px; left:16px; right:16px; height:42px; display:flex; align-items:center; gap:10px; box-sizing:border-box; padding:0 14px; background:white; border:1px solid #f1f5f9; border-radius:16px; box-shadow:0 3px 12px #16203314; font:14px system-ui,sans-serif; }
    #query { flex:1; min-width:0; border:0; outline:0; color:#1e293b; background:transparent; font:14px system-ui,sans-serif; }
    #query::placeholder { color:#64748b; }
    #clear { width:20px; height:20px; border:0; outline:0; border-radius:10px; color:white; background:#94a3b8; padding:0; cursor:pointer; line-height:18px; transition:transform 90ms ease,background 90ms ease; }
    #clear:active { transform:scale(.88); background:#64748b; }
    .leaflet-control-zoom a { transition:transform 90ms ease,background 90ms ease; }
    .leaflet-control-zoom a:active { transform:scale(.92); background:#e7fafa !important; }
    #error { position:absolute; z-index:1000; top:88px; left:16px; right:16px; color:#ef4444; font:11px system-ui,sans-serif; pointer-events:none; }
    #coordinate { position:absolute; z-index:999; left:16px; bottom:18px; padding:7px 10px; border-radius:12px; background:#ffffffdd; color:#475569; box-shadow:0 2px 10px #16203318; font:11px system-ui,sans-serif; pointer-events:none; }
  </style>
</head>
<body>
  <div id="map"></div>
  <form id="search">
    <span aria-hidden="true">&#9906;</span>
    <input id="query" autocomplete="off" placeholder="Cari lokasi tim, korban......" aria-label="Cari lokasi">
    <button id="clear" type="button" aria-label="Hapus pencarian">&times;</button>
  </form>
  <div id="error"></div>
  <div id="coordinate">Ketuk peta untuk menandai lokasi</div>
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
          integrity="sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=" crossorigin=""></script>
  <script>
    const map = L.map('map', {zoomControl:true, preferCanvas:true}).setView([-2.5, 117], 5);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom:19,
      attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(map);
    window.nusaMarkers = L.layerGroup().addTo(map);
    const searchMarkers = L.layerGroup().addTo(map);
    const form = document.getElementById('search');
    const query = document.getElementById('query');
    const error = document.getElementById('error');
    document.getElementById('clear').onclick = function() {
      query.value = '';
      error.textContent = '';
      searchMarkers.clearLayers();
      query.focus();
    };
    form.onsubmit = async function(event) {
      event.preventDefault();
      const term = query.value.trim();
      if (!term) return;
      error.textContent = '';
      try {
        const url = 'https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=' + encodeURIComponent(term);
        const response = await fetch(url, {headers:{'Accept':'application/json'}});
        if (!response.ok) throw new Error('HTTP ' + response.status);
        const places = await response.json();
        if (!places.length) { error.textContent = 'Lokasi tidak ditemukan'; return; }
        const latitude = Number(places[0].lat);
        const longitude = Number(places[0].lon);
        searchMarkers.clearLayers();
        L.circleMarker([latitude, longitude], {radius:9,color:'#fff',weight:3,fillColor:'#0284C7',fillOpacity:1})
          .addTo(searchMarkers).bindPopup(places[0].display_name).openPopup();
        map.setView([latitude, longitude], 14);
        query.blur();
      } catch (_) {
        error.textContent = 'Pencarian lokasi gagal. Cek koneksi internet.';
      }
    };
    const previewMarkers = L.layerGroup().addTo(map);
    const coordinate = document.getElementById('coordinate');
    map.on('click', function(event) {
      const latitude = Number(event.latlng.lat.toFixed(6));
      const longitude = Number(event.latlng.lng.toFixed(6));
      L.circleMarker([latitude, longitude], {
        radius:9, color:'#fff', weight:3, fillColor:'#10B981', fillOpacity:1
      }).addTo(previewMarkers)
        .bindPopup('Titik lokasi<br>' + latitude + ', ' + longitude)
        .openPopup();
      coordinate.textContent = latitude + ', ' + longitude;
    });
    window.setNusaUnits = function(units) {
      window.nusaMarkers.clearLayers();
      const bounds = [];
      for (const unit of units) {
        if (!Number.isFinite(unit.latitude) || !Number.isFinite(unit.longitude)) continue;
        L.circleMarker([unit.latitude, unit.longitude], {
          radius:9, color:'#fff', weight:3, fillColor:'#0284C7', fillOpacity:1
        }).addTo(window.nusaMarkers).bindPopup(String(unit.name || 'Unit'));
        bounds.push([unit.latitude, unit.longitude]);
      }
      if (bounds.length) map.fitBounds(bounds, {padding:[36,36], maxZoom:15});
    };
  </script>
</body>
</html>
""".trimIndent()

