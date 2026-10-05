package id.nusamesh.app.mesh.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceTransferTest {
    @Test
    fun segmentCodecRoundTrips() {
        val original = ByteArray(321) { (it * 17).toByte() }
        val segment = VoiceSegment("transfer-1", "#sar|voice.c2", "audio/x-codec2", 42, 1, 3, 800, original)
        val decoded = VoiceSegment.decode(segment.encode())!!
        assertEquals(segment.transferId, decoded.transferId)
        assertEquals(segment.fileName, decoded.fileName)
        assertEquals(segment.mimeType, decoded.mimeType)
        assertEquals(segment.durationSeconds, decoded.durationSeconds)
        assertEquals(segment.index, decoded.index)
        assertContentEquals(original, decoded.content)
    }

    @Test
    fun assemblerJoinsOutOfOrderAndIgnoresDuplicate() {
        var now = 10_000L
        val bytes = ByteArray(VoiceSegment.CHUNK_BYTES * 2 + 77) { (it * 31).toByte() }
        val segments = VoiceSegment.split("long-note", "voice.c2", "audio/x-codec2", 600, bytes)
        val assembler = VoiceTransferAssembler { now++ }
        assertNull(assembler.accept("peer-a", segments[2], 123L))
        assertNull(assembler.accept("peer-a", segments[0], 124L))
        assertNull(assembler.accept("peer-a", segments[0], 124L))
        val complete = assembler.accept("peer-a", segments[1], 125L)!!
        assertEquals("voice.c2", complete.file.fileName)
        assertEquals(600, complete.durationSeconds)
        assertEquals(123L, complete.timestampMs)
        assertContentEquals(bytes, complete.file.content)
    }

    @Test
    fun malformedEnvelopeIsRejected() {
        assertNull(VoiceSegment.decode(byteArrayOf(1, 2, 3)))
        assertNull(VoiceSegment.decode(ByteArray(30)))
    }
}
