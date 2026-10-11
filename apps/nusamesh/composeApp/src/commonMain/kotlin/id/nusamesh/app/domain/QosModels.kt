package id.nusamesh.app.domain

import kotlin.math.roundToInt

/**
 * Data ukur satu pesan yang diterima (geser gelembung chat untuk melihatnya). Bagian LoRa hanya terisi
 * bila pesan datang lewat Nusa Node ber-firmware uji QoS.
 */
data class RxMeasurement(
    val receivedAtMs: Long,
    val path: DeliveryPath,
    val loraRssiDbm: Float? = null,
    val loraSnrDb: Float? = null,
    val spreadingFactor: Int? = null,
    val bandwidthKHz: Float? = null,
    val hopLeft: Int? = null,
    /** msgId dari node cocok dengan paket ini (bukti pasangan data ukur benar). */
    val metaMatched: Boolean? = null,
    val bleRssiDbm: Int? = null,
    /** Jarak pengirim ↔ penerima saat diterima; null bila salah satu posisi belum diketahui. */
    val distanceMeters: Double? = null,
    /** Umur posisi pengirim yang dipakai menghitung jarak. */
    val senderPositionAgeMs: Long? = null,
    /** Waktu terima − waktu kirim (jam HP berbeda bisa meleset). */
    val latencyMs: Long? = null,
)

data class QosSample(val seq: Int, val rx: RxMeasurement)

/** Satu sesi uji QoS dari seorang pengirim, dilihat dari sisi penerima. */
data class QosRun(
    val runId: String,
    val senderPeerId: String,
    val senderName: String,
    val label: String,
    val total: Int,
    val senderSf: Int?,
    val samples: List<QosSample> = emptyList(),
    val startedAtMs: Long,
) {
    val received: Int get() = samples.map { it.seq }.distinct().size
    /** Packet Success Probability (%) = paket unik diterima / paket dikirim. */
    val pspPercent: Double get() = received * 100.0 / total
    val highestSeq: Int get() = samples.maxOfOrNull { it.seq } ?: 0
    val meanRssi: Double? get() = samples.mapNotNull { it.rx.loraRssiDbm?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
    val meanSnr: Double? get() = samples.mapNotNull { it.rx.loraSnrDb?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
    val meanDistance: Double? get() = samples.mapNotNull { it.rx.distanceMeters }.takeIf { it.isNotEmpty() }?.average()
    val meanLatencyMs: Double? get() = samples.mapNotNull { it.rx.latencyMs?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
    /** SF yang benar-benar terukur di node penerima (lebih bisa dipercaya dari klaim pengirim). */
    val measuredSf: Int? get() = samples.mapNotNull { it.rx.spreadingFactor }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun summary(): String = buildString {
        append("$received/$total diterima (${(pspPercent * 10).roundToInt() / 10.0}%)")
        (measuredSf ?: senderSf)?.let { append(" · SF$it") }
        meanRssi?.let { append(" · RSSI ${(it * 10).roundToInt() / 10.0} dBm") }
        meanSnr?.let { append(" · SNR ${(it * 10).roundToInt() / 10.0} dB") }
        meanDistance?.let { append(" · ${it.roundToInt()} m") }
    }
}

/** Sesi uji yang sedang dikirim HP ini. */
data class QosSending(val runId: String, val label: String, val sent: Int, val total: Int, val intervalMs: Long)
