package id.nusamesh.app.routing

import id.nusamesh.app.mesh.protocol.GeoPoint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Uji router pada graf Bandung asli (dilewati bila belum dibangun dengan tools/offline-map/build_graph.py). */
class BandungGraphTest {
    private val file = File("offline-maps/routing/bandung.nmrg")

    @Test
    fun routesTelkomUniversityToAlunAlun() {
        if (!file.exists()) return println("Lewati: ${file.absolutePath} belum ada")
        val loadStart = System.nanoTime()
        val graph = RoadGraph.parse(file.readBytes())
        println("Graf: ${graph.nodeCount} simpul, ${graph.segmentCount} ruas, muat ${(System.nanoTime() - loadStart) / 1_000_000} ms")
        val telkom = GeoPoint(-6.9733, 107.6303)
        val alunAlun = GeoPoint(-6.9218, 107.6070)
        val straight = RoadGraph.meters(telkom, alunAlun)
        for (mode in TravelMode.entries) {
            val start = System.nanoTime()
            val route = assertNotNull(graph.route(telkom, alunAlun, mode), "rute $mode")
            println(
                "$mode: ${"%.1f".format(route.distanceMeters / 1000)} km (lurus ${"%.1f".format(straight / 1000)} km), " +
                    "${(route.durationSeconds / 60).toInt()} mnt, ${route.points.size} titik, luar jalan ${route.offRoadMeters.toInt()} m, " +
                    "${(System.nanoTime() - start) / 1_000_000} ms",
            )
            assertTrue(route.distanceMeters in straight..straight * 1.8, "jarak rute masuk akal")
            // Titik uji di dalam kampus (jalan internal berakses privat) → kendaraan ditempel ke jalan umum terdekat.
            assertTrue(route.offRoadMeters < 500, "luar jalan ${route.offRoadMeters} m")
            // Tidak ada lompatan lurus panjang: rute benar-benar mengikuti geometri jalan.
            val longestHop = route.points.zipWithNext { a, b -> RoadGraph.meters(a, b) }.max()
            assertTrue(longestHop < 1_500, "lompatan terpanjang $longestHop m")
        }
    }
}
