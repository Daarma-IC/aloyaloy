package id.nusamesh.app.mesh.core

import id.nusamesh.app.mesh.protocol.MessageType
import id.nusamesh.app.mesh.protocol.WirePacket
import id.nusamesh.app.mesh.protocol.peerIdBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Clock(var t: Long = 1_700_000_000_000) { fun now() = t }

class FragmenterTest {
    private val clock = Clock()
    private val me = peerIdBytes("a1a1a1a1a1a1a1a1")

    @Test
    fun smallPacketIsNotFragmented() {
        val f = Fragmenter(Random(1), clock::now)
        val p = WirePacket(type = 4, senderId = me, timestamp = clock.t, payload = ByteArray(50), ttl = 7)
        assertEquals(listOf(p), f.split(p))
    }

    @Test
    fun largePacketSplitsAndReassemblesOutOfOrderWithDuplicates() {
        val f = Fragmenter(Random(2), clock::now)
        val payload = Random(3).nextBytes(3000)   // acak → tidak terkompresi
        val p = WirePacket(type = 4, senderId = me, timestamp = clock.t, payload = payload, ttl = 7)
        val parts = f.split(p)
        assertTrue(parts.size > 1)
        assertEquals(MessageType.FRAGMENT_START.value, parts.first().type)
        assertEquals(MessageType.FRAGMENT_END.value, parts.last().type)

        val rx = Fragmenter(Random(4), clock::now)
        val order = parts.reversed() + parts.first()
        var whole: WirePacket? = null
        order.forEach { rx.accept(it)?.let { w -> whole = w } }
        assertContentEquals(payload, assertNotNull(whole).payload)
        assertEquals(0, rx.pendingSets)
    }

    @Test
    fun fragmentSizeFitsNimbleLimitAtHighMtu() {
        val size = Fragmenter.fragmentDataSize(517)
        assertTrue(size + 13 + 34 <= 512, "fragmen harus muat satu write NimBLE ($size)")
    }

    @Test
    fun incompleteSetsExpire() {
        val f = Fragmenter(Random(5), clock::now)
        val parts = f.split(WirePacket(type = 4, senderId = me, timestamp = clock.t, payload = Random(6).nextBytes(2000), ttl = 7))
        assertNull(f.accept(parts[0]))
        clock.t += Fragmenter.FRAGMENT_TIMEOUT_MS + 1
        assertEquals(1, f.expire())
    }
}

class PacketGuardTest {
    private val clock = Clock()
    private val other = peerIdBytes("b2b2b2b2b2b2b2b2")

    @Test
    fun dropsOwnExpiredOldAndDuplicate() {
        val g = PacketGuard("a1a1a1a1a1a1a1a1", clock::now)
        val p = WirePacket(type = 4, senderId = other, timestamp = clock.t, payload = byteArrayOf(1), ttl = 3)
        assertEquals(GuardVerdict.Accept, g.check(p))
        assertEquals(GuardVerdict.Duplicate, g.check(p.withTtl(2)), "relay ulang dengan TTL lain tetap duplikat")
        assertEquals(GuardVerdict.Own, g.check(WirePacket(type = 4, senderId = peerIdBytes("a1a1a1a1a1a1a1a1"), timestamp = clock.t, payload = byteArrayOf(1), ttl = 3)))
        assertEquals(GuardVerdict.TtlExpired, g.check(p.withTtl(0)))
        val oldCtrl = WirePacket(type = 1, senderId = other, timestamp = clock.t - PacketAgePolicy.CONTROL_WINDOW_MS - 1, payload = byteArrayOf(1), ttl = 3)
        assertEquals(GuardVerdict.TooOld, g.check(oldCtrl))
        val slowData = WirePacket(type = 4, senderId = other, timestamp = clock.t - 10 * 60_000L, payload = byteArrayOf(2), ttl = 3)
        assertEquals(GuardVerdict.Accept, g.check(slowData), "data lewat LoRa boleh telat 10 menit")
    }
}

class RoutingTest {
    private val clock = Clock()

    @Test
    fun prefersReliablePathOverShortUnreliableOne() {
        val topo = MeshTopology(clock::now)
        topo.observeDirectLink("me", "a", 0.9)
        topo.observeDirectLink("me", "d", 0.2)
        topo.updateRemoteClaims("a", null, mapOf("me" to 0.9, "b" to 0.9), clock.t)
        topo.updateRemoteClaims("b", null, mapOf("a" to 0.9, "d" to 0.9), clock.t)
        topo.updateRemoteClaims("d", null, mapOf("b" to 0.9, "me" to 0.2), clock.t)
        val route = assertNotNull(RoutePlanner.bestRoute("me", "d", topo.snapshot.value))
        assertEquals(listOf("me", "a", "b", "d"), route.path)
    }

    @Test
    fun gossipCodecRoundTrips() {
        val n = listOf(Neighbor("4e55534e00000002", 1.0), Neighbor("b2b2b2b2b2b2b2b2", 0.5))
        val back = TopologyGossipCodec.decode(TopologyGossipCodec.encode(n))
        assertEquals(n.map { it.peerId }, back.map { it.peerId })
        assertEquals(0.5, back[1].quality, 0.01)
    }

    @Test
    fun relayDirectedWhenRouteKnownElseFloodByTtl() {
        val topo = MeshTopology(clock::now)
        topo.observeDirectLink("me", "a", 1.0)
        topo.updateRemoteClaims("a", null, mapOf("me" to 1.0, "c" to 1.0), clock.t)
        val policy = RelayPolicy("me", Random(1))
        val unicast = WirePacket(type = 4, senderId = peerIdBytes("b0"), recipientId = peerIdBytes("c"), timestamp = 1, payload = byteArrayOf(1), ttl = 7)
        // Tujuan "c" sebagai hex 8 byte berbeda dari "c" topologi; gunakan id hex konsisten:
        val cHex = peerIdBytes("c").let { id -> id.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } }
        topo.updateRemoteClaims("a", null, mapOf("me" to 1.0, cHex to 1.0), clock.t + 1)
        val d = policy.decide(unicast, fromPeer = "x", networkSize = 50, topology = topo.snapshot.value)
        val directed = assertIs<RelayDecision.Directed>(d)
        assertEquals("a", directed.nextHop)
        assertEquals(6, directed.packet.ttl)

        val broadcast = WirePacket(type = 4, senderId = peerIdBytes("b0"), timestamp = 1, payload = byteArrayOf(1), ttl = 7)
        assertIs<RelayDecision.Flood>(policy.decide(broadcast, "x", 500, topo.snapshot.value), "TTL tinggi selalu diteruskan")
        assertIs<RelayDecision.Drop>(policy.decide(broadcast.withTtl(0), "x", 5, topo.snapshot.value))
    }

    @Test
    fun findsNebengRouteThroughConnectedRelay() {
        val topo = MeshTopology(clock::now)
        val node = "4e55534e00000002"
        topo.observeDirectLink("me", "relay", 1.0)
        topo.updateRemoteClaims("relay", "Budi", mapOf("me" to 1.0, node to 1.0), clock.t)
        val r = assertNotNull(Hcma.findNebengRoute("me", emptyList(), listOf("relay"), topo.snapshot.value))
        assertEquals("relay", r.relayPeerId)
        assertEquals("Budi", r.relayName)
        assertEquals(node, r.nodeId)
        assertNull(Hcma.findNebengRoute("me", listOf(node), listOf("relay"), topo.snapshot.value), "tersambung langsung bukan nebeng")
        assertNull(Hcma.findNebengRoute("me", emptyList(), emptyList(), topo.snapshot.value), "perantara harus masih tersambung")
        assertEquals(2, nusaNodeNumber(node))
    }
}

class NodeQueueTest {
    private val clock = Clock()
    private val me = peerIdBytes("1111111111111111")

    private fun pkt(type: Int, sender: Int, tag: Int) = ByteArray(25).also {
        it[0] = 2; it[1] = type.toByte()
        for (i in 16 until 24) it[i] = sender.toByte()
        it[24] = tag.toByte()
    }
    private val noop: suspend (ByteArray) -> Boolean = { true }

    @Test
    fun ownBeforeRelayedAndClassOrder() {
        val q = NodeQueue(me, clock::now)
        q.offer(pkt(0x04, 0x22, 1), noop)   // titipan
        q.offer(pkt(0x04, 0x11, 2), noop)   // milik sendiri
        q.offer(pkt(0x0A, 0x22, 3), noop)   // ACK titipan: kelas lebih tinggi
        q.offer(pkt(0x40, 0x11, 4), noop)   // NODE_REGISTER
        assertEquals(listOf(4, 3, 2, 1), generateSequence { q.takeNext()?.bytes?.get(24)?.toInt() }.toList())
    }

    @Test
    fun agedRelayedCatchesUp() {
        val q = NodeQueue(me, clock::now)
        q.offer(pkt(0x04, 0x22, 1), noop)
        clock.t += NodeQueue.RELAY_AGING_MS
        q.offer(pkt(0x04, 0x11, 2), noop)
        assertEquals(1, q.takeNext()?.bytes?.get(24)?.toInt(), "titipan yang sudah menua setara → FIFO")
    }

    @Test
    fun relayedShareIsCapped() {
        val q = NodeQueue(me, clock::now)
        repeat(NodeQueue.MAX_RELAYED) { assertTrue(q.offer(pkt(0x04, 0x22, it), noop)) }
        assertTrue(!q.offer(pkt(0x04, 0x33, 99), noop))
        assertTrue(q.offer(pkt(0x04, 0x11, 100), noop))
    }
}
