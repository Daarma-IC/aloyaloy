package id.nusamesh.app

import id.nusamesh.app.data.*
import id.nusamesh.app.domain.*
import id.nusamesh.app.media.*
import id.nusamesh.app.mesh.engine.FakeLink
import id.nusamesh.app.mesh.engine.FakeRadio
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceStreamIntegrationTest {
    private fun TestScope.pair(): Pair<AppController,AppController> {
        val radio=FakeRadio()
        fun phone(name:String,id:String):AppController {
            val store=InMemoryKeyValueStore().apply { put("peer_id",id);put("nickname",name) }
            return AppController(MeshRepository(FakeLink(radio,name),store,backgroundScope),store,appScope=backgroundScope).also { it.startMesh() }
        }
        val a=phone("Ayu","0a0a0a0a0a0a0a0a");val b=phone("Budi","0b0b0b0b0b0b0b0b")
        radio.setRssi("Ayu","Budi",-55)
        backgroundScope.launch { while(isActive) { radio.scanTick();delay(1000) } }
        advanceTimeBy(5000);runCurrent()
        return a to b
    }
    @Test fun receiverGetsPlayableFramesBeforeStopThenOneArchiveAndOneReceipt()=runTest {
        val (a,b)=pair();val live=mutableListOf<IncomingVoiceChunk>()
        backgroundScope.launch { b.voiceChunks.collect { live+=it } };runCurrent()
        assertTrue(a.beginVoiceStream())
        val first=VoiceChunk(90,0,0,5120,ByteArray(96){it.toByte()})
        a.sendVoiceChunk(first);advanceTimeBy(1000);runCurrent()
        assertEquals(1,live.size)
        val receiving=b.state.value.messages.single { !it.outgoing && it.kind==ChatMessageKind.Voice }
        assertNull(receiving.attachmentData)
        assertEquals(DeliveryState.Sending,receiving.delivery)
        assertTrue(a.state.value.messages.last { it.outgoing }.ackedBy.isEmpty())
        val last=VoiceChunk(90,1,5120,321,ByteArray(12){8},end=true)
        a.sendVoiceChunk(last)
        val archive=VoiceStream.note(5441,first.frames+last.frames)
        a.finishVoiceStream(ChatAttachment("voice.c2","audio/x-codec2",archive,ChatMessageKind.Voice,1))
        advanceTimeBy(5000);runCurrent()
        assertEquals(listOf(0,1),live.map { it.chunk.sequence })
        val complete=b.state.value.messages.single { it.id==receiving.id }
        assertContentEquals(archive,complete.attachmentData)
        assertEquals(DeliveryState.Received,complete.delivery)
        val sent=a.state.value.messages.single { it.id==receiving.id }
        assertEquals(DeliveryState.Sent,sent.delivery)
        assertEquals(listOf("Budi"),sent.ackedBy)
        assertTrue(a.beginVoiceStream());a.cancelVoiceStream()
    }
    @Test fun cancellingPartialVoiceDoesNotProduceAnArchiveOrReceipt()=runTest {
        val (a,b)=pair()
        assertTrue(a.beginVoiceStream())
        a.sendVoiceChunk(VoiceChunk(91,0,0,5120,ByteArray(96)))
        advanceTimeBy(1000);runCurrent()
        a.cancelVoiceStream();advanceTimeBy(5000);runCurrent()
        val incoming=b.state.value.messages.last { !it.outgoing && it.kind==ChatMessageKind.Voice }
        assertEquals(DeliveryState.Failed,incoming.delivery);assertNull(incoming.attachmentData)
        assertTrue(a.state.value.messages.last { it.outgoing }.ackedBy.isEmpty())
        assertTrue(a.beginVoiceStream());a.cancelVoiceStream()
    }
}
