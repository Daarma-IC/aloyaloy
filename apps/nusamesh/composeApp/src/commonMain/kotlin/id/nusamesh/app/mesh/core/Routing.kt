package id.nusamesh.app.mesh.core

import id.nusamesh.app.mesh.protocol.ByteReader
import id.nusamesh.app.mesh.protocol.ByteWriter
import id.nusamesh.app.mesh.protocol.SpecialRecipients
import id.nusamesh.app.mesh.protocol.WirePacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

data class Neighbor(val peerId: String, val quality: Double, val nickname: String? = null)

data class TopoNode(val peerId: String, val nickname: String?)
data class TopoLink(val a: String, val b: String, val quality: Double, val bidirectional: Boolean)
data class TopoSnapshot(val nodes: List<TopoNode> = emptyList(), val links: List<TopoLink> = emptyList())
data class Route(val path: List<String>, val cost: Double, val confidence: Double)

/**
 * Peta topologi multi-hop (NusaMeshTopology Nusa Mesh): siapa bertetangga dengan siapa dan seberapa andal.
 * Tautan dua arah dirata-rata; tautan satu arah dikenai penalti; semuanya meluruh menurut umur.
 */
class MeshTopology(private val now: () -> Long) {
    companion object {
        const val MAX_NEIGHBORS = 12
        const val ONE_WAY_PENALTY = 0.6
        const val LINK_TTL_MS = 5 * 60_000L
    }

    private val claims = HashMap<String, MutableMap<String, Double>>()
    private val nicknames = HashMap<String, String>()
    private val lastUpdate = HashMap<String, Long>()
    private val _snapshot = MutableStateFlow(TopoSnapshot())
    val snapshot: StateFlow<TopoSnapshot> = _snapshot.asStateFlow()

    fun observeDirectLink(selfId: String, neighborId: String, quality: Double) {
        if (selfId == neighborId) return
        claims.getOrPut(selfId) { HashMap() }[neighborId] = quality.coerceIn(0.0, 1.0)
        lastUpdate[selfId] = now()
        publish()
    }

    /** Klaim tetangga dari gossip peer lain; diabaikan bila tidak lebih baru dari klaim sebelumnya. */
    fun updateRemoteClaims(origin: String, originNickname: String?, neighbors: Map<String, Double>, timestampMs: Long) {
        if (originNickname != null) nicknames[origin] = originNickname
        val prev = lastUpdate[origin]
        if (prev != null && prev >= timestampMs) return
        lastUpdate[origin] = timestampMs
        claims[origin] = neighbors.filterKeys { it != origin }.entries.take(MAX_NEIGHBORS)
            .associate { it.key to it.value.coerceIn(0.0, 1.0) }.toMutableMap()
        publish()
    }

    fun setNickname(peerId: String, nickname: String?) {
        if (nickname.isNullOrEmpty() || nicknames[peerId] == nickname) return
        nicknames[peerId] = nickname
        publish()
    }

    fun removePeer(peerId: String) {
        claims.remove(peerId); nicknames.remove(peerId); lastUpdate.remove(peerId)
        claims.values.forEach { it.remove(peerId) }
        publish()
    }

    fun pruneStale() {
        val t = now()
        val expired = lastUpdate.filterValues { t - it > LINK_TTL_MS }.keys
        if (expired.isEmpty()) return
        expired.forEach { claims.remove(it); lastUpdate.remove(it) }
        publish()
    }

    private fun publish() {
        val t = now()
        val ids = HashSet<String>().apply {
            addAll(claims.keys); addAll(nicknames.keys); claims.values.forEach { addAll(it.keys) }
        }
        val done = HashSet<Pair<String, String>>()
        val links = ArrayList<TopoLink>()
        claims.forEach { (source, targets) ->
            targets.keys.forEach { target ->
                val pair = if (source <= target) source to target else target to source
                if (done.add(pair)) {
                    val (a, b) = pair
                    val qab = claims[a]?.get(b)
                    val qba = claims[b]?.get(a)
                    val bidir = qab != null && qba != null
                    val q = if (bidir) (qab!! + qba!!) / 2.0 else (qab ?: qba ?: 0.0) * ONE_WAY_PENALTY
                    val newest = maxOf(lastUpdate[a] ?: 0L, lastUpdate[b] ?: 0L)
                    val fresh = if (newest == 0L) 1.0 else (1.0 - (t - newest).coerceAtLeast(0).toDouble() / LINK_TTL_MS).coerceIn(0.2, 1.0)
                    links += TopoLink(a, b, (q * fresh).coerceIn(0.0, 1.0), bidir)
                }
            }
        }
        _snapshot.value = TopoSnapshot(
            ids.map { TopoNode(it, nicknames[it]) }.sortedBy { it.peerId },
            links.sortedWith(compareBy({ it.a }, { it.b })),
        )
    }
}

/** Jalur paling ANDAL (bukan hop tersedikit): biaya tautan = −ln(kualitas). */
object RoutePlanner {
    private const val MIN_QUALITY = 0.01

    fun bestRoute(src: String, dst: String, snap: TopoSnapshot): Route? {
        if (src == dst) return Route(listOf(src), 0.0, 1.0)
        val adj = HashMap<String, MutableList<Pair<String, Double>>>()
        snap.links.forEach { l ->
            val w = -ln(l.quality.coerceAtLeast(MIN_QUALITY))
            adj.getOrPut(l.a) { mutableListOf() } += l.b to w
            adj.getOrPut(l.b) { mutableListOf() } += l.a to w
        }
        if (src !in adj || dst !in adj) return null
        val dist = HashMap<String, Double>().apply { put(src, 0.0) }
        val prev = HashMap<String, String>()
        val visited = HashSet<String>()
        val frontier = ArrayList<Pair<String, Double>>().apply { add(src to 0.0) }
        while (frontier.isNotEmpty()) {
            val best = frontier.indices.minBy { frontier[it].second }
            val (u, d) = frontier.removeAt(best)
            if (!visited.add(u)) continue
            if (u == dst) break
            adj[u]?.forEach { (v, w) ->
                if (v !in visited && d + w < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = d + w; prev[v] = u; frontier += v to d + w
                }
            }
        }
        val total = dist[dst] ?: return null
        val path = ArrayDeque<String>()
        var cur: String? = dst
        while (cur != null) { path.addFirst(cur); cur = prev[cur] }
        return Route(path.toList(), total, exp(-total).coerceIn(0.0, 1.0))
    }

    fun nextHop(src: String, dst: String, snap: TopoSnapshot): String? = bestRoute(src, dst, snap)?.path?.getOrNull(1)
}

/**
 * Payload TOPOLOGY_GOSSIP (0x31) — sama dengan TopologyGossipCodec Nusa Mesh:
 * [versi=1][count] lalu count × ([idLen][peerId UTF-8][kualitas×255]).
 */
object TopologyGossipCodec {
    private const val VERSION = 1
    const val MAX_NEIGHBORS = 12
    private const val MAX_ID_LEN = 64

    fun encode(neighbors: List<Neighbor>): ByteArray {
        val valid = neighbors.take(MAX_NEIGHBORS)
        val w = ByteWriter().byte(VERSION).byte(valid.size)
        valid.forEach { n ->
            val id = n.peerId.encodeToByteArray()
            if (id.isEmpty() || id.size > MAX_ID_LEN) return@forEach
            w.byte(id.size).bytes(id).byte((n.quality.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255))
        }
        return w.toByteArray()
    }

    fun decode(payload: ByteArray): List<Neighbor> = try {
        val r = ByteReader(payload)
        if (payload.size < 2 || r.u8() != VERSION) emptyList() else {
            val count = r.u8()
            val out = ArrayList<Neighbor>(count)
            while (out.size < count && r.hasRemaining) {
                val len = r.u8()
                if (len == 0 || r.remaining < len + 1) break
                val id = r.utf8(len)
                out += Neighbor(id, r.u8() / 255.0)
            }
            out
        }
    } catch (e: IndexOutOfBoundsException) {
        emptyList()
    }
}

/** Keputusan relay untuk paket yang BUKAN untuk kita (PacketRelayManager Nusa Mesh). */
sealed interface RelayDecision {
    data object Drop : RelayDecision
    /** Unicast yang rutenya diketahui: cukup ke satu tetangga. */
    data class Directed(val nextHop: String, val packet: WirePacket) : RelayDecision
    data class Flood(val packet: WirePacket) : RelayDecision
}

class RelayPolicy(
    private val myPeerId: String,
    private val random: Random = Random.Default,
) {
    /**
     * @param fromPeer tetangga yang mengantar paket (bukan pengirim asli).
     * @param networkSize jumlah peer aktif untuk peluang relay adaptif.
     */
    fun decide(packet: WirePacket, fromPeer: String, networkSize: Int, topology: TopoSnapshot): RelayDecision {
        if (fromPeer == myPeerId || packet.ttl == 0) return RelayDecision.Drop
        if (!SpecialRecipients.isBroadcast(packet.recipientId) && packet.recipientHex == myPeerId) return RelayDecision.Drop
        val next = packet.withTtl(packet.ttl - 1)

        // Unicast dengan rute dikenal: deterministik, tidak boleh digugurkan peluang acak flood.
        if (!SpecialRecipients.isBroadcast(packet.recipientId)) {
            val hop = RoutePlanner.nextHop(myPeerId, packet.recipientHex!!, topology)
            if (hop != null && hop != myPeerId && hop != fromPeer) return RelayDecision.Directed(hop, next)
        }
        if (next.ttl >= 4 || networkSize <= 3) return RelayDecision.Flood(next)
        val p = when {
            networkSize <= 10 -> 1.0
            networkSize <= 30 -> 0.85
            networkSize <= 50 -> 0.7
            networkSize <= 100 -> 0.55
            else -> 0.4
        }
        return if (random.nextDouble() < p) RelayDecision.Flood(next) else RelayDecision.Drop
    }
}
