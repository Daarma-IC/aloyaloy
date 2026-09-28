package id.nusamesh.app.mesh.engine

import id.nusamesh.app.mesh.core.Fragmenter
import id.nusamesh.app.mesh.core.GuardVerdict
import id.nusamesh.app.mesh.core.Hcma
import id.nusamesh.app.mesh.core.MeshTopology
import id.nusamesh.app.mesh.core.Neighbor
import id.nusamesh.app.mesh.core.NodeQueue
import id.nusamesh.app.mesh.core.PacketGuard
import id.nusamesh.app.mesh.core.RelayDecision
import id.nusamesh.app.mesh.core.RelayPolicy
import id.nusamesh.app.mesh.core.TopologyGossipCodec
import id.nusamesh.app.mesh.core.isNusaNodeId
import id.nusamesh.app.mesh.core.nusaNodeNumber
import id.nusamesh.app.mesh.mobility.HandoverDecider
import id.nusamesh.app.mesh.mobility.HandoverEvent
import id.nusamesh.app.mesh.mobility.MobilityConfig
import id.nusamesh.app.mesh.mobility.RssiSample
import id.nusamesh.app.mesh.protocol.FilePacket
import id.nusamesh.app.mesh.protocol.MeshMessage
import id.nusamesh.app.mesh.protocol.MessageType
import id.nusamesh.app.mesh.protocol.SpecialRecipients
import id.nusamesh.app.mesh.protocol.WirePacket
import id.nusamesh.app.mesh.protocol.WireProtocol
import id.nusamesh.app.mesh.protocol.peerIdBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Otak mesh NusaMesh di commonMain (setara BluetoothMeshService + PacketProcessor + PeerManager +
 * BluetoothConnectionManager Nusa Mesh Android, tanpa kode radio).
 *
 * Semua state diubah dari SATU jalur eksekusi: [scope] harus memakai dispatcher satu-jalur
 * (lihat [MeshEngine.create]) sehingga tidak butuh kunci dan berperilaku sama di Android dan iOS.
 */
class MeshEngine(
    private val link: BleLink,
    val myPeerId: String,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val random: Random = Random.Default,
    private val mobilityLog: MobilityLogSink = MobilityLogSink { _, _, _, _, _, _, _ -> },
) {
    companion object {
        const val ANNOUNCE_TTL = 3
        const val ANNOUNCE_PERIOD_MS = 30_000L
        const val GOSSIP_PERIOD_MS = 30_000L
        const val MAINTENANCE_PERIOD_MS = 5_000L
        const val PEER_STALE_MS = 180_000L
        const val MAX_PHONE_LINKS = 6
        const val CONNECT_RETRY_MS = 10_000L
        const val NODE_ELECTION_WINDOW_MS = 2_500L
        const val REGISTER_REFRESH_MS = 60_000L
        const val REGISTER_RETRY_MS = 5_000L
        const val PREV_NODE_MEMORY_MS = 600_000L
        const val FRAGMENT_GAP_MS = 60L
        /** Batas berkas yang boleh lewat LoRa (profil gambar LoRa ≤ 1,2 KB + header TLV). */
        const val LORA_FILE_MAX_BYTES = 2_048

        /** TTL awal pesan: sama dengan PacketRelayManager.getRecommendedTTL Nusa Mesh. */
        fun recommendedTtl(networkSize: Int) = if (networkSize > 20) 15 else 7
    }

    // ----------------------------------------------------------------------------------------------
    //  State internal (hanya disentuh dari jalur engine)
    // ----------------------------------------------------------------------------------------------
    private class Device(val id: String) {
        var peerId: String? = null
        var name: String? = null
        var scanRssi: Int? = null
        var scanAt: Long = 0
        var connected = false
        var connecting = false
        var mtu = 185
        var lastAttempt = Long.MIN_VALUE / 2
        var queue: NodeQueue? = null
        val isNode get() = peerId?.let { isNusaNodeId(it, name) } ?: (name?.startsWith("NusaNode-") == true)
    }

    private class PeerState(val id: String) {
        var nickname: String? = null
        var lastSeen = 0L
    }

    private class NodeHealth(
        var neighbors: Int? = null, var bestRssi: Int? = null, var bestSnr: Float? = null,
        var slots: Pair<Int, Int>? = null,
    )

    private val myIdBytes = peerIdBytes(myPeerId)
    private val devices = HashMap<String, Device>()
    private val peers = HashMap<String, PeerState>()
    private val nodeHealth = HashMap<String, NodeHealth>()
    private val guard = PacketGuard(myPeerId, now)
    private val fragmenter = Fragmenter(random, now)
    private val relay = RelayPolicy(myPeerId, random)
    val topology = MeshTopology(now)
    private val handover = HandoverDecider(now)

    var nickname: String = "NusaMesh"
        set(value) { field = value.take(40) }
    var mobilityConfig = MobilityConfig()

    // Pemilihan node: tepat SATU node pelayanan (klaim) supaya pesan tak dipancarkan ganda ke LoRa.
    private var servingNode: String? = null
    private var lockedNode: String? = null
    private var electionUntil: Long? = null
    private var pendingHandoverTo: String? = null
    private var pendingHandoverAt = 0L
    private var registeredNode: String? = null
    private var registeredAt = Long.MIN_VALUE / 2
    private var registerAcked: String? = null
    private var lastSuccessHandover: Triple<String, String, Long>? = null
    private var lastServingBeforeHandover: String? = null

    private var relayedCount = 0L
    private var receivedCount = 0L
    private var running = false
    private var dirty = false
    private val jobs = mutableListOf<Job>()

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()
    private val _messages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<IncomingMessage> = _messages.asSharedFlow()
    private val _files = MutableSharedFlow<IncomingFile>(extraBufferCapacity = 16)
    val files: SharedFlow<IncomingFile> = _files.asSharedFlow()
    /** Paket mentah yang ditujukan ke HP ini (untuk lapisan di atas engine: Noise, media, ACK). */
    private val _packets = MutableSharedFlow<Pair<WirePacket, String>>(extraBufferCapacity = 64)
    val packets: SharedFlow<Pair<WirePacket, String>> = _packets.asSharedFlow()

    // ----------------------------------------------------------------------------------------------
    //  Siklus hidup
    // ----------------------------------------------------------------------------------------------
    fun start() = scope.launch {
        if (running) return@launch
        running = true
        link.start(myIdBytes)
        jobs += scope.launch { link.events.collect { handleLinkEvent(it) } }
        jobs += scope.launch { link.state.collect { publish() } }
        jobs += periodic(ANNOUNCE_PERIOD_MS) { broadcastAnnounce() }
        jobs += periodic(GOSSIP_PERIOD_MS) { broadcastGossip() }
        jobs += periodic(MAINTENANCE_PERIOD_MS) { maintenance() }
        jobs += periodic(500L) { if (dirty) publish() }
        jobs += scope.launch {
            while (isActive) {
                delay(mobilityConfig.checkIntervalMs)
                evaluateHandover()
            }
        }
        publish()
    }

    fun stop() = scope.launch {
        if (!running) return@launch
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        devices.values.forEach { it.queue?.close() }
        devices.clear()
        servingNode = null
        link.stop()
        publish()
    }

    private fun periodic(periodMs: Long, block: suspend () -> Unit) = scope.launch {
        while (isActive) {
            delay(periodMs)
            block()
        }
    }

    // ----------------------------------------------------------------------------------------------
    //  API aplikasi
    // ----------------------------------------------------------------------------------------------

    /** Kirim pesan publik ke seluruh mesh (dan LoRa bila ada jalur). */
    fun sendPublic(message: MeshMessage) = scope.launch {
        val packet = WirePacket(
            type = MessageType.MESSAGE.value, senderId = myIdBytes, recipientId = SpecialRecipients.BROADCAST,
            timestamp = now(), payload = message.encode(), ttl = recommendedTtl(peers.size),
        )
        broadcast(packet, except = null)
    }

    /** Kirim berkas publik (FILE_TRANSFER, kompatibel Nusa Mesh). Dipecah fragmen sesuai MTU. */
    fun sendFile(file: FilePacket) = scope.launch {
        val packet = WirePacket(
            type = MessageType.FILE_TRANSFER.value, senderId = myIdBytes, recipientId = SpecialRecipients.BROADCAST,
            timestamp = now(), payload = file.encode(), ttl = recommendedTtl(peers.size),
        )
        broadcast(packet, except = null)
    }

    /** Kirim paket ber-recipient (dipakai lapisan Noise/ACK). Rute terarah bila diketahui. */
    fun sendTo(recipientPeerId: String, type: Int, payload: ByteArray) = scope.launch {
        val packet = WirePacket(
            type = type, senderId = myIdBytes, recipientId = peerIdBytes(recipientPeerId),
            timestamp = now(), payload = payload, ttl = recommendedTtl(peers.size),
        )
        val hop = id.nusamesh.app.mesh.core.RoutePlanner.nextHop(myPeerId, recipientPeerId, topology.snapshot.value)
        val hopDevice = hop?.let { deviceOfPeer(it) }
        if (hopDevice != null) sendToDevice(hopDevice, packet) else broadcast(packet, except = null)
    }

    /** Kunci node secara manual (null = kembali otomatis). */
    fun lockNode(peerId: String?) = scope.launch {
        lockedNode = peerId
        mobilityLog.log(if (peerId != null) "MANUAL_SELECT" else "MANUAL_RELEASE", null, peerId, null, null, null, "")
        if (peerId != null && peerId != servingNode) switchServing(peerId, reason = "manual")
        publish()
    }

    // ----------------------------------------------------------------------------------------------
    //  Event radio
    // ----------------------------------------------------------------------------------------------
    private suspend fun handleLinkEvent(e: LinkEvent) {
        when (e) {
            is LinkEvent.Discovered -> onDiscovered(e)
            is LinkEvent.Connected -> onConnected(e)
            is LinkEvent.Disconnected -> onDisconnected(e.deviceId)
            is LinkEvent.ConnectFailed -> devices[e.deviceId]?.let { it.connecting = false }
            is LinkEvent.MtuChanged -> devices[e.deviceId]?.let { it.mtu = e.mtu; updateMtus() }
            is LinkEvent.RssiRead -> devices[e.deviceId]?.let { it.scanRssi = e.rssi }
            is LinkEvent.Received -> onReceived(e.deviceId, e.data)
        }
        // Scan bisa puluhan event per detik: cukup tandai, penerbit berkala yang menghitung ulang.
        if (e is LinkEvent.Discovered) dirty = true else publish()
    }

    private fun device(id: String) = devices.getOrPut(id) { Device(id) }

    private fun onDiscovered(e: LinkEvent.Discovered) {
        val d = device(e.deviceId)
        if (e.peerId != null && e.peerId != myPeerId) d.peerId = e.peerId
        if (e.name != null) d.name = e.name
        d.scanRssi = e.rssi
        d.scanAt = now()
        if (d.peerId == myPeerId) return
        if (d.connected || d.connecting) return
        if (d.isNode) considerNode(d) else considerPhone(d)
    }

    private fun considerPhone(d: Device) {
        val pid = d.peerId
        if (pid != null && deviceOfPeer(pid) != null) return          // sudah tersambung lewat jalur lain
        if (devices.values.count { it.connected && !it.isNode } >= MAX_PHONE_LINKS) return
        // Dua HP Android saling melihat peerID: cukup satu yang memulai (yang peerID-nya lebih kecil).
        if (pid != null && myPeerId > pid) return
        tryConnect(d)
    }

    private fun considerNode(d: Device) {
        val pid = d.peerId ?: return
        val locked = lockedNode
        when {
            locked != null -> if (pid == locked) tryConnect(d)
            servingNode == pid -> tryConnect(d)
            servingNode == null && pendingHandoverTo == null -> {
                // Pemilihan: kumpulkan kandidat sebentar, lalu ambil RSSI terkuat (lihat maintenance/evaluate).
                if (electionUntil == null) {
                    electionUntil = now() + NODE_ELECTION_WINDOW_MS
                    scope.launch {
                        delay(NODE_ELECTION_WINDOW_MS)
                        runElection()
                    }
                }
            }
        }
    }

    private fun runElection() {
        electionUntil = null
        if (servingNode != null || lockedNode != null) return
        val fresh = now() - mobilityConfig.scanFreshMs
        val candidates = devices.values.filter { it.isNode && it.scanAt >= fresh && it.peerId != null }
        val winner = candidates.filter { !handover.isBanned(it.peerId!!) }.maxByOrNull { it.scanRssi ?: -999 }
            ?: candidates.maxByOrNull { it.scanRssi ?: -999 }
            ?: return
        servingNode = winner.peerId
        mobilityLog.log("ELECTED", null, winner.peerId, null, winner.scanRssi, null, "kandidat=${candidates.size}")
        tryConnect(winner)
        publish()
    }

    private fun tryConnect(d: Device) {
        val t = now()
        if (t - d.lastAttempt < CONNECT_RETRY_MS) return
        d.lastAttempt = t
        d.connecting = true
        link.connect(d.id)
    }

    private suspend fun onConnected(e: LinkEvent.Connected) {
        val d = device(e.deviceId)
        d.connected = true
        d.connecting = false
        d.mtu = e.mtu
        updateMtus()
        if (d.isNode) {
            val pid = d.peerId
            // Hanya satu node boleh aktif: node lain yang kebetulan tersambung diputus.
            if (pid != null && servingNode != null && pid != servingNode) {
                link.disconnect(d.id)
                return
            }
            if (pid != null && servingNode == null && lockedNode == null) servingNode = pid
            d.queue = NodeQueue(myIdBytes, now).also { it.start(scope) }
            if (pid != null && pid == pendingHandoverTo) {
                mobilityLog.log("BLE_CONNECTED", null, pid, null, null, now() - pendingHandoverAt, "since_attempt")
            }
            pid?.let { registerWithNode(it, force = true) }
        }
        sendAnnounceTo(d)
        d.peerId?.let { markDirect(it) }
    }

    private fun onDisconnected(deviceId: String) {
        val d = devices[deviceId] ?: return
        d.connected = false
        d.connecting = false
        d.queue?.close()
        d.queue = null
        updateMtus()
        val pid = d.peerId
        if (pid != null && pid == servingNode && pendingHandoverTo == null) {
            servingNode = null
            mobilityLog.log("SERVING_LOST", null, pid, null, null, null, "")
        }
        if (pid != null && !d.isNode) topology.removePeer(pid)
    }

    private fun updateMtus() {
        fragmenter.connectionMtus = devices.values.filter { it.connected }.map { it.mtu }
    }

    // ----------------------------------------------------------------------------------------------
    //  Paket masuk
    // ----------------------------------------------------------------------------------------------
    private suspend fun onReceived(deviceId: String, data: ByteArray) {
        val packet = WireProtocol.decode(data) ?: return
        val d = device(deviceId)
        learnDirectPeer(d, packet)
        when (guard.check(packet)) {
            GuardVerdict.Accept -> {}
            else -> return
        }
        receivedCount++
        processPacket(packet, d)
    }

    /** Paket yang PASTI dikirim tetangga langsung (bukan relay) memberi tahu siapa di ujung koneksi ini. */
    private fun learnDirectPeer(d: Device, p: WirePacket) {
        val direct = (p.type == MessageType.ANNOUNCE.value && p.ttl == ANNOUNCE_TTL) ||
            p.type == MessageType.NODE_LORA_HEALTH.value || p.type == MessageType.NODE_REGISTER_ACK.value ||
            p.type == MessageType.TOPOLOGY_GOSSIP.value && p.ttl <= 1
        if (!direct) return
        val sender = p.senderHex
        if (sender == myPeerId) return
        if (d.peerId != sender) {
            d.peerId = sender
            if (d.isNode && d.connected && servingNode == null && lockedNode == null) servingNode = sender
        }
        markDirect(sender)
    }

    private fun markDirect(peerId: String) {
        val q = devices.values.firstOrNull { it.peerId == peerId && it.connected }?.scanRssi?.let { rssiQuality(it) } ?: 0.8
        topology.observeDirectLink(myPeerId, peerId, q)
        peer(peerId).lastSeen = now()
    }

    private fun peer(id: String) = peers.getOrPut(id) { PeerState(id) }

    private suspend fun processPacket(p: WirePacket, from: Device) {
        val forMe = p.recipientHex == myPeerId
        val broadcast = SpecialRecipients.isBroadcast(p.recipientId)
        val sender = p.senderHex
        if (!isNusaNodeId(sender)) peer(sender).lastSeen = now()

        when (p.messageType) {
            MessageType.ANNOUNCE -> {
                val nick = p.payload.decodeToString().substringBefore('~').ifBlank { null }
                peer(sender).nickname = nick
                topology.setNickname(sender, nick)
            }
            MessageType.LEAVE -> { peers.remove(sender); topology.removePeer(sender) }
            MessageType.MESSAGE -> if (forMe || broadcast) {
                MeshMessage.decode(p.payload)?.let { m ->
                    _messages.tryEmit(IncomingMessage(m, sender, from.isNode, forMe))
                }
            }
            MessageType.FRAGMENT_START, MessageType.FRAGMENT_CONTINUE, MessageType.FRAGMENT_END ->
                if (forMe || broadcast) fragmenter.accept(p)?.let { inner ->
                    // Paket utuh hasil rakitan diproses seperti datang langsung (tanpa relay ulang: fragmennya sudah di-relay).
                    if (guard.check(inner) == GuardVerdict.Accept) processInner(inner, from)
                }
            MessageType.TOPOLOGY_GOSSIP -> {
                val neighbors = TopologyGossipCodec.decode(p.payload).filter { it.peerId != sender }
                if (neighbors.isNotEmpty()) {
                    topology.updateRemoteClaims(sender, peers[sender]?.nickname, neighbors.associate { it.peerId to it.quality }, p.timestamp)
                }
            }
            MessageType.NODE_LORA_HEALTH -> onNodeHealth(sender, p.payload)
            MessageType.NODE_REGISTER_ACK -> if (forMe) onRegisterAck(sender, p.payload)
            MessageType.FILE_TRANSFER -> if (forMe || broadcast) emitFile(p, from, forMe)
            else -> if (forMe) _packets.tryEmit(p to sender)
        }
        if (forMe && p.messageType == MessageType.MESSAGE) _packets.tryEmit(p to sender)

        // Relay: paket lokal (TTL 1, node↔HP) dan yang untuk kita tidak diteruskan.
        if (forMe || p.ttl <= 1) return
        if (p.messageType == MessageType.NODE_LORA_HEALTH || p.messageType == MessageType.NODE_REGISTER ||
            p.messageType == MessageType.NODE_REGISTER_ACK
        ) return
        relayPacket(p, from)
    }

    private suspend fun processInner(inner: WirePacket, from: Device) {
        val forMe = inner.recipientHex == myPeerId
        when (inner.messageType) {
            MessageType.MESSAGE -> MeshMessage.decode(inner.payload)?.let { m ->
                _messages.tryEmit(IncomingMessage(m, inner.senderHex, from.isNode, forMe))
            }
            MessageType.FILE_TRANSFER -> if (forMe || SpecialRecipients.isBroadcast(inner.recipientId)) emitFile(inner, from, forMe)
            else -> if (forMe) _packets.tryEmit(inner to inner.senderHex)
        }
        if (forMe && inner.messageType == MessageType.MESSAGE) _packets.tryEmit(inner to inner.senderHex)
    }

    private fun emitFile(p: WirePacket, from: Device, forMe: Boolean) {
        FilePacket.decode(p.payload)?.let { _files.tryEmit(IncomingFile(it, p.senderHex, p.timestamp, from.isNode, forMe)) }
    }

    private suspend fun relayPacket(p: WirePacket, from: Device) {
        val fromPeer = from.peerId ?: from.id
        when (val decision = relay.decide(p, fromPeer, peers.size, topology.snapshot.value)) {
            RelayDecision.Drop -> return
            is RelayDecision.Directed -> {
                val target = deviceOfPeer(decision.nextHop)
                if (target != null && target.id != from.id) sendToDevice(target, decision.packet)
                else broadcast(decision.packet, except = from.id)
            }
            is RelayDecision.Flood -> broadcast(decision.packet, except = from.id)
        }
        relayedCount++
    }

    // ----------------------------------------------------------------------------------------------
    //  Nusa Node: kesehatan, registrasi, handover
    // ----------------------------------------------------------------------------------------------
    private fun onNodeHealth(nodeId: String, payload: ByteArray) {
        if (payload.isEmpty()) return
        val h = nodeHealth.getOrPut(nodeId) { NodeHealth() }
        h.neighbors = payload[0].toInt() and 0xFF
        if (payload.size >= 3) {
            h.bestRssi = payload[1].toInt().takeIf { it != -128 }
            h.bestSnr = payload[2].toInt().takeIf { it != -128 }?.div(2f)
        }
        if (payload.size >= 5) h.slots = (payload[3].toInt() and 0xFF) to (payload[4].toInt() and 0xFF)
        // Registrasi berkala / ulang bila ACK belum datang.
        registerWithNode(nodeId, force = false)
    }

    private fun registerWithNode(nodeId: String, force: Boolean) {
        val d = deviceOfPeer(nodeId) ?: return
        val t = now()
        val same = registeredNode == nodeId
        val interval = if (registerAcked == nodeId) REGISTER_REFRESH_MS else REGISTER_RETRY_MS
        if (!force && same && t - registeredAt < interval) return
        val prev = registeredNode?.takeIf { !same && t - registeredAt < PREV_NODE_MEMORY_MS }
        val packet = WirePacket(
            type = MessageType.NODE_REGISTER.value, senderId = myIdBytes, recipientId = peerIdBytes(nodeId),
            timestamp = t, payload = byteArrayOf((prev?.let { nusaNodeNumber(it) } ?: 0).toByte()), ttl = 1,
        )
        if (!same) registerAcked = null
        registeredNode = nodeId
        registeredAt = t
        // Langsung ke antrean node (bukan lewat launch): harus mendahului ANNOUNCE, karena setelah paket
        // ber-airtime antrean menunggu jeda puluhan detik — registrasi tertunda = delay handover membengkak.
        val q = d.queue
        if (q != null) q.offerBatch(listOf(WireProtocol.encode(packet, random = random))) { bytes -> link.send(d.id, bytes) }
        else scope.launch { sendToDevice(d, packet) }
        mobilityLog.log("REGISTER_SENT", prev, nodeId, null, null, null, "")
    }

    private fun onRegisterAck(nodeId: String, payload: ByteArray) {
        if (payload.size < 4) return
        if (payload[1].toInt() != 0) {
            mobilityLog.log("FAILED", null, nodeId, null, null, null, "register_rejected_${payload[1]}")
            return
        }
        registerAcked = nodeId
        val direct = payload[2].toInt() and 0xFF
        val total = payload[3].toInt() and 0xFF
        val pending = pendingHandoverTo
        if (pending == nodeId) {
            val t = now()
            val from = lastServingBeforeHandover ?: ""
            mobilityLog.log("SUCCESS", from, nodeId, null, null, t - pendingHandoverAt, "users=$direct/$total")
            lastSuccessHandover?.let { (pf, pt, at) ->
                if (pf == nodeId && pt == from && t - at <= mobilityConfig.pingPongWindowMs) {
                    mobilityLog.log("PING_PONG", from, nodeId, null, null, t - at, "A-B-A dalam jendela")
                }
            }
            lastSuccessHandover = Triple(from, nodeId, t)
            pendingHandoverTo = null
        } else {
            mobilityLog.log("REGISTERED", null, nodeId, null, null, null, "users=$direct/$total")
        }
    }

    private fun evaluateHandover() {
        val cfg = mobilityConfig
        val t = now()
        pendingHandoverTo?.let { target ->
            val connected = deviceOfPeer(target) != null
            if (!connected && t - pendingHandoverAt > cfg.connectTimeoutMs) {
                mobilityLog.log("FAILED", lastServingBeforeHandover, target, null, null, t - pendingHandoverAt, "connect_timeout")
                handover.ban(target, cfg)
                pendingHandoverTo = null
                if (servingNode == target) servingNode = null
            }
            return
        }
        val serving = servingNode ?: return
        val servingDevice = deviceOfPeer(serving) ?: return
        val fresh = t - cfg.scanFreshMs
        val servingSample = RssiSample(serving, servingDevice.scanRssi ?: return, servingDevice.scanAt)
        val neighbors = devices.values
            .filter { it.isNode && it.peerId != null && it.peerId != serving && it.scanAt >= fresh && it.scanRssi != null }
            .map { RssiSample(it.peerId!!, it.scanRssi!!, it.scanAt) }
        when (val ev = handover.evaluate(servingSample, neighbors, cfg, manualLock = lockedNode != null)) {
            is HandoverEvent.ConditionMet -> mobilityLog.log("CONDITION_MET", ev.from, ev.to, ev.servingRssi, ev.targetRssi, null, "")
            is HandoverEvent.ConditionReset -> mobilityLog.log("CONDITION_RESET", null, ev.target, null, null, ev.heldMs, ev.reason)
            is HandoverEvent.Attempt -> {
                mobilityLog.log("ATTEMPT", ev.from, ev.to, ev.servingRssi, ev.targetRssi, ev.sinceConditionMs, "since_condition")
                switchServing(ev.to, reason = "handover")
            }
            null -> {}
        }
    }

    /** Break-before-make: klaim pindah ke [target], node lama diputus, target disambung. */
    private fun switchServing(target: String, reason: String) {
        val old = servingNode
        lastServingBeforeHandover = old
        servingNode = target
        pendingHandoverTo = target
        pendingHandoverAt = now()
        handover.markHandover()
        old?.let { oldId -> deviceOfPeer(oldId)?.let { link.disconnect(it.id) } }
        devices.values.firstOrNull { it.peerId == target }?.let { d ->
            d.lastAttempt = Long.MIN_VALUE / 2
            tryConnect(d)
        }
    }

    // ----------------------------------------------------------------------------------------------
    //  Kirim
    // ----------------------------------------------------------------------------------------------
    private fun deviceOfPeer(peerId: String) = devices.values.firstOrNull { it.connected && it.peerId == peerId }

    private suspend fun broadcast(packet: WirePacket, except: String?) {
        // Berkas besar tidak dimasukkan ke antrean LoRa: satu gambar 50 KB = puluhan menit airtime.
        val tooBigForLora = packet.type == MessageType.FILE_TRANSFER.value && packet.payload.size > LORA_FILE_MAX_BYTES
        val targets = devices.values.filter { it.connected && it.id != except && !(tooBigForLora && it.isNode) }
        if (targets.isEmpty()) return
        val parts = fragmenter.split(packet)
        for (d in targets) sendParts(d, parts)
    }

    private suspend fun sendToDevice(d: Device, packet: WirePacket) = sendParts(d, fragmenter.split(packet))

    private suspend fun sendParts(d: Device, parts: List<WirePacket>) {
        val encoded = parts.map { WireProtocol.encode(it, random = random) }
        val q = d.queue
        if (q != null) {
            // Ke Nusa Node: lewat antrean berjeda (airtime LoRa) dengan kebijakan HCMA.
            q.offerBatch(encoded) { bytes -> link.send(d.id, bytes) }
            return
        }
        scope.launch {
            encoded.forEachIndexed { i, bytes ->
                if (!link.send(d.id, bytes)) return@launch
                if (i < encoded.lastIndex) delay(FRAGMENT_GAP_MS)
            }
        }
    }

    private fun announcePacket() = WirePacket(
        type = MessageType.ANNOUNCE.value, senderId = myIdBytes, timestamp = now(),
        payload = "$nickname~".encodeToByteArray(), ttl = ANNOUNCE_TTL,
    )

    private suspend fun sendAnnounceTo(d: Device) = sendToDevice(d, announcePacket())

    private suspend fun broadcastAnnounce() {
        broadcast(announcePacket(), except = null)
    }

    private suspend fun broadcastGossip() {
        val direct = devices.values.filter { it.connected && it.peerId != null }
            .map { Neighbor(it.peerId!!, it.scanRssi?.let(::rssiQuality) ?: 0.8) }
        if (direct.isEmpty()) return
        val p = WirePacket(
            type = MessageType.TOPOLOGY_GOSSIP.value, senderId = myIdBytes, timestamp = now(),
            payload = TopologyGossipCodec.encode(direct), ttl = 1,
        )
        // Gossip hanya untuk tetangga langsung, dan tidak perlu dikirim ke node (bukan trafik LoRa).
        val parts = listOf(p)
        devices.values.filter { it.connected && !it.isNode }.forEach { sendParts(it, parts) }
    }

    // ----------------------------------------------------------------------------------------------
    //  Perawatan berkala
    // ----------------------------------------------------------------------------------------------
    private fun maintenance() {
        val t = now()
        fragmenter.expire()
        guard.expire()
        topology.pruneStale()
        val directPeers = devices.values.filter { it.connected }.mapNotNull { it.peerId }.toSet()
        peers.entries.removeAll { (id, p) -> id !in directPeers && t - p.lastSeen > PEER_STALE_MS }
        servingNode?.let { registerWithNode(it, force = false) }
        if (servingNode == null && lockedNode == null && electionUntil == null && pendingHandoverTo == null &&
            devices.values.any { it.isNode && it.scanAt >= t - mobilityConfig.scanFreshMs }
        ) runElection()
        publish()
    }

    private fun rssiQuality(rssi: Int) = ((rssi + 100) / 60.0).coerceIn(0.05, 1.0)

    private fun publish() {
        dirty = false
        val connectedPeers = devices.values.filter { it.connected && !it.isNode }.mapNotNull { it.peerId }
        val directNodes = devices.values.filter { it.connected && it.isNode }.mapNotNull { it.peerId }
        val nebeng = Hcma.findNebengRoute(myPeerId, directNodes, connectedPeers, topology.snapshot.value) { peers[it]?.nickname }
        val directSet = connectedPeers.toSet()
        _snapshot.value = EngineSnapshot(
            running = running,
            linkState = link.state.value,
            peers = peers.values.filter { !isNusaNodeId(it.id, it.nickname) }.map { p ->
                PeerInfo(p.id, p.nickname, p.id in directSet, devices.values.firstOrNull { it.peerId == p.id }?.scanRssi, p.lastSeen)
            }.sortedWith(compareByDescending<PeerInfo> { it.direct }.thenByDescending { it.lastSeenMs }),
            nodes = devices.values.filter { it.isNode && it.peerId != null }.groupBy { it.peerId!! }.map { (pid, list) ->
                val d = list.firstOrNull { it.connected } ?: list.maxBy { it.scanAt }
                val h = nodeHealth[pid]
                NodeInfo(
                    peerId = pid, name = d.name ?: peers[pid]?.nickname ?: "Nusa Node", deviceId = d.id,
                    connected = d.connected, serving = pid == servingNode, rssi = d.scanRssi,
                    loraNeighbors = h?.neighbors, loraBestRssi = h?.bestRssi, loraBestSnr = h?.bestSnr,
                    userSlots = h?.slots, registered = registerAcked == pid, locked = lockedNode == pid,
                )
            }.sortedByDescending { it.rssi ?: -999 },
            nebeng = nebeng,
            relayed = relayedCount,
            received = receivedCount,
        )
    }
}
