package id.nusamesh.app

import id.nusamesh.app.data.InMemoryKeyValueStore
import id.nusamesh.app.data.MeshRepository
import id.nusamesh.app.domain.*
import id.nusamesh.app.mesh.engine.FakeLink
import id.nusamesh.app.mesh.engine.FakeRadio
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.random.Random
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceReceiptTest {
    private fun TestScope.pair(): Pair<AppController,AppController> {
        val radio=FakeRadio()
        fun phone(address:String,id:String): AppController {
            val store=InMemoryKeyValueStore().apply { put("peer_id",id); put("nickname",address) }
            val repo=MeshRepository(FakeLink(radio,address),store,backgroundScope)
            return AppController(repo,store,appScope=backgroundScope).also { it.startMesh() }
        }
        val a=phone("Ayu","0a0a0a0a0a0a0a0a")
        val b=phone("Budi","0b0b0b0b0b0b0b0b")
        radio.setRssi("Ayu","Budi",-55)
        backgroundScope.launch { while(isActive) { radio.scanTick(); delay(1000) } }
        advanceTimeBy(5000);runCurrent()
        assertTrue(a.state.value.mesh.engine.peers.any { it.direct })
        return a to b
    }

    @Test fun completeVoiceIsConfirmedByReceiverWithTheSameTransferId() = runTest {
        val (a,b)=pair()
        val bytes=Random(4).nextBytes(2000)
        a.sendAttachment(ChatAttachment("voice.c2","audio/x-codec2",bytes,ChatMessageKind.Voice,10))
        val submitted=a.state.value.messages.last { it.outgoing }
        assertTrue(submitted.ackedBy.isEmpty())
        advanceTimeBy(10000);runCurrent()
        val outgoing=a.state.value.messages.first { it.id==submitted.id }
        val incoming=b.state.value.messages.first { it.id==submitted.id }
        assertEquals(DeliveryState.Sent,outgoing.delivery)
        assertEquals(listOf("Budi"),outgoing.ackedBy)
        assertNotNull(outgoing.acknowledgedAtMs)
        assertNull(outgoing.transferEstimate)
        assertContentEquals(bytes,incoming.attachmentData)
        assertFalse(incoming.outgoing)
    }

    @Test fun nonVoiceAttachmentsDoNotGenerateNewVoiceReceipts() = runTest {
        val (a,b)=pair()
        a.sendAttachment(ChatAttachment("file.bin","application/octet-stream",Random(8).nextBytes(1000),ChatMessageKind.File))
        advanceTimeBy(10000);runCurrent()
        assertTrue(b.state.value.messages.any { it.kind==ChatMessageKind.File })
        assertTrue(a.state.value.messages.filter { it.outgoing }.all { it.ackedBy.isEmpty() })
    }
}
