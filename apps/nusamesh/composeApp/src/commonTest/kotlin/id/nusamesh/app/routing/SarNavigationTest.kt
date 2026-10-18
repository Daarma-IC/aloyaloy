package id.nusamesh.app.routing

import id.nusamesh.app.mesh.protocol.GeoPoint
import kotlin.test.*

class SarNavigationTest {
    private val request = SarRequest("test-1", GeoPoint(0.0, 0.0), GeoPoint(0.0, .002), "unit:target", "Korban")
    private val json = """{"id":"test-1","result":{"status":"available","engine":"Hybrid","packageId":"kaliurang-real-terrain","packageVersion":"v1","geometry":[[0,0],[0,0.001],[0,0.002]],"segments":[{"kind":"osm","distanceM":111.195,"seconds":80,"name":"Jalan"},{"kind":"terrain","distanceM":111.195,"seconds":160,"name":"Medan"}],"elevationProfile":[{"elevationM":100},{"elevationM":110},{"elevationM":120}],"length":222.39,"durationEstimate":240,"ascent":20,"descent":0}}"""
    @Test fun decodesAndRestoresExactGeometryAndTime() {
        val route = SarRouteCodec.decode(json, request)
        assertEquals(3, route.points.size); assertEquals(240.0, route.seconds)
        assertEquals(route, SarRouteCodec.restore(SarRouteCodec.save(json, request)))
    }
    @Test fun segmentProjectionUsesPhysicalRemainingEta() {
        val route = SarRouteCodec.decode(json, request); val p = sarProgress(route, GeoPoint(0.0, .0005))
        assertEquals(0, p.index); assertEquals(200.0, p.remainingSeconds, .001)
        assertTrue(p.offRouteMeters < .01); assertEquals(166.7925, p.remainingMeters, .001)
    }
    @Test fun regionalPackagesKeepTheirIdentityAndElevationMetadata() {
        val regional = json.replace("kaliurang-real-terrain", "jabar-cibodas").replace("\"packageVersion\":\"v1\"", "\"packageVersion\":\"v2\",\"packageName\":\"Gede–Cibodas\",\"packageResolutionM\":40,\"verticalDatum\":\"EGM2008\"")
        val route = SarRouteCodec.decode(regional, request)
        assertEquals("jabar-cibodas", route.packageId)
        assertEquals("Gede–Cibodas", route.packageName)
        assertEquals(40.0, route.resolutionM)
        assertEquals(route, SarRouteCodec.restore(SarRouteCodec.save(regional, request)))
        assertFails { SarRouteCodec.decode(json.replace("kaliurang-real-terrain", "arbitrary"), request) }
        assertFails { SarRouteCodec.decode(regional.replace("\"packageResolutionM\":40", "\"packageResolutionM\":-1"), request) }
    }
    @Test fun mismatchedRequestAndUnrelatedEndpointAreRejected() {
        assertFails { SarRouteCodec.decode(json.replace("test-1", "old"), request) }
        assertFails { SarRouteCodec.decode(json, request.copy(to = GeoPoint(5.0, 6.0))) }
    }
    @Test fun invalidSegmentTotalsAndNonfiniteValuesAreRejected() {
        assertFails { SarRouteCodec.decode(json.replace("\"durationEstimate\":240", "\"durationEstimate\":10"), request) }
        assertFails { SarRouteCodec.decode(json.replace("\"seconds\":80", "\"seconds\":-1"), request) }
        assertFails { SarRouteCodec.decode(json.replace("[0,0.002]", "[0,999]"), request) }
    }
    @Test fun gpsLossPausesAndTargetMovementInvalidatesInsteadOfKeepingActiveGuidance() {
        val nav = SarNavigation(route = SarRouteCodec.decode(json, request), status = "ACTIVE")
        assertEquals("PAUSED", checkedSarNavigation(nav, request.from, 61000, 5.0, request.to).status)
        assertEquals("STALE", checkedSarNavigation(nav, request.from, 0, 5.0, GeoPoint(.001, .002)).status)
        assertEquals("STALE", checkedSarNavigation(nav, GeoPoint(.001, .001), 0, 5.0, request.to).status)
        assertEquals("ARRIVED", checkedSarNavigation(nav, request.to, 0, 5.0, request.to).status)
    }
}
