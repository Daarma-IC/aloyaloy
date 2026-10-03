package id.nusamesh.app.ui

import id.nusamesh.app.domain.TrackedUser
import kotlin.math.*

internal data class OfflineGuidance(
    val distanceMeters: Double,
    val distanceLabel: String,
    val bearing: Double,
    val cardinal: String,
    val turnInstruction: String,
)

internal fun guidance(from: TrackedUser, to: TrackedUser, heading: Float?): OfflineGuidance {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    val distance = 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    val cardinal = listOf("U", "TL", "T", "TG", "S", "BD", "B", "BL")[((bearing + 22.5) / 45.0).toInt() % 8]
    val instruction = if (heading == null) {
        "Arahkan kompas ke ${bearing.toInt()} deg ($cardinal)"
    } else {
        val delta = ((bearing - heading + 540.0) % 360.0) - 180.0
        when {
            abs(delta) <= 12 -> "Sudah menghadap tujuan - maju sesuai kondisi medan"
            delta > 0 -> "Putar kanan ${abs(delta).toInt()} deg"
            else -> "Putar kiri ${abs(delta).toInt()} deg"
        }
    }
    val label = if (distance < 1_000) "${distance.roundToInt()} m" else "${(distance / 100.0).roundToInt() / 10.0} km"
    return OfflineGuidance(distance, label, bearing, cardinal, instruction)
}
