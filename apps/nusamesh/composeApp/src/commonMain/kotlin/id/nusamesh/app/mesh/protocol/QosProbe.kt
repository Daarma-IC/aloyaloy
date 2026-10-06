package id.nusamesh.app.mesh.protocol

import kotlin.math.roundToLong

/**
 * Paket uji QoS bernomor: penerima menghitung Packet Success Probability = diterima / [total] dan
 * mencatat RSSI/SNR per paket. Posisi pengirim ikut dikirim supaya jarak dihitung dari titik saat paket
 * dipancarkan (bukan lokasi rutin yang bisa basi 30 detik).
 * Format: "@meshta-qos-v1|run|seq|total|sf|lat1e5|lon1e5|label" (label terakhir).
 */
data class QosProbe(
    val runId: String,
    val seq: Int,
    val total: Int,
    /** SF node pengirim saat memancar (null = tidak diketahui / lewat BLE saja). */
    val senderSf: Int?,
    val senderPoint: GeoPoint?,
    val label: String,
) {
    fun encode(): String = listOf(
        PREFIX, runId, seq, total, senderSf ?: "",
        senderPoint?.let { (it.latitude * 1e5).roundToLong() } ?: "",
        senderPoint?.let { (it.longitude * 1e5).roundToLong() } ?: "",
        telemetryText(label),
    ).joinToString("|")

    companion object {
        const val PREFIX = "@meshta-qos-v1"
        const val MAX_TOTAL = 1_000

        fun decode(content: String): QosProbe? {
            val f = content.split('|', limit = 8)
            if (f.size != 8 || f[0] != PREFIX || f[1].isBlank()) return null
            val total = f[3].toIntOrNull()?.takeIf { it in 1..MAX_TOTAL } ?: return null
            val seq = f[2].toIntOrNull()?.takeIf { it in 1..total } ?: return null
            val lat = f[5].toLongOrNull()
            val lon = f[6].toLongOrNull()
            val point = if (lat != null && lon != null) GeoPoint(lat / 1e5, lon / 1e5).takeIf { it.valid } else null
            return QosProbe(f[1], seq, total, f[4].toIntOrNull()?.takeIf { it in 7..12 }, point, f[7])
        }
    }
}
