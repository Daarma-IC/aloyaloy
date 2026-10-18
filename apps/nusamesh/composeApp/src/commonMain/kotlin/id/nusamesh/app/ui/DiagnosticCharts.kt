package id.nusamesh.app.ui

import id.nusamesh.app.mesh.engine.DiagnosticLayer
import id.nusamesh.app.mesh.engine.DiagnosticSnapshot

internal data class ChartPoint(val timeMs: Long, val value: Double)
internal data class TransmissionCharts(
    val startMs: Long, val endMs: Long,
    val accepted: List<ChartPoint>, val failed: List<ChartPoint>,
    val txRate: List<ChartPoint>, val rxRate: List<ChartPoint>,
    val ackRtt: List<ChartPoint>, val queueWait: List<ChartPoint>,
    val bleRssi: List<ChartPoint>, val loraRssi: List<ChartPoint>, val loraSnr: List<ChartPoint>,
    val truncated: Boolean,
)

/** Only measured events in the current session. Missing history is never plotted as zero traffic. */
internal fun transmissionCharts(s: DiagnosticSnapshot, deviceId: String? = null): TransmissionCharts {
    val session = s.events.filter { it.sessionId == s.sessionId }
    val end = maxOf(s.startedAtMs + s.elapsedMs, session.maxOfOrNull { it.timeMs } ?: s.startedAtMs)
    val clearAt = session.lastOrNull { it.name == "LOG_CLEARED" }?.timeMs ?: s.startedAtMs
    val retainedAt = if (s.trimmedEvents > 0) session.minOfOrNull { it.timeMs } ?: end else s.startedAtMs
    val start = maxOf(end - 300_000, s.startedAtMs, clearAt, retainedAt)
    val events = session.filter { it.timeMs in start..end && (deviceId == null || it.deviceId == deviceId) }
    val interval = 10_000L
    val count = ((end - start).coerceAtLeast(0) / interval).toInt() + 1
    fun buckets(name: String, rate: Boolean = false): List<ChartPoint> {
        if (events.none { it.name == name }) return emptyList()
        val sums = DoubleArray(count)
        events.filter { it.name == name }.forEach { e ->
            val i = ((e.timeMs - start) / interval).toInt().coerceIn(sums.indices)
            sums[i] += if (rate) (e.bytes ?: 0).toDouble() else 1.0
        }
        return sums.mapIndexed { i, v ->
            val at = start + i * interval
            val seconds = (minOf(end, at + interval) - at).coerceAtLeast(1_000) / 1000.0
            ChartPoint(at, if (rate) v / seconds else v)
        }
    }
    fun series(name: String? = null, value: (id.nusamesh.app.mesh.engine.DiagnosticEvent) -> Double?): List<ChartPoint> =
        events.filter { name == null || it.name == name }.mapNotNull { e -> value(e)?.takeIf { it.isFinite() }?.let { ChartPoint(e.timeMs, it) } }.sortedBy { it.timeMs }
    return TransmissionCharts(start, end,
        buckets("TX_ACCEPTED"), buckets("TX_FAILED"), buckets("TX_ACCEPTED", true), buckets("RX_BYTES", true),
        series("ACK_MATCHED") { it.durationMs?.toDouble() }, series("QUEUE_DEQUEUED") { it.durationMs?.toDouble() },
        series { if (it.layer == DiagnosticLayer.BLE) it.rssiDbm else null },
        series("RX_META_MATCHED") { it.rssiDbm }, series("RX_META_MATCHED") { it.snrDb },
        s.trimmedEvents > 0 || clearAt > s.startedAtMs)
}
