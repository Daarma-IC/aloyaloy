package id.nusamesh.app.mesh.engine

import id.nusamesh.app.mesh.protocol.FilePacket
import id.nusamesh.app.mesh.protocol.MeshMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MeshEngineTest {
    private val base = 1_700_000_000_000L

    private class Phone(
        val engine: MeshEngine, val link: FakeLink, val inbox: MutableList<String>, val log: MutableList<String>,
        val files: MutableList<FilePacket>,
    )

    private fun TestScope.world(): FakeRadio {
        val radio = FakeRadio()
        backgroundScope.launch { while (isActive) { radio.scanTick(); delay(1_000) } }
        return radio
    }

    private fun TestScope.phone(radio: FakeRadio, address: String, peerId: String, nick: String): Phone {
        val link = FakeLink(radio, address)
        val log = mutableListOf<String>()
        val engine = MeshEngine(link, peerId, backgroundScope, { base + testScheduler.currentTime }, Random(peerId.hashCode()),
            MobilityLogSink { ev, from, to, _, _, _, detail -> log += "$ev $from->$to $detail" })
        engine.nickname = nick
        val inbox = mutableListOf<String>()
        backgroundScope.launch { engine.messages.collect { inbox += "${it.message.sender}:${it.message.content}" } }
        val files = mutableListOf<FilePacket>()
        backgroundScope.launch { engine.files.collect { files += it.file } }
        engine.start()
        return Phone(engine, link, inbox, log, files)
    }

    private fun TestScope.node(radio: FakeRadio, address: String, n: Int) =
        FakeNode(radio, address, n) { base + testScheduler.currentTime }

    private fun msg(from: String, text: String) = MeshMessage(sender = from, content = text, timestampMs = base)

    @Test
    fun messageRelaysThroughMiddlePhone() = runTest {
        val radio = world()
        val a = phone(radio, "A", "0a0a0a0a0a0a0a0a", "Ayu")
        val b = phone(radio, "B", "0b0b0b0b0b0b0b0b", "Budi")
        val c = phone(radio, "C", "0c0c0c0c0c0c0c0c", "Citra")
        radio.setRssi("A", "B", -60); radio.setRssi("B", "C", -60)   // A tidak menjangkau C
        advanceTimeBy(5_000); runCurrent()
        assertTrue(radio.isConnected("A", "B") && radio.isConnected("B", "C"))

        a.engine.sendPublic(msg("Ayu", "jalan utama tertutup"))
        advanceTimeBy(2_000); runCurrent()
        assertTrue("Ayu:jalan utama tertutup" in c.inbox, "C harus menerima lewat relay B: ${c.inbox}")
        assertTrue("Ayu:jalan utama tertutup" in b.inbox)
        assertTrue(a.inbox.isEmpty(), "pesan sendiri tidak kembali sebagai pesan masuk")
        assertEquals(setOf("0b0b0b0b0b0b0b0b"), a.engine.snapshot.value.peers.filter { it.direct }.map { it.peerId }.toSet())
    }

    @Test
    fun electsStrongestNodeOnlyAndRegisters() = runTest {
        val radio = world()
        val n1 = node(radio, "N1", 1)
        val n2 = node(radio, "N2", 2)
        val a = phone(radio, "A", "0a0a0a0a0a0a0a0a", "Ayu")
        radio.setRssi("A", "N1", -82); radio.setRssi("A", "N2", -61)
        advanceTimeBy(8_000); runCurrent()
        assertTrue(radio.isConnected("A", "N2"))
        assertTrue(!radio.isConnected("A", "N1"), "hanya satu node boleh aktif")
        assertEquals(listOf("0a0a0a0a0a0a0a0a" to 0), n2.registrations.take(1))
        val serving = assertNotNull(a.engine.snapshot.value.servingNode)
        assertEquals(n2.peerId, serving.peerId)
        assertTrue(serving.registered)
        n2.sendHealth(neighbors = 1); runCurrent()
        assertEquals(1, a.engine.snapshot.value.servingNode?.loraNeighbors)
        assertEquals(1 to 3, a.engine.snapshot.value.servingNode?.userSlots)
        assertTrue(a.engine.snapshot.value.loraPathAvailable)
    }

    @Test
    fun nebengRouteThroughPhoneConnectedToNode() = runTest {
        val radio = world()
        val n1 = node(radio, "N1", 1)
        val a = phone(radio, "A", "0a0a0a0a0a0a0a0a", "Ayu")
        phone(radio, "B", "0b0b0b0b0b0b0b0b", "Budi")
        radio.setRssi("A", "B", -55); radio.setRssi("B", "N1", -60)   // A di luar jangkauan node
        advanceTimeBy(MeshEngine.GOSSIP_PERIOD_MS + 6_000); runCurrent()
        val nebeng = assertNotNull(a.engine.snapshot.value.nebeng, "A harus tahu bisa nebeng lewat B")
        assertEquals("0b0b0b0b0b0b0b0b", nebeng.relayPeerId)
        assertEquals("Budi", nebeng.relayName)
        assertEquals(n1.peerId, nebeng.nodeId)
        assertTrue(a.engine.snapshot.value.loraPathAvailable)
    }

    @Test
    fun messageCrossesLoraBackboneToAnotherVillage() = runTest {
        val radio = world()
        val n1 = node(radio, "N1", 1)
        val n2 = node(radio, "N2", 2)
        n1.loraPeers += n2; n2.loraPeers += n1
        val a = phone(radio, "A", "0a0a0a0a0a0a0a0a", "Ayu")
        val z = phone(radio, "Z", "0f0f0f0f0f0f0f0f", "Zaki")
        radio.setRssi("A", "N1", -60); radio.setRssi("Z", "N2", -60)
        advanceTimeBy(8_000); runCurrent()
        a.engine.sendPublic(msg("Ayu", "butuh air bersih di RT 3"))
        // Antrean node berjeda airtime (≥ 4 s per paket): beri waktu.
        advanceTimeBy(30_000); runCurrent()
        assertTrue("Ayu:butuh air bersih di RT 3" in z.inbox, "pesan harus melintas LoRa ke desa lain: ${z.inbox}")
    }

    @Test
    fun handoverToStrongerNodeWithRegistrationAndPrevNode() = runTest {
        val radio = world()
        val n1 = node(radio, "N1", 1)
        val n2 = node(radio, "N2", 2)
        val a = phone(radio, "A", "0a0a0a0a0a0a0a0a", "Ayu")
        a.engine.mobilityConfig = a.engine.mobilityConfig.copy(filterAlpha = 1f)
        radio.setRssi("A", "N1", -58); radio.setRssi("A", "N2", -88)
        advanceTimeBy(8_000); runCurrent()
        assertEquals(n1.peerId, a.engine.snapshot.value.servingNode?.peerId)

        // Ayu berjalan ke arah N2.
        radio.setRssi("A", "N1", -86); radio.setRssi("A", "N2", -60)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(n2.peerId, a.engine.snapshot.value.servingNode?.peerId, "log: ${a.log}")
        assertTrue(radio.isConnected("A", "N2") && !radio.isConnected("A", "N1"))
        assertEquals("0a0a0a0a0a0a0a0a" to 1, n2.registrations.last(), "registrasi membawa node sebelumnya")
        assertTrue(a.log.any { it.startsWith("ATTEMPT") } && a.log.any { it.startsWith("SUCCESS") }, a.log.toString())
    }

    @Test
    fun fileTravelsOverBleFragmentsAndLargeFilesSkipLora() = runTest {
        val radio = world()
        val n1 = node(radio, "N1", 1)
        val n2 = node(radio, "N2", 2)
        n1.loraPeers += n2; n2.loraPeers += n1
        val a = phone(radio, "A", "0a0a0a0a0a0a0a0a", "Ayu")
        val b = phone(radio, "B", "0b0b0b0b0b0b0b0b", "Budi")
        val z = phone(radio, "Z", "0f0f0f0f0f0f0f0f", "Zaki")
        radio.setRssi("A", "B", -55); radio.setRssi("A", "N1", -60); radio.setRssi("Z", "N2", -60)
        advanceTimeBy(8_000); runCurrent()

        val big = FilePacket("foto.jpg", "image/jpeg", ByteArray(20_000) { (it * 31).toByte() })
        a.engine.sendFile(big)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, b.files.size, "HP tetangga menerima berkas besar lewat fragmen BLE")
        assertTrue(b.files[0].content.contentEquals(big.content))
        assertTrue(z.files.isEmpty(), "berkas 20 KB tidak boleh masuk antrean LoRa")

        val small = FilePacket("lora.webp", "image/webp", ByteArray(900) { it.toByte() })
        a.engine.sendFile(small)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("lora.webp"), z.files.map { it.fileName }, "berkas kecil melintas LoRa")
    }
}

@Suppress("unused")
private fun CoroutineScope.unusedMarker() = Unit
