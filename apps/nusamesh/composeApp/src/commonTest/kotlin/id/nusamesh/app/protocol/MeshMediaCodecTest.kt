package id.nusamesh.app.protocol

import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessageKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeshMediaCodecTest {
    @Test
    fun fragmentsAndReassemblesImageWithoutChangingBytes() {
        val original = ByteArray(370) { (it % 251).toByte() }
        val chunks = MeshMediaCodec.encode(
            ChatAttachment("foto.jpg", "image/jpeg", original, ChatMessageKind.Image),
            messageId = 42L,
        )
        val reassembler = MeshMediaReassembler()
        var decoded: DecodedMedia? = null
        chunks.forEachIndexed { index, chunk ->
            val result = reassembler.accept(
                NusaPacket(chunk.packetType, 1L, ByteArray(8), chunk.payload),
            )
            if (index < chunks.lastIndex) assertNull(result) else decoded = result
        }
        assertEquals("foto.jpg", decoded?.name)
        assertEquals(ChatMessageKind.Image, decoded?.kind)
        assertContentEquals(original, decoded?.bytes)
    }
}
