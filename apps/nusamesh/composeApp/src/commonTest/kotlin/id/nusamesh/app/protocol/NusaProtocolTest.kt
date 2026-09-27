package id.nusamesh.app.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NusaProtocolTest {
    @Test
    fun v2PacketMatchesFirmwareLayout() {
        val original = NusaPacket(
            type = PacketType.Message,
            timestampMs = 1_700_000_000_123,
            senderId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            payload = "halo mesh".encodeToByteArray(),
        )

        val bytes = NusaProtocol.encode(original)
        val decoded = NusaProtocol.decode(bytes).getOrThrow()

        assertEquals(2, bytes[0].toInt())
        assertEquals(0x04, bytes[1].toInt())
        assertEquals(original.timestampMs, decoded.timestampMs)
        assertContentEquals(original.senderId, decoded.senderId)
        assertContentEquals(original.payload, decoded.payload)
    }
}

