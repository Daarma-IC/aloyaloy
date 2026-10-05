package id.nusamesh.app.data

import id.nusamesh.app.domain.MapWaypoint
import id.nusamesh.app.domain.RouteKind
import id.nusamesh.app.domain.SharedRoute
import id.nusamesh.app.domain.TrackedUser
import id.nusamesh.app.mesh.protocol.GeoPoint
import id.nusamesh.app.mesh.protocol.RoutePolyline
import id.nusamesh.app.mesh.protocol.WaypointType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapPersistenceTest {
    private fun line(n: Int, lat: Double) = (0 until n).map { GeoPoint(lat + it * 0.0011, 106.8 + it * 0.0011) }
    private fun same(a: List<GeoPoint>, b: List<GeoPoint>) =
        a.size == b.size && a.zip(b).all { (x, y) -> abs(x.latitude - y.latitude) < 1e-5 && abs(x.longitude - y.longitude) < 1e-5 }

    @Test
    fun routesRoundTripWithSegmentsAndPipesInPolyline() {
        val first = line(30, -6.2)
        val second = line(10, -6.2 + 29 * 0.0011)
        assertTrue(RoutePolyline.encode(first).contains('|'))
        val routes = listOf(
            SharedRoute("t1", "peerA", "Tim|Alfa", "Jejak Tim Alfa", RouteKind.LiveTrack, mapOf(0 to first, 3 to second), 1_700L),
            SharedRoute("r2", "me", "Saya", "Jalur evakuasi", RouteKind.Plan, mapOf(0 to line(4, -7.0)), 1_800L, own = true),
        )
        val restored = MapPersistence.decodeRoutes(MapPersistence.encodeRoutes(routes))
        assertEquals(2, restored.size)
        assertEquals("Tim/Alfa", restored[0].ownerName)
        assertEquals(setOf(0, 3), restored[0].segments.keys)
        assertTrue(same(first, restored[0].segments.getValue(0)))
        assertTrue(same(second, restored[0].segments.getValue(3)))
        assertEquals(RouteKind.Plan, restored[1].kind)
        assertTrue(restored[1].own)
    }

    @Test
    fun waypointsRoundTrip() {
        val wp = MapWaypoint("w1", "peerB", "Budi", WaypointType.Korban, GeoPoint(-0.00004, 109.33333), "2 orang, kaki patah", 99L)
        val restored = MapPersistence.decodeWaypoints(MapPersistence.encodeWaypoints(listOf(wp))).single()
        assertEquals(WaypointType.Korban, restored.type)
        assertEquals("2 orang, kaki patah", restored.label)
        assertEquals("Budi", restored.ownerName)
        assertTrue(abs(restored.point.latitude - wp.point.latitude) < 1e-5)
    }

    @Test
    fun corruptLinesAreSkippedNotFatal() {
        val good = MapPersistence.encodeRoutes(listOf(SharedRoute("ok", "p", "A", "A", RouteKind.Track, mapOf(0 to line(3, -6.0)), 1L)))
        val restored = MapPersistence.decodeRoutes("rusak|baris\n$good\nv1|x|p|A|A|Track|1|0|0:!!!")
        assertEquals(listOf("ok"), restored.map { it.id })
        assertEquals(0, MapPersistence.decodeWaypoints(null).size)
    }

    @Test
    fun sosVictimsRoundTrip() {
        val victim = TrackedUser("4f2a", "Sari|HP", -7.54123, 110.44571, 12.6f, 1_234L, rssi = -80, emergency = true)
        val restored = MapPersistence.decodeEmergencies(MapPersistence.encodeEmergencies(listOf(victim))).single()
        assertEquals("4f2a", restored.peerId)
        assertEquals("Sari/HP", restored.name)
        assertEquals(1_234L, restored.updatedAtMs)
        assertTrue(restored.emergency && !restored.own)
        assertTrue(abs(restored.latitude - victim.latitude) < 1e-5 && abs(restored.longitude - victim.longitude) < 1e-5)
    }

    @Test
    fun victimDetailsSurviveRestart() {
        val victim = id.nusamesh.app.mesh.protocol.VictimInfo(4, id.nusamesh.app.mesh.protocol.Triage.Kuning, evacuated = true)
        val wp = MapWaypoint("k1", "peerC", "Citra", WaypointType.Korban, GeoPoint(-7.5, 110.4), "di bawah jembatan", 5L, victim = victim)
        val restored = MapPersistence.decodeWaypoints(MapPersistence.encodeWaypoints(listOf(wp))).single()
        assertEquals(victim, restored.victim)
        assertEquals("di bawah jembatan", restored.label)
    }

    @Test
    fun verifiedFlagPersistsAndOldV1LinesStillLoad() {
        val wp = MapWaypoint("w9", "p", "Ayu", WaypointType.Bahaya, GeoPoint(-7.5, 110.4), "longsor", 1L, verified = true)
        assertTrue(MapPersistence.decodeWaypoints(MapPersistence.encodeWaypoints(listOf(wp))).single().verified)
        val route = SharedRoute("r9", "p", "Ayu", "Jalur", RouteKind.Plan, mapOf(0 to line(3, -6.0)), 1L, verified = true)
        assertTrue(MapPersistence.decodeRoutes(MapPersistence.encodeRoutes(listOf(route))).single().verified)
        // Baris format lama (sebelum ada kolom verified) tetap terbaca, dianggap belum terverifikasi.
        val oldWaypoint = MapPersistence.decodeWaypoints("v1|w1|bahaya|-750000|11040000|p|Ayu|1|0|jalan putus").single()
        assertEquals("jalan putus", oldWaypoint.label)
        assertTrue(!oldWaypoint.verified)
        val oldRoute = MapPersistence.decodeRoutes("v1|r1|p|Ayu|Jalur|Plan|1|1|0:${RoutePolyline.encode(line(3, -6.0))}").single()
        assertEquals(3, oldRoute.points.size)
    }
}
