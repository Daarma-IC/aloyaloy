package id.nusamesh.app.mesh.protocol

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val latitude: Double, val longitude: Double) {
    val valid get() = latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/**
 * Encoded polyline (algoritma Google/OSRM, presisi 1e-5 ≈ 1 m): tiap titik hanya menyimpan selisih
 * dari titik sebelumnya, jadi jejak jalan kaki ±3–4 karakter per titik — muat satu frame LoRa.
 */
object RoutePolyline {
    private const val SCALE = 1e5

    fun encode(points: List<GeoPoint>): String = buildString {
        var previousLat = 0L
        var previousLon = 0L
        for (point in points) {
            val lat = (point.latitude * SCALE).roundToLong()
            val lon = (point.longitude * SCALE).roundToLong()
            appendValue(lat - previousLat)
            appendValue(lon - previousLon)
            previousLat = lat
            previousLon = lon
        }
    }

    private fun StringBuilder.appendValue(value: Long) {
        var bits = if (value < 0) (value shl 1).inv() else value shl 1
        while (bits >= 0x20) {
            append(((0x20L or (bits and 0x1f)) + 63).toInt().toChar())
            bits = bits shr 5
        }
        append((bits + 63).toInt().toChar())
    }

    /** null bila string rusak (terpotong di tengah angka, karakter di luar alfabet, koordinat mustahil). */
    fun decode(encoded: String): List<GeoPoint>? {
        val points = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long? {
            var result = 0L
            var shift = 0
            while (true) {
                if (index >= encoded.length || shift > 60) return null
                val chunk = encoded[index++].code - 63
                if (chunk !in 0..63) return null
                result = result or ((chunk and 0x1f).toLong() shl shift)
                shift += 5
                if (chunk < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < encoded.length) {
            lat += next() ?: return null
            lon += next() ?: return null
            val point = GeoPoint(lat / SCALE, lon / SCALE)
            if (!point.valid) return null
            points += point
        }
        return points
    }

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.latitude * PI / 180
        val lat2 = b.latitude * PI / 180
        val dLat = lat2 - lat1
        val dLon = (b.longitude - a.longitude) * PI / 180
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    fun lengthMeters(points: List<GeoPoint>) = points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    /** Douglas-Peucker: buang titik yang menyimpang < [toleranceMeters] dari garis lurus tetangganya. */
    fun simplify(points: List<GeoPoint>, toleranceMeters: Double): List<GeoPoint> {
        if (points.size <= 2) return points
        val originLat = points[0].latitude * PI / 180
        val xs = DoubleArray(points.size) { points[it].longitude * 111_320.0 * cos(originLat) }
        val ys = DoubleArray(points.size) { points[it].latitude * 110_540.0 }
        val keep = BooleanArray(points.size).also { it[0] = true; it[points.lastIndex] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.lastIndex) }
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var farthest = -1
            var maxDistance = toleranceMeters
            val dx = xs[last] - xs[first]
            val dy = ys[last] - ys[first]
            val length = sqrt(dx * dx + dy * dy)
            for (i in first + 1 until last) {
                val distance = if (length == 0.0) {
                    sqrt((xs[i] - xs[first]).pow(2) + (ys[i] - ys[first]).pow(2))
                } else {
                    abs(dy * xs[i] - dx * ys[i] + xs[last] * ys[first] - ys[last] * xs[first]) / length
                }
                if (distance > maxDistance) { maxDistance = distance; farthest = i }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                stack.add(first to farthest)
                stack.add(farthest to last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}

/** Teks bebas di dalam telemetri: tanpa pemisah field/baris dan dibatasi supaya paket tetap kecil. */
internal fun telemetryText(value: String, max: Int = 40) =
    value.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim().take(max)

/**
 * Potongan jejak GPS yang sedang direkam (tim perintis). Dikirim berkala selama perekaman; titik pertama
 * tiap potongan = titik terakhir potongan sebelumnya supaya garis tersambung di penerima.
 */
data class TrackSegmentTelemetry(val trackId: String, val seq: Int, val name: String, val points: List<GeoPoint>) {
    fun encode(): String = "$PREFIX|$trackId|$seq|${telemetryText(name)}|${RoutePolyline.encode(points)}"

    companion object {
        const val PREFIX = "@meshta-trk-v1"

        fun decode(value: String): TrackSegmentTelemetry? {
            // Polyline boleh berisi '|', jadi field terakhir diambil utuh.
            val fields = value.split('|', limit = 5)
            if (fields.size != 5 || fields[0] != PREFIX || fields[1].isBlank()) return null
            val seq = fields[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val points = RoutePolyline.decode(fields[4])?.takeIf { it.isNotEmpty() } ?: return null
            return TrackSegmentTelemetry(fields[1], seq, fields[3], points)
        }
    }
}

/**
 * Rute utuh: rencana yang digambar di peta ([Kind.Plan]) atau ringkasan jejak yang sudah selesai direkam
 * ([Kind.Track], id sama dengan jejaknya sehingga menggantikan potongan-potongan yang mungkin hilang).
 */
data class RouteTelemetry(val routeId: String, val kind: Kind, val name: String, val points: List<GeoPoint>) {
    enum class Kind(val code: String) { Track("t"), Plan("p") }

    fun encode(): String = "$PREFIX|$routeId|${kind.code}|${telemetryText(name)}|${RoutePolyline.encode(points)}"

    companion object {
        const val PREFIX = "@meshta-route-v1"

        fun decode(value: String): RouteTelemetry? {
            val fields = value.split('|', limit = 5)
            if (fields.size != 5 || fields[0] != PREFIX || fields[1].isBlank()) return null
            val kind = Kind.entries.firstOrNull { it.code == fields[2] } ?: return null
            val points = RoutePolyline.decode(fields[4])?.takeIf { it.size >= 2 } ?: return null
            return RouteTelemetry(fields[1], kind, fields[3], points)
        }
    }
}

enum class WaypointType(val code: String, val label: String) {
    Posko("posko", "Posko"),
    Korban("korban", "Korban"),
    Bahaya("bahaya", "Bahaya"),
    Helipad("heli", "Helipad"),
    Air("air", "Air bersih"),
    Lainnya("lain", "Titik"),
}

/** Titik penting di lapangan. Koordinat dikirim sebagai bilangan bulat 1e-5 derajat (±1 m). */
data class WaypointTelemetry(
    val waypointId: String,
    val type: WaypointType,
    val point: GeoPoint,
    val label: String,
) {
    fun encode(): String = listOf(
        PREFIX, waypointId, type.code,
        (point.latitude * 1e5).roundToLong(), (point.longitude * 1e5).roundToLong(), telemetryText(label, MAX_LABEL),
    ).joinToString("|")

    companion object {
        const val PREFIX = "@meshta-wp-v1"
        /** Cukup untuk detail korban + keterangan singkat, tetap satu frame LoRa. */
        const val MAX_LABEL = 100

        fun decode(value: String): WaypointTelemetry? {
            val fields = value.split('|', limit = 6)
            if (fields.size != 6 || fields[0] != PREFIX || fields[1].isBlank()) return null
            val type = WaypointType.entries.firstOrNull { it.code == fields[2] } ?: WaypointType.Lainnya
            val lat = fields[3].toLongOrNull() ?: return null
            val lon = fields[4].toLongOrNull() ?: return null
            val point = GeoPoint(lat / 1e5, lon / 1e5).takeIf { it.valid } ?: return null
            return WaypointTelemetry(fields[1], type, point, fields[5])
        }
    }
}
