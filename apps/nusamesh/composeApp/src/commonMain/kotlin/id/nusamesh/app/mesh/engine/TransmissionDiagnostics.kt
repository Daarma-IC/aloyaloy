package id.nusamesh.app.mesh.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil
import kotlin.time.TimeSource

enum class DiagnosticLayer { SYSTEM, BLE, QUEUE, LORA, MESH, MESSAGE }
enum class DiagnosticLevel { INFO, WARN, ERROR }

data class DiagnosticEvent(
    val sequence: Long,
    val sessionId: String,
    val timeMs: Long,
    val layer: DiagnosticLayer,
    val name: String,
    val level: DiagnosticLevel = DiagnosticLevel.INFO,
    val deviceId: String? = null,
    val messageId: String? = null,
    val bytes: Int? = null,
    val durationMs: Long? = null,
    val rssiDbm: Double? = null,
    val snrDb: Double? = null,
    val sf: Int? = null,
    val detail: String = "",
)

data class DiagnosticSnapshot(
    val sessionId: String,
    val startedAtMs: Long,
    val elapsedMs: Long = 0,
    val events: List<DiagnosticEvent> = emptyList(),
    val counts: Map<String, Long> = emptyMap(),
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val bleDurations: List<Long> = emptyList(),
    val queueDurations: List<Long> = emptyList(),
    val ackDurations: List<Long> = emptyList(),
    val trackedMessages: Int = 0,
    val acknowledgedMessages: Long = 0,
    val trackingEvictions: Long = 0,
    val trimmedEvents: Long = 0,
    val persistenceAvailable: Boolean? = null,
    val devices: Map<String, DeviceTransmissionStats> = emptyMap(),
) {
    fun count(name: String) = counts[name] ?: 0L
    val bleAcceptancePercent: Double? get() {
        val completed = count("TX_ACCEPTED") + count("TX_FAILED")
        return if (completed == 0L) null else count("TX_ACCEPTED") * 100.0 / completed
    }
    /** Bytes accepted by the BLE stack per session second, NOT LoRa goodput. */
    val bleTxBytesPerSecond: Double? get() = if (elapsedMs > 0) txBytes * 1000.0 / elapsedMs else null
    fun percentile(values: List<Long>, percent: Double): Long? = values.sorted().takeIf { it.isNotEmpty() }
        ?.let { it[(ceil(percent * it.size).toInt() - 1).coerceIn(it.indices)] }
}

data class DeviceTransmissionStats(
    val acceptedWrites: Long = 0, val failedWrites: Long = 0,
    val txBytes: Long = 0, val rxBytes: Long = 0,
    val bleRssiDbm: Double? = null, val loraRssiDbm: Double? = null, val loraSnrDb: Double? = null,
    val lastEventAtMs: Long = 0,
)

/** Mutated on the engine's serial dispatcher. Payloads, chat text and keys never enter this log. */
class TransmissionDiagnostics(
    private val now: () -> Long,
    val sessionId: String = "${now()}",
    restored: List<DiagnosticEvent> = emptyList(),
    private val monotonicMs: () -> Long = monotonicClock(),
) {
    companion object {
        const val MAX_EVENTS = 1000
        const val MAX_SAMPLES = 256
        const val MAX_TRACKED = 2048
        private fun monotonicClock(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
    private data class Attempt(val at: Long, var acknowledged: Boolean = false)
    private val attempts = LinkedHashMap<String, Attempt>()
    private val start = monotonicMs()
    private var sequence = restored.maxOfOrNull { it.sequence } ?: 0L
    private val mutable = MutableStateFlow(DiagnosticSnapshot(sessionId, now(), events = restored.takeLast(MAX_EVENTS)))
    val snapshot = mutable.asStateFlow()
    fun elapsedMs() = (monotonicMs() - start).coerceAtLeast(0)
    fun tick() { mutable.value = mutable.value.copy(elapsedMs = elapsedMs()) }
    fun persistenceResult(success: Boolean) {
        val previous = mutable.value.persistenceAvailable
        mutable.value = mutable.value.copy(persistenceAvailable = success)
        if (!success && previous != false) record(DiagnosticLayer.SYSTEM, "LOG_STORAGE_FAILED", DiagnosticLevel.ERROR,
            detail = "Log remains in memory; export CSV before closing app")
        if (success && previous == false) record(DiagnosticLayer.SYSTEM, "LOG_STORAGE_RECOVERED")
    }
    fun clearLog() {
        mutable.value = mutable.value.copy(events = emptyList())
        record(DiagnosticLayer.SYSTEM, "LOG_CLEARED", detail = "Session counters retained")
    }

    fun messageSubmitted(id: String) {
        val retry = id in attempts
        if (!retry) {
            if (attempts.size >= MAX_TRACKED) {
                attempts.remove(attempts.keys.first())
                mutable.value = mutable.value.copy(trackingEvictions = mutable.value.trackingEvictions + 1)
            }
            attempts[id] = Attempt(monotonicMs())
        }
        record(DiagnosticLayer.MESSAGE, if (retry) "MESSAGE_RESUBMITTED" else "MESSAGE_SUBMITTED", messageId = id)
    }

    fun acknowledgement(id: String, sender: String) {
        val attempt = attempts[id]
        if (attempt == null) {
            record(DiagnosticLayer.MESSAGE, "ACK_UNMATCHED", deviceId = sender, messageId = id)
            return
        }
        if (attempt.acknowledged) {
            record(DiagnosticLayer.MESSAGE, "ACK_ADDITIONAL", deviceId = sender, messageId = id)
            return
        }
        attempt.acknowledged = true
        val duration = (monotonicMs() - attempt.at).coerceAtLeast(0)
        mutable.value = mutable.value.copy(acknowledgedMessages = mutable.value.acknowledgedMessages + 1)
        record(DiagnosticLayer.MESSAGE, "ACK_MATCHED", deviceId = sender, messageId = id, durationMs = duration,
            detail = "First receiver ACK since first submission; includes queueing and return path")
    }

    fun record(
        layer: DiagnosticLayer, name: String, level: DiagnosticLevel = DiagnosticLevel.INFO,
        deviceId: String? = null, messageId: String? = null, bytes: Int? = null,
        durationMs: Long? = null, rssiDbm: Double? = null, snrDb: Double? = null, sf: Int? = null,
        detail: String = "",
    ) {
        val event = DiagnosticEvent(++sequence, sessionId, now(), layer, name.take(64), level,
            deviceId?.take(128), messageId?.take(128), bytes, durationMs,
            rssiDbm?.takeIf { it.isFinite() }, snrDb?.takeIf { it.isFinite() }, sf, detail.take(256))
        val s = mutable.value
        var deviceStats = s.devices
        if (deviceId != null) {
            if (deviceId !in deviceStats && deviceStats.size >= 64) deviceStats = deviceStats - deviceStats.keys.first()
            val d = deviceStats[deviceId] ?: DeviceTransmissionStats()
            deviceStats = deviceStats + (deviceId to d.copy(
                acceptedWrites = d.acceptedWrites + if (name == "TX_ACCEPTED") 1 else 0,
                failedWrites = d.failedWrites + if (name == "TX_FAILED") 1 else 0,
                txBytes = d.txBytes + if (name == "TX_ACCEPTED") bytes?.toLong() ?: 0 else 0,
                rxBytes = d.rxBytes + if (name == "RX_BYTES") bytes?.toLong() ?: 0 else 0,
                bleRssiDbm = if (layer == DiagnosticLayer.BLE && event.rssiDbm != null) event.rssiDbm else d.bleRssiDbm,
                loraRssiDbm = if (name == "RX_META_MATCHED") event.rssiDbm else d.loraRssiDbm,
                loraSnrDb = if (name == "RX_META_MATCHED") event.snrDb else d.loraSnrDb,
                lastEventAtMs = event.timeMs,
            ))
        }
        fun samples(old: List<Long>, eventName: String) = if (name == eventName && durationMs != null)
            (old + durationMs).takeLast(MAX_SAMPLES) else old
        mutable.value = s.copy(
            elapsedMs = elapsedMs(), events = (s.events + event).takeLast(MAX_EVENTS),
            devices = deviceStats,
            counts = s.counts + (name to ((s.counts[name] ?: 0L) + 1)),
            txBytes = s.txBytes + if (name == "TX_ACCEPTED") bytes?.toLong() ?: 0 else 0,
            rxBytes = s.rxBytes + if (name == "RX_BYTES") bytes?.toLong() ?: 0 else 0,
            bleDurations = samples(s.bleDurations, "TX_ACCEPTED"),
            queueDurations = samples(s.queueDurations, "QUEUE_DEQUEUED"),
            ackDurations = samples(s.ackDurations, "ACK_MATCHED"),
            trackedMessages = attempts.count { !it.value.acknowledged },
            trimmedEvents = s.trimmedEvents + if (s.events.size >= MAX_EVENTS) 1 else 0,
        )
    }
}
