package id.nusamesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.data.MobilityLogEntry
import id.nusamesh.app.data.formatClock
import id.nusamesh.app.mesh.engine.EngineSnapshot
import id.nusamesh.app.mesh.engine.NodeInfo
import id.nusamesh.app.mesh.mobility.MobilityConfig

/**
 * Lembar Nusa Node: daftar node (RSSI BLE, slot HP, kesehatan LoRa), kunci manual / kembali otomatis,
 * dan panel uji mobility (parameter handover + log) untuk pengujian TA.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeSheet(
    snapshot: EngineSnapshot,
    mobilityConfig: MobilityConfig,
    mobilityLog: List<MobilityLogEntry>,
    onLock: (String?) -> Unit,
    onConfigChange: (MobilityConfig) -> Unit,
    onClearLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val locked = snapshot.nodes.firstOrNull { it.locked }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.White) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Nusa Node", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (locked != null) "Dikunci manual ke ${locked.name}. Handover otomatis dimatikan."
                    else "Otomatis: HP memilih node dengan sinyal terkuat dan berpindah (handover) saat Anda bergerak.",
                    color = Slate, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }
            if (locked != null) {
                SheetButton("Kembali ke otomatis", filled = false) { onLock(null) }
            }
            snapshot.nebeng?.let { route ->
                NebengInfo("Tidak ada node dalam jangkauan langsung. Pesan dititipkan lewat ${route.relayName} ke ${route.nodeName}.")
            }
            if (snapshot.nodes.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 70.dp).border(1.dp, Border, RoundedCornerShape(16.dp)).padding(16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("Belum ada Nusa Node terdeteksi. Pastikan mesh aktif dan node menyala dalam jarak Bluetooth.", color = Slate, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
            snapshot.nodes.forEach { node ->
                NodeRow(node) { onLock(if (node.locked) null else node.peerId) }
            }
            Spacer(Modifier.height(4.dp))
            MobilityPanel(mobilityConfig, mobilityLog, onConfigChange, onClearLog)
        }
    }
}

@Composable
private fun NodeRow(node: NodeInfo, onClick: () -> Unit) {
    val active = node.serving && node.connected
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().background(if (active) BrandTint else Color(0xFFF8FAFC), shape)
            .border(1.dp, if (active) BrandSoft else Border, shape).pressableClick(onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(if (active) Brand else Color.White, CircleShape).border(1.dp, BrandSoft, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(IconKind.Node, if (active) Color.White else Brand, Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(node.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (node.locked) AppIcon(IconKind.Lock, Brand, Modifier.size(12.dp))
            }
            Text(
                listOfNotNull(
                    node.rssi?.let { "RSSI $it dBm" },
                    node.userSlots?.let { "${it.first}/${it.second} HP" },
                    node.loraNeighbors?.let { "$it tetangga LoRa" },
                    node.loraBestSnr?.let { "SNR $it dB" },
                ).joinToString(" · ").ifEmpty { "Menunggu data node…" },
                color = Slate, fontSize = 9.sp, lineHeight = 13.sp,
            )
            Text(
                when {
                    active && node.registered -> "Melayani HP ini · terdaftar"
                    active -> "Melayani HP ini · mendaftar…"
                    node.connected -> "Tersambung"
                    node.userSlots?.let { it.first >= it.second } == true -> "Slot penuh: bisa nebeng lewat HP yang tersambung"
                    else -> "Terdeteksi · ketuk untuk kunci"
                },
                color = if (active) Brand else Muted, fontSize = 9.sp,
            )
        }
        AppIcon(IconKind.Chevron, Muted, Modifier.size(14.dp))
    }
}

@Composable
private fun NebengInfo(text: String) {
    Row(
        Modifier.fillMaxWidth().background(WarningTint, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(IconKind.Relay, Warning, Modifier.size(20.dp))
        Text(text, color = Warning, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun MobilityPanel(
    config: MobilityConfig,
    log: List<MobilityLogEntry>,
    onChange: (MobilityConfig) -> Unit,
    onClearLog: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().border(1.dp, Border, shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().pressableClick { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            AppIcon(IconKind.Signal, Brand, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Panel uji mobility", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("Hysteresis ${config.hysteresisDb} dB · TTT ${config.timeToTriggerMs / 1000} s · ${log.size} log", color = Slate, fontSize = 9.sp)
            }
            Text(if (open) "Tutup" else "Buka", color = Brand, fontSize = 10.sp)
        }
        if (!open) return@Column
        ToggleRow("Handover otomatis", config.handoverEnabled) { onChange(config.copy(handoverEnabled = it)) }
        Stepper("Hysteresis (A3)", "${config.hysteresisDb} dB",
            onMinus = { onChange(config.copy(hysteresisDb = (config.hysteresisDb - 1).coerceAtLeast(0))) },
            onPlus = { onChange(config.copy(hysteresisDb = (config.hysteresisDb + 1).coerceAtMost(20))) })
        Stepper("Time-to-trigger", "${config.tttChecks}× cek",
            onMinus = { onChange(config.copy(tttChecks = (config.tttChecks - 1).coerceAtLeast(1))) },
            onPlus = { onChange(config.copy(tttChecks = (config.tttChecks + 1).coerceAtMost(10))) })
        Stepper("Interval cek", "${config.checkIntervalMs / 1000} s",
            onMinus = { onChange(config.copy(checkIntervalMs = (config.checkIntervalMs - 1_000).coerceAtLeast(1_000))) },
            onPlus = { onChange(config.copy(checkIntervalMs = (config.checkIntervalMs + 1_000).coerceAtMost(15_000))) })
        Stepper("Ambang lemah (A2)", "${config.weakRssiDbm} dBm",
            onMinus = { onChange(config.copy(weakRssiDbm = (config.weakRssiDbm - 1).coerceAtLeast(-110))) },
            onPlus = { onChange(config.copy(weakRssiDbm = (config.weakRssiDbm + 1).coerceAtMost(-60))) })
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Filter RSSI (EWMA α)", color = Ink, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.3f, 0.5f, 0.7f, 1f).forEach { a ->
                    Chip(if (a == 1f) "Mati" else a.toString(), config.filterAlpha == a) { onChange(config.copy(filterAlpha = a)) }
                }
            }
        }
        ToggleRow("Catat log", config.loggingEnabled) { onChange(config.copy(loggingEnabled = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Reset parameter", false) { onChange(MobilityConfig()) }
            Chip("Hapus log", false, onClearLog)
        }
        if (log.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                log.takeLast(25).asReversed().forEach { e ->
                    Text(
                        "${formatClock(e.timeMs)}  ${e.event}  ${e.from?.takeLast(4) ?: "-"}→${e.to?.takeLast(4) ?: "-"}" +
                            (e.targetRssi?.let { "  $it dBm" } ?: "") + (e.valueMs?.let { "  ${it} ms" } ?: "") +
                            (if (e.detail.isNotEmpty()) "  ${e.detail}" else ""),
                        color = Ink, fontSize = 8.sp, lineHeight = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 10.sp, modifier = Modifier.weight(1f))
        StepButton("−", onMinus)
        Text(value, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(64.dp).padding(horizontal = 6.dp), maxLines = 1)
        StepButton("+", onPlus)
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).background(BrandTint, CircleShape).pressableClick(onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Brand, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().pressableClick { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(38.dp).height(22.dp).background(if (checked) Brand else Color(0xFFCBD5E1), RoundedCornerShape(11.dp)).padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(16.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) Brand else BrandTint, RoundedCornerShape(14.dp)).pressableClick(onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (selected) Color.White else Brand, fontSize = 10.sp)
    }
}

@Composable
private fun SheetButton(text: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).background(if (filled) Brand else BrandTint, RoundedCornerShape(20.dp)).pressableClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color.White else Brand, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
