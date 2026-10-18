package id.nusamesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.data.formatClock
import id.nusamesh.app.mesh.engine.*
import kotlin.math.roundToInt

@Composable
fun TransmissionMonitorScreen(
    diagnostics: DiagnosticSnapshot, engine: EngineSnapshot, padding: PaddingValues,
    onExportLog: () -> Unit, onExportSummary: () -> Unit, onClear: () -> Unit, onBack: () -> Unit,
) {
    var frozen by remember { mutableStateOf<DiagnosticSnapshot?>(null) }
    var tab by remember { mutableStateOf(0) }
    var selectedDevice by remember { mutableStateOf<String?>(null) }
    var layer by remember { mutableStateOf<DiagnosticLayer?>(null) }
    var query by remember { mutableStateOf("") }
    var problems by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val s = frozen ?: diagnostics
    val logScroll = rememberLazyListState()
    val charts = remember(s.events, s.elapsedMs, selectedDevice) { transmissionCharts(s, selectedDevice) }
    val rows = remember(s.events, selectedDevice, layer, query, problems) {
        s.events.asReversed().filter { e ->
            (selectedDevice == null || selectedDevice == e.deviceId) && (layer == null || layer == e.layer) &&
                (!problems || e.level != DiagnosticLevel.INFO) &&
                listOf(e.name,e.deviceId.orEmpty(),e.messageId.orEmpty(),e.detail).any { it.contains(query,true) }
        }
    }
    LaunchedEffect(rows.firstOrNull()?.sequence, tab, frozen) { if(tab==2 && frozen==null) logScroll.scrollToItem(0) }
    Column(Modifier.fillMaxSize().background(Canvas).padding(
        top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick=onBack) { Text("Kembali") }
            Column(Modifier.weight(1f)) {
                Text("QoS pesan", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(if (frozen == null) "Langsung · BLE & LoRa" else "Tampilan dijeda · rekaman tetap berjalan", color = if (frozen == null) BrandDeep else Warning, fontSize = 11.sp)
            }
            TextButton(onClick = { frozen = if (frozen == null) diagnostics else null }) { Text(if (frozen == null) "Jeda" else "Lanjut") }
        }
        if (diagnostics.persistenceAvailable == false) Text("Log belum tersimpan. Ekspor CSV sebelum keluar.", Modifier.padding(horizontal=20.dp), color=Danger, fontSize=11.sp)
        Text("${engine.nodes.count { it.connected }} node tersambung · ${engine.nodes.sumOf { it.queuedPackets }} paket antre", Modifier.padding(horizontal=20.dp), color=Slate,fontSize=11.sp)
        TabRow(selectedTabIndex = tab, containerColor = Canvas) {
            listOf("Grafik", "Metrik", "Log").forEachIndexed { i, name -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) }) }
        }
        if (s.devices.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(selected=selectedDevice==null,onClick={selectedDevice=null},label={Text("Semua device",fontSize=10.sp)})
            s.devices.keys.forEach { id -> FilterChip(selected=selectedDevice==id,onClick={selectedDevice=id},label={Text(id,fontSize=10.sp)}) }
        }
        when(tab) {
            0 -> LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    MonitorCard {
                        val completed=s.count("TX_ACCEPTED")+s.count("TX_FAILED")
                        Text(if(completed==0L) "Menunggu transmisi" else if(s.count("TX_FAILED")>0) "Ada pengiriman BLE yang gagal" else "Pengiriman diterima stack BLE", color=if(s.count("TX_FAILED")>0) Warning else BrandDeep, fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                        Text("Sesi semua device: ${s.count("TX_ACCEPTED")} OK · ${s.count("TX_FAILED")} gagal · ${s.acknowledgedMessages} pesan ber-ACK",color=Ink,fontSize=12.sp)
                        Text("Grafik hingga 5 menit terakhir · kelompok 10 detik. Ketuk grafik untuk membaca sampel.",color=Slate,fontSize=10.sp)
                        if(charts.truncated) Text("Sebagian riwayat terpangkas/dihapus; grafik hanya memakai data yang tersedia.",color=Warning,fontSize=10.sp)
                    }
                }
                item { CombinedQosPlot(charts) }
                item { Text("OK BLE bukan konfirmasi pancar LoRa. Tanpa ACK belum berarti paket hilang. Sinyal LoRa memakai frame terakhir yang melengkapi paket.",color=Slate,fontSize=11.sp) }
            }
            1 -> LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                item { MonitorCard {
                    Text("Ringkasan sesi",fontWeight=FontWeight.Bold,color=Ink)
                    Text("${s.sessionId} · ${s.elapsedMs/1000} detik",color=Slate,fontSize=10.sp)
                    MetricLine("Penerimaan stack BLE",s.bleAcceptancePercent?.let { "${(it*10).roundToInt()/10.0}%" } ?: "Belum ada data")
                    MetricLine("Byte TX / RX BLE","${s.txBytes} / ${s.rxBytes}")
                    MetricLine("Laju rata-rata sesi",s.bleTxBytesPerSecond?.let { "${it.roundToInt()} B/s" } ?: "—")
                    MetricLine("BLE p50 / p95","${ms(s.percentile(s.bleDurations,.5))} / ${ms(s.percentile(s.bleDurations,.95))}")
                    MetricLine("Antre p95",ms(s.percentile(s.queueDurations,.95)))
                    MetricLine("ACK RTT p50 / p95","${ms(s.percentile(s.ackDurations,.5))} / ${ms(s.percentile(s.ackDurations,.95))}")
                    MetricLine("Pesan ber-ACK / tanpa ACK","${s.acknowledgedMessages} / ${s.trackedMessages}")
                    MetricLine("Antrean ditolak / duplikat RX","${s.count("QUEUE_REJECTED")} / ${s.count("RX_DUPLICATE")}")
                    MetricLine("Pengajuan ulang / ACK tanpa korelasi","${s.count("MESSAGE_RESUBMITTED")} / ${s.count("ACK_UNMATCHED")}")
                    Text("Durasi memakai 256 sampel terakhir. Ringkasan ini seluruh sesi; filter device membatasi grafik dan log.",fontSize=10.sp,color=Slate)
                } }
                selectedDevice?.let { id -> s.devices[id]?.let { d -> item { MonitorCard {
                    Text("Device $id",color=Ink,fontWeight=FontWeight.Bold,fontSize=12.sp)
                    MetricLine("Write OK / gagal","${d.acceptedWrites} / ${d.failedWrites}")
                    MetricLine("Byte TX / RX","${d.txBytes} / ${d.rxBytes}")
                    MetricLine("BLE RSSI",d.bleRssiDbm?.let { "$it dBm" } ?: "—")
                    MetricLine("LoRa RSSI / SNR","${d.loraRssiDbm ?: "—"} dBm / ${d.loraSnrDb ?: "—"} dB")
                } } } }
                item { MonitorCard {
                    Text("Ekspor data",fontWeight=FontWeight.Bold,color=Ink)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick=onExportLog) { Text("CSV log") }
                        OutlinedButton(onClick=onExportSummary) { Text("CSV metrik") }
                    }
                    Text("Ekspor memakai data live, termasuk riwayat log sesi sebelumnya.",fontSize=10.sp,color=Slate)
                } }
                item { Text("ACK RTT mencakup antrean dan perjalanan balik. Firmware belum melaporkan TX radio aktual; packet loss trafik umum tidak diasumsikan dari ketiadaan ACK.",color=Slate,fontSize=11.sp) }
            }
            else -> {
                Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(query,{query=it.take(128)},Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Cari event, device, message ID",fontSize=12.sp)})
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        FilterChip(selected=layer==null,onClick={layer=null},label={Text("Semua",fontSize=10.sp)})
                        DiagnosticLayer.entries.forEach { v -> FilterChip(selected=layer==v,onClick={layer=v},label={Text(v.name,fontSize=10.sp)}) }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        FilterChip(selected=problems,onClick={problems=!problems},label={Text("Warning / error",fontSize=10.sp)})
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick={if(confirmClear){onClear();frozen=null;confirmClear=false}else confirmClear=true}) { Text(if(confirmClear) "Yakin hapus?" else "Hapus log",color=Danger,fontSize=10.sp) }
                    }
                    Text("${rows.size} event · maks. 1.000 · terbaru di atas",color=Slate,fontSize=10.sp)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=logScroll,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(rows.isEmpty()) item { Text("Belum ada event sesuai filter.",color=Slate,fontSize=12.sp) }
                    items(rows,key={"${it.sessionId}-${it.sequence}"}) { e -> MonitorCard {
                        Text("${formatClock(e.timeMs)} · ${e.layer} · ${e.name}",fontSize=11.sp,color=when(e.level){DiagnosticLevel.ERROR->Danger;DiagnosticLevel.WARN->Warning;else->BrandDeep},fontWeight=FontWeight.SemiBold)
                        Text(listOfNotNull(e.deviceId,e.messageId,e.bytes?.let{"$it B"},e.durationMs?.let{"$it ms"},e.rssiDbm?.let{"$it dBm"},e.snrDb?.let{"$it dB"},e.sf?.let{"SF$it"}).joinToString(" · "),color=Ink,fontSize=10.sp)
                        if(e.detail.isNotBlank()) Text(e.detail,fontSize=10.sp,color=Slate)
                        Text("sesi ${e.sessionId} · #${e.sequence}",fontSize=9.sp,color=Muted)
                    } }
                }
            }
        }
    }
}

@Composable private fun MonitorCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(16.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp),content=content)
}
@Composable private fun MetricLine(label:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(label,Modifier.weight(1f),color=Slate,fontSize=11.sp)
        Text(value,color=Ink,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
    }
}
private fun ms(v:Long?)=v?.let{"$it ms"} ?: "—"
