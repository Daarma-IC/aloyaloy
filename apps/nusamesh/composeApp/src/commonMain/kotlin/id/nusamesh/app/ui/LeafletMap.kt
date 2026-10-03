package id.nusamesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import nusamesh.composeapp.generated.resources.Res
import id.nusamesh.app.domain.TrackedUser

/**
 * Leaflet dan peta vektor regional dibundel di aplikasi. Ubin OpenStreetMap bersifat opsional dan
 * hanya menambah detail jalan saat jaringan tersedia.
 */
@Composable
fun LeafletMap(modifier: Modifier, units: List<TrackedUser> = emptyList()) {
    val htmlResult by produceState<Result<String>?>(null, units) {
        value = runCatching {
            buildLeafletHtml(
                js = Res.readBytes("files/leaflet/leaflet.js").decodeToString(),
                css = Res.readBytes("files/leaflet/leaflet.css").decodeToString(),
                offlineRegion = Res.readBytes("files/maps/natural-earth-se-asia.geojson").decodeToString(),
                unitsJson = units.toMapJson(),
            )
        }
    }
    Box(modifier.background(BrandTint), contentAlignment = Alignment.Center) {
        when {
            htmlResult == null -> Text("Memuat peta...", color = Slate, fontSize = 11.sp)
            htmlResult?.isFailure == true -> Text("Aset peta gagal dimuat", color = Slate, fontSize = 11.sp)
            else -> LeafletWebView(Modifier.fillMaxSize(), htmlResult!!.getOrThrow())
        }
    }
}

/** WebView platform yang memuat [html] dengan base URL [MAP_BASE_URL] (dipakai sebagai Referer ubin OSM). */
@Composable
expect fun LeafletWebView(modifier: Modifier, html: String)

internal const val MAP_BASE_URL = "https://meshta.app/"

internal fun buildLeafletHtml(js: String, css: String, offlineRegion: String, unitsJson: String = "[]"): String = leafletHtml
    .replace(LEAFLET_CSS_TAG, "<style>$css</style>")
    .replace(LEAFLET_JS_TAG, "<script>$js</script>")
    .replace(OFFLINE_REGION_TAG, offlineRegion)
    .replace(MAP_UNITS_TAG, unitsJson)

private const val LEAFLET_CSS_TAG = "<!--LEAFLET_CSS-->"
private const val LEAFLET_JS_TAG = "<!--LEAFLET_JS-->"
private const val OFFLINE_REGION_TAG = "/*OFFLINE_REGION*/"
private const val MAP_UNITS_TAG = "/*MAP_UNITS*/"

private fun List<TrackedUser>.toMapJson() = joinToString(prefix = "[", postfix = "]") { unit ->
    val safeName = unit.name.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    "{\"name\":\"$safeName\",\"latitude\":${unit.latitude},\"longitude\":${unit.longitude}," +
        "\"accuracy\":${unit.accuracyMeters},\"rssi\":${unit.rssi ?: "null"},\"direct\":${unit.direct},\"own\":${unit.own}}"
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
    const initialUnits = /*MAP_UNITS*/;
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
    const coordinate = document.getElementById('coordinate');
    map.on('click', function(event) {
      const latitude = Number(event.latlng.lat.toFixed(6));
      const longitude = Number(event.latlng.lng.toFixed(6));
      L.circleMarker([latitude, longitude], {
        radius:9, color:'#fff', weight:3, fillColor:'#0D9488', fillOpacity:1
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
          radius:9, color:'#fff', weight:3, fillColor:'#3B5BDB', fillOpacity:1
        }).addTo(window.nusaMarkers).bindPopup(
          '<b>' + String(unit.name || 'Unit') + '</b><br>' +
          (unit.own ? 'Perangkat ini' : (unit.direct ? 'BLE langsung' : 'Via relay')) + '<br>' +
          (unit.rssi == null ? 'RSSI tidak tersedia' : 'RSSI ' + unit.rssi + ' dBm') + '<br>' +
          'Akurasi ±' + Math.round(unit.accuracy || 0) + ' m'
        );
        bounds.push([unit.latitude, unit.longitude]);
      }
      if (bounds.length) map.fitBounds(bounds, {padding:[36,36], maxZoom:15});
    };
    window.setNusaUnits(initialUnits);
  </script>
</body>
</html>
""".trimIndent()

