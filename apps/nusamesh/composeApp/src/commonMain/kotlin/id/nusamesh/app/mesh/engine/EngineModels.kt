package id.nusamesh.app.mesh.engine

import id.nusamesh.app.mesh.core.NebengRoute
import id.nusamesh.app.mesh.protocol.FilePacket
import id.nusamesh.app.mesh.protocol.MeshMessage

/** Peer mesh yang dikenal (langsung tersambung, atau terdengar lewat ANNOUNCE yang di-relay). */
data class PeerInfo(
    val peerId: String,
    val nickname: String?,
    val direct: Boolean,
    val rssi: Int?,
    val lastSeenMs: Long,
)

/** Nusa Node yang terdengar/tersambung (gateway LoRa). */
data class NodeInfo(
    val peerId: String,
    val name: String,
    val deviceId: String?,
    val connected: Boolean,
    val serving: Boolean,
    val rssi: Int?,
    val loraNeighbors: Int? = null,
    val loraBestRssi: Int? = null,
    val loraBestSnr: Float? = null,
    /** (HP tersambung langsung, batas slot) — firmware terbaru mengirimnya di LORA_HEALTH. */
    val userSlots: Pair<Int, Int>? = null,
    val registered: Boolean = false,
    val locked: Boolean = false,
)

data class IncomingMessage(
    val message: MeshMessage,
    val fromPeerId: String,
    /** true bila hop terakhir yang mengantar adalah Nusa Node (pesan datang lewat LoRa). */
    val viaNode: Boolean,
    val isPrivateToMe: Boolean,
)

/** Berkas (gambar/voice/file) utuh dari FILE_TRANSFER 0x22. */
data class IncomingFile(
    val file: FilePacket,
    val fromPeerId: String,
    val timestampMs: Long,
    val viaNode: Boolean,
    val isPrivateToMe: Boolean,
)

data class EngineSnapshot(
    val running: Boolean = false,
    val linkState: LinkState = LinkState.Unsupported,
    val peers: List<PeerInfo> = emptyList(),
    val nodes: List<NodeInfo> = emptyList(),
    val nebeng: NebengRoute? = null,
    val relayed: Long = 0,
    val received: Long = 0,
) {
    val servingNode: NodeInfo? get() = nodes.firstOrNull { it.serving && it.connected }
    /** Pesan akan sampai ke LoRa: lewat node langsung ATAU nebeng HP perantara. */
    val loraPathAvailable: Boolean get() = servingNode != null || nebeng != null
}

/** Sink log metrik mobility (CSV di platform). Default: diabaikan. */
fun interface MobilityLogSink {
    fun log(event: String, from: String?, to: String?, servingRssi: Int?, targetRssi: Int?, valueMs: Long?, detail: String)
}
