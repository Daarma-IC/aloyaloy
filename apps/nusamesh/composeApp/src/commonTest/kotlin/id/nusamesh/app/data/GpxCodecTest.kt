package id.nusamesh.app.data

import id.nusamesh.app.domain.MapWaypoint
import id.nusamesh.app.domain.RouteKind
import id.nusamesh.app.domain.SharedRoute
import id.nusamesh.app.domain.TrackedUser
import id.nusamesh.app.mesh.protocol.GeoPoint
import id.nusamesh.app.mesh.protocol.Triage
import id.nusamesh.app.mesh.protocol.VictimInfo
import id.nusamesh.app.mesh.protocol.WaypointType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxCodecTest {
    private val track = SharedRoute(
        "t1", "p1", "Ayu & <Tim>", "Jejak Ayu", RouteKind.Track,
        mapOf(0 to listOf(GeoPoint(-7.5, 110.4), GeoPoint(-7.501, 110.401)), 2 to listOf(GeoPoint(-7.503, 110.403), GeoPoint(-7.504, 110.404))),
        1L,
    )
    private val plan = SharedRoute("r1", "p1", "Ayu", "Evakuasi \"utara\"", RouteKind.Plan, mapOf(0 to listOf(GeoPoint(-7.4, 110.3), GeoPoint(-7.41, 110.31), GeoPoint(-7.42, 110.3))), 1L)
    private val korban = MapWaypoint(
        "k1", "p2", "Budi", WaypointType.Korban, GeoPoint(-7.52, 110.42), "kaki patah", 1_759_650_000_000L,
        victim = VictimInfo(3, Triage.Merah),
    )
    private val sos = TrackedUser("v1", "Sari", -7.53, 110.43, 15f, 1_759_650_000_000L, emergency = true, batteryPercent = 9)

    @Test
    fun exportParsesBackWithVictimDetails() {
        val gpx = GpxCodec.export(listOf(track, plan), listOf(korban), listOf(sos), 1_759_650_000_000L)
        println("GPX_EXPORT_BEGIN\n$gpx\nGPX_EXPORT_END") // divalidasi pustaka GPX lain di luar tes
        val imported = assertNotNull(GpxCodec.parse(gpx))
        assertEquals(listOf("Evakuasi \"utara\"", "Jejak Ayu"), imported.routes.map { it.first })
        assertEquals(4, imported.routes[1].second.size, "semua segmen jejak terbaca")
        val victim = imported.waypoints.first { it.type == WaypointType.Korban }
        assertEquals(VictimInfo(3, Triage.Merah), victim.victim)
        assertEquals("kaki patah", victim.label)
        assertTrue(imported.waypoints.any { it.label.contains("baterai 9%") }, "korban SOS ikut diekspor")
    }

    @Test
    fun parsesGpxFromOtherAppsLeniently() {
        val foreign = """
            <?xml version='1.0'?>
            <gpx xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3" version="1.0">
              <wpt lon='106.8272' lat='-6.1754'><name><![CDATA[Posko <Monas>]]></name><sym>Flag</sym></wpt>
              <rte><name>Jalur &amp; sungai</name>
                <rtept lon="106.80" lat="-6.20"></rtept><rtept lat="-6.21" lon="106.81"/>
              </rte>
              <trk><name>Trek</name><trkseg><trkpt lat="-6.3" lon="106.9"><ele>12</ele></trkpt><trkpt lat="-6.31" lon="106.91"/></trkseg></trk>
              <wpt lat="95" lon="10"><name>rusak</name></wpt>
            </gpx>
        """.trimIndent()
        val imported = assertNotNull(GpxCodec.parse(foreign))
        assertEquals(listOf("Jalur & sungai", "Trek"), imported.routes.map { it.first })
        assertEquals(1, imported.waypoints.size, "koordinat mustahil dibuang")
        assertEquals("Posko <Monas>", imported.waypoints.single().label)
        assertEquals(WaypointType.Lainnya, imported.waypoints.single().type)
        assertNull(GpxCodec.parse("<html>bukan gpx</html>"))
        assertNull(GpxCodec.parse("<gpx></gpx>"))
    }

    @Test
    fun isoTimeMatchesKnownInstants() {
        assertEquals("1970-01-01T00:00:00Z", GpxCodec.isoTime(0))
        assertEquals("2026-10-05T07:40:00Z", GpxCodec.isoTime(1_791_186_000_000L))
        assertEquals("2024-02-29T23:59:59Z", GpxCodec.isoTime(1_709_251_199_000L))
        assertEquals("1969-12-31T23:59:59Z", GpxCodec.isoTime(-1_000L))
    }
}
