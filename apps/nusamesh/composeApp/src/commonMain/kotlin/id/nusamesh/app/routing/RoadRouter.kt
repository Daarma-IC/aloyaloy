package id.nusamesh.app.routing

import id.nusamesh.app.mesh.protocol.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Urutan HARUS sama dengan CLASSES di tools/offline-map/build_graph.py. */
enum class RoadClass {
    Motorway, Trunk, Primary, Secondary, Tertiary, Unclassified, Residential,
    LivingStreet, Service, Track, Path, Footway, Pedestrian, Steps, Cycleway, Bridleway,
}

enum class TravelMode(val label: String) {
    /** Tim SAR: boleh jalan setapak, track, tangga; arah satu jalur tidak berlaku. */
    Foot("Jalan kaki"),
    /** Mobil/motor: patuhi satu arah, tidak lewat jalan setapak. */
    Vehicle("Kendaraan"),
}

data class RoadRoute(
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val mode: TravelMode,
    /** Jarak dari titik asal/tujuan ke jalan terdekat (bagian ini tidak lewat jalan). */
    val offRoadMeters: Double,
)

/**
 * Graf jalan offline (format "NMRG" v1, lihat build_graph.py) + pencarian rute A*. Murni Kotlin supaya
 * jalan di semua platform dan bisa diuji. Biaya = waktu tempuh (panjang ÷ kecepatan per kelas & mode),
 * heuristik = jarak lurus ÷ kecepatan maksimum mode (tidak pernah melebihi biaya sebenarnya → rute optimal).
 */
class RoadGraph private constructor(
    private val nodeLat: IntArray,
    private val nodeLon: IntArray,
    private val segA: IntArray,
    private val segB: IntArray,
    private val segLenDm: IntArray,
    private val segClass: ByteArray,
    private val segFlag: ByteArray,
    private val shapeStart: IntArray,
    private val shapeCount: IntArray,
    private val shapeLat: IntArray,
    private val shapeLon: IntArray,
) {
    val nodeCount get() = nodeLat.size
    val segmentCount get() = segA.size

    // Adjacency CSR: tiap simpul → daftar (ruas*2 + arah); arah 0 = a→b, 1 = b→a.
    private val adjFirst = IntArray(nodeCount + 1)
    private val adjEdge = IntArray(segmentCount * 2)

    init {
        for (s in 0 until segmentCount) { adjFirst[segA[s] + 1]++; adjFirst[segB[s] + 1]++ }
        for (i in 0 until nodeCount) adjFirst[i + 1] += adjFirst[i]
        val fill = adjFirst.copyOf()
        for (s in 0 until segmentCount) {
            adjEdge[fill[segA[s]]++] = s * 2
            adjEdge[fill[segB[s]]++] = s * 2 + 1
        }
    }

    private fun point(i: Int) = GeoPoint(nodeLat[i] / 1e6, nodeLon[i] / 1e6)

    /** Detik untuk melewati ruas [s] arah [reverse], atau -1 bila terlarang bagi [mode]. */
    private fun cost(s: Int, reverse: Boolean, mode: TravelMode): Double {
        val flag = segFlag[s].toInt()
        val cls = CLASSES[segClass[s].toInt() and 0xFF]
        val meters = segLenDm[s] / 10.0
        return when (mode) {
            TravelMode.Foot -> if (flag and FLAG_NO_FOOT != 0) -1.0 else meters / footSpeed(cls)
            TravelMode.Vehicle -> when {
                flag and FLAG_NO_VEHICLE != 0 || cls in NON_VEHICLE -> -1.0
                reverse && flag and FLAG_ONEWAY != 0 -> -1.0
                else -> meters / vehicleSpeed(cls)
            }
        }
    }

    private fun usable(node: Int, mode: TravelMode): Boolean {
        for (k in adjFirst[node] until adjFirst[node + 1]) {
            val e = adjEdge[k]
            if (cost(e shr 1, e and 1 == 1, mode) >= 0) return true
        }
        return false
    }

    private val mainNetwork = HashMap<TravelMode, BooleanArray>()

    /**
     * Simpul yang bisa dicapai DAN bisa kembali ke jaringan jalan utama untuk [mode] (komponen terhubung kuat
     * dari ruas jalan besar). Titik asal/tujuan hanya ditempel ke sini supaya tidak nyangkut di potongan jalan
     * terputus (mis. jalan kompleks berakses privat) atau ruas satu arah yang buntu.
     */
    private fun mainNetwork(mode: TravelMode): BooleanArray = mainNetwork.getOrPut(mode) {
        val seed = (0 until segmentCount).firstOrNull { s ->
            CLASSES[segClass[s].toInt() and 0xFF] <= RoadClass.Secondary && cost(s, false, mode) >= 0 && cost(s, true, mode) >= 0
        }?.let { segA[it] } ?: (0 until nodeCount).firstOrNull { usable(it, mode) }
        val forward = BooleanArray(nodeCount)
        val backward = BooleanArray(nodeCount)
        if (seed != null) { reach(seed, mode, against = false, forward); reach(seed, mode, against = true, backward) }
        BooleanArray(nodeCount) { forward[it] && backward[it] }
    }

    /** Tandai semua simpul yang bisa dicapai dari [root] (atau, bila [against], yang bisa mencapai [root]). */
    private fun reach(root: Int, mode: TravelMode, against: Boolean, seen: BooleanArray) {
        val stack = IntArray(nodeCount)
        var top = 0
        stack[top++] = root
        seen[root] = true
        while (top > 0) {
            val n = stack[--top]
            for (k in adjFirst[n] until adjFirst[n + 1]) {
                val e = adjEdge[k]
                val s = e shr 1
                val reverse = e and 1 == 1
                // Ke depan: lewati ruas searah e; ke belakang: ruas harus bisa dilalui dari simpul tetangga ke n.
                if (cost(s, if (against) !reverse else reverse, mode) < 0) continue
                val next = if (reverse) segA[s] else segB[s]
                if (!seen[next]) { seen[next] = true; stack[top++] = next }
            }
        }
    }

    /** Simpul terdekat yang bisa dilalui [mode]; -1 bila tak ada dalam [maxMeters]. */
    fun nearestNode(p: GeoPoint, mode: TravelMode, maxMeters: Double = 2_000.0): Int {
        val lat = (p.latitude * 1e6).toInt()
        val lon = (p.longitude * 1e6).toInt()
        val kx = cos(p.latitude * PI / 180)
        val network = mainNetwork(mode)
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in 0 until nodeCount) {
            val dy = (nodeLat[i] - lat).toDouble()
            val dx = (nodeLon[i] - lon) * kx
            val d = dx * dx + dy * dy
            if (d < bestD && network[i]) { bestD = d; best = i }
        }
        if (best < 0) return -1
        return if (meters(p, point(best)) <= maxMeters) best else -1
    }

    /** Rute tercepat; null bila asal/tujuan jauh dari jalan atau tidak terhubung. */
    fun route(from: GeoPoint, to: GeoPoint, mode: TravelMode): RoadRoute? {
        val start = nearestNode(from, mode)
        val goal = nearestNode(to, mode)
        if (start < 0 || goal < 0) return null
        val maxSpeed = if (mode == TravelMode.Foot) FOOT_MAX_SPEED else VEHICLE_MAX_SPEED
        val goalPoint = point(goal)
        val g = DoubleArray(nodeCount) { Double.MAX_VALUE }
        val via = IntArray(nodeCount) { -1 }
        val closed = BooleanArray(nodeCount)
        val heap = MinHeap(1024)
        g[start] = 0.0
        heap.push(start, meters(point(start), goalPoint) / maxSpeed)
        while (heap.size > 0) {
            val n = heap.pop()
            if (closed[n]) continue
            if (n == goal) break
            closed[n] = true
            for (k in adjFirst[n] until adjFirst[n + 1]) {
                val e = adjEdge[k]
                val s = e shr 1
                val reverse = e and 1 == 1
                val c = cost(s, reverse, mode)
                if (c < 0) continue
                val next = if (reverse) segA[s] else segB[s]
                val ng = g[n] + c
                if (ng < g[next]) {
                    g[next] = ng
                    via[next] = e
                    heap.push(next, ng + meters(point(next), goalPoint) / maxSpeed)
                }
            }
        }
        if (g[goal] == Double.MAX_VALUE) return null

        val edges = ArrayList<Int>()
        var n = goal
        while (n != start) {
            val e = via[n]
            edges += e
            n = if (e and 1 == 1) segB[e shr 1] else segA[e shr 1]
        }
        edges.reverse()
        val points = ArrayList<GeoPoint>()
        points += from
        points += point(start)
        var distance = 0.0
        for (e in edges) {
            val s = e shr 1
            val shape = (0 until shapeCount[s]).map { GeoPoint(shapeLat[shapeStart[s] + it] / 1e6, shapeLon[shapeStart[s] + it] / 1e6) }
            if (e and 1 == 1) { points += shape.asReversed(); points += point(segA[s]) }
            else { points += shape; points += point(segB[s]) }
            distance += segLenDm[s] / 10.0
        }
        points += to
        val offRoad = meters(from, point(start)) + meters(to, goalPoint)
        val offRoadSeconds = offRoad / OFF_ROAD_SPEED
        return RoadRoute(points.distinctConsecutive(), distance + offRoad, g[goal] + offRoadSeconds, mode, offRoad)
    }

    private fun List<GeoPoint>.distinctConsecutive() = filterIndexed { i, p -> i == 0 || p != this[i - 1] }

    /** Heap biner berisi (simpul, prioritas); entri usang dilewati saat diambil (lazy deletion). */
    private class MinHeap(capacity: Int) {
        private var nodes = IntArray(capacity)
        private var keys = DoubleArray(capacity)
        var size = 0
            private set

        fun push(node: Int, key: Double) {
            if (size == nodes.size) { nodes = nodes.copyOf(size * 2); keys = keys.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) / 2
                if (keys[parent] <= key) break
                nodes[i] = nodes[parent]; keys[i] = keys[parent]; i = parent
            }
            nodes[i] = node; keys[i] = key
        }

        fun pop(): Int {
            val top = nodes[0]
            val lastNode = nodes[--size]
            val lastKey = keys[size]
            var i = 0
            while (true) {
                var child = i * 2 + 1
                if (child >= size) break
                if (child + 1 < size && keys[child + 1] < keys[child]) child++
                if (keys[child] >= lastKey) break
                nodes[i] = nodes[child]; keys[i] = keys[child]; i = child
            }
            nodes[i] = lastNode; keys[i] = lastKey
            return top
        }
    }

    companion object {
        private val CLASSES = RoadClass.entries
        const val FLAG_ONEWAY = 1
        const val FLAG_NO_FOOT = 2
        const val FLAG_NO_VEHICLE = 4
        private val NON_VEHICLE = setOf(RoadClass.Path, RoadClass.Footway, RoadClass.Pedestrian, RoadClass.Steps, RoadClass.Cycleway, RoadClass.Bridleway)

        // Kecepatan (m/s). Jalan kaki: medan kasar & tangga lebih lambat. Kendaraan: perkiraan kota Indonesia.
        private const val FOOT_MAX_SPEED = 5.0 / 3.6
        private const val VEHICLE_MAX_SPEED = 80.0 / 3.6
        private const val OFF_ROAD_SPEED = 2.5 / 3.6

        private fun footSpeed(c: RoadClass) = when (c) {
            RoadClass.Steps -> 2.0 / 3.6
            RoadClass.Track, RoadClass.Path, RoadClass.Bridleway -> 3.5 / 3.6
            else -> 4.5 / 3.6
        }

        private fun vehicleSpeed(c: RoadClass) = when (c) {
            RoadClass.Motorway -> 80.0
            RoadClass.Trunk -> 55.0
            RoadClass.Primary -> 45.0
            RoadClass.Secondary -> 38.0
            RoadClass.Tertiary -> 32.0
            RoadClass.Unclassified -> 28.0
            RoadClass.Residential -> 22.0
            RoadClass.LivingStreet, RoadClass.Service -> 15.0
            else -> 10.0
        } / 3.6

        fun meters(a: GeoPoint, b: GeoPoint): Double {
            val lat = (a.latitude + b.latitude) / 2 * PI / 180
            val dx = (b.longitude - a.longitude) * 111_320.0 * cos(lat)
            val dy = (b.latitude - a.latitude) * 110_540.0
            return sqrt(dx * dx + dy * dy)
        }

        /** Baca file graf "NMRG" v1 (little-endian). */
        fun parse(bytes: ByteArray): RoadGraph {
            require(bytes.size >= 36 && bytes.decodeToString(0, 4) == "NMRG" && bytes[4].toInt() == 1) { "Bukan graf jalan NusaMesh v1" }
            var pos = 8
            fun u32(): Int { val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or ((bytes[pos + 2].toInt() and 0xFF) shl 16) or (bytes[pos + 3].toInt() shl 24); pos += 4; return v }
            fun u16(): Int { val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }
            val nodes = u32(); val segs = u32(); val shapes = u32()
            pos += 16 // bbox
            fun ints(n: Int) = IntArray(n) { u32() }
            fun byteArr(n: Int) = ByteArray(n) { bytes[pos + it] }.also { pos += n }
            val nodeLat = ints(nodes); val nodeLon = ints(nodes)
            val segA = ints(segs); val segB = ints(segs); val segLen = ints(segs)
            val segClass = byteArr(segs); val segFlag = byteArr(segs)
            val shapeStart = ints(segs); val shapeCount = IntArray(segs) { u16() }
            val shapeLat = ints(shapes); val shapeLon = ints(shapes)
            require(pos == bytes.size) { "Ukuran graf tidak cocok" }
            return RoadGraph(nodeLat, nodeLon, segA, segB, segLen, segClass, segFlag, shapeStart, shapeCount, shapeLat, shapeLon)
        }
    }
}
