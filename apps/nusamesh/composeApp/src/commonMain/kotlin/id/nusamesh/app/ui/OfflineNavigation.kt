package id.nusamesh.app.ui

import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.domain.TrackedUser
import id.nusamesh.app.mesh.protocol.GeoPoint
import id.nusamesh.app.mesh.protocol.RoutePolyline
import kotlin.math.*

internal data class OfflineGuidance(
    val distanceMeters: Double,
    val distanceLabel: String,
    val bearing: Double,
    val cardinal: String,
    val turnInstruction: String,
)

internal fun guidance(from: TrackedUser, to: TrackedUser, heading: Float?): OfflineGuidance =
    guidance(GeoPoint(from.latitude, from.longitude), GeoPoint(to.latitude, to.longitude), heading)

internal fun guidance(from: GeoPoint, to: GeoPoint, heading: Float?): OfflineGuidance {
    val lat1 = from.latitude * PI / 180.0
    val lat2 = to.latitude * PI / 180.0
    val dLat = lat2 - lat1
    val dLon = (to.longitude - from.longitude) * PI / 180.0
    val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    val distance = 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val bearing = (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
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

/** Tujuan navigasi aktif: unit, titik penting, atau titik berikutnya pada rute yang diikuti. */
internal data class NavigationTarget(
    val title: String,
    val point: GeoPoint,
    /** Sisa panjang rute setelah [point] (hanya saat mengikuti rute). */
    val remainingAfterMeters: Double? = null,
)

internal fun navigationTarget(state: AppUiState): NavigationTarget? {
    val own = state.trackedUsers.firstOrNull { it.own } ?: return null
    val here = GeoPoint(own.latitude, own.longitude)
    state.selectedTargetPeerId?.let { id ->
        val unit = state.trackedUsers.firstOrNull { it.peerId == id } ?: return null
        return NavigationTarget("Menuju ${unit.name}", GeoPoint(unit.latitude, unit.longitude))
    }
    state.selectedWaypointId?.let { id ->
        val waypoint = state.waypoints.firstOrNull { it.id == id } ?: return null
        return NavigationTarget("Menuju ${waypoint.type.label}: ${waypoint.label}", waypoint.point)
    }
    state.followedRouteId?.let { id ->
        val route = state.routes.firstOrNull { it.id == id } ?: return null
        val points = route.points
        val index = nextRoutePointIndex(points, here) ?: return null
        val remaining = RoutePolyline.lengthMeters(points.subList(index, points.size))
        val title = if (index == points.lastIndex) "Ujung ${route.name}" else "Ikuti ${route.name} (titik ${index + 1}/${points.size})"
        return NavigationTarget(title, points[index], remaining)
    }
    return null
}

/**
 * Titik rute berikutnya dari posisi [here]: mulai dari verteks terdekat, lalu maju selama titik itu
 * sudah dalam jangkauan [reachMeters] supaya arahan selalu menunjuk ke depan.
 */
internal fun nextRoutePointIndex(points: List<GeoPoint>, here: GeoPoint, reachMeters: Double = 25.0): Int? {
    if (points.isEmpty()) return null
    var index = points.indices.minBy { RoutePolyline.distanceMeters(points[it], here) }
    while (index < points.lastIndex && RoutePolyline.distanceMeters(points[index], here) < reachMeters) index++
    return index
}
