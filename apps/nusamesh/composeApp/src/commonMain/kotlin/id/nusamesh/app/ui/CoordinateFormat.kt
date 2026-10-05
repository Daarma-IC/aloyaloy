package id.nusamesh.app.ui

import id.nusamesh.app.mesh.protocol.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Format koordinat untuk dibacakan lewat HT / dicocokkan dengan peta kertas. */
enum class CoordinateFormat(val label: String) { Decimal("Desimal"), Dms("DMS"), Utm("UTM") }

fun formatCoordinate(point: GeoPoint, format: CoordinateFormat): String = when (format) {
    CoordinateFormat.Decimal -> "${round5(point.latitude)}, ${round5(point.longitude)}"
    CoordinateFormat.Dms -> "${dms(point.latitude, 'U', 'S')} ${dms(point.longitude, 'T', 'B')}"
    CoordinateFormat.Utm -> utm(point)?.let { "${it.zone}${it.band} ${it.easting}mE ${it.northing}mN" } ?: "Di luar jangkauan UTM"
}

private fun round5(value: Double): String {
    val scaled = (abs(value) * 1e5).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return "$sign${scaled / 100_000}.${(scaled % 100_000).toString().padStart(5, '0')}"
}

/** 7°32'26.8"S — arah pakai singkatan Indonesia (U/S/T/B). */
private fun dms(value: Double, positive: Char, negative: Char): String {
    val tenths = (abs(value) * 36_000).roundToLong() // persepuluh detik, dibulatkan sekali supaya 59,95" jadi 1'
    val degrees = tenths / 36_000
    val minutes = (tenths % 36_000) / 600
    val seconds = tenths % 600
    return "$degrees°${minutes.toString().padStart(2, '0')}'${(seconds / 10).toString().padStart(2, '0')}.${seconds % 10}\"" +
        if (value < 0) negative else positive
}

data class UtmCoordinate(val zone: Int, val band: Char, val easting: Long, val northing: Long)

/**
 * WGS84 → UTM (deret Snyder/USGS, galat < 1 m di dalam zona). Pengecualian zona Norwegia/Svalbard tidak
 * ditangani — tidak relevan untuk Indonesia.
 */
fun utm(point: GeoPoint): UtmCoordinate? {
    if (point.latitude < -80.0 || point.latitude > 84.0 || !point.valid) return null
    val a = 6_378_137.0
    val f = 1 / 298.257223563
    val k0 = 0.9996
    val e2 = f * (2 - f)
    val ep2 = e2 / (1 - e2)
    val zone = (floor((point.longitude + 180) / 6).toInt() + 1).coerceAtMost(60)
    val lon0 = ((zone - 1) * 6 - 180 + 3) * PI / 180
    val phi = point.latitude * PI / 180
    val lambda = point.longitude * PI / 180

    val n = a / sqrt(1 - e2 * sin(phi) * sin(phi))
    val t = tan(phi) * tan(phi)
    val c = ep2 * cos(phi) * cos(phi)
    val aa = cos(phi) * (lambda - lon0)
    val e4 = e2 * e2
    val e6 = e4 * e2
    val m = a * (
        (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
            (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
            (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
            (35 * e6 / 3072) * sin(6 * phi)
        )
    val easting = k0 * n * (
        aa + (1 - t + c) * aa * aa * aa / 6 +
            (5 - 18 * t + t * t + 72 * c - 58 * ep2) * aa * aa * aa * aa * aa / 120
        ) + 500_000.0
    var northing = k0 * (
        m + n * tan(phi) * (
            aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * aa * aa * aa * aa / 24 +
                (61 - 58 * t + t * t + 600 * c - 330 * ep2) * aa * aa * aa * aa * aa * aa / 720
            )
        )
    if (point.latitude < 0) northing += 10_000_000.0
    val band = "CDEFGHJKLMNPQRSTUVWXX"[((point.latitude + 80) / 8).toInt().coerceIn(0, 20)]
    return UtmCoordinate(zone, band, floor(easting).toLong(), floor(northing).toLong())
}
