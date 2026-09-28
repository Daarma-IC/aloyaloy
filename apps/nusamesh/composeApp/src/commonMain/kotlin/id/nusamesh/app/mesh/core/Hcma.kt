package id.nusamesh.app.mesh.core

/** Nusa Node dikenali dari peerID berawalan "NUSN" (4e55534e, lihat nusaNodePeerId() firmware) atau nickname. */
fun isNusaNodeId(peerId: String, nickname: String? = null): Boolean =
    peerId.startsWith("4e55534e", ignoreCase = true) || nickname?.startsWith("NusaNode-") == true

/** Nomor node (byte terakhir peerID NUSN…), dipakai di NODE_REGISTER dan tampilan "N2". */
fun nusaNodeNumber(peerId: String): Int? =
    if (peerId.length >= 16 && isNusaNodeId(peerId)) peerId.substring(14, 16).toIntOrNull(16) else null

/**
 * Jalur nebeng HCMA: HP ini tidak tersambung langsung ke Nusa Node (penuh 3/3 atau di luar jangkauan),
 * tetapi topologi mesh punya jalur Saya → perantara → … → Nusa Node.
 */
data class NebengRoute(val relayPeerId: String, val relayName: String, val nodeId: String, val nodeName: String)

object Hcma {
    /**
     * @param directNodes Nusa Node yang tersambung langsung ke HP ini (bila tidak kosong, bukan nebeng).
     * @param connectedPeers tetangga BLE yang sedang tersambung (perantara harus salah satunya).
     */
    fun findNebengRoute(
        myPeerId: String,
        directNodes: Collection<String>,
        connectedPeers: Collection<String>,
        topology: TopoSnapshot,
        nickname: (String) -> String? = { null },
    ): NebengRoute? {
        if (directNodes.isNotEmpty()) return null
        val connected = connectedPeers.toSet()
        return topology.nodes
            .filter { isNusaNodeId(it.peerId, it.nickname) }
            .mapNotNull { node -> RoutePlanner.bestRoute(myPeerId, node.peerId, topology)?.let { node to it } }
            .filter { (_, r) -> r.path.size > 2 && r.path[1] in connected }
            .maxByOrNull { (_, r) -> r.confidence }
            ?.let { (node, r) ->
                val relay = r.path[1]
                NebengRoute(
                    relayPeerId = relay,
                    relayName = nickname(relay) ?: topology.nodes.firstOrNull { it.peerId == relay }?.nickname ?: "${relay.take(6)}…",
                    nodeId = node.peerId,
                    nodeName = node.nickname ?: nickname(node.peerId) ?: "Nusa Node",
                )
            }
    }
}
