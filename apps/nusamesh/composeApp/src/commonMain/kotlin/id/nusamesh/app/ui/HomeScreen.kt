package id.nusamesh.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.domain.MeshStatus
import id.nusamesh.app.mesh.engine.LinkState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import nusamesh.composeapp.generated.resources.Res
import nusamesh.composeapp.generated.resources.avatar
import nusamesh.composeapp.generated.resources.bluetooth
import nusamesh.composeapp.generated.resources.device_phone
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun HomeScreen(
    state: AppUiState,
    padding: PaddingValues,
    onMeshToggle: () -> Unit,
    onOpenNodes: () -> Unit,
    onRename: (String) -> Unit,
    onDismissNebeng: () -> Unit,
    onSosToggle: () -> Unit,
) {
    var confirmSos by remember { mutableStateOf(false) }
    val snapshot = state.mesh.engine
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Canvas),
        contentPadding = PaddingValues(
            start = 30.dp,
            end = 30.dp,
            top = padding.calculateTopPadding() + 28.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        item { HomeHeader(state.nickname, onRename) }
        item { Spacer(Modifier.height(20.dp)) }
        item { ModuleQualityCard(state, onMeshToggle, onOpenNodes) }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            SosButton(state.sosActive) {
                if (state.sosActive) onSosToggle() else confirmSos = true
            }
        }
        state.nebengNotice?.let { notice ->
            item { Spacer(Modifier.height(12.dp)) }
            item { NebengBanner(notice, onDismissNebeng) }
        }
        item { Spacer(Modifier.height(25.dp)) }
        val phones = snapshot.peers
        item { DevicesHeader(phones.count { it.direct } + snapshot.nodes.count { it.connected }) }
        item { Spacer(Modifier.height(12.dp)) }
        if (phones.isEmpty() && snapshot.nodes.isEmpty()) {
            item { EmptyDevices(state.mesh) }
        } else {
            items(snapshot.nodes, key = { "node-" + it.peerId }) { node ->
                DeviceCard(
                    icon = IconKind.Node,
                    name = node.name,
                    subtitle = listOfNotNull(
                        node.rssi?.let { "RSSI $it dBm" },
                        node.userSlots?.let { "${it.first}/${it.second} HP" },
                        node.loraNeighbors?.let { "$it node LoRa" },
                    ).joinToString(" · ").ifEmpty { "Nusa Node" },
                    status = when {
                        node.serving && node.connected -> "Melayani"
                        node.connected -> "Tersambung"
                        else -> "Terdeteksi"
                    },
                    active = node.connected,
                    onClick = onOpenNodes,
                )
                Spacer(Modifier.height(12.dp))
            }
            items(phones, key = { "peer-" + it.peerId }) { peer ->
                DeviceCard(
                    icon = null,
                    name = peer.nickname ?: "HP ${peer.peerId.takeLast(4).uppercase()}",
                    subtitle = if (peer.direct) "BLE langsung" + (peer.rssi?.let { " · RSSI $it dBm" } ?: "")
                    else "Terjangkau lewat relay mesh",
                    status = if (peer.direct) "Terhubung" else "Relay",
                    active = peer.direct,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            containerColor = Color.White,
            title = { Text("Aktifkan SOS?", color = Danger, fontWeight = FontWeight.Bold) },
            text = { Text("Koordinat Anda akan disiarkan berulang ke seluruh jaringan mesh sampai SOS dibatalkan.", color = Slate) },
            confirmButton = {
                Text("Kirim SOS", color = Danger, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp).pressableClick {
                    confirmSos = false
                    onSosToggle()
                })
            },
            dismissButton = { Text("Batal", color = Slate, modifier = Modifier.padding(12.dp).pressableClick { confirmSos = false }) },
        )
    }
}

@Composable
private fun SosButton(active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().height(54.dp)
            .background(if (active) Color(0xFF991B1B) else Danger, shape)
            .shadow(10.dp, shape, ambientColor = Danger.copy(alpha = .3f), spotColor = Danger.copy(alpha = .3f))
            .pressableClick(onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (active) "BATALKAN SOS" else "SOS DARURAT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun HomeHeader(nickname: String, onRename: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).pressableClick { editing = true }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Halo, $nickname", color = Ink, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("Ketuk untuk ganti nama di mesh", color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Box(Modifier.size(48.dp).background(Color(0xFFF8FAFC), CircleShape).border(2.dp, BrandSoft, CircleShape).padding(3.dp)) {
            Image(painterResource(Res.drawable.avatar), "Profil Meshta", Modifier.fillMaxSize().clip(CircleShape))
            Box(Modifier.align(Alignment.BottomEnd).size(10.dp).background(Success, CircleShape).border(2.dp, Color.White, CircleShape))
        }
    }
    if (editing) RenameDialog(nickname, onDismiss = { editing = false }, onSave = { onRename(it); editing = false })
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("Nama di mesh", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nama ini terlihat oleh HP lain dan Nusa Node.", color = Slate, fontSize = 11.sp)
                Box(
                    Modifier.fillMaxWidth().height(42.dp).background(Color(0xFFF1F5F9), RoundedCornerShape(21.dp)).padding(horizontal = 15.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it.take(24) },
                        singleLine = true,
                        textStyle = TextStyle(color = Ink, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { Text("Simpan", color = Brand, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(8.dp).pressableClick { onSave(value) }) },
        dismissButton = { Text("Batal", color = Slate, fontSize = 12.sp, modifier = Modifier.padding(8.dp).pressableClick(onDismiss)) },
    )
}

private enum class MeshBadge(val label: String, val color: Color, val background: Color) {
    Serving("Via Node", Success, SuccessTint),
    Nebeng("Nebeng", Warning, WarningTint),
    BleOnly("BLE saja", Brand, BrandTint),
    Searching("Mencari", Brand, BrandTint),
    RadioOff("Bluetooth mati", Danger, DangerTint),
    Offline("Offline", Slate, Color(0xFFF1F5F9)),
}

private fun badgeOf(mesh: MeshStatus): MeshBadge {
    val s = mesh.engine
    return when {
        !mesh.active -> MeshBadge.Offline
        s.linkState == LinkState.PoweredOff || s.linkState == LinkState.Unauthorized -> MeshBadge.RadioOff
        s.servingNode != null -> MeshBadge.Serving
        s.nebeng != null -> MeshBadge.Nebeng
        s.peers.any { it.direct } -> MeshBadge.BleOnly
        else -> MeshBadge.Searching
    }
}

@Composable
private fun ModuleQualityCard(state: AppUiState, onMeshToggle: () -> Unit, onOpenNodes: () -> Unit) {
    val snapshot = state.mesh.engine
    val node = snapshot.servingNode
    val badge = badgeOf(state.mesh)
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth()
            .shadow(20.dp, shape, ambientColor = Brand.copy(alpha = .35f), spotColor = Brand.copy(alpha = .35f))
            .background(Brush.linearGradient(listOf(BrandDeep, Brand, BrandBright)), shape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("JALUR SAAT INI", color = Color.White.copy(alpha = .7f), fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
                Text(
                    node?.name ?: snapshot.nebeng?.let { "Nebeng ${it.relayName}" } ?: if (state.mesh.active) "Mencari Nusa Node" else "Mesh nonaktif",
                    color = Color.White, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Text(
                    when {
                        node != null -> "Node ${node.peerId.takeLast(6)}" + if (node.locked) " · dikunci" else " · otomatis"
                        snapshot.nebeng != null -> "Dititipkan ke ${snapshot.nebeng.nodeName}"
                        else -> "ID Anda ${state.myPeerId.takeLast(6)}"
                    },
                    color = Color.White.copy(alpha = .75f), fontSize = 10.sp,
                )
            }
            GlassPill(badge.label, badge.color)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("RSSI BLE", node?.rssi?.let { "$it" } ?: "--", "dBm", Modifier.weight(1f))
            Metric("SNR LoRa", node?.loraBestSnr?.let { "$it" } ?: "--", "dB", Modifier.weight(1f))
            Metric("Slot", node?.userSlots?.let { "${it.first}/${it.second}" } ?: "--", "HP", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroButton(if (state.mesh.active) "Matikan Mesh" else "Aktifkan Mesh", filled = !state.mesh.active, Modifier.weight(1f), onMeshToggle) { tint ->
                Icon(painterResource(Res.drawable.bluetooth), null, tint = tint, modifier = Modifier.size(15.dp))
            }
            HeroButton(if (snapshot.nodes.isNotEmpty()) "Nusa Node (${snapshot.nodes.size})" else "Nusa Node", filled = false, Modifier.weight(1f), onOpenNodes) { tint ->
                AppIcon(IconKind.Node, tint, Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun GlassPill(text: String, dot: Color) {
    Row(
        Modifier.background(Color.White.copy(alpha = .18f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = .3f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(if (dot == Slate) Color.White.copy(alpha = .6f) else dot, CircleShape).border(1.dp, Color.White, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HeroButton(label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val tint = if (filled) Brand else Color.White
    Row(
        modifier.height(42.dp)
            .background(if (filled) Color.White else Color.White.copy(alpha = .16f), shape)
            .border(1.dp, Color.White.copy(alpha = if (filled) 1f else .35f), shape)
            .pressableClick(onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(tint)
        Spacer(Modifier.width(8.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier) {
    Column(
        modifier.background(Color.White.copy(alpha = .14f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = .72f), fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Color.White, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(3.dp))
            Text(unit, color = Color.White.copy(alpha = .72f), fontSize = 9.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
internal fun NebengBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(WarningTint, RoundedCornerShape(16.dp))
            .border(1.dp, Warning.copy(alpha = .25f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(IconKind.Relay, Warning, Modifier.size(20.dp))
        Text(text, color = Warning, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(24.dp).pressableClick(onDismiss), contentAlignment = Alignment.Center) {
            AppIcon(IconKind.Close, Warning, Modifier.size(12.dp))
        }
    }
}



@Composable
private fun DevicesHeader(count: Int) {
    Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("PERANGKAT SALING TERHUBUNG", color = Brand, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
        StatusPill("$count Aktif", Brand, BrandSoft)
    }
}

@Composable
private fun EmptyDevices(mesh: MeshStatus) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 70.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            when {
                !mesh.active -> "Mesh belum aktif. Tekan Aktifkan Mesh untuk mulai mencari HP dan Nusa Node."
                mesh.engine.linkState == LinkState.PoweredOff -> "Bluetooth mati. Nyalakan Bluetooth agar mesh bisa berjalan."
                mesh.engine.linkState == LinkState.Unauthorized -> "Izin Bluetooth belum diberikan."
                mesh.engine.linkState == LinkState.Unsupported -> "Perangkat ini tidak mendukung mesh Bluetooth."
                else -> "Sedang mencari HP Meshta dan Nusa Node di sekitar…"
            },
            color = Slate,
            fontSize = 10.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun DeviceCard(icon: IconKind?, name: String, subtitle: String, status: String, active: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(70.dp).shadow(6.dp, RoundedCornerShape(20.dp), ambientColor = Ink.copy(alpha = .08f), spotColor = Ink.copy(alpha = .08f))
            .background(Color.White, RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.pressableClick(onClick) else Modifier).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(40.dp).background(if (icon == IconKind.Node) AccentTint else BrandTint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            if (icon != null) AppIcon(icon, Accent, Modifier.size(18.dp))
            else Icon(painterResource(Res.drawable.device_phone), null, tint = Brand, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
            Text(subtitle, color = Muted, fontSize = 8.sp, lineHeight = 11.sp, maxLines = 1)
        }
        StatusPill(status, if (active) Success else Slate, if (active) SuccessTint else Color(0xFFF1F5F9))
    }
}
