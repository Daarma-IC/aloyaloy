package id.nusamesh.app.data

import id.nusamesh.app.domain.MapWaypoint
import id.nusamesh.app.domain.RouteKind
import id.nusamesh.app.domain.SharedRoute
import id.nusamesh.app.domain.TrackedUser
import id.nusamesh.app.mesh.protocol.GeoPoint
import id.nusamesh.app.mesh.protocol.RoutePolyline
import id.nusamesh.app.mesh.protocol.VictimInfo
import id.nusamesh.app.mesh.protocol.WaypointTelemetry
import id.nusamesh.app.mesh.protocol.WaypointType
import id.nusamesh.app.mesh.protocol.telemetryText
import kotlin.math.roundToLong

/**
 * Simpan jalur & titik penting di [KeyValueStore] sebagai teks ringkas (satu baris per item, titik
 * sebagai encoded polyline) supaya tetap ada setelah aplikasi ditutup. Baris rusak dilewati, bukan
 * menggagalkan seluruh muatan.
 */
object MapPersistence {
    private const val VERSION = "v1"
    /** v2 = v1 + kolom "terverifikasi" (kunci operasi). Baris v1 tetap terbaca. */
    private const val VERSION_2 = "v2"

    // Rute: v2|id|ownerPeer|ownerName|name|kind|updatedAt|own|verified|seq:polyline seq:polyline …
    // Polyline bisa berisi '|' tapi tidak pernah spasi/':' (alfabet 63..126), jadi segmen terakhir & dipisah spasi.
    fun encodeRoutes(routes: List<SharedRoute>): String = routes.joinToString("\n") { route ->
        val segments = route.segments.entries.sortedBy { it.key }
            .joinToString(" ") { (seq, points) -> "$seq:${RoutePolyline.encode(points)}" }
        listOf(
            VERSION_2, route.id, route.ownerPeerId, telemetryText(route.ownerName), telemetryText(route.name),
            route.kind.name, route.updatedAtMs, if (route.own) "1" else "0", if (route.verified) "1" else "0", segments,
        ).joinToString("|")
    }

    fun decodeRoutes(value: String?): List<SharedRoute> = value.orEmpty().lineSequence().mapNotNull { line ->
        val v2 = line.startsWith("$VERSION_2|")
        val raw = line.split('|', limit = if (v2) 10 else 9)
        if (raw.size != (if (v2) 10 else 9) || raw[0] != (if (v2) VERSION_2 else VERSION) || raw[1].isBlank()) return@mapNotNull null
        val verified = v2 && raw[8] == "1"
        // Samakan ke tata letak v1 (tanpa kolom verified) supaya sisa parser sama.
        val f = if (v2) raw.subList(0, 8) + raw[9] else raw
        val kind = RouteKind.entries.firstOrNull { it.name == f[5] } ?: return@mapNotNull null
        val segments = f[8].split(' ').filter { it.isNotEmpty() }.associate { part ->
            val seq = part.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            seq to (RoutePolyline.decode(part.substringAfter(':')) ?: return@mapNotNull null)
        }
        if (segments.isEmpty()) return@mapNotNull null
        SharedRoute(
            id = f[1], ownerPeerId = f[2], ownerName = f[3], name = f[4], kind = kind, segments = segments,
            updatedAtMs = f[6].toLongOrNull() ?: 0L, own = f[7] == "1", verified = verified,
        )
    }.toList()

    // Titik: v2|id|type|lat1e5|lon1e5|ownerPeer|ownerName|createdAt|own|verified|label
    fun encodeWaypoints(waypoints: List<MapWaypoint>): String = waypoints.joinToString("\n") { wp ->
        listOf(
            VERSION_2, wp.id, wp.type.code, (wp.point.latitude * 1e5).roundToLong(), (wp.point.longitude * 1e5).roundToLong(),
            wp.ownerPeerId, telemetryText(wp.ownerName), wp.createdAtMs, if (wp.own) "1" else "0", if (wp.verified) "1" else "0",
            telemetryText(VictimInfo.compose(wp.victim, wp.label), WaypointTelemetry.MAX_LABEL),
        ).joinToString("|")
    }

    fun decodeWaypoints(value: String?): List<MapWaypoint> = value.orEmpty().lineSequence().mapNotNull { line ->
        val v2 = line.startsWith("$VERSION_2|")
        val raw = line.split('|', limit = if (v2) 11 else 10)
        if (raw.size != (if (v2) 11 else 10) || raw[0] != (if (v2) VERSION_2 else VERSION) || raw[1].isBlank()) return@mapNotNull null
        val verified = v2 && raw[9] == "1"
        val f = if (v2) raw.subList(0, 9) + raw[10] else raw
        val lat = f[3].toLongOrNull() ?: return@mapNotNull null
        val lon = f[4].toLongOrNull() ?: return@mapNotNull null
        val point = GeoPoint(lat / 1e5, lon / 1e5).takeIf { it.valid } ?: return@mapNotNull null
        val type = WaypointType.entries.firstOrNull { it.code == f[2] } ?: WaypointType.Lainnya
        val (victim, label) = if (type == WaypointType.Korban) VictimInfo.parse(f[9]) else null to f[9]
        MapWaypoint(
            id = f[1], ownerPeerId = f[5], ownerName = f[6], type = type,
            point = point, label = label, createdAtMs = f[7].toLongOrNull() ?: 0L, own = f[8] == "1", victim = victim,
            verified = verified,
        )
    }.toList()

    // Korban SOS (posisi terakhir): v1|peerId|lat1e5|lon1e5|accuracy|updatedAt|name
    fun encodeEmergencies(units: List<TrackedUser>): String = units.joinToString("\n") { unit ->
        listOf(
            VERSION, unit.peerId, (unit.latitude * 1e5).roundToLong(), (unit.longitude * 1e5).roundToLong(),
            unit.accuracyMeters.toInt(), unit.updatedAtMs, telemetryText(unit.name),
        ).joinToString("|")
    }

    fun decodeEmergencies(value: String?): List<TrackedUser> = value.orEmpty().lineSequence().mapNotNull { line ->
        val f = line.split('|', limit = 7)
        if (f.size != 7 || f[0] != VERSION || f[1].isBlank()) return@mapNotNull null
        val lat = f[2].toLongOrNull() ?: return@mapNotNull null
        val lon = f[3].toLongOrNull() ?: return@mapNotNull null
        val point = GeoPoint(lat / 1e5, lon / 1e5).takeIf { it.valid } ?: return@mapNotNull null
        TrackedUser(
            peerId = f[1], name = f[6], latitude = point.latitude, longitude = point.longitude,
            accuracyMeters = (f[4].toIntOrNull() ?: 0).toFloat(), updatedAtMs = f[5].toLongOrNull() ?: 0L,
            emergency = true,
        )
    }.toList()
}
