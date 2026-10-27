package id.nusamesh.app.media

import id.nusamesh.app.mesh.core.Fragmenter
import id.nusamesh.app.mesh.core.NodeQueue
import id.nusamesh.app.mesh.protocol.*
import id.nusamesh.app.security.*
import kotlin.test.*

class VoiceStreamTest {
    private fun chunk(seq: Int, end: Boolean = false) = VoiceChunk(42, seq, seq*5120, 5120, ByteArray(96) { (it+seq).toByte() }, end)
    @Test fun firstChunkIsAvailableBeforeTheVoiceNoteAndOrderSurvivesReordering() {
        val a = VoiceStreamAssembler()
        assertEquals(listOf(0), a.accept(chunk(0)).map { it.sequence })
        assertNull(a.note())
        assertTrue(a.accept(chunk(2,true)).isEmpty())
        assertEquals(listOf(1,2),a.accept(chunk(1)).map { it.sequence })
        assertEquals(15360, Codec2Format.samples(assertNotNull(a.note())))
        assertTrue(a.accept(chunk(0)).isEmpty())
    }
    @Test fun holesNeverProduceACompletedVoiceOrAdvanceTheDecoder() {
        val a=VoiceStreamAssembler()
        assertTrue(a.accept(chunk(1,true)).isEmpty())
        assertFalse(a.finished); assertNull(a.note())
        assertEquals(listOf(0,1),a.accept(chunk(0)).map { it.sequence })
        assertNotNull(a.note())
    }
    @Test fun cancellationClosesImmediatelyEvenWithMissingPriorChunks() {
        val a=VoiceStreamAssembler(); a.accept(chunk(0))
        val cancel=VoiceChunk(42,10,51200,0,ByteArray(0),end=true,cancelled=true)
        assertEquals(listOf(cancel),a.accept(cancel)); assertTrue(a.finished && a.cancelled); assertNull(a.note())
    }
    @Test fun rejectsMalformedGeometryAndInconsistentOffsets() {
        val raw=VoiceStream.encode(chunk(0))
        assertEquals(42L,assertNotNull(VoiceStream.decode(raw)).streamId)
        assertNull(VoiceStream.decode(raw.copyOf(raw.size-1)))
        assertNull(VoiceStream.decode(raw.copyOf().also { it[21]=1 }))
        assertFalse(VoiceStream.valid(chunk(0).copy(samples=1)))
        val a=VoiceStreamAssembler();a.accept(chunk(0));a.accept(chunk(1).copy(offset=10240))
        assertTrue(a.finished && a.cancelled);assertNull(a.note())
    }
    @Test fun partialFinalFrameKeepsExactDuration() {
        val a=VoiceStreamAssembler()
        a.accept(VoiceChunk(7,0,0,321,ByteArray(12),end=true))
        assertEquals(321,Codec2Format.samples(assertNotNull(a.note())))
    }
    @Test fun chunksKeepSecurityAndAvoidPaddingDrivenBleFragmentation() {
        val key=assertNotNull(OperationKey.fromCode("K7Q2M9XAP3RTW8YHC4NB"))
        val c=VoiceChunk(42,0,0,VoiceStream.NEXT_FRAMES*320,ByteArray(VoiceStream.NEXT_FRAMES*6))
        val file=FilePacket("#tim-"+"a".repeat(20)+"~v0123456789abcdef",VoiceStream.MIME,VoiceStream.encode(c))
        val sealed=OperationSecurity.sealFile(file,key)
        assertNull(OperationSecurity.openFile(sealed,null))
        val opened=assertNotNull(OperationSecurity.openFile(sealed,key))
        assertContentEquals(file.content,opened.content)
        val p=WirePacket(type=0x22,senderId=ByteArray(8),recipientId=SpecialRecipients.BROADCAST,
            timestamp=1,payload=sealed.encode(),ttl=3,voicePriority=true)
        val raw=WireProtocol.encode(p)
        assertTrue(raw.size<=400,"Largest encrypted team chunk must fit fallback BLE packet: ${raw.size}")
        assertEquals(1,Fragmenter(now={0}).split(p).size)
        assertEquals(2,NodeQueue.priorityOf(raw))
        assertEquals(p,WireProtocol.decode(raw))
    }
    @Test fun unpaddedVoiceAtLegacyBlockSizesCannotBeAccidentallyUnpadded() {
        for(size in listOf(256,512,1024)) {
            val p=WirePacket(type=0x22,senderId=ByteArray(8),timestamp=1,payload=ByteArray(size-24) { 7 },ttl=1,voicePriority=true)
            val raw=WireProtocol.encode(p)
            assertEquals(size+1,raw.size)
            assertEquals(p,WireProtocol.decode(raw))
        }
    }
}
