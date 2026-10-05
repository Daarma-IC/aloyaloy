package id.nusamesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import nusamesh.composeapp.generated.resources.Res
import id.nusamesh.app.domain.MapWaypoint
import id.nusamesh.app.domain.SharedRoute
import id.nusamesh.app.domain.TrackedUser
import id.nusamesh.app.mesh.protocol.GeoPoint

/**
 * Leaflet dan peta vektor regional dibundel di aplikasi. Ubin OpenStreetMap bersifat opsional dan
 * hanya menambah detail jalan saat jaringan tersedia.
 *
 * HTML dimuat sekali; perubahan data (unit, jejak, rute, titik) dikirim ke `window.setNusaData`
 * tanpa memuat ulang halaman, jadi zoom/geser peta tidak ter-reset saat GPS atau rute berubah.
 */
@Composable
fun LeafletMap(modifier: Modifier, data: MapData = MapData(), onMapTap: (GeoPoint) -> Unit = {}) {
    val htmlResult by produceState<Result<String>?>(null) {
        value = runCatching {
            buildLeafletHtml(
                js = Res.readBytes("files/leaflet/leaflet.js").decodeToString(),
                css = Res.readBytes("files/leaflet/leaflet.css").decodeToString(),
                offlineRegion = Res.readBytes("files/maps/natural-earth-se-asia.geojson").decodeToString(),
            )
        }
    }
    val dataJson = remember(data) { data.toJson() }
    Box(modifier.background(BrandTint), contentAlignment = Alignment.Center) {
        when {
            htmlResult == null -> Text("Memuat peta...", color = Slate, fontSize = 11.sp)
            htmlResult?.isFailure == true -> Text("Aset peta gagal dimuat", color = Slate, fontSize = 11.sp)
            else -> LeafletWebView(Modifier.fillMaxSize(), htmlResult!!.getOrThrow(), dataJson) { lat, lon ->
                onMapTap(GeoPoint(lat, lon))
            }
        }
    }
}

/** Isi dinamis peta. [guide] = garis arahan dari posisi sendiri ke tujuan navigasi aktif. */
data class MapData(
    val units: List<TrackedUser> = emptyList(),
    val routes: List<SharedRoute> = emptyList(),
    val waypoints: List<MapWaypoint> = emptyList(),
    val draft: List<GeoPoint>? = null,
    val picked: GeoPoint? = null,
    val guide: Pair<GeoPoint, GeoPoint>? = null,
    val guideLabel: String? = null,
    /** Berubah saat tujuan navigasi berganti: peta menyesuaikan zoom sekali. */
    val focusKey: String? = null,
    /** Paket peta offline (MBTiles) yang diimpor; disajikan WebView di /mbtiles/{z}/{x}/{y}. */
    val offlinePack: OfflinePackInfo? = null,
    /** Kunci operasi aktif: label "terverifikasi / belum terverifikasi" bermakna. */
    val haveKey: Boolean = false,
    /** Label status cepat terakhir per peerId (tampil di popup unit). */
    val statuses: Map<String, String> = emptyMap(),
)

/**
 * WebView platform yang memuat [html] sekali dengan base URL [MAP_BASE_URL] (dipakai sebagai Referer
 * ubin OSM), lalu meneruskan [dataJson] ke `window.setNusaData` setiap kali berubah. Ketukan peta
 * dilaporkan lewat [onMapTap].
 */
@Composable
expect fun LeafletWebView(modifier: Modifier, html: String, dataJson: String, onMapTap: (Double, Double) -> Unit)

internal const val MAP_BASE_URL = "https://meshta.app/"

internal fun buildLeafletHtml(
    js: String,
    css: String,
    offlineRegion: String,
    dataJson: String = "null",
): String = leafletHtml
    .replace(LEAFLET_CSS_TAG, "<style>$css</style>")
    .replace(LEAFLET_JS_TAG, "<script>$js</script>")
    .replace(OFFLINE_REGION_TAG, offlineRegion)
    .replace(MAP_DATA_TAG, dataJson)

private const val LEAFLET_CSS_TAG = "<!--LEAFLET_CSS-->"
private const val LEAFLET_JS_TAG = "<!--LEAFLET_JS-->"
private const val OFFLINE_REGION_TAG = "/*OFFLINE_REGION*/"
private const val MAP_DATA_TAG = "/*MAP_DATA*/"

/** String JSON yang juga aman disisipkan sebagai literal JavaScript. */
internal fun jsonString(value: String) = buildString {
    append('"')
    for (c in value) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c < ' ' || c == '\u2028' || c == '\u2029' || c == '<' ->
            append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else -> append(c)
    }
    append('"')
}

private fun GeoPoint.json() = "[$latitude,$longitude]"
private fun List<GeoPoint>.json() = joinToString(prefix = "[", postfix = "]") { it.json() }

internal fun MapData.toJson(): String = buildString {
    fun trust(own: Boolean, verified: Boolean) = trustLabel(own, verified, haveKey).removePrefix(" | ")
    append("{\"units\":")
    append(units.joinToString(prefix = "[", postfix = "]") { unit ->
        "{\"peerId\":${jsonString(unit.peerId)},\"name\":${jsonString(unit.name)},\"latitude\":${unit.latitude}," +
            "\"longitude\":${unit.longitude},\"accuracy\":${unit.accuracyMeters},\"rssi\":${unit.rssi ?: "null"}," +
            "\"direct\":${unit.direct},\"own\":${unit.own},\"emergency\":${unit.emergency}," +
            "\"battery\":${unit.batteryPercent ?: "null"},\"status\":${statuses[unit.peerId]?.let(::jsonString) ?: "null"}}"
    })
    append(",\"routes\":")
    append(routes.joinToString(prefix = "[", postfix = "]") { route ->
        val segments = route.segments.entries.sortedBy { it.key }.joinToString(prefix = "[", postfix = "]") { it.value.json() }
        "{\"id\":${jsonString(route.id)},\"name\":${jsonString(route.name)},\"owner\":${jsonString(route.ownerName)}," +
            "\"kind\":${jsonString(route.kind.name)},\"own\":${route.own},\"trust\":${jsonString(trust(route.own, route.verified))}," +
            "\"segments\":$segments}"
    })
    append(",\"waypoints\":")
    append(waypoints.joinToString(prefix = "[", postfix = "]") { wp ->
        "{\"id\":${jsonString(wp.id)},\"type\":${jsonString(wp.type.code)},\"typeLabel\":${jsonString(wp.type.label)}," +
            "\"label\":${jsonString(listOfNotNull(wp.victim?.summary(), wp.label.ifBlank { null }).joinToString(" · "))}," +
            "\"owner\":${jsonString(wp.ownerName)},\"point\":${wp.point.json()}," +
            "\"count\":${wp.victim?.count ?: "null"},\"triage\":${wp.victim?.triage?.name?.let(::jsonString) ?: "null"}," +
            "\"evacuated\":${wp.victim?.evacuated == true},\"trust\":${jsonString(trust(wp.own, wp.verified))}}"
    })
    append(",\"draft\":").append(draft?.json() ?: "null")
    append(",\"picked\":").append(picked?.json() ?: "null")
    append(",\"guide\":").append(guide?.let { "[${it.first.json()},${it.second.json()}]" } ?: "null")
    append(",\"guideLabel\":").append(guideLabel?.let(::jsonString) ?: "null")
    append(",\"focusKey\":").append(focusKey?.let(::jsonString) ?: "null")
    append(",\"pack\":").append(offlinePack?.let { pack ->
        "{\"name\":${jsonString(pack.name)},\"minZoom\":${pack.minZoom},\"maxZoom\":${pack.maxZoom}," +
            "\"bounds\":${pack.bounds?.joinToString(prefix = "[", postfix = "]") ?: "null"}}"
    } ?: "null")
    append('}')
}

internal val leafletHtml = """
<!doctype html>
<html lang="id">
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <!--LEAFLET_CSS-->
  <style>
    html, body, #map { width:100%; height:100%; margin:0; padding:0; background:transparent; }
    .leaflet-control-attribution { font:10px sans-serif !important; }
    .leaflet-top { top:94px; }
    .leaflet-control-zoom { border:1px solid #e2e8f0 !important; border-radius:8px !important; overflow:hidden; box-shadow:0 3px 12px #16203322 !important; }
    #search { position:absolute; z-index:1000; top:40px; left:16px; right:16px; height:42px; display:flex; align-items:center; gap:10px; box-sizing:border-box; padding:0 14px; background:white; border:1px solid #f1f5f9; border-radius:16px; box-shadow:0 3px 12px #16203314; font:14px system-ui,sans-serif; }
    #query { flex:1; min-width:0; border:0; outline:0; color:#0F172A; background:transparent; font:14px system-ui,sans-serif; }
    #query::placeholder { color:#64748b; }
    #clear { width:20px; height:20px; border:0; outline:0; border-radius:10px; color:white; background:#94a3b8; padding:0; cursor:pointer; line-height:18px; transition:transform 90ms ease,background 90ms ease; }
    #clear:active { transform:scale(.88); background:#64748b; }
    .leaflet-control-zoom a { transition:transform 90ms ease,background 90ms ease; }
    .leaflet-control-zoom a:active { transform:scale(.92); background:#EEF2FF !important; }
    #error { position:absolute; z-index:1000; top:88px; left:16px; right:16px; color:#ef4444; font:11px system-ui,sans-serif; pointer-events:none; }
    #offline { display:none; position:absolute; z-index:1000; left:16px; right:16px; top:94px; padding:10px 12px; border-radius:12px; background:#FFF4E5; color:#B45309; font:12px system-ui,sans-serif; box-shadow:0 2px 10px #16203318; pointer-events:none; }
    #coordinate { position:absolute; z-index:999; left:16px; bottom:18px; padding:7px 10px; border-radius:12px; background:#ffffffdd; color:#475569; box-shadow:0 2px 10px #16203318; font:11px system-ui,sans-serif; pointer-events:none; }
    #map-status { position:absolute; z-index:1001; left:50%; top:50%; transform:translate(-50%,-50%); padding:9px 12px; border-radius:10px; background:#ffffffdd; color:#475569; font:12px system-ui,sans-serif; }
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
  <div id="offline">Mode offline: peta wilayah tersedia, detail jalan memerlukan internet.</div>
  <div id="coordinate">Ketuk peta untuk menandai lokasi</div>
  <div id="map-status">Menyiapkan peta...</div>
  <!--LEAFLET_JS-->
  <script>
    const map = window.nusaMap = L.map('map', {zoomControl:true}).setView([-2.5, 117], 5);
    const initialData = /*MAP_DATA*/;
    document.getElementById('map-status').style.display = 'none';
    const offlineRegion = /*OFFLINE_REGION*/;
    const offlineLayer = L.geoJSON(offlineRegion, {
      style: function() { return {color:'#8296ad',weight:1,fillColor:'#f8fafc',fillOpacity:1}; },
      onEachFeature: function(feature, layer) {
        if (feature.properties && feature.properties.name) layer.bindTooltip(feature.properties.name);
      }
    }).addTo(map);
    L.control.attribution({position:'bottomright', prefix:false})
      .addAttribution('Offline map: Natural Earth (public domain)').addTo(map);
    let loadedTileCount = 0;
    const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom:19,
      attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(map);
    const offline = document.getElementById('offline');
    tiles.on('tileerror', function() {
      if (!map.hasLayer(offlineLayer)) offlineLayer.addTo(map);
      offline.style.display = 'block';
    });
    tiles.on('tileload', function() {
      loadedTileCount += 1;
      if (map.hasLayer(offlineLayer)) map.removeLayer(offlineLayer);
      offline.style.display = 'none';
    });
    setTimeout(function() {
      if (loadedTileCount === 0) offline.style.display = 'block';
    }, 8000);
    // WebView sering dibuat saat ukurannya masih 0×0: tanpa ini Leaflet tidak pernah memuat ubin.
    const fixSize = function() { map.invalidateSize(false); };
    if (window.ResizeObserver) new ResizeObserver(fixSize).observe(document.getElementById('map'));
    window.addEventListener('resize', fixSize);
    [100, 400, 1200].forEach(function(ms) { setTimeout(fixSize, ms); });
    window.nusaMarkers = L.layerGroup().addTo(map);
    window.nusaGuidance = L.layerGroup().addTo(map);
    const searchMarkers = L.layerGroup().addTo(map);
    const form = document.getElementById('search');
    const query = document.getElementById('query');
    const error = document.getElementById('error');
    const offlinePlaces = [
      ['jakarta',-6.2088,106.8456,'Jakarta'],['bandung',-6.9175,107.6191,'Bandung'],
      ['surabaya',-7.2575,112.7521,'Surabaya'],['yogyakarta',-7.7956,110.3695,'Yogyakarta'],
      ['semarang',-6.9667,110.4167,'Semarang'],['medan',3.5952,98.6722,'Medan'],
      ['padang',-0.9471,100.4172,'Padang'],['palembang',-2.9909,104.7566,'Palembang'],
      ['denpasar',-8.6705,115.2126,'Denpasar'],['pontianak',-0.0263,109.3425,'Pontianak'],
      ['banjarmasin',-3.3186,114.5944,'Banjarmasin'],['makassar',-5.1477,119.4327,'Makassar'],
      ['manado',1.4748,124.8421,'Manado'],['ambon',-3.6954,128.1814,'Ambon'],
      ['jayapura',-2.5916,140.6690,'Jayapura'],['kupang',-10.1772,123.6070,'Kupang']
    ];
    function showPlace(latitude, longitude, label, zoom) {
      searchMarkers.clearLayers();
      L.circleMarker([latitude, longitude], {radius:9,color:'#fff',weight:3,fillColor:'#3B5BDB',fillOpacity:1})
        .addTo(searchMarkers).bindPopup(label).openPopup();
      map.setView([latitude, longitude], zoom || 11);
      query.blur();
    }
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
      const normalized = term.toLocaleLowerCase('id-ID');
      const local = offlinePlaces.find(function(place) { return place[0].includes(normalized) || normalized.includes(place[0]); });
      if (local) { showPlace(local[1], local[2], local[3], 11); return; }
      try {
        const url = 'https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=' + encodeURIComponent(term);
        const response = await fetch(url, {headers:{'Accept':'application/json'}});
        if (!response.ok) throw new Error('HTTP ' + response.status);
        const places = await response.json();
        if (!places.length) { error.textContent = 'Lokasi tidak ditemukan'; return; }
        const latitude = Number(places[0].lat);
        const longitude = Number(places[0].lon);
        showPlace(latitude, longitude, places[0].display_name, 14);
      } catch (_) {
        error.textContent = 'Lokasi belum tersedia offline. Sambungkan internet untuk pencarian lengkap.';
      }
    };
    const previewMarkers = L.layerGroup().addTo(map);
    window.nusaRoutes = L.layerGroup().addTo(map);
    window.nusaWaypoints = L.layerGroup().addTo(map);
    window.nusaDraft = L.layerGroup().addTo(map);
    const coordinate = document.getElementById('coordinate');
    // Ketukan diteruskan ke aplikasi: Android (addJavascriptInterface), iOS (WKScriptMessageHandler), web (iframe induk).
    function reportTap(latitude, longitude) {
      try {
        if (window.NusaBridge) { window.NusaBridge.tap(latitude, longitude); return true; }
        if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.nusaTap) {
          window.webkit.messageHandlers.nusaTap.postMessage([latitude, longitude]); return true;
        }
        if (window.parent !== window && window.parent.nusaMapTap) { window.parent.nusaMapTap(latitude, longitude); return true; }
      } catch (_) {}
      return false;
    }
    map.on('click', function(event) {
      const latitude = Number(event.latlng.lat.toFixed(6));
      const longitude = Number(event.latlng.lng.toFixed(6));
      const bridged = reportTap(latitude, longitude);
      coordinate.textContent = latitude + ', ' + longitude;
      if (bridged) return;
      previewMarkers.clearLayers();
      L.circleMarker([latitude, longitude], {
        radius:9, color:'#fff', weight:3, fillColor:'#0D9488', fillOpacity:1
      }).addTo(previewMarkers)
        .bindPopup('Titik lokasi<br>' + latitude + ', ' + longitude)
        .openPopup();
    });
    function esc(value) {
      return String(value == null ? '' : value).replace(/[&<>"']/g, function(c) {
        return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
      });
    }
    const waypointStyle = {
      posko:['P','#2563EB'], korban:['K','#DC2626'], bahaya:['!','#D97706'],
      heli:['H','#7C3AED'], air:['A','#0891B2'], lain:['•','#475569']
    };
    const triageColors = {Merah:'#DC2626', Kuning:'#CA8A04', Hijau:'#16A34A', Hitam:'#111827'};
    let lastFocusKey;
    let packLayer = null;
    let packName = null;
    // Paket MBTiles: di atas ubin OSM, di dalam batas paket saja. Tetap tampil tanpa internet.
    function applyPack(pack) {
      const name = pack ? pack.name : null;
      if (name === packName) return;
      if (packLayer) { map.removeLayer(packLayer); packLayer = null; }
      packName = name;
      if (!pack) { offline.textContent = 'Mode offline: peta wilayah tersedia, detail jalan memerlukan internet.'; return; }
      const b = pack.bounds;
      packLayer = L.tileLayer('/mbtiles/{z}/{x}/{y}', {
        minZoom: 0, minNativeZoom: pack.minZoom, maxNativeZoom: pack.maxZoom, maxZoom: 19,
        bounds: b ? [[b[1], b[0]], [b[3], b[2]]] : undefined,
        attribution: 'Peta offline: ' + esc(pack.name)
      }).addTo(map);
      offline.textContent = 'Tanpa internet: memakai peta offline "' + pack.name + '" dan ubin yang pernah dilihat.';
      if (b && !fitted) { map.fitBounds([[b[1], b[0]], [b[3], b[2]]]); fitted = true; }
    }
    let fitted = false;
    window.setNusaData = function(data) {
      if (!data) return;
      applyPack(data.pack || null);
      const units = data.units || [];
      window.nusaMarkers.clearLayers();
      window.nusaGuidance.clearLayers();
      window.nusaRoutes.clearLayers();
      window.nusaWaypoints.clearLayers();
      window.nusaDraft.clearLayers();
      const bounds = [];
      for (const route of data.routes || []) {
        const plan = route.kind === 'Plan';
        const live = route.kind === 'LiveTrack';
        const style = {
          color: plan ? '#7C3AED' : (route.own ? '#0F766E' : '#EA580C'),
          weight: 5, opacity: .85, dashArray: plan ? '12 8' : null, lineJoin:'round'
        };
        const label = '<b>' + esc(route.name) + '</b><br>' + esc(route.owner) +
          (live ? ' · sedang direkam' : (plan ? ' · rencana' : ' · jejak')) +
          (route.trust ? '<br><b>' + esc(route.trust) + '</b>' : '');
        let last = null;
        for (const segment of route.segments || []) {
          if (!segment.length) continue;
          if (segment.length > 1) L.polyline(segment, style).addTo(window.nusaRoutes).bindPopup(label);
          segment.forEach(function(p) { bounds.push(p); });
          last = segment[segment.length - 1];
        }
        const first = route.segments && route.segments.length && route.segments[0][0];
        if (first) L.circleMarker(first, {radius:5, color:'#fff', weight:2, fillColor:style.color, fillOpacity:1})
          .addTo(window.nusaRoutes).bindPopup(label + '<br>Awal');
        if (last) L.circleMarker(last, {radius:live ? 8 : 6, color:style.color, weight:3, fillColor:'#fff', fillOpacity:1})
          .addTo(window.nusaRoutes).bindPopup(label + (live ? '<br>Posisi terakhir perintis' : '<br>Ujung'));
      }
      for (const wp of data.waypoints || []) {
        let s = waypointStyle[wp.type] || waypointStyle.lain;
        // Korban: angka = jumlah orang, warna = triase, abu-abu bila sudah dievakuasi.
        if (wp.count != null) s = [String(wp.count), wp.evacuated ? '#94A3B8' : (triageColors[wp.triage] || s[1])];
        L.marker(wp.point, {icon: L.divIcon({
          className:'', iconSize:[28,28], iconAnchor:[14,14],
          html:'<div style="width:24px;height:24px;border-radius:12px;border:2px solid #fff;background:' + s[1] +
            ';color:#fff;font:bold 13px system-ui,sans-serif;display:flex;align-items:center;justify-content:center;box-shadow:0 1px 4px #0005">' + s[0] + '</div>'
        })}).addTo(window.nusaWaypoints).bindPopup('<b>' + esc(wp.typeLabel) + '</b>: ' + esc(wp.label) + '<br>oleh ' + esc(wp.owner) +
          '<br>' + wp.point[0].toFixed(5) + ', ' + wp.point[1].toFixed(5) +
          (wp.trust ? '<br><b>' + esc(wp.trust) + '</b>' : ''));
        bounds.push(wp.point);
      }
      for (const unit of units) {
        if (!Number.isFinite(unit.latitude) || !Number.isFinite(unit.longitude)) continue;
        L.circleMarker([unit.latitude, unit.longitude], {
          radius:unit.emergency ? 13 : 9, color:'#fff', weight:3,
          fillColor:unit.emergency ? '#DC2626' : '#3B5BDB', fillOpacity:1
        }).addTo(window.nusaMarkers).bindPopup(
          '<b>' + (unit.emergency ? 'SOS - ' : '') + esc(unit.name || 'Unit') + '</b><br>' +
          (unit.own ? 'Perangkat ini' : (unit.direct ? 'BLE langsung' : 'Via relay')) + '<br>' +
          (unit.rssi == null ? 'RSSI tidak tersedia' : 'RSSI ' + unit.rssi + ' dBm') + '<br>' +
          'Akurasi ±' + Math.round(unit.accuracy || 0) + ' m' +
          (unit.battery == null ? '' : '<br>Baterai ' + unit.battery + '%') +
          (unit.status ? '<br><b>' + esc(unit.status) + '</b>' : '')
        );
        bounds.push([unit.latitude, unit.longitude]);
      }
      if (Array.isArray(data.draft)) {
        previewMarkers.clearLayers();
        if (data.draft.length > 1) L.polyline(data.draft, {color:'#0D9488', weight:4, dashArray:'6 6'}).addTo(window.nusaDraft);
        data.draft.forEach(function(p, i) {
          L.circleMarker(p, {radius:6, color:'#fff', weight:2, fillColor:'#0D9488', fillOpacity:1})
            .addTo(window.nusaDraft).bindTooltip(String(i + 1), {permanent:true, direction:'top', offset:[0,-4]});
        });
      } else if (data.picked) {
        previewMarkers.clearLayers();
        L.circleMarker(data.picked, {radius:9, color:'#fff', weight:3, fillColor:'#0D9488', fillOpacity:1})
          .addTo(previewMarkers).bindTooltip('Titik dipilih', {permanent:true, direction:'top'});
      } else previewMarkers.clearLayers();
      if (data.guide) {
        L.polyline(data.guide, {color:'#F97316', weight:4, opacity:.9, dashArray:'10 8'}).addTo(window.nusaGuidance);
        L.circleMarker(data.guide[1], {radius:14, color:'#F97316', weight:4, fillColor:'#fff', fillOpacity:.35})
          .addTo(window.nusaGuidance).bindTooltip(esc(data.guideLabel || 'Tujuan'), {permanent:true, direction:'top'});
      }
      // Zoom hanya disesuaikan saat pertama ada data atau tujuan navigasi berganti, bukan tiap update GPS.
      if (data.focusKey !== lastFocusKey && data.guide) {
        map.fitBounds(data.guide, {padding:[54,54], maxZoom:17});
        fitted = true;
      } else if (!fitted && bounds.length) {
        map.fitBounds(bounds, {padding:[36,36], maxZoom:15});
        fitted = true;
      }
      lastFocusKey = data.focusKey;
    };
    window.setNusaData(initialData);
    // Web (iframe): data terbaru dititipkan induk sebelum peta siap.
    try {
      const pending = window.frameElement && window.frameElement.dataset.nusaData;
      if (pending) window.setNusaData(JSON.parse(pending));
    } catch (_) {}
  </script>
</body>
</html>
""".trimIndent()

