package id.nusamesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.AppController
import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.domain.RouteKind
import id.nusamesh.app.domain.MapWaypoint
import id.nusamesh.app.domain.SharedRoute
import id.nusamesh.app.domain.TrackedUser
import id.nusamesh.app.data.currentEpochMillis
import id.nusamesh.app.mesh.protocol.GeoPoint
import id.nusamesh.app.mesh.protocol.RoutePolyline
import id.nusamesh.app.mesh.protocol.Triage
import id.nusamesh.app.mesh.protocol.VictimInfo
import id.nusamesh.app.mesh.protocol.VictimNeed
import id.nusamesh.app.mesh.protocol.WaypointType

/** Aksi layar peta (diteruskan ke AppController). */
class MapActions(
    val selectTarget: (String?) -> Unit,
    val selectWaypoint: (String?) -> Unit,
    val followRoute: (String?) -> Unit,
    val mapTap: (GeoPoint) -> Unit,
    val startTrack: () -> Unit,
    val stopTrack: () -> Unit,
    val startDraft: () -> Unit,
    val undoDraft: () -> Unit,
    val cancelDraft: () -> Unit,
    val sendDraft: (String) -> Unit,
    val addWaypoint: (WaypointType, String, VictimInfo?) -> Unit,
    val updateWaypoint: (String, String, VictimInfo?) -> Unit,
    val clearPicked: () -> Unit,
    val deleteRoute: (String) -> Unit,
    val deleteWaypoint: (String) -> Unit,
    val resolveEmergency: (String) -> Unit,
    val shareRoute: (String) -> Unit = {},
    val shareWaypoint: (String) -> Unit = {},
    val exportGpx: () -> Unit = {},
    val importGpx: () -> Unit = {},
    val importOfflineMap: () -> Unit = {},
    val removeOfflineMap: () -> Unit = {},
)

@Composable
fun MapScreen(
    state: AppUiState,
    padding: PaddingValues,
    actions: MapActions,
    offlinePack: OfflinePackInfo? = null,
    offlineSummary: String = "",
) {
    var sheetFraction by remember { mutableFloatStateOf(0.45f) }
    var markingOpen by remember { mutableStateOf(false) }
    /** Titik yang sedang diperbarui lewat panel tandai (null = membuat titik baru). */
    var editingWaypointId by remember { mutableStateOf<String?>(null) }
    var coordinateFormat by remember { mutableStateOf(CoordinateFormat.Utm) }
    // Back menutup panel yang sedang terbuka dulu, bukan langsung meninggalkan peta.
    PlatformBackHandler(enabled = markingOpen || editingWaypointId != null || state.routeDraft != null) {
        when {
            markingOpen || editingWaypointId != null -> { markingOpen = false; editingWaypointId = null; actions.clearPicked() }
            else -> actions.cancelDraft()
        }
    }
    val own = state.trackedUsers.firstOrNull { it.own }
    val target = navigationTarget(state)
    val here = own?.let { GeoPoint(it.latitude, it.longitude) }
    val guidance = if (here != null && target != null) guidance(here, target.point, state.headingDegrees) else null
    val mapData = MapData(
        units = state.trackedUsers,
        routes = state.routes,
        waypoints = state.waypoints,
        draft = state.routeDraft,
        picked = state.pickedPoint.takeIf { markingOpen },
        guide = if (here != null && target != null) here to target.point else null,
        guideLabel = target?.title,
        focusKey = state.selectedTargetPeerId ?: state.selectedWaypointId ?: state.followedRouteId,
        statuses = state.unitStatuses.mapValues { it.value.status.label },
        haveKey = state.operationCode != null,
        offlinePack = offlinePack,
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFFF8FAFC))
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) {
        val density = LocalDensity.current
        val availablePx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f - sheetFraction).background(BrandTint)) {
                LeafletMap(Modifier.fillMaxSize(), mapData, actions.mapTap)
            }
            Column(
                Modifier.fillMaxWidth()
                .weight(sheetFraction)
                .background(Color.White, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(24.dp).pointerInput(availablePx) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        sheetFraction = (sheetFraction - dragAmount / availablePx).coerceIn(0.24f, 0.78f)
                    }
                },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.width(48.dp).height(6.dp).background(Color(0xFFCBD5E1), RoundedCornerShape(3.dp)))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Unit Lapangan", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                StatusPill("${state.trackedUsers.size} Online")
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CoordinatePanel(here, target?.point, coordinateFormat) { coordinateFormat = it }
                if (guidance != null && target != null) {
                    Text(target.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${guidance.distanceLabel} | bearing ${guidance.bearing.toInt()} deg ${guidance.cardinal}" +
                            (target.remainingAfterMeters?.let { " | sisa rute ${AppController.formatDistance(it)}" } ?: ""),
                        color = Color(0xFF2563EB), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(guidance.turnInstruction, color = Ink, fontSize = 13.sp)
                    Text(
                        "Arah langsung offline, bukan jaminan jalur aman. Jangan menerobos tebing, sungai, longsor, atau area tertutup.",
                        color = Color(0xFFB45309), fontSize = 10.sp,
                    )
                    OutlinedButton(onClick = {
                        actions.selectTarget(null); actions.selectWaypoint(null); actions.followRoute(null)
                    }) { Text("Hentikan arahan") }
                }

                TrailActions(state, markingOpen, onToggleMarking = {
                    markingOpen = !markingOpen
                    if (!markingOpen) actions.clearPicked()
                }, actions)
                state.routeDraft?.let { draft -> DraftPanel(draft, actions) }
                val editing = editingWaypointId?.let { id -> state.waypoints.firstOrNull { it.id == id } }
                if (markingOpen || editing != null) {
                    WaypointPanel(
                        state.pickedPoint, editing,
                        onDone = { markingOpen = false; editingWaypointId = null },
                        actions,
                    )
                }

                if (state.routes.isNotEmpty()) {
                    SectionTitle("Jalur")
                    state.routes.asReversed().forEach { route ->
                        RouteRow(
                            route,
                            trust = trustLabel(route.own, route.verified, state.operationCode != null),
                            followed = route.id == state.followedRouteId,
                            onFollow = { actions.followRoute(if (route.id == state.followedRouteId) null else route.id) },
                            onDelete = if (route.id == state.recordingTrackId) null else ({ actions.deleteRoute(route.id) }),
                            onShare = if (route.own && route.id != state.recordingTrackId) ({ actions.shareRoute(route.id) }) else null,
                        )
                    }
                }
                if (state.waypoints.isNotEmpty()) {
                    SectionTitle("Titik penting")
                    state.waypoints.asReversed().forEach { wp ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WaypointBadge(wp.type, wp.victim)
                            Text(
                                "${wp.type.label}: " + listOfNotNull(wp.victim?.summary(), wp.label.ifBlank { null }).joinToString(" · ") +
                                    " | ${if (wp.own) "Anda" else wp.ownerName}" +
                                    (wp.updatedBy?.let { " (diperbarui $it)" } ?: "") +
                                    trustLabel(wp.own, wp.verified, state.operationCode != null) +
                                    (if (wp.ackedBy.isNotEmpty()) " | ${ackLabel(wp.ackedBy)}" else "") +
                                    (here?.let { " | ${AppController.formatDistance(RoutePolyline.distanceMeters(it, wp.point))}" } ?: ""),
                                color = Slate, fontSize = 12.sp, modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { actions.selectWaypoint(if (wp.id == state.selectedWaypointId) null else wp.id) }) {
                                Text(if (wp.id == state.selectedWaypointId) "Dipilih" else "Arahkan")
                            }
                            if (wp.type == WaypointType.Korban) {
                                OutlinedButton(onClick = { markingOpen = false; editingWaypointId = wp.id }) { Text("Perbarui") }
                            } else if (wp.own) {
                                OutlinedButton(onClick = { actions.shareWaypoint(wp.id) }) { Text("Bagikan") }
                            }
                            DeleteButton { actions.deleteWaypoint(wp.id) }
                        }
                    }
                }

                SectionTitle("Peta offline")
                Text(
                    offlinePack?.let { pack ->
                        (if (pack.builtIn) "Peta bawaan: " else "Paket: ") + "${pack.name} · zoom ${pack.minZoom}–${pack.maxZoom}" +
                            (if (pack.sizeBytes > 0) " · ${pack.sizeBytes / (1024 * 1024)} MB" else "") +
                            (if (pack.builtIn) " · siap offline tanpa impor" else "")
                    } ?: "Belum ada paket peta. Impor file MBTiles (raster) dari posko.",
                    color = Ink, fontSize = 12.sp,
                )
                if (offlineSummary.isNotBlank()) Text(offlineSummary, color = Slate, fontSize = 11.sp)
                Text(
                    "Peta yang pernah dilihat saat ada internet tersimpan otomatis. Sebelum berangkat, jelajahi area operasi (geser & zoom) di posko.",
                    color = Slate, fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.importOfflineMap) {
                        Text(if (offlinePack == null || offlinePack.builtIn) "Tambah wilayah (MBTiles)" else "Ganti paket")
                    }
                    if (offlinePack != null && !offlinePack.builtIn) DeleteButton(actions.removeOfflineMap)
                }

                SectionTitle("Data operasi")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.exportGpx) { Text("Ekspor GPX") }
                    OutlinedButton(onClick = actions.importGpx) { Text("Impor GPX") }
                }

                SectionTitle("Unit")
                if (state.trackedUsers.isEmpty()) {
                    Text("Belum ada unit dengan koordinat yang diterima.", color = Slate, fontSize = 12.sp)
                }
                state.trackedUsers.forEach { unit ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${if (unit.emergency) "SOS - " else ""}${if (unit.own) "Anda" else unit.name}${if (unit.verified && !unit.own) " ✓" else ""} | ${unit.rssi?.let { "$it dBm" } ?: "RSSI relay"} | +/-${unit.accuracyMeters.toInt()} m" +
                                lastSeenLabel(unit) +
                                (unit.batteryPercent?.let { " | baterai $it%" } ?: "") +
                                (state.unitStatuses[unit.peerId]?.let { " | ${it.status.label}" } ?: ""),
                            color = when {
                                unit.emergency -> Danger
                                (unit.batteryPercent ?: 100) <= LOW_BATTERY_PERCENT || state.unitStatuses[unit.peerId]?.status?.urgent == true -> Warning
                                else -> Slate
                            },
                            fontWeight = if (unit.emergency) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                        if (!unit.own) {
                            Button(onClick = { actions.selectTarget(unit.peerId) }) {
                                Text(if (unit.peerId == state.selectedTargetPeerId) "Dipilih" else "Arahkan")
                            }
                        }
                        if (unit.emergency && !unit.own) ResolveButton { actions.resolveEmergency(unit.peerId) }
                    }
                }
            }
            }
        }
    }
}

private const val LOW_BATTERY_PERCENT = 20

/**
 * Hanya bermakna bila kita punya kunci operasi: tanpa kunci tidak ada yang bisa diverifikasi, jadi tidak
 * ditampilkan agar tidak menakut-nakuti warga.
 */
internal fun trustLabel(own: Boolean, verified: Boolean, haveKey: Boolean) = when {
    own || !haveKey -> ""
    verified -> " | ✓ tim"
    else -> " | belum terverifikasi"
}

/** Posisi sendiri (dan tujuan) dalam format pilihan, siap dibacakan lewat HT atau disalin. */
@Composable
private fun CoordinatePanel(here: GeoPoint?, target: GeoPoint?, format: CoordinateFormat, onFormat: (CoordinateFormat) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().background(BrandTint, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Koordinat", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CoordinateFormat.entries.forEach { option ->
                val selected = option == format
                Text(
                    option.label, color = if (selected) Color.White else Brand, fontSize = 11.sp,
                    modifier = Modifier.background(if (selected) Brand else Color.White, RoundedCornerShape(12.dp))
                        .pressableClick { onFormat(option) }.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        listOfNotNull(here?.let { "Anda" to it }, target?.let { "Tujuan" to it }).forEach { (label, point) ->
            val text = formatCoordinate(point, format)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$label: $text", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    "Salin", color = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.pressableClick { clipboard.setText(AnnotatedString(text)) }.padding(6.dp),
                )
            }
        }
        if (here == null) Text("Menunggu GPS...", color = Slate, fontSize = 12.sp)
    }
}

/** Korban SOS tetap tampil walau tak ada kabar: beri tahu seberapa lama posisinya tidak diperbarui. */
private fun lastSeenLabel(unit: TrackedUser): String {
    if (!unit.emergency || unit.own) return ""
    val minutes = (currentEpochMillis() - unit.updatedAtMs) / 60_000L
    return if (minutes < 1) " | baru saja" else " | terakhir terlihat $minutes mnt lalu"
}

@Composable
private fun TrailActions(state: AppUiState, markingOpen: Boolean, onToggleMarking: () -> Unit, actions: MapActions) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val recording = state.recordingTrackId != null
        if (recording) {
            val points = state.routes.firstOrNull { it.id == state.recordingTrackId }?.points.orEmpty()
            Button(
                onClick = actions.stopTrack,
                colors = ButtonDefaults.buttonColors(containerColor = Danger),
            ) { Text("Stop rekam (${AppController.formatDistance(RoutePolyline.lengthMeters(points))})") }
        } else {
            Button(onClick = actions.startTrack, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Rekam jejak") }
        }
        if (state.routeDraft == null) OutlinedButton(onClick = actions.startDraft) { Text("Gambar rute") }
        OutlinedButton(onClick = onToggleMarking) { Text(if (markingOpen) "Tutup tandai" else "Tandai titik") }
    }
}

@Composable
private fun DraftPanel(draft: List<GeoPoint>, actions: MapActions) {
    var name by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().background(AccentTint, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Ketuk peta untuk menambah titik rute", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("${draft.size} titik | ${AppController.formatDistance(RoutePolyline.lengthMeters(draft))}", color = Slate, fontSize = 12.sp)
        InputField(name, "Nama rute (mis. Jalur evakuasi utara)") { name = it.take(40) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.undoDraft, enabled = draft.isNotEmpty()) { Text("Undo") }
            OutlinedButton(onClick = actions.cancelDraft) { Text("Batal") }
            Button(onClick = { actions.sendDraft(name) }, enabled = draft.size >= 2) { Text("Kirim") }
        }
    }
}

@Composable
private fun WaypointPanel(picked: GeoPoint?, editing: MapWaypoint?, onDone: () -> Unit, actions: MapActions) {
    var type by remember(editing?.id) { mutableStateOf(editing?.type ?: WaypointType.Bahaya) }
    var label by remember(editing?.id) { mutableStateOf(editing?.label.orEmpty()) }
    var victim by remember(editing?.id) { mutableStateOf(editing?.victim ?: VictimInfo()) }
    Column(
        Modifier.fillMaxWidth().background(WarningTint, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            when {
                editing != null -> "Perbarui ${editing.type.label} (${formatCoordinate(editing.point, CoordinateFormat.Decimal)})"
                picked != null -> "Di titik yang diketuk (${formatCoordinate(picked, CoordinateFormat.Decimal)})"
                else -> "Di posisi Anda (ketuk peta untuk memilih titik lain)"
            },
            color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
        if (editing == null) {
            ChipRow(WaypointType.entries, { it == type }, { it.label }) { type = it }
        }
        if (type == WaypointType.Korban) VictimEditor(victim) { victim = it }
        InputField(label, if (type == WaypointType.Korban) "Keterangan (mis. kaki patah, tertimbun)" else "Keterangan (mis. longsor, jalan putus)") {
            label = it.take(50)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.clearPicked(); onDone() }) { Text("Batal") }
            Button(onClick = {
                val info = victim.takeIf { type == WaypointType.Korban }
                if (editing != null) actions.updateWaypoint(editing.id, label, info) else actions.addWaypoint(type, label, info)
                onDone()
            }) { Text(if (editing != null) "Kirim pembaruan" else "Kirim titik") }
        }
    }
}

/** Jumlah, triase, kebutuhan, dan status evakuasi korban. */
@Composable
private fun VictimEditor(victim: VictimInfo, onChange: (VictimInfo) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Jumlah", color = Ink, fontSize = 12.sp)
        OutlinedButton(onClick = { onChange(victim.copy(count = (victim.count - 1).coerceAtLeast(1))) }) { Text("−") }
        Text("${victim.count} org", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onChange(victim.copy(count = (victim.count + 1).coerceAtMost(VictimInfo.MAX_COUNT))) }) { Text("+") }
    }
    Text("Triase", color = Ink, fontSize = 12.sp)
    ChipRow(Triage.entries, { it == victim.triage }, { it.label }, color = { triageColor(it) }) {
        onChange(victim.copy(triage = if (victim.triage == it) null else it))
    }
    Text("Kebutuhan", color = Ink, fontSize = 12.sp)
    ChipRow(VictimNeed.entries, { it in victim.needs }, { it.label }) {
        onChange(victim.copy(needs = if (it in victim.needs) victim.needs - it else victim.needs + it))
    }
    ChipRow(listOf(true), { victim.evacuated }, { "Sudah dievakuasi" }, color = { Success }) {
        onChange(victim.copy(evacuated = !victim.evacuated))
    }
}

internal fun triageColor(triage: Triage) = when (triage) {
    Triage.Merah -> Color(0xFFDC2626)
    Triage.Kuning -> Color(0xFFCA8A04)
    Triage.Hijau -> Color(0xFF16A34A)
    Triage.Hitam -> Color(0xFF111827)
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    color: (T) -> Color = { Brand },
    onClick: (T) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val on = selected(option)
            val tint = color(option)
            Text(
                label(option),
                color = if (on) Color.White else tint,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(if (on) tint else Color.White, RoundedCornerShape(14.dp))
                    .pressableClick { onClick(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RouteRow(route: SharedRoute, trust: String, followed: Boolean, onFollow: () -> Unit, onDelete: (() -> Unit)?, onShare: (() -> Unit)?) {
    val points = route.points
    val kind = when (route.kind) {
        RouteKind.LiveTrack -> "jejak langsung"
        RouteKind.Track -> "jejak"
        RouteKind.Plan -> "rencana"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${route.name} | ${if (route.own) "Anda" else route.ownerName} | $kind | ${AppController.formatDistance(RoutePolyline.lengthMeters(points))}$trust",
            color = if (route.kind == RouteKind.LiveTrack || trust.contains("belum")) Warning else Slate,
            fontSize = 12.sp, modifier = Modifier.weight(1f),
        )
        if (points.size >= 2) Button(onClick = onFollow) { Text(if (followed) "Diikuti" else "Ikuti") }
        if (points.size >= 2) onShare?.let { OutlinedButton(onClick = it) { Text("Bagikan") } }
        onDelete?.let { DeleteButton(it) }
    }
}

/** Penanda SOS korban dihapus dari HP ini; dua ketukan supaya tidak tertutup karena salah sentuh. */
@Composable
private fun ResolveButton(onConfirm: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { if (confirming) { confirming = false; onConfirm() } else confirming = true }) {
        Text(if (confirming) "Yakin?" else "Ditangani", color = if (confirming) Danger else Success, fontSize = 12.sp)
    }
}

/** Hapus dari HP ini saja; minta konfirmasi supaya jalur penting tidak terhapus karena salah ketuk. */
@Composable
private fun DeleteButton(onConfirm: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        Text(
            "Hapus?", color = Danger, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.pressableClick { confirming = false; onConfirm() }.padding(6.dp),
        )
    } else {
        Box(Modifier.size(28.dp).pressableClick { confirming = true }, contentAlignment = Alignment.Center) {
            AppIcon(IconKind.Close, Muted, Modifier.size(12.dp))
        }
    }
}

@Composable
private fun WaypointBadge(type: WaypointType, victim: VictimInfo? = null) {
    var (letter, color) = when (type) {
        WaypointType.Posko -> "P" to Color(0xFF2563EB)
        WaypointType.Korban -> "K" to Color(0xFFDC2626)
        WaypointType.Bahaya -> "!" to Color(0xFFD97706)
        WaypointType.Helipad -> "H" to Brand
        WaypointType.Air -> "A" to Color(0xFF0891B2)
        WaypointType.Lainnya -> "•" to Color(0xFF475569)
    }
    if (victim != null) {
        // Korban: warna triase, angka = jumlah orang; abu-abu bila sudah dievakuasi.
        letter = victim.count.toString()
        color = if (victim.evacuated) Muted else victim.triage?.let(::triageColor) ?: color
    }
    Box(Modifier.size(24.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun InputField(value: String, placeholder: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = 12.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
