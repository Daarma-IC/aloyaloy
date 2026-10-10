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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import id.nusamesh.app.domain.RoadRouteInfo
import id.nusamesh.app.routing.TravelMode

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
    val planRoadRoute: (TravelMode) -> Unit = {},
    val followDraft: (String) -> Unit = {},
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
    /** Panel diciutkan: tinggal pegangan + judul, peta hampir layar penuh. */
    var collapsed by remember { mutableStateOf(false) }
    var markingOpen by remember { mutableStateOf(false) }
    var roadOpen by remember { mutableStateOf(false) }
    /** Titik yang sedang diperbarui lewat panel tandai (null = membuat titik baru). */
    var editingWaypointId by remember { mutableStateOf<String?>(null) }
    var coordinateFormat by remember { mutableStateOf(CoordinateFormat.Utm) }
    var tab by remember { mutableStateOf(SheetTab.Units) }
    // Back menutup panel yang sedang terbuka dulu, bukan langsung meninggalkan peta.
    PlatformBackHandler(enabled = markingOpen || roadOpen || editingWaypointId != null || state.routeDraft != null) {
        when {
            markingOpen || editingWaypointId != null -> { markingOpen = false; editingWaypointId = null; actions.clearPicked() }
            roadOpen && state.routeDraft == null -> { roadOpen = false; actions.clearPicked() }
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
        picked = state.pickedPoint.takeIf { markingOpen || roadOpen },
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
            Box(Modifier.fillMaxWidth().weight(if (collapsed) 1f else 1f - sheetFraction).background(BrandTint)) {
                LeafletMap(Modifier.fillMaxSize(), mapData, actions.mapTap)
            }
            Column(
                Modifier.fillMaxWidth()
                .then(if (collapsed) Modifier else Modifier.weight(sheetFraction))
                .background(Color.White, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Seret pegangan: atur tinggi panel; seret ke bawah melewati batas = ciutkan, seret ke atas = buka lagi.
            // Ketuk pegangan/judul juga membuka-tutup.
            Box(
                Modifier.fillMaxWidth().height(24.dp)
                    .pointerInput(availablePx) {
                        var pulled = 0f
                        detectVerticalDragGestures(
                            onDragStart = { pulled = 0f },
                            onDragEnd = {
                                if (!collapsed && sheetFraction <= COLLAPSE_FRACTION) { collapsed = true; sheetFraction = 0.45f }
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            if (collapsed) {
                                pulled += dragAmount
                                if (pulled < -24f) { collapsed = false; sheetFraction = 0.3f }
                            } else {
                                sheetFraction = (sheetFraction - dragAmount / availablePx).coerceIn(COLLAPSE_FRACTION, 0.85f)
                            }
                        }
                    }
                    .pressableClick { collapsed = !collapsed },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.width(48.dp).height(6.dp).background(Color(0xFFCBD5E1), RoundedCornerShape(3.dp)))
            }
            Row(
                Modifier.fillMaxWidth().pressableClick { collapsed = !collapsed },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Unit Lapangan", color = Ink, fontSize = if (collapsed) 16.sp else 20.sp, fontWeight = FontWeight.SemiBold)
                    // Saat diciutkan arahan tetap terlihat ringkas.
                    if (collapsed && guidance != null && target != null) {
                        Text(
                            "${guidance.distanceLabel} ${guidance.cardinal} · ${target.title}",
                            color = BrandDeep, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val sos = state.trackedUsers.count { it.emergency && !it.own }
                if (sos > 0) StatusPill("$sos SOS", Danger, DangerTint) else StatusPill("${state.trackedUsers.size} Online")
                Text(if (collapsed) "Buka ▲" else "Ciutkan ▼", color = Slate, fontSize = 11.sp)
            }
            if (!collapsed) Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CoordinatePanel(here, target?.point, coordinateFormat) { coordinateFormat = it }
                if (guidance != null && target != null) {
                    GuidanceCard(target, guidance) { actions.selectTarget(null); actions.selectWaypoint(null); actions.followRoute(null) }
                }

                // Rute / Gambar / Tandai saling eksklusif seperti tab: membuka satu menutup yang lain
                // (draft yang belum dikirim dibuang), mengetuk yang aktif menutupnya.
                val mode = when {
                    markingOpen || editingWaypointId != null -> SheetMode.Mark
                    state.routeDraft != null && state.roadRoute == null -> SheetMode.Draw
                    roadOpen || state.roadRoute != null -> SheetMode.Road
                    else -> null
                }
                TrailActions(state, mode, actions) { tapped ->
                    if (state.routeDraft != null) actions.cancelDraft()
                    markingOpen = false
                    roadOpen = false
                    editingWaypointId = null
                    actions.clearPicked()
                    when (tapped.takeIf { it != mode }) {
                        SheetMode.Road -> roadOpen = true
                        SheetMode.Draw -> actions.startDraft()
                        SheetMode.Mark -> markingOpen = true
                        null -> Unit
                    }
                }
                if (roadOpen && state.routeDraft == null) RoadRoutePanel(state, actions)
                state.routeDraft?.let { draft -> DraftPanel(draft, state.roadRoute, actions, onClosed = { roadOpen = false }) }
                val editing = editingWaypointId?.let { id -> state.waypoints.firstOrNull { it.id == id } }
                if (markingOpen || editing != null) {
                    WaypointPanel(
                        state.pickedPoint, editing,
                        onDone = { markingOpen = false; editingWaypointId = null },
                        actions,
                    )
                }

                SheetTabs(tab, state) { tab = it }
                when (tab) {
                    SheetTab.Units -> UnitList(state, here, actions)
                    SheetTab.Routes -> {
                        if (state.routes.isEmpty()) EmptyHint("Belum ada jalur. Rekam jejak, cari rute jalan, atau gambar rute di peta.")
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
                    SheetTab.Points -> {
                        if (state.waypoints.isEmpty()) EmptyHint("Belum ada titik. Ketuk \"Tandai titik\" untuk menandai korban, bahaya, posko, dll.")
                        state.waypoints.asReversed().forEach { wp ->
                            WaypointRow(
                                wp, here, state,
                                onSelect = { actions.selectWaypoint(if (wp.id == state.selectedWaypointId) null else wp.id) },
                                onEdit = if (wp.type == WaypointType.Korban) ({
                                    if (state.routeDraft != null) actions.cancelDraft()
                                    markingOpen = false; roadOpen = false; editingWaypointId = wp.id
                                }) else null,
                                onShare = if (wp.type != WaypointType.Korban && wp.own) ({ actions.shareWaypoint(wp.id) }) else null,
                                onDelete = { actions.deleteWaypoint(wp.id) },
                            )
                        }
                    }
                    SheetTab.More -> MoreTab(offlinePack, offlineSummary, actions)
                }
            }
            }
        }
    }
}

private const val LOW_BATTERY_PERCENT = 20
private const val COLLAPSE_FRACTION = 0.16f

/**
 * Hanya bermakna bila kita punya kunci operasi: tanpa kunci tidak ada yang bisa diverifikasi, jadi tidak
 * ditampilkan agar tidak menakut-nakuti warga.
 */
internal fun trustLabel(own: Boolean, verified: Boolean, haveKey: Boolean) = when {
    own || !haveKey -> ""
    verified -> " | ✓ tim"
    else -> " | belum terverifikasi"
}

/** Posisi sendiri (dan tujuan) dalam format pilihan, siap dibacakan lewat HT atau disalin. Ketuk format untuk ganti. */
@Composable
private fun CoordinatePanel(here: GeoPoint?, target: GeoPoint?, format: CoordinateFormat, onFormat: (CoordinateFormat) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().background(BrandTint, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (here == null) {
            Text("Menunggu GPS...", color = Slate, fontSize = 12.sp)
            return@Column
        }
        listOfNotNull("Anda" to here, target?.let { "Tujuan" to it }).forEachIndexed { index, (label, point) ->
            val text = formatCoordinate(point, format)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = Slate, fontSize = 11.sp, modifier = Modifier.width(44.dp))
                Text(text, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (index == 0) {
                    val next = CoordinateFormat.entries[(format.ordinal + 1) % CoordinateFormat.entries.size]
                    SmallAction(format.label) { onFormat(next) }
                }
                SmallAction("Salin") { clipboard.setText(AnnotatedString(text)) }
            }
        }
    }
}

/** Arahan ke unit/titik/rute: jarak besar di kiri, arah & instruksi di kanan. */
@Composable
private fun GuidanceCard(target: NavigationTarget, guidance: OfflineGuidance, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(BrandSoft, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(guidance.distanceLabel, color = BrandDeep, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Text(target.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${guidance.cardinal} ${guidance.bearing.toInt()}°" +
                        (target.remainingAfterMeters?.let { " · sisa rute ${AppController.formatDistance(it)}" } ?: ""),
                    color = Slate, fontSize = 12.sp,
                )
            }
            Box(Modifier.size(32.dp).background(Color.White, CircleShape).pressableClick(onStop), contentAlignment = Alignment.Center) {
                AppIcon(IconKind.Close, Slate, Modifier.size(12.dp))
            }
        }
        Text(guidance.turnInstruction, color = Ink, fontSize = 13.sp)
        Text(
            "Arah langsung, bukan jaminan jalur aman. Hindari tebing, sungai, longsor, dan area tertutup.",
            color = Warning, fontSize = 10.sp, lineHeight = 13.sp,
        )
    }
}

private enum class SheetTab(val label: String) { Units("Unit"), Routes("Jalur"), Points("Titik"), More("Lainnya") }

@Composable
private fun SheetTabs(selected: SheetTab, state: AppUiState, onSelect: (SheetTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(14.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SheetTab.entries.forEach { option ->
            val count = when (option) {
                SheetTab.Units -> state.trackedUsers.size
                SheetTab.Routes -> state.routes.size
                SheetTab.Points -> state.waypoints.size
                SheetTab.More -> 0
            }
            val on = option == selected
            Text(
                option.label + if (count > 0) " $count" else "",
                color = if (on) Ink else Slate, fontSize = 12.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.weight(1f)
                    .background(if (on) Color.White else Color.Transparent, RoundedCornerShape(11.dp))
                    .pressableClick { onSelect(option) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/** SOS dulu, lalu diri sendiri, lalu yang terdekat. */
@Composable
private fun UnitList(state: AppUiState, here: GeoPoint?, actions: MapActions) {
    if (state.trackedUsers.isEmpty()) return EmptyHint("Belum ada unit dengan koordinat yang diterima.")
    val sorted = state.trackedUsers.sortedWith(
        compareByDescending<TrackedUser> { it.emergency && !it.own }
            .thenByDescending { it.own }
            .thenBy { unit -> here?.let { RoutePolyline.distanceMeters(it, GeoPoint(unit.latitude, unit.longitude)) } ?: 0.0 },
    )
    sorted.forEach { unit -> UnitRow(unit, here, state, actions) }
}

@Composable
private fun UnitRow(unit: TrackedUser, here: GeoPoint?, state: AppUiState, actions: MapActions) {
    val sos = unit.emergency && !unit.own
    val status = state.unitStatuses[unit.peerId]?.status
    val lowBattery = (unit.batteryPercent ?: 100) <= LOW_BATTERY_PERCENT
    val name = if (unit.own) "Anda" else unit.name
    val meta = listOfNotNull(
        here?.takeIf { !unit.own }?.let { AppController.formatDistance(RoutePolyline.distanceMeters(it, GeoPoint(unit.latitude, unit.longitude))) },
        if (unit.own) null else unit.rssi?.let { "$it dBm" } ?: "via relay",
        "±${unit.accuracyMeters.toInt()} m",
        unit.batteryPercent?.let { "baterai $it%" },
    ).joinToString(" · ") + lastSeenLabel(unit).replace(" | ", " · ")
    ItemCard(tint = if (sos) DangerTint else Color(0xFFF8FAFC)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(name.take(1).uppercase(), if (sos) Danger else if (unit.own) BrandDeep else Accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        (if (sos) "SOS · " else "") + name + if (unit.verified && !unit.own) " ✓" else "",
                        color = if (sos) Danger else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    status?.let { Tag(it.label, if (it.urgent) Warning else Slate) }
                }
                Text(meta, color = if (lowBattery) Warning else Slate, fontSize = 11.sp, lineHeight = 14.sp)
            }
            if (!unit.own) {
                val selected = unit.peerId == state.selectedTargetPeerId
                PillButton(if (selected) "Dipilih" else "Arahkan", filled = selected) { actions.selectTarget(if (selected) null else unit.peerId) }
            }
        }
        if (sos) ItemActions { ResolveButton { actions.resolveEmergency(unit.peerId) } }
    }
}

@Composable
private fun WaypointRow(
    wp: MapWaypoint,
    here: GeoPoint?,
    state: AppUiState,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val meta = listOfNotNull(
        if (wp.own) "Anda" else wp.ownerName,
        here?.let { AppController.formatDistance(RoutePolyline.distanceMeters(it, wp.point)) },
        wp.updatedBy?.let { "diperbarui $it" },
        wp.ackedBy.takeIf { it.isNotEmpty() }?.let(::ackLabel),
    ).joinToString(" · ") + trustLabel(wp.own, wp.verified, state.operationCode != null).replace(" | ", " · ")
    ItemCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WaypointBadge(wp.type, wp.victim)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    listOfNotNull(wp.type.label, wp.label.ifBlank { null }).joinToString(": "),
                    color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                wp.victim?.summary()?.takeIf { it.isNotBlank() }?.let { Text(it, color = Ink, fontSize = 12.sp) }
                Text(meta, color = Slate, fontSize = 11.sp, lineHeight = 14.sp)
            }
            val selected = wp.id == state.selectedWaypointId
            PillButton(if (selected) "Dipilih" else "Arahkan", filled = selected, onClick = onSelect)
        }
        ItemActions {
            onEdit?.let { SmallAction("Perbarui", onClick = it) }
            onShare?.let { SmallAction("Bagikan", onClick = it) }
            DeleteButton(onDelete)
        }
    }
}

@Composable
private fun MoreTab(offlinePack: OfflinePackInfo?, offlineSummary: String, actions: MapActions) {
    ItemCard {
        Text("Peta offline", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            offlinePack?.let { pack ->
                (if (pack.builtIn) "Bawaan: " else "Paket: ") + "${pack.name} · zoom ${pack.minZoom}–${pack.maxZoom}" +
                    (if (pack.sizeBytes > 0) " · ${pack.sizeBytes / (1024 * 1024)} MB" else "")
            } ?: "Belum ada paket peta. Impor file MBTiles (raster) dari posko.",
            color = Ink, fontSize = 12.sp,
        )
        if (offlineSummary.isNotBlank()) Text(offlineSummary, color = Slate, fontSize = 11.sp)
        Text("Peta yang pernah dilihat saat online ikut tersimpan otomatis.", color = Slate, fontSize = 11.sp)
        ItemActions {
            SmallAction(if (offlinePack == null || offlinePack.builtIn) "Tambah wilayah (MBTiles)" else "Ganti paket", onClick = actions.importOfflineMap)
            if (offlinePack != null && !offlinePack.builtIn) DeleteButton(actions.removeOfflineMap)
        }
    }
    ItemCard {
        Text("Data operasi (GPX)", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Jalur & titik untuk dibuka di aplikasi lain atau dibagikan ke posko.", color = Slate, fontSize = 11.sp)
        ItemActions {
            SmallAction("Impor", onClick = actions.importGpx)
            SmallAction("Ekspor", onClick = actions.exportGpx)
        }
    }
}

@Composable
private fun ItemCard(tint: Color = Color(0xFFF8FAFC), content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(tint, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** Aksi sekunder kecil, rata kanan di bawah isi kartu. */
@Composable
private fun ItemActions(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun PillButton(text: String, filled: Boolean = false, color: Color = BrandDeep, onClick: () -> Unit) {
    Text(
        text, color = if (filled) Color.White else color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.background(if (filled) color else color.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .pressableClick(onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun SmallAction(text: String, color: Color = BrandDeep, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.pressableClick(onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Avatar(letter: String, color: Color) {
    Box(Modifier.size(36.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = Slate, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center)
}

/** Korban SOS tetap tampil walau tak ada kabar: beri tahu seberapa lama posisinya tidak diperbarui. */
private fun lastSeenLabel(unit: TrackedUser): String {
    if (!unit.emergency || unit.own) return ""
    val minutes = (currentEpochMillis() - unit.updatedAtMs) / 60_000L
    return if (minutes < 1) " | baru saja" else " | terakhir terlihat $minutes mnt lalu"
}

private enum class SheetMode { Road, Draw, Mark }

/** Empat aksi utama sebagai ubin sejajar; ubin mode yang sedang terbuka diberi warna penuh. */
@Composable
private fun TrailActions(state: AppUiState, mode: SheetMode?, actions: MapActions, onMode: (SheetMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val tile = Modifier.weight(1f)
        if (state.recordingTrackId != null) {
            val points = state.routes.firstOrNull { it.id == state.recordingTrackId }?.points.orEmpty()
            ActionTile("Stop rekam", AppController.formatDistance(RoutePolyline.lengthMeters(points)), true, Danger, tile, actions.stopTrack)
        } else {
            ActionTile("Rekam", "jejak", false, Accent, tile, actions.startTrack)
        }
        ActionTile("Rute", "jalan", mode == SheetMode.Road, BrandDeep, tile) { onMode(SheetMode.Road) }
        ActionTile("Gambar", "rute", mode == SheetMode.Draw, BrandDeep, tile) { onMode(SheetMode.Draw) }
        ActionTile("Tandai", "titik", mode == SheetMode.Mark, Warning, tile) { onMode(SheetMode.Mark) }
    }
}

@Composable
private fun ActionTile(title: String, subtitle: String, active: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.background(if (active) color else color.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .pressableClick(onClick).padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = if (active) Color.White else color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(subtitle, color = if (active) Color.White.copy(alpha = 0.85f) else color.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun DraftPanel(draft: List<GeoPoint>, road: RoadRouteInfo?, actions: MapActions, onClosed: () -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().background(AccentTint, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (road == null) {
            Text("Ketuk peta untuk menambah titik rute", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("${draft.size} titik | ${AppController.formatDistance(RoutePolyline.lengthMeters(draft))}", color = Slate, fontSize = 12.sp)
        } else {
            Text(
                "Rute ${road.modeLabel.lowercase()}: ${AppController.formatDistance(road.distanceMeters)} | ±${formatDuration(road.durationSeconds)}",
                color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            val offRoad = if (road.offRoadMeters >= 30) " Termasuk ${AppController.formatDistance(road.offRoadMeters)} di luar jalan (ke/dari jalan terdekat)." else ""
            Text("Mengikuti jalan dari data OSM offline; kondisi lapangan (longsor, banjir) tidak diketahui.$offRoad", color = Slate, fontSize = 12.sp)
        }
        InputField(name, "Nama rute (mis. Jalur evakuasi utara)") { name = it.take(40) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (road == null) OutlinedButton(onClick = actions.undoDraft, enabled = draft.isNotEmpty()) { Text("Undo") }
            OutlinedButton(onClick = { actions.cancelDraft(); onClosed() }) { Text("Batal") }
            if (road != null) OutlinedButton(onClick = { actions.followDraft(name); onClosed() }) { Text("Ikuti") }
            Button(onClick = { actions.sendDraft(name); onClosed() }, enabled = draft.size >= 2) { Text(if (road == null) "Kirim" else "Kirim ke tim") }
        }
    }
}

/** Cari rute mengikuti jalan (offline) dari posisi sendiri ke titik yang diketuk / tujuan arahan aktif. */
@Composable
private fun RoadRoutePanel(state: AppUiState, actions: MapActions) {
    var mode by remember { mutableStateOf(TravelMode.Foot) }
    val target = state.pickedPoint
    val fallback = navigationTarget(state).takeIf { state.selectedTargetPeerId != null || state.selectedWaypointId != null }
    Column(
        Modifier.fillMaxWidth().background(AccentTint, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            when {
                target != null -> "Tujuan: ${formatCoordinate(target, CoordinateFormat.Decimal)}"
                fallback != null -> "Tujuan: ${fallback.title} (atau ketuk peta untuk tujuan lain)"
                else -> "Ketuk tujuan di peta"
            },
            color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
        ChipRow(TravelMode.entries, { it == mode }, { it.label }) { mode = it }
        Button(
            onClick = { actions.planRoadRoute(mode) },
            enabled = !state.routing && (target != null || fallback != null),
        ) { Text(if (state.routing) "Menghitung..." else "Cari rute") }
    }
}

private fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).toInt().coerceAtLeast(1)
    return if (minutes < 60) "$minutes mnt" else "${minutes / 60} j ${minutes % 60} mnt"
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
    // Warna sama dengan garis di peta: rencana biru, jejak sendiri teal, jejak tim oranye.
    val color = when {
        route.kind == RouteKind.Plan -> BrandDeep
        route.own -> Color(0xFF0F766E)
        else -> Color(0xFFEA580C)
    }
    ItemCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).background(color.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.width(16.dp).height(4.dp).background(color, RoundedCornerShape(2.dp)))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(route.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${AppController.formatDistance(RoutePolyline.lengthMeters(points))} · $kind · ${if (route.own) "Anda" else route.ownerName}" +
                        trust.replace(" | ", " · "),
                    color = if (route.kind == RouteKind.LiveTrack || trust.contains("belum")) Warning else Slate,
                    fontSize = 11.sp, lineHeight = 14.sp,
                )
            }
            if (points.size >= 2) PillButton(if (followed) "Diikuti" else "Ikuti", filled = followed, onClick = onFollow)
        }
        if ((points.size >= 2 && onShare != null) || onDelete != null) {
            ItemActions {
                if (points.size >= 2) onShare?.let { SmallAction("Bagikan", onClick = it) }
                onDelete?.let { DeleteButton(it) }
            }
        }
    }
}

/** Penanda SOS korban dihapus dari HP ini; dua ketukan supaya tidak tertutup karena salah sentuh. */
@Composable
private fun ResolveButton(onConfirm: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    PillButton(if (confirming) "Yakin sudah ditangani?" else "Tandai ditangani", filled = confirming, color = if (confirming) Danger else Success) {
        if (confirming) { confirming = false; onConfirm() } else confirming = true
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
    Box(Modifier.size(36.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
