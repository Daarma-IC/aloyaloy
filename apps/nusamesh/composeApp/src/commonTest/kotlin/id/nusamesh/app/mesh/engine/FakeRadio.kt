package id.nusamesh.app.mesh.engine

import id.nusamesh.app.mesh.protocol.MessageType
import id.nusamesh.app.mesh.protocol.WirePacket
import id.nusamesh.app.mesh.protocol.WireProtocol
import id.nusamesh.app.mesh.protocol.peerIdBytes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Ruang BLE simulasi untuk tes engine: jangkauan diatur lewat RSSI antar-alamat (tak ada entri = di luar
 * jangkauan). Satu paket = satu "write/notify", sama seperti GATT sungguhan.
 */
class FakeRadio {
    interface Endpoint {
        val address: String
        val advertisedPeerId: String?
        val advertisedName: String?
        val scanning: Boolean
        fun discovered(other: Endpoint, rssi: Int)
        fun connected(other: String, asCentral: Boolean)
        fun disconnected(other: String)
        fun received(from: String, data: ByteArray)
        fun acceptsConnection(): Boolean = true
    }

    private val endpoints = LinkedHashMap<String, Endpoint>()
    private val rssi = HashMap<Set<String>, Int>()
    private val connections = HashSet<Set<String>>()

    fun add(e: Endpoint) { endpoints[e.address] = e }

    fun setRssi(a: String, b: String, value: Int?) {
        val k = setOf(a, b)
        if (value == null) {
            rssi.remove(k)
            if (connections.remove(k)) { endpoints[a]?.disconnected(b); endpoints[b]?.disconnected(a) }
        } else rssi[k] = value
    }

    fun isConnected(a: String, b: String) = setOf(a, b) in connections
    fun connectionsOf(a: String) = connections.filter { a in it }.map { (it - a).first() }

    /** Satu putaran scan: setiap pemindai mendengar semua yang beriklan dalam jangkauan. */
    fun scanTick() {
        for (s in endpoints.values.filter { it.scanning }) {
            for (o in endpoints.values) {
                if (o === s) continue
                val r = rssi[setOf(s.address, o.address)] ?: continue
                s.discovered(o, r)
            }
        }
    }

    fun connect(from: String, to: String): Boolean {
        val k = setOf(from, to)
        val target = endpoints[to] ?: return false
        if (k !in rssi || k in connections || !target.acceptsConnection()) return false
        connections += k
        endpoints[from]?.connected(to, asCentral = true)
        target.connected(from, asCentral = false)
        return true
    }

    fun disconnect(a: String, b: String) {
        if (connections.remove(setOf(a, b))) { endpoints[a]?.disconnected(b); endpoints[b]?.disconnected(a) }
    }

    fun send(from: String, to: String, data: ByteArray): Boolean {
        if (setOf(from, to) !in connections) return false
        endpoints[to]?.received(from, data)
        return true
    }
}

/** [BleLink] di atas [FakeRadio] untuk satu HP. */
class FakeLink(private val radio: FakeRadio, override val address: String) : BleLink, FakeRadio.Endpoint {
    override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.Ready)
    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 4096)
    override val events = _events.asSharedFlow()
    override var advertisedPeerId: String? = null
    override val advertisedName: String? = null
    override var scanning = false

    init { radio.add(this) }

    override fun start(localPeerId: ByteArray) {
        advertisedPeerId = localPeerId.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        scanning = true
    }
    override fun stop() { scanning = false; advertisedPeerId = null }
    override fun connect(deviceId: String) {
        if (!radio.connect(address, deviceId)) _events.tryEmit(LinkEvent.ConnectFailed(deviceId, "gagal"))
    }
    override fun disconnect(deviceId: String) = radio.disconnect(address, deviceId)
    override suspend fun send(deviceId: String, data: ByteArray) = radio.send(address, deviceId, data)

    override fun discovered(other: FakeRadio.Endpoint, rssi: Int) {
        _events.tryEmit(LinkEvent.Discovered(other.address, other.advertisedName, rssi, other.advertisedPeerId))
    }
    override fun connected(other: String, asCentral: Boolean) { _events.tryEmit(LinkEvent.Connected(other, asCentral, 247)) }
    override fun disconnected(other: String) { _events.tryEmit(LinkEvent.Disconnected(other)) }
    override fun received(from: String, data: ByteArray) { _events.tryEmit(LinkEvent.Received(from, data.copyOf())) }
}

/**
 * Nusa Node tiruan dengan perilaku firmware yang relevan: maks. 3 klien BLE, ANNOUNCE saat tersambung,
 * NODE_REGISTER → NODE_REGISTER_ACK, dedup, dan penerusan antar-klien + ke node lain lewat "LoRa".
 */
class FakeNode(
    private val radio: FakeRadio,
    override val address: String,
    number: Int,
    private val now: () -> Long,
) : FakeRadio.Endpoint {
    val peerId = "4e55534e000000" + number.toString(16).padStart(2, '0')
    override val advertisedPeerId: String = peerId
    override val advertisedName = "NusaNode-$number"
    override val scanning = false
    private val clients = LinkedHashSet<String>()
    private val seen = HashSet<String>()
    val loraPeers = mutableListOf<FakeNode>()
    val registrations = mutableListOf<Pair<String, Int>>()   // (peerId HP, prevNode)

    init { radio.add(this) }

    override fun acceptsConnection() = clients.size < 3
    override fun discovered(other: FakeRadio.Endpoint, rssi: Int) {}
    override fun connected(other: String, asCentral: Boolean) {
        clients += other
        val announce = WirePacket(type = MessageType.ANNOUNCE.value, senderId = peerIdBytes(peerId), timestamp = now(),
            payload = "$advertisedName~".encodeToByteArray(), ttl = 3)
        radio.send(address, other, WireProtocol.encode(announce))
    }
    override fun disconnected(other: String) { clients -= other }

    override fun received(from: String, data: ByteArray) {
        val p = WireProtocol.decode(data) ?: return
        if (p.type == MessageType.NODE_REGISTER.value) {
            val prev = p.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            registrations += p.senderHex to prev
            val ack = WirePacket(type = MessageType.NODE_REGISTER_ACK.value, senderId = peerIdBytes(peerId),
                recipientId = p.senderId, timestamp = now(), payload = byteArrayOf(1, 0, clients.size.toByte(), clients.size.toByte()), ttl = 1)
            radio.send(address, from, WireProtocol.encode(ack))
            return
        }
        forward(p, data, exceptClient = from)
    }

    private fun forward(p: WirePacket, data: ByteArray, exceptClient: String?) {
        val key = "${p.timestamp}-${p.senderHex}-${p.type}-${p.payload.contentHashCode()}"
        if (!seen.add(key)) return
        clients.filter { it != exceptClient }.forEach { radio.send(address, it, data) }
        loraPeers.forEach { it.forward(p, data, exceptClient = null) }
    }

    fun sendHealth(neighbors: Int = loraPeers.size) {
        val h = WirePacket(type = MessageType.NODE_LORA_HEALTH.value, senderId = peerIdBytes(peerId), timestamp = now(),
            payload = byteArrayOf(neighbors.toByte(), (-70).toByte(), 16, clients.size.toByte(), 3), ttl = 1)
        clients.forEach { radio.send(address, it, WireProtocol.encode(h)) }
    }
}
