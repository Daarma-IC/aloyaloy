package id.nusamesh.app.mesh.core

import id.nusamesh.app.mesh.protocol.*
import kotlin.random.Random
import kotlin.test.*

class VoicePriorityTest {
    @Test fun voiceHintSurvivesWireFragmentationRelayAndReassembly() {
        val packet = WirePacket(type = 0x22, senderId = ByteArray(8), timestamp = 1,
            payload = Random(4).nextBytes(1512), ttl = 3, voicePriority = true)
        val fragmenter = Fragmenter(now = { 0 })
        val parts = fragmenter.split(packet)
        assertTrue(parts.size > 1)
        val q = NodeQueue(null, { 0 })
        val image = WirePacket(type = 0x22, senderId = ByteArray(8), timestamp = 2, payload = byteArrayOf(1), ttl = 3)
        assertTrue(q.offer(WireProtocol.encode(image)) { true })
        assertTrue(q.offerBatch(parts.map { WireProtocol.encode(it) }, { true }))
        var rebuilt: WirePacket? = null
        repeat(parts.size) {
            val raw = assertNotNull(q.takeNext()).bytes
            assertEquals(2, NodeQueue.priorityOf(raw))
            val decoded = assertNotNull(WireProtocol.decode(raw)).withTtl(2)
            assertTrue(decoded.voicePriority)
            rebuilt = fragmenter.accept(decoded) ?: rebuilt
        }
        assertEquals(packet, rebuilt)
        assertEquals(3, assertNotNull(q.takeNext()).priority)
    }

    @Test fun ackAndTextStayAheadOfVoiceWhileHintDoesNotElevateUnrelatedTypes() {
        fun raw(type: Int, voice: Boolean = true) = WireProtocol.encode(WirePacket(type = type,
            senderId = ByteArray(8), timestamp = 1, payload = byteArrayOf(1), ttl = 1, voicePriority = voice))
        assertEquals(0, NodeQueue.priorityOf(raw(0x0A)))
        assertEquals(1, NodeQueue.priorityOf(raw(0x04)))
        assertEquals(2, NodeQueue.priorityOf(raw(0x22)))
        assertEquals(3, NodeQueue.priorityOf(raw(0x22, false)))
        assertEquals(3, NodeQueue.priorityOf(raw(0x12)))
        assertEquals(4, NodeQueue.priorityOf(raw(0x30)))
    }
}
