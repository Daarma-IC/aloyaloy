package id.nusamesh.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.mesh.protocol.GeoPoint
import id.nusamesh.app.routing.sarProgress
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun SarNavigationCard(state: AppUiState, actions: MapActions) {
    val nav = state.sarNavigation
    var expanded by rememberSaveable(nav.route?.request?.title) { mutableStateOf(false) }
    if (state.pickedPoint != null && state.routeDraft == null) {
        OutlinedButton(onClick = actions.planNavigation) { Text("Arahkan ke titik peta") }
    }
    if (nav.status == "IDLE") {
        return
    }
    Column(Modifier.fillMaxWidth().background(BrandTint, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val statusLabel = when (nav.status) {
                "ACTIVE" -> "Navigasi aktif"
                "PAUSED" -> "Navigasi dijeda"
                "STALE" -> "Rute perlu diperbarui"
                "READY" -> "Rute siap"
                else -> "Navigasi"
            }
            Text(statusLabel, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (nav.status == "STALE") Warning else BrandDeep)
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (expanded) "Tutup detail" else "Detail", fontSize = 12.sp)
            }
            TextButton(onClick = actions.stopNavigation, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Akhiri", color = Danger, fontSize = 12.sp)
            }
        }
        if (nav.status == "CALCULATING") Text("Menghitung rute lokal…", fontSize = 12.sp, color = Slate)
        nav.message?.let { Text(it, fontSize = 12.sp, color = Warning) }
        nav.route?.let { route ->
            val own = state.trackedUsers.firstOrNull { it.own }
            val p = own?.let { sarProgress(route, GeoPoint(it.latitude, it.longitude)) }
            Text(route.request.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(id.nusamesh.app.AppController.formatDistance(p?.remainingMeters ?: route.meters), color = BrandDeep, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text("±${ceil((p?.remainingSeconds ?: route.seconds) / 60).toInt()} mnt · estimasi", color = Slate, fontSize = 11.sp)
                }
                if (nav.status in listOf("READY", "PAUSED")) Button(enabled = own != null, onClick = { actions.activateNavigation(true) }) { Text(if (nav.status == "PAUSED") "Lanjut" else "Mulai") }
                if (nav.status == "ACTIVE") OutlinedButton(onClick = { actions.activateNavigation(false) }) { Text("Jeda") }
            }
            if (own == null) Text("Menunggu posisi GPS perangkat ini.", color = Slate, fontSize = 11.sp)
            if (expanded) {
                Text("Naik ${route.ascent.roundToInt()} m / turun ${route.descent.roundToInt()} m · ${nav.status}", color = Slate, fontSize = 11.sp)
                Text("Biru: OSM · ungu: medan · jingga: transisi", fontSize = 10.sp, color = Slate)
                Text("Profil elevasi DEM (m) sepanjang rute", fontSize = 11.sp, color = Slate)
                val low = route.elevations.minOrNull() ?: 0.0; val high = route.elevations.maxOrNull() ?: low
                Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                    var d = 0.0
                    route.segments.forEachIndexed { i, s ->
                        val x0 = (d / route.meters.coerceAtLeast(1.0) * size.width).toFloat(); d += s.meters
                        val x1 = (d / route.meters.coerceAtLeast(1.0) * size.width).toFloat()
                        fun y(z: Double) = (size.height - 4 - (z - low) / (high - low).coerceAtLeast(1.0) * (size.height - 8)).toFloat()
                        drawLine(if (s.kind == "osm") Color(0xFF2563EB) else if (s.kind == "terrain") Color(0xFF9333EA) else Color(0xFFD97706), Offset(x0, y(route.elevations[i])), Offset(x1, y(route.elevations[i + 1])), strokeWidth = 3f)
                    }
                }
                Text("${low.roundToInt()}–${high.roundToInt()} m · jarak 0–${route.meters.roundToInt()} m · grid ${route.resolutionM.roundToInt()} m, datum ${route.verticalDatum}", fontSize = 10.sp, color = Slate)
                Text("Paket: ${route.packageName} · ${route.packageVersion}", fontSize = 10.sp, color = Slate)
            }
        }
        if (nav.status != "CALCULATING" && (expanded || nav.route == null)) TextButton(onClick = actions.planNavigation) { Text("Hitung ulang rute") }
        if (expanded) {
            Text("Copernicus DEM + WorldCover + OSM. Paket dipilih sesuai wilayah awal dan tujuan. ETA belum kalibrasi lapangan; deteksi jurang belum tersedia.", color = Slate, fontSize = 10.sp)
        }
    }
}
