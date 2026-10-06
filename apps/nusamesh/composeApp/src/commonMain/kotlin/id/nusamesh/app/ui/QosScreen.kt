package id.nusamesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.mesh.core.NodeQueue

/** Aksi uji QoS (diteruskan ke AppController / pemilih file). */
class QosActions(
    val start: (total: Int, intervalSeconds: Int, label: String) -> Unit = { _, _, _ -> },
    val stop: () -> Unit = {},
    val clear: () -> Unit = {},
    val exportPackets: () -> Unit = {},
    val exportSummary: () -> Unit = {},
)

/**
 * Uji QoS untuk TA: HP pengirim memancarkan N paket bernomor; HP penerima mencatat RSSI/SNR/SF/jarak
 * tiap paket dan menghitung Packet Success Probability. Ulangi per jarak & per SF, lalu ekspor CSV.
 */
@Composable
fun QosDialog(state: AppUiState, actions: QosActions, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("50") }
    var interval by remember { mutableStateOf("10") }
    var confirmClear by remember { mutableStateOf(false) }
    val node = state.mesh.engine.servingNode
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 640.dp)
                .background(Color.White, RoundedCornerShape(20.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Uji QoS LoRa", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                node?.let {
                    "Node: ${it.name} · ${it.spreadingFactor?.let { sf -> "SF$sf" } ?: "SF ? (firmware lama)"} · mode uji ${if (it.testMode) "AKTIF" else "mati"}"
                } ?: "Belum tersambung ke Nusa Node — paket hanya lewat Bluetooth.",
                color = if (node == null) Warning else Slate, fontSize = 11.sp,
            )

            Text("Kirim paket uji", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            val sending = state.qosSending
            if (sending != null) {
                Text("\"${sending.label}\": ${sending.sent}/${sending.total} terkirim, tiap ${sending.intervalMs / 1000} dtk", color = Ink, fontSize = 12.sp)
                LinearProgressIndicator(progress = { sending.sent.toFloat() / sending.total }, modifier = Modifier.fillMaxWidth().height(5.dp))
                OutlinedButton(onClick = actions.stop) { Text("Hentikan uji") }
            } else {
                QosField("Label titik uji (mis. 500 m SF9)", label) { label = it.take(40) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { QosField("Jumlah paket", total, numeric = true) { total = it.filter(Char::isDigit).take(4) } }
                    Box(Modifier.weight(1f)) { QosField("Interval (detik)", interval, numeric = true) { interval = it.filter(Char::isDigit).take(3) } }
                }
                val sf = node?.spreadingFactor ?: NodeQueue.DEFAULT_SF
                val packetAirMs = NodeQueue.airtimeMs(ByteArray(QOS_PACKET_BYTES), sf)
                // Duty cycle HP di node 4%: jeda minimal agar paket tidak menumpuk di antrean.
                val minInterval = kotlin.math.ceil(packetAirMs / 0.04 / 1000).toInt().coerceAtLeast(4)
                Text(
                    "SF$sf: ±${packetAirMs.toInt()} ms per paket. Interval minimal ±$minInterval dtk supaya tidak tertahan antrean duty cycle.",
                    color = if ((interval.toIntOrNull() ?: 0) < minInterval) Warning else Slate, fontSize = 10.sp,
                )
                Button(onClick = {
                    actions.start(total.toIntOrNull() ?: 50, interval.toIntOrNull() ?: 10, label)
                }) { Text("Mulai kirim") }
            }

            Text("Hasil diterima HP ini", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (state.qosRuns.isEmpty()) {
                Text("Belum ada. Hasil muncul di HP penerima saat HP lain mengirim uji.", color = Slate, fontSize = 11.sp)
            }
            state.qosRuns.asReversed().forEach { run ->
                Column(
                    Modifier.fillMaxWidth().background(BrandTint, RoundedCornerShape(12.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("${run.label} · dari ${run.senderName}", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("PSP " + run.summary(), color = Ink, fontSize = 11.sp)
                    if (run.highestSeq < run.total) {
                        Text("Masih berjalan atau berhenti di paket ${run.highestSeq}/${run.total}", color = Slate, fontSize = 10.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = actions.exportPackets, enabled = state.qosRuns.isNotEmpty()) { Text("CSV per paket") }
                OutlinedButton(onClick = actions.exportSummary, enabled = state.qosRuns.isNotEmpty()) { Text("CSV ringkasan") }
            }
            if (state.qosRuns.isNotEmpty()) {
                Text(
                    if (confirmClear) "Ketuk lagi untuk menghapus semua hasil" else "Hapus semua hasil",
                    color = Danger, fontSize = 11.sp,
                    modifier = Modifier.pressableClick { if (confirmClear) { confirmClear = false; actions.clear() } else confirmClear = true }.padding(vertical = 4.dp),
                )
            }
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Slate)) { Text("Tutup") }
        }
    }
}

/** Perkiraan ukuran satu paket uji di udara (header app + probe teks), untuk saran interval. */
private const val QOS_PACKET_BYTES = 140

@Composable
private fun QosField(placeholder: String, value: String, numeric: Boolean = false, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).background(Color(0xFFF1F5F9), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = 12.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 12.sp),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
