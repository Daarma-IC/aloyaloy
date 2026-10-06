package id.nusamesh.app.data

import id.nusamesh.app.domain.DeliveryPath
import id.nusamesh.app.domain.QosRun
import id.nusamesh.app.domain.QosSample
import id.nusamesh.app.domain.RxMeasurement
import id.nusamesh.app.mesh.protocol.telemetryText

/**
 * Hasil uji QoS: disimpan permanen (data TA tidak boleh hilang saat aplikasi ditutup) dan diekspor CSV
 * untuk grafik RSSI vs jarak dan PSP vs jarak per SF. CSV memakai koma & titik desimal (Python, MATLAB,
 * Google Sheets; di Excel berbahasa Indonesia buka lewat Data → From Text/CSV).
 */
object QosRecords {
    private const val VERSION = "q1"

    // Satu baris per sampel: q1|run|sender|senderName|label|total|senderSf|startedAt|seq|rxAt|rssi|snr|sf|bw|hop|matched|ble|dist|age|latency|path
    fun encode(runs: List<QosRun>): String = runs.flatMap { run ->
        run.samples.map { s ->
            val rx = s.rx
            listOf(
                VERSION, run.runId, run.senderPeerId, telemetryText(run.senderName), telemetryText(run.label), run.total,
                run.senderSf ?: "", run.startedAtMs, s.seq, rx.receivedAtMs, rx.loraRssiDbm ?: "", rx.loraSnrDb ?: "",
                rx.spreadingFactor ?: "", rx.bandwidthKHz ?: "", rx.hopLeft ?: "", rx.metaMatched?.let { if (it) 1 else 0 } ?: "",
                rx.bleRssiDbm ?: "", rx.distanceMeters ?: "", rx.senderPositionAgeMs ?: "", rx.latencyMs ?: "", rx.path.name,
            ).joinToString("|")
        }
    }.joinToString("\n")

    fun decode(value: String?): List<QosRun> {
        val runs = LinkedHashMap<Pair<String, String>, QosRun>()
        for (line in value.orEmpty().lineSequence()) {
            val f = line.split('|')
            if (f.size != 21 || f[0] != VERSION) continue
            val total = f[5].toIntOrNull() ?: continue
            val seq = f[8].toIntOrNull() ?: continue
            val rx = RxMeasurement(
                receivedAtMs = f[9].toLongOrNull() ?: continue,
                path = DeliveryPath.entries.firstOrNull { it.name == f[20] } ?: DeliveryPath.Ble,
                loraRssiDbm = f[10].toFloatOrNull(), loraSnrDb = f[11].toFloatOrNull(), spreadingFactor = f[12].toIntOrNull(),
                bandwidthKHz = f[13].toFloatOrNull(), hopLeft = f[14].toIntOrNull(), metaMatched = f[15].toIntOrNull()?.let { it == 1 },
                bleRssiDbm = f[16].toIntOrNull(), distanceMeters = f[17].toDoubleOrNull(), senderPositionAgeMs = f[18].toLongOrNull(),
                latencyMs = f[19].toLongOrNull(),
            )
            val key = f[1] to f[2]
            val run = runs[key] ?: QosRun(f[1], f[2], f[3], f[4], total, f[6].toIntOrNull(), emptyList(), f[7].toLongOrNull() ?: 0L)
            runs[key] = run.copy(samples = run.samples + QosSample(seq, rx))
        }
        return runs.values.toList()
    }

    fun packetsCsv(runs: List<QosRun>): String = buildString {
        appendLine(
            "run_id,label,sender,seq,total,received_at_utc,rssi_dbm,snr_db,sf,bw_khz,hop_left,meta_matched," +
                "ble_rssi_dbm,distance_m,sender_position_age_s,latency_ms,path",
        )
        for (run in runs) for (s in run.samples.sortedBy { it.seq }) {
            val rx = s.rx
            appendLine(
                listOf(
                    run.runId, csv(run.label), csv(run.senderName), s.seq, run.total, GpxCodec.isoTime(rx.receivedAtMs),
                    rx.loraRssiDbm ?: "", rx.loraSnrDb ?: "", rx.spreadingFactor ?: run.senderSf ?: "", rx.bandwidthKHz ?: "",
                    rx.hopLeft ?: "", rx.metaMatched?.let { if (it) 1 else 0 } ?: "", rx.bleRssiDbm ?: "",
                    rx.distanceMeters?.let { round1(it) } ?: "", rx.senderPositionAgeMs?.let { it / 1000 } ?: "", rx.latencyMs ?: "", rx.path.name,
                ).joinToString(","),
            )
        }
    }

    fun summaryCsv(runs: List<QosRun>): String = buildString {
        appendLine("run_id,label,sender,sf,total_sent,received,psp_percent,mean_rssi_dbm,mean_snr_db,mean_distance_m,mean_latency_ms,started_at_utc")
        for (run in runs) {
            appendLine(
                listOf(
                    run.runId, csv(run.label), csv(run.senderName), run.measuredSf ?: run.senderSf ?: "", run.total, run.received,
                    round1(run.pspPercent), run.meanRssi?.let(::round1) ?: "", run.meanSnr?.let(::round1) ?: "",
                    run.meanDistance?.let(::round1) ?: "", run.meanLatencyMs?.let(::round1) ?: "", GpxCodec.isoTime(run.startedAtMs),
                ).joinToString(","),
            )
        }
    }

    private fun round1(v: Double) = (kotlin.math.round(v * 10) / 10).toString()
    private fun csv(text: String) = if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
}
