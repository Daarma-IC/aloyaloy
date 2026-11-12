package id.nusamesh.app.data

import id.nusamesh.app.mesh.engine.*
import kotlinx.serialization.json.*

object DiagnosticRecords {
    private val fields = listOf("sequence", "session_id", "time_ms", "layer", "event", "level", "device_id", "message_id", "bytes", "duration_ms", "rssi_dbm", "snr_db", "sf", "detail")
    private fun values(e: DiagnosticEvent): List<Any?> = listOf(e.sequence, e.sessionId, e.timeMs,
        e.layer.name, e.name, e.level.name, e.deviceId, e.messageId, e.bytes, e.durationMs, e.rssiDbm, e.snrDb, e.sf, e.detail)

    fun encode(events: List<DiagnosticEvent>): String = buildJsonArray {
        events.takeLast(TransmissionDiagnostics.MAX_EVENTS).forEach { e -> add(buildJsonObject {
            fields.zip(values(e)).forEach { (key, value) -> put(key, when (value) {
                null -> JsonNull
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }) }
        }) }
    }.toString()

    fun decode(text: String?): List<DiagnosticEvent> {
        if (text == null || text.length > 2_000_000) return emptyList()
        return runCatching { Json.parseToJsonElement(text).jsonArray.takeLast(TransmissionDiagnostics.MAX_EVENTS).mapNotNull { row ->
            runCatching {
                val o = row.jsonObject
                fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull
                fun long(key: String) = o[key]?.jsonPrimitive?.longOrNull
                fun double(key: String) = o[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() }
                DiagnosticEvent(long("sequence") ?: 0, str("session_id")!!.take(64), long("time_ms")!!,
                    DiagnosticLayer.valueOf(str("layer")!!), str("event")!!.take(64), DiagnosticLevel.valueOf(str("level")!!),
                    str("device_id")?.take(128), str("message_id")?.take(128), long("bytes")?.toInt(), long("duration_ms"),
                    double("rssi_dbm"), double("snr_db"), long("sf")?.toInt(), str("detail").orEmpty().take(TransmissionDiagnostics.MAX_DETAIL))
            }.getOrNull()
        } }.getOrDefault(emptyList())
    }

    /**
     * Sel CSV: kosong untuk null, angka tanpa tanda kutip (desimal dibulatkan 2 angka), teks diberi
     * tanda kutip hanya bila perlu (berisi koma, kutip, baris baru, atau spasi di tepi).
     */
    private fun cell(value: Any?): String {
        if (value == null) return ""
        if (value is Double || value is Float) {
            val d = (value as Number).toDouble()
            return if (d.isFinite()) (kotlin.math.round(d * 100) / 100).toString() else ""
        }
        if (value is Number) return value.toString()
        var text = value.toString()
        // Device-supplied strings must not become spreadsheet formulas.
        val formula = text.trimStart().firstOrNull() in listOf('=', '+', '-', '@')
        if (formula) text = "'$text"
        val quote = formula || text != text.trim() || text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (quote) "\"${text.replace("\"", "\"\"")}\"" else text
    }

    /** Baris judul CSV log (tanpa BOM), dipakai juga oleh rekaman log ke file. */
    val csvHeader: String = (fields + listOf("local_time", "node_id", "node_name")).joinToString(",")

    /**
     * Kolom tambahan di akhir: jam perangkat dalam zona waktunya (mis. 2026-11-12T13:12:26.769+07:00),
     * lalu ID dan nama Nusa Node asal data (dari device_id: peerID node atau MAC BLE node).
     */
    fun csvRow(e: DiagnosticEvent): String {
        val node = NodeDirectory.resolve(e.deviceId)
        return (values(e) + listOf(LocalTime.iso(e.timeMs), node?.number, node?.name))
            .joinToString(",", transform = ::cell)
    }

    fun eventsCsv(events: List<DiagnosticEvent>): String = "\uFEFF" + csvHeader + "\r\n" +
        events.joinToString("\r\n", transform = ::csvRow)

    fun summaryCsv(s: DiagnosticSnapshot): String {
        val metrics = linkedMapOf<String, Any?>(
            "session_id" to s.sessionId, "started_at_ms" to s.startedAtMs, "elapsed_ms" to s.elapsedMs,
            "ble_tx_accepted_bytes" to s.txBytes, "ble_rx_bytes" to s.rxBytes,
            "ble_stack_acceptance_percent" to s.bleAcceptancePercent,
            "ble_accepted_bytes_per_session_second" to s.bleTxBytesPerSecond,
            "ble_duration_p50_ms_last_256" to s.percentile(s.bleDurations, .5),
            "ble_duration_p95_ms_last_256" to s.percentile(s.bleDurations, .95),
            "queue_wait_p95_ms_last_256" to s.percentile(s.queueDurations, .95),
            "first_receiver_ack_rtt_p50_ms_last_256" to s.percentile(s.ackDurations, .5),
            "first_receiver_ack_rtt_p95_ms_last_256" to s.percentile(s.ackDurations, .95),
            "messages_with_receiver_ack" to s.acknowledgedMessages, "tracked_without_ack" to s.trackedMessages,
            "tracking_evictions" to s.trackingEvictions, "trimmed_log_events" to s.trimmedEvents,
            "lora_tx_confirmation" to "unavailable: firmware has no per-packet TX completion telemetry",
            "packet_loss_percent" to "unavailable: no known expected receiver population for general traffic",
        )
        s.counts.forEach { (event, count) -> metrics["event_$event"] = count }
        s.devices.forEach { (id, d) ->
            metrics["device_${id}_ble_accepted_writes"] = d.acceptedWrites
            metrics["device_${id}_ble_failed_writes"] = d.failedWrites
            metrics["device_${id}_ble_tx_bytes"] = d.txBytes
            metrics["device_${id}_ble_rx_bytes"] = d.rxBytes
            metrics["device_${id}_ble_rssi_dbm"] = d.bleRssiDbm
            metrics["device_${id}_matched_lora_rssi_dbm"] = d.loraRssiDbm
            metrics["device_${id}_matched_lora_snr_db"] = d.loraSnrDb
            metrics["device_${id}_last_event_at_ms"] = d.lastEventAtMs
        }
        return "\uFEFFmetric,value\r\n" + metrics.entries.joinToString("\r\n") { cell(it.key) + "," + cell(it.value) }
    }
}
