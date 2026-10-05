package id.nusamesh.app.mesh.protocol

import id.nusamesh.app.domain.RouteKind
import id.nusamesh.app.domain.SharedRoute
import id.nusamesh.app.ui.nextRoutePointIndex
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTelemetryTest {
    private fun close(a: GeoPoint, b: GeoPoint) = abs(a.latitude - b.latitude) < 1e-5 && abs(a.longitude - b.longitude) < 1e-5

    /** Jalan kaki ±10 m per titik dengan sedikit belok, mulai di lereng Merapi. */
    private fun walk(count: Int, seed: Int = 1): List<GeoPoint> {
        val random = Random(seed)
        var lat = -7.5407
        var lon = 110.4457
        return List(count) {
            lat += 0.00009 + random.nextDouble(-0.00003, 0.00003)
            lon += random.nextDouble(-0.00006, 0.00006)
            GeoPoint(lat, lon)
        }
    }

    @Test
    fun polylineMatchesGoogleReference() {
        val points = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
        val encoded = RoutePolyline.encode(points)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encoded)
        assertEquals(points, RoutePolyline.decode(encoded))
    }

    @Test
    fun malformedPolylineIsRejected() {
        val encoded = RoutePolyline.encode(walk(5))
        assertNull(RoutePolyline.decode(encoded.dropLast(1)))
        assertNull(RoutePolyline.decode("abc def"))
    }

    @Test
    fun trackSegmentRoundTripsAndFitsOneLoraFrame() {
        val points = walk(15)
        val encoded = TrackSegmentTelemetry("k3x9a", 4, "Tim|Alfa", points).encode()
        // Satu potongan berkala (≤ 12–15 titik) harus muat satu frame LoRa (243 B data) bersama header pesan.
        assertTrue(encoded.encodeToByteArray().size < 160, "ukuran ${encoded.length}")
        val decoded = assertNotNull(TrackSegmentTelemetry.decode(encoded))
        assertEquals("k3x9a", decoded.trackId)
        assertEquals(4, decoded.seq)
        assertEquals("Tim/Alfa", decoded.name)
        assertTrue(decoded.points.zip(points).all { (a, b) -> close(a, b) })
    }

    @Test
    fun polylineContainingPipeStillDecodes() {
        // '|' (124) termasuk alfabet polyline: field terakhir harus diambil utuh.
        val points = (0 until 40).map { GeoPoint(-6.2 + it * 0.0011, 106.8 + it * 0.0011) }
        val route = RouteTelemetry("r1", RouteTelemetry.Kind.Plan, "Evakuasi", points)
        val encoded = route.encode()
        assertTrue(RoutePolyline.encode(points).contains('|'))
        assertEquals(points.size, assertNotNull(RouteTelemetry.decode(encoded)).points.size)
    }

    @Test
    fun waypointRoundTrip() {
        val wp = WaypointTelemetry("w1", WaypointType.Bahaya, GeoPoint(-0.00004, 109.33333), "Longsor\njalan|putus")
        val decoded = assertNotNull(WaypointTelemetry.decode(wp.encode()))
        assertEquals(WaypointType.Bahaya, decoded.type)
        assertEquals("Longsor jalan/putus", decoded.label)
        assertTrue(close(wp.point, decoded.point))
        assertNull(WaypointTelemetry.decode("@meshta-wp-v1|w1|bahaya|9100000|0|x"))
    }

    @Test
    fun simplifyDropsStraightPointsButKeepsCorners() {
        val straight = (0..20).map { GeoPoint(-7.0 + it * 0.0001, 110.0) }
        val corner = (1..20).map { GeoPoint(-7.0 + 20 * 0.0001, 110.0 + it * 0.0001) }
        val simplified = RoutePolyline.simplify(straight + corner, 4.0)
        assertEquals(3, simplified.size)
        assertEquals(straight.last(), simplified[1])
    }

    @Test
    fun liveSegmentsJoinWithoutDuplicates() {
        val points = walk(10)
        val route = SharedRoute(
            "t", "p", "A", "Jejak A", RouteKind.LiveTrack,
            mapOf(1 to points.subList(4, 10), 0 to points.subList(0, 5)), 0L,
        )
        assertEquals(points, route.points)
    }

    @Test
    fun followingRoutePointsAhead() {
        val route = (0..10).map { GeoPoint(-7.0 + it * 0.001, 110.0) } // titik tiap ±110 m
        assertEquals(0, nextRoutePointIndex(route, GeoPoint(-7.0005, 110.0)))
        // Sudah berdiri di titik 3 → arahan menunjuk titik 4, bukan titik yang sedang diinjak.
        assertEquals(4, nextRoutePointIndex(route, GeoPoint(-6.997, 110.00001)))
        assertEquals(10, nextRoutePointIndex(route, GeoPoint(-6.990, 110.0)))
    }

    @Test
    fun batteryRidesAlongLocationAndSosBackwardCompatible() {
        val withBattery = LocationTelemetry(-7.5, 110.4, 8f, 123L, batteryPercent = 37)
        assertEquals(withBattery, LocationTelemetry.decode(withBattery.encode()))
        // Format lama (5 field) tetap terbaca, baterai null.
        assertEquals(null, LocationTelemetry.decode("${LocationTelemetry.PREFIX}|-7.5|110.4|8.0|123")?.batteryPercent)
        val sos = EmergencyTelemetry(EmergencyTelemetry.Action.Alert, -7.5, 110.4, 6f, 9L, batteryPercent = 4)
        assertEquals(4, EmergencyTelemetry.decode(sos.encode())?.batteryPercent)
        assertEquals(null, EmergencyTelemetry.decode(sos.copy(batteryPercent = null).encode())?.batteryPercent)
    }

    @Test
    fun quickStatusIsReadableAndDecodable() {
        val text = QuickStatus.Medis.encode()
        assertEquals("#status:medis Butuh medis", text)
        assertEquals(QuickStatus.Medis, QuickStatus.decode(text))
        assertEquals("Status: Butuh medis", QuickStatus.display(text))
        assertNull(QuickStatus.decode("#status:entah apa"))
        assertEquals("halo", QuickStatus.display("halo"))
    }
}
