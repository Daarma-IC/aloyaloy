package id.nusamesh.app.routing

import id.nusamesh.app.mesh.protocol.GeoPoint
import id.nusamesh.app.mesh.protocol.RoutePolyline
import kotlinx.serialization.json.*
import kotlin.math.*

data class SarRequest(val id: String, val from: GeoPoint, val to: GeoPoint, val targetKey: String, val title: String)
data class SarSegment(val kind: String, val meters: Double, val seconds: Double, val name: String)
data class SarRoute(val request: SarRequest, val points: List<GeoPoint>, val elevations: List<Double>, val segments: List<SarSegment>, val meters: Double, val seconds: Double, val ascent: Double, val descent: Double, val packageVersion: String, val packageId: String = "kaliurang-real-terrain", val packageName: String = packageId, val resolutionM: Double = 35.0, val verticalDatum: String = "EGM2008")
data class SarNavigation(val request: SarRequest? = null, val route: SarRoute? = null, val status: String = "IDLE", val message: String? = null)
data class SarProgress(val index: Int, val remainingMeters: Double, val remainingSeconds: Double, val offRouteMeters: Double)

fun checkedSarNavigation(nav: SarNavigation, here: GeoPoint?, gpsAgeMs: Long, accuracyMeters: Double, destination: GeoPoint?): SarNavigation {
    val route = nav.route ?: return nav
    if (nav.status !in listOf("READY", "PAUSED", "ACTIVE")) return nav
    if (destination != null && RoutePolyline.distanceMeters(destination, route.request.to) > 10) return nav.copy(status = "STALE", message = "Koordinat tujuan berubah; hitung ulang rute")
    if (nav.status != "ACTIVE") return nav
    if (here == null || gpsAgeMs > 60000) return nav.copy(status = "PAUSED", message = "GPS belum diperbarui; navigasi dijeda")
    val progress = sarProgress(route, here)
    if (progress.offRouteMeters > maxOf(30.0, accuracyMeters * 2)) return nav.copy(status = "STALE", message = "Posisi keluar rute; hitung ulang dari posisi sekarang")
    if (progress.remainingMeters < 10 && RoutePolyline.distanceMeters(here, route.request.to) < 20) return nav.copy(status = "ARRIVED", message = "Mendekati koordinat; temuan korban belum dikonfirmasi")
    return nav
}

/** Project onto route segments; remaining ETA uses physical segment seconds. */
fun sarProgress(route: SarRoute, here: GeoPoint): SarProgress {
    val scale = PI / 180 * 6371000.0; val cos = cos(here.latitude * PI / 180)
    var best = Double.POSITIVE_INFINITY; var index = 0; var fraction = 0.0
    route.segments.indices.forEach { i ->
        val a = route.points[i]; val b = route.points[i + 1]
        val ax = (a.longitude - here.longitude) * scale * cos; val ay = (a.latitude - here.latitude) * scale
        val dx = (b.longitude - a.longitude) * scale * cos; val dy = (b.latitude - a.latitude) * scale; val l = dx * dx + dy * dy
        if (l > 1e-12) {
            val t = (-(ax * dx + ay * dy) / l).coerceIn(0.0, 1.0); val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best) { best = d; index = i; fraction = t }
        }
    }
    var meters = 0.0; var seconds = 0.0
    for (i in index until route.segments.size) { val f = if (i == index) 1 - fraction else 1.0; meters += route.segments[i].meters * f; seconds += route.segments[i].seconds * f }
    return SarProgress(index, meters, seconds, best)
}

object SarRouteCodec {
    fun save(text: String, request: SarRequest): String {
        val values = objectOf(text).toMutableMap()
        values["savedRequest"] = buildJsonObject {
            put("id", request.id); put("targetKey", request.targetKey); put("title", request.title)
            put("from", JsonArray(listOf(JsonPrimitive(request.from.latitude), JsonPrimitive(request.from.longitude))))
            put("to", JsonArray(listOf(JsonPrimitive(request.to.latitude), JsonPrimitive(request.to.longitude))))
        }
        return JsonObject(values).toString()
    }
    fun restore(text: String): SarRoute {
        val r = objectOf(text).getValue("savedRequest").jsonObject
        val request = SarRequest(r.getValue("id").jsonPrimitive.content, point(r.getValue("from")), point(r.getValue("to")), r.getValue("targetKey").jsonPrimitive.content, r.getValue("title").jsonPrimitive.content)
        return decode(text, request)
    }
    fun objectOf(text: String): JsonObject { require(text.length < 2_000_000); return Json.parseToJsonElement(text).jsonObject }
    fun point(element: JsonElement): GeoPoint {
        val a = element.jsonArray; require(a.size == 2)
        val lat = a[0].jsonPrimitive.double; val lon = a[1].jsonPrimitive.double
        require(lat.isFinite() && lon.isFinite() && abs(lat) <= 90 && abs(lon) <= 180)
        return GeoPoint(lat, lon)
    }
    fun decode(text: String, request: SarRequest): SarRoute {
        val o = objectOf(text); require(o["id"]?.jsonPrimitive?.content == request.id)
        val r = o.getValue("result").jsonObject; require(r["status"]?.jsonPrimitive?.content == "available" && r["engine"]?.jsonPrimitive?.content == "Hybrid")
        val points = r.getValue("geometry").jsonArray.map(::point); require(points.size in 2..20000)
        require(RoutePolyline.distanceMeters(points.first(), request.from) < .1 && RoutePolyline.distanceMeters(points.last(), request.to) < .1)
        val segments = r.getValue("segments").jsonArray.map { item ->
            val s = item.jsonObject; val kind = s.getValue("kind").jsonPrimitive.content
            val meters = s.getValue("distanceM").jsonPrimitive.double; val seconds = s.getValue("seconds").jsonPrimitive.double
            require(kind in listOf("osm", "terrain", "transition") && meters.isFinite() && seconds.isFinite() && meters >= 0 && seconds >= 0)
            SarSegment(kind, meters, seconds, s["name"]?.jsonPrimitive?.content ?: "Lintasan")
        }
        require(segments.size == points.size - 1)
        val elevations = r.getValue("elevationProfile").jsonArray.map { it.jsonObject.getValue("elevationM").jsonPrimitive.double }
        require(elevations.size == points.size && elevations.all { it.isFinite() })
        fun number(key: String) = r.getValue(key).jsonPrimitive.double.also { require(it.isFinite() && it >= 0) }
        val meters = number("length"); val seconds = number("durationEstimate")
        require(abs(segments.sumOf { it.meters } - meters) < .1 && abs(segments.sumOf { it.seconds } - seconds) < .1)
        val packageId = r.getValue("packageId").jsonPrimitive.content
        require(Regex("^(kaliurang-real-terrain|jabar-[a-z0-9-]+|indonesia-[a-f0-9]{16})$").matches(packageId))
        val resolution = r["packageResolutionM"]?.jsonPrimitive?.double ?: 35.0
        require(resolution.isFinite() && resolution in 1.0..1000.0)
        val datum = r["verticalDatum"]?.jsonPrimitive?.content ?: "EGM2008"
        require(datum.isNotBlank() && datum.length <= 80)
        val version = r.getValue("packageVersion").jsonPrimitive.content
        require(version.isNotBlank() && version.length <= 200)
        val name = r["packageName"]?.jsonPrimitive?.content ?: packageId
        require(name.length <= 200)
        return SarRoute(request, points, elevations, segments, meters, seconds, number("ascent"), number("descent"), version, packageId, name, resolution, datum)
    }
}
