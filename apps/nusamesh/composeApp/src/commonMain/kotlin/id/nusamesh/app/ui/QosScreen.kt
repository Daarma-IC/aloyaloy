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
import id.nusamesh.app.media.ChatMediaActions
import id.nusamesh.app.media.SrSpeed
import androidx.compose.runtime.LaunchedEffect
import id.nusamesh.app.mesh.core.NodeQueue
import id.nusamesh.app.mesh.protocol.NodeLiveStats

/** Aksi terkait QoS dan ekspor (diteruskan ke AppController / pemilih file). */
class QosActions(
    val start: (total: Int, intervalSeconds: Int, label: String) -> Unit = { _, _, _ -> },
    val stop: () -> Unit = {},
    val clear: () -> Unit = {},
    val exportPackets: () -> Unit = {},
    val exportSummary: () -> Unit = {},
    val setLoraOnly: (Boolean) -> Unit = {},
    val exportVoice: () -> Unit = {},
)

/** Statistik radio node yang diperbarui tiap ±2 detik (blok live LORA_HEALTH). */
@Composable
internal fun LiveNodePanel(live: NodeLiveStats?) {
    Column(
        Modifier.fillMaxWidth().background(BrandTint, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Radio LoRa node (live)", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (live == null) {
            Text("Firmware node belum mengirim data live. Flash firmware NusaNode terbaru.", color = Slate, fontSize = 11.sp)
            return@Column
        }
        if (!live.radioReady) {
            val reason = if (live.dualRadio) "board companion belum tersambung atau belum siap" else "radio belum siap"
            Text("Radio tidak bisa kirim/terima: $reason.", color = Warning, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        LiveRow("Mode", if (live.dualRadio) "dua radio" else "satu radio")
        LiveRow("Frame terkirim", "${live.txFrames} (terakhir ${ageText(live.lastTxAgeSeconds)})")
        LiveRow("Frame diterima", "${live.rxFrames} (terakhir ${ageText(live.lastRxAgeSeconds)})")
        if (live.rxFrames > 0) {
            LiveRow("Sinyal terakhir", "RSSI ${formatOne(live.lastRssiDbm)} dBm · SNR ${formatOne(live.lastSnrDb)} dB")
        }
        val capacity = live.radio?.budgetCapacityMs?.let { " dari $it" } ?: ""
        LiveRow("Antrean TX", "${live.queueDepth} frame · sisa airtime ${live.budgetMs}$capacity ms")
        live.airtimeTotalMs?.let { LiveRow("Airtime terpakai", "${it / 1000} dtk sejak node menyala") }
        live.radio?.let { r ->
            LiveRow("Radio", "${r.frequencyMhz} MHz · ${r.txPowerDbm} dBm · BW ${r.bandwidthKhz} · CR 4/${r.codingRate}")
            LiveRow("Batas duty cycle", "${r.dutyLimitPercent}%")
        }
        live.ble?.let { b ->
            LiveRow(
                "Putus BLE (menurut node)",
                "${b.disconnects}× · terakhir ${id.nusamesh.app.mesh.protocol.BleReasons.nimble(b.lastReason)}",
                warn = b.disconnects > 0 && (b.lastDisconnectAgeSeconds ?: Int.MAX_VALUE) < 60,
            )
        }
        live.batteryMv?.let { LiveRow("Baterai node","${it / 1000}.${(it % 1000 / 10).toString().padStart(2, '0')} V") }
        LiveRow(
            "Dibuang / CRC rusak", "${live.droppedFrames} / ${live.crcErrors}",
            warn = live.droppedFrames > 0 || live.crcErrors > 0,
        )
    }
}

@Composable
private fun LiveRow(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Slate, fontSize = 11.sp)
        Text(value, color = if (warn) Warning else Ink, fontSize = 11.sp)
    }
}

private fun ageText(seconds: Int?): String = when {
    seconds == null -> "belum pernah"
    seconds < 60 -> "$seconds dtk lalu"
    else -> "${seconds / 60} mnt lalu"
}

private fun formatOne(value: Float): String {
    val tenths = kotlin.math.round(value * 10).toInt()
    val sign = if (tenths < 0) "-" else ""
    val abs = kotlin.math.abs(tenths)
    return "$sign${abs / 10},${abs % 10}"
}

/**
 * Uji kecepatan AI super-resolution di HP ini: tiap model dijalankan pada gambar 128×128 (median 3 kali).
 * Hasilnya juga menentukan model yang dipakai tombol "Perjelas (AI)". Data waktu nyata per HP untuk TA.
 */
@Composable
fun SrBenchmarkDialog(media: ChatMediaActions, onDismiss: () -> Unit) {
    var results by remember { mutableStateOf<List<SrSpeed>?>(null) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        media.benchmarkSuperResolution({ speeds, pick -> results = speeds; chosen = pick }, { error = it })
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Uji kecepatan AI (HP ini)", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Perjelas gambar LoRa 128 → 512 px, 4 thread CPU, median 3 kali.", color = Slate, fontSize = 11.sp)
            when {
                error != null -> Text(error!!, color = Danger, fontSize = 12.sp)
                results == null -> Text("Mengukur… (bisa beberapa detik di HP lambat)", color = Ink, fontSize = 12.sp)
                else -> {
                    results!!.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.model + if (r.model == chosen) "  ✓ dipakai" else "",
                                color = Ink, fontSize = 13.sp,
                                fontWeight = if (r.model == chosen) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            Text(r.millis?.let { "$it ms" } ?: "gagal", color = if (r.millis == null) Danger else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("  ${r.sizeKb} KB", color = Slate, fontSize = 11.sp)
                        }
                        r.error?.let { Text(it, color = Danger, fontSize = 10.sp) }
                    }
                    Text(
                        "Model terbaik yang ≤ 1,5 dtk dipilih otomatis untuk tombol \"Perjelas (AI)\".",
                        color = Slate, fontSize = 10.sp,
                    )
                }
            }
            Button(onClick = onDismiss) { Text("Tutup") }
        }
    }
}
