package id.nusamesh.app.routing

import id.nusamesh.app.mesh.protocol.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadRouterTest {
    private class Seg(val a: Int, val b: Int, val cls: RoadClass, val flag: Int = 0, val shape: List<GeoPoint> = emptyList())

    /** Penulis format NMRG v1 — sama dengan tools/offline-map/build_graph.py. */
    private fun graph(nodes: List<GeoPoint>, segs: List<Seg>): RoadGraph {
        val out = ArrayList<Byte>()
        fun u32(v: Int) { for (i in 0 until 4) out += (v ushr (8 * i)).toByte() }
        fun u16(v: Int) { out += v.toByte(); out += (v ushr 8).toByte() }
        "NMRG".encodeToByteArray().forEach { out += it }; out += 1; out += 0; out += 0; out += 0
        val shapes = segs.flatMap { it.shape }
        u32(nodes.size); u32(segs.size); u32(shapes.size); repeat(4) { u32(0) }
        nodes.forEach { u32((it.latitude * 1e6).toInt()) }; nodes.forEach { u32((it.longitude * 1e6).toInt()) }
        segs.forEach { u32(it.a) }; segs.forEach { u32(it.b) }
        segs.forEach { s -> u32((RoadGraph.meters(nodes[s.a], nodes[s.b]) * 10).toInt().coerceAtLeast(1) + s.shape.size * 0) }
        segs.forEach { out += it.cls.ordinal.toByte() }; segs.forEach { out += it.flag.toByte() }
        var start = 0
        segs.forEach { u32(start); start += it.shape.size }; segs.forEach { u16(it.shape.size) }
        shapes.forEach { u32((it.latitude * 1e6).toInt()) }; shapes.forEach { u32((it.longitude * 1e6).toInt()) }
        return RoadGraph.parse(out.toByteArray())
    }

    // Persegi ±110 m: A(0) kiri-bawah, B(1) kiri-atas, C(2) kanan-atas, D(3) kanan-bawah.
    private val a = GeoPoint(-7.0, 110.0)
    private val b = GeoPoint(-6.999, 110.0)
    private val c = GeoPoint(-6.999, 110.001)
    private val d = GeoPoint(-7.0, 110.001)
    private val nodes = listOf(a, b, c, d)

    @Test
    fun footTakesFootpathShortcutVehicleCannot() {
        val g = graph(nodes, listOf(
            Seg(0, 1, RoadClass.Residential), Seg(1, 2, RoadClass.Residential),
            Seg(0, 3, RoadClass.Residential), Seg(3, 2, RoadClass.Residential),
            Seg(0, 2, RoadClass.Footway, RoadGraph.FLAG_NO_VEHICLE), // diagonal jalan setapak
        ))
        val foot = assertNotNull(g.route(a, c, TravelMode.Foot))
        assertEquals(listOf(a, c), foot.points, "pejalan kaki lewat jalan setapak diagonal")
        assertTrue(abs(foot.distanceMeters - RoadGraph.meters(a, c)) < 1)
        val car = assertNotNull(g.route(a, c, TravelMode.Vehicle))
        assertEquals(3, car.points.size, "kendaraan memutar lewat jalan biasa")
        assertTrue(car.distanceMeters > foot.distanceMeters * 1.3)
    }

    @Test
    fun vehicleRespectsOnewayFootIgnoresIt() {
        // A→B→C satu arah BERLAWANAN (hanya C→B→A boleh untuk kendaraan); A→D→C dua arah.
        val g = graph(nodes, listOf(
            Seg(1, 0, RoadClass.Primary, RoadGraph.FLAG_ONEWAY), Seg(2, 1, RoadClass.Primary, RoadGraph.FLAG_ONEWAY),
            Seg(0, 3, RoadClass.Residential), Seg(3, 2, RoadClass.Residential),
        ))
        assertEquals(listOf(a, d, c), assertNotNull(g.route(a, c, TravelMode.Vehicle)).points)
        assertEquals(listOf(c, b, a), assertNotNull(g.route(c, a, TravelMode.Vehicle)).points, "searah: jalan primer lebih cepat")
        val foot = assertNotNull(g.route(a, c, TravelMode.Foot))
        assertEquals(3, foot.points.size)
    }

    @Test
    fun motorwayClosedToPedestrians() {
        val g = graph(listOf(a, c), listOf(Seg(0, 1, RoadClass.Motorway, RoadGraph.FLAG_NO_FOOT or RoadGraph.FLAG_ONEWAY)))
        assertNull(g.route(a, c, TravelMode.Foot))
        assertNotNull(g.route(a, c, TravelMode.Vehicle))
    }

    @Test
    fun shapePointsFollowTravelDirection() {
        val bend = listOf(GeoPoint(-6.9995, 109.9995), GeoPoint(-6.9990, 109.9998))
        val g = graph(listOf(a, b), listOf(Seg(0, 1, RoadClass.Track, shape = bend)))
        assertEquals(listOf(a) + bend + listOf(b), assertNotNull(g.route(a, b, TravelMode.Foot)).points)
        assertEquals(listOf(b) + bend.reversed() + listOf(a), assertNotNull(g.route(b, a, TravelMode.Foot)).points)
    }

    @Test
    fun snapsOffRoadPointsAndRejectsFarOnes() {
        val g = graph(nodes, listOf(Seg(0, 1, RoadClass.Residential), Seg(1, 2, RoadClass.Residential)))
        val near = GeoPoint(-7.0002, 109.9998)                      // ±30 m dari A
        val route = assertNotNull(g.route(near, c, TravelMode.Foot))
        assertEquals(near, route.points.first()); assertEquals(a, route.points[1])
        assertTrue(route.offRoadMeters in 20.0..40.0)
        assertNull(g.route(GeoPoint(-7.1, 110.0), c, TravelMode.Foot), "11 km dari jalan mana pun")
        assertFailsWith<IllegalArgumentException> { RoadGraph.parse("bukan graf".encodeToByteArray()) }
    }
}
