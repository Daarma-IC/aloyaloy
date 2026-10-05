package id.nusamesh.app.data

import id.nusamesh.app.domain.MapWaypoint
import id.nusamesh.app.domain.RouteKind
import id.nusamesh.app.domain.SharedRoute
import id.nusamesh.app.domain.TrackedUser
import id.nusamesh.app.mesh.protocol.GeoPoint
import id.nusamesh.app.mesh.protocol.VictimInfo
import id.nusamesh.app.mesh.protocol.WaypointType

/**
 * GPX 1.1 untuk laporan pascaoperasi (ekspor) dan rute rencana dari posko (impor). Dibuka di Google
 * Earth, OsmAnd, Garmin Basecamp, QGIS, dll. Parser sengaja toleran (regex, bukan XML penuh): GPX dari
 * aplikasi lain sering beda urutan atribut, kutip, atau namespace.
 */
object GpxCodec {
    const val MIME = "application/gpx+xml"
    /** Batas file impor: GPX rute operasi jarang lebih dari ratusan KB. */
    const val MAX_IMPORT_CHARS = 5_000_000

    data class Imported(val routes: List<Pair<String, List<GeoPoint>>>, val waypoints: List<ImportedWaypoint>)
    data class ImportedWaypoint(val type: WaypointType, val point: GeoPoint, val label: String, val victim: VictimInfo?)

    fun export(routes: List<SharedRoute>, waypoints: List<MapWaypoint>, victims: List<TrackedUser>, nowMs: Long): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<gpx version="1.1" creator="NusaMesh" xmlns="http://www.topografix.com/GPX/1/1">""")
        appendLine("  <metadata><name>NusaMesh ${isoTime(nowMs)}</name><time>${isoTime(nowMs)}</time></metadata>")
        for (unit in victims) {
            append("""  <wpt lat="${unit.latitude}" lon="${unit.longitude}">""")
            append("<time>${isoTime(unit.updatedAtMs)}</time><name>${xml("SOS ${unit.name}")}</name>")
            append("<desc>${xml("Posisi terakhir korban SOS, akurasi ±${unit.accuracyMeters.toInt()} m" + (unit.batteryPercent?.let { ", baterai $it%" } ?: ""))}</desc>")
            appendLine("<type>sos</type></wpt>")
        }
        for (wp in waypoints) {
            append("""  <wpt lat="${wp.point.latitude}" lon="${wp.point.longitude}">""")
            append("<time>${isoTime(wp.createdAtMs)}</time><name>${xml(wp.type.label + (wp.label.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))}</name>")
            append("<desc>${xml(VictimInfo.compose(wp.victim, wp.label) + " (oleh ${wp.ownerName})")}</desc>")
            appendLine("<type>${wp.type.code}</type></wpt>")
        }
        for (route in routes.filter { it.kind == RouteKind.Plan }) {
            appendLine("  <rte><name>${xml(route.name)}</name><desc>${xml("Rencana oleh ${route.ownerName}")}</desc>")
            route.points.forEach { appendLine("""    <rtept lat="${it.latitude}" lon="${it.longitude}"/>""") }
            appendLine("  </rte>")
        }
        for (route in routes.filter { it.kind != RouteKind.Plan }) {
            appendLine("  <trk><name>${xml(route.name)}</name><desc>${xml("Jejak ${route.ownerName}")}</desc>")
            // Potongan yang hilang di jalan tetap jadi segmen terpisah, bukan garis lurus menyambung celah.
            route.segments.entries.sortedBy { it.key }.forEach { (_, points) ->
                appendLine("    <trkseg>")
                points.forEach { appendLine("""      <trkpt lat="${it.latitude}" lon="${it.longitude}"/>""") }
                appendLine("    </trkseg>")
            }
            appendLine("  </trk>")
        }
        appendLine("</gpx>")
    }

    private val pointTag = Regex("""<(wpt|rtept|trkpt)\b([^>]*?)(/>|>(.*?)</\1\s*>)""", RegexOption.DOT_MATCHES_ALL)
    private val block = Regex("""<(trk|rte)\b[^>]*>(.*?)</\1\s*>""", RegexOption.DOT_MATCHES_ALL)

    /** null bila bukan GPX / tidak berisi koordinat sama sekali. */
    fun parse(text: String): Imported? {
        if (text.length > MAX_IMPORT_CHARS || !text.contains("<gpx", ignoreCase = true)) return null
        val routes = block.findAll(text).mapNotNull { match ->
            val body = match.groupValues[2]
            // Nama blok: <name> yang muncul sebelum titik pertama.
            val head = body.substringBefore("<${if (match.groupValues[1] == "trk") "trkpt" else "rtept"}")
            val name = child(head, "name") ?: if (match.groupValues[1] == "trk") "Jejak impor" else "Rute impor"
            val points = pointTag.findAll(body).mapNotNull { point(it.groupValues[2]) }.toList()
            points.takeIf { it.size >= 2 }?.let { name to it }
        }.toList()
        val waypoints = pointTag.findAll(text).filter { it.groupValues[1] == "wpt" }.mapNotNull { match ->
            val point = point(match.groupValues[2]) ?: return@mapNotNull null
            val inner = match.groupValues[4]
            val type = WaypointType.entries.firstOrNull { it.code == child(inner, "type")?.lowercase() } ?: WaypointType.Lainnya
            val desc = child(inner, "desc")?.substringBeforeLast(" (oleh ")
            val raw = desc ?: child(inner, "name") ?: type.label
            val (victim, label) = if (type == WaypointType.Korban) VictimInfo.parse(raw) else null to raw
            ImportedWaypoint(type, point, label.take(60), victim)
        }.toList()
        if (routes.isEmpty() && waypoints.isEmpty()) return null
        return Imported(routes, waypoints)
    }

    private fun attr(attributes: String, name: String) =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""").find(attributes)?.groupValues?.get(1)?.trim()?.toDoubleOrNull()

    private fun point(attributes: String): GeoPoint? {
        val lat = attr(attributes, "lat") ?: return null
        val lon = attr(attributes, "lon") ?: return null
        return GeoPoint(lat, lon).takeIf { it.valid }
    }

    private fun child(body: String, tag: String): String? =
        Regex("""<$tag\b[^>]*>(.*?)</$tag\s*>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            ?.let(::unxml)?.trim()?.ifBlank { null }

    private fun xml(value: String) = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unxml(value: String) = value.removePrefix("<![CDATA[").removeSuffix("]]>")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Epoch ms → "2026-10-05T07:30:00Z" (algoritma days-from-civil Howard Hinnant, tanpa pustaka tanggal). */
    fun isoTime(epochMs: Long): String {
        val seconds = epochMs.floorDiv(1000L)
        val days = seconds.floorDiv(86_400L)
        val secOfDay = seconds - days * 86_400L
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "$year-${two(month)}-${two(day)}T${two(secOfDay / 3600)}:${two(secOfDay % 3600 / 60)}:${two(secOfDay % 60)}Z"
    }
}
