package id.nusamesh.app.mesh.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireProtocolTest {
    private val sender = ByteArray(8) { 0x11 }
    private val recipient = ByteArray(8) { 0x22 }

    @Test
    fun encodesExactLayoutUsedByNusaMeshAndFirmware() {
        val p = WirePacket(type = 0x04, senderId = sender, recipientId = recipient,
            timestamp = 0x0102030405060708, payload = "hi".encodeToByteArray(), ttl = 7)
        val raw = WireProtocol.encode(p, pad = false)
        val expected = (
            "02" + "04" + "07" + "0102030405060708" + "01" + "00000002" +  // header v2
                "1111111111111111" + "2222222222222222" + "6869"             // sender, recipient, payload
            )
        assertEquals(expected, raw.toHex())
        // Firmware membaca senderID di offset 16 dan recipient di 24 (nusa_frame.h, nusaAppPeek).
        assertContentEquals(sender, raw.copyOfRange(16, 24))
        assertContentEquals(recipient, raw.copyOfRange(24, 32))
    }

    @Test
    fun paddedPacketIsBlockSizedAndRoundTrips() {
        val p = WirePacket(type = 0x01, senderId = sender, timestamp = 42, payload = "Budi~".encodeToByteArray(), ttl = 3)
        val enc = WireProtocol.encode(p, random = Random(1))
        assertEquals(256, enc.size)
        assertEquals(p, WireProtocol.decode(enc))
    }

    @Test
    fun decodesVersion1Header() {
        // v1: panjang payload 2 byte, header 14 byte.
        val hex = "01" + "04" + "03" + "0000000000000001" + "00" + "0002" + "1111111111111111" + "6f6b"
        val p = assertNotNull(WireProtocol.decode(hex.hexToBytes()))
        assertEquals(1, p.version)
        assertEquals("ok", p.payload.decodeToString())
    }

    @Test
    fun signatureAndUnknownTypeSurvive() {
        val sig = ByteArray(64) { it.toByte() }
        val p = WirePacket(type = 0x7E, senderId = sender, timestamp = 9, payload = byteArrayOf(1, 2, 3), signature = sig, ttl = 1)
        val back = assertNotNull(WireProtocol.decode(WireProtocol.encode(p)))
        assertEquals(0x7E, back.type)
        assertNull(back.messageType)
        assertContentEquals(sig, back.signature)
    }

    @Test
    fun rejectsTruncatedPackets() {
        val enc = WireProtocol.encode(WirePacket(type = 4, senderId = sender, timestamp = 1, payload = ByteArray(40), ttl = 1), pad = false)
        assertNull(WireProtocol.decode(enc.copyOf(enc.size - 5)))
        assertNull(WireProtocol.decode(ByteArray(0)))
    }

    @Test
    fun compressiblePayloadRoundTripsWhenPlatformSupportsZlib() {
        val text = ("Laporan: jalan utama tertutup longsor, air bersih menipis. ").repeat(8).encodeToByteArray()
        val p = WirePacket(type = 4, senderId = sender, timestamp = 5, payload = text, ttl = 7)
        val enc = WireProtocol.encode(p, pad = false)
        if (Zlib.deflate(text) != null) {
            assertTrue(enc.size < text.size, "payload berulang harus terkompresi")
            assertEquals(WireProtocol.FLAG_IS_COMPRESSED, enc[11].toInt() and WireProtocol.FLAG_IS_COMPRESSED)
        }
        assertContentEquals(text, assertNotNull(WireProtocol.decode(enc)).payload)
    }

    @Test
    fun peerIdHexMatchesNusaMeshConversion() {
        assertEquals("a1b2c3d4e5f60708", peerIdBytes("a1b2c3d4e5f60708").toHex())
        assertEquals("abcd000000000000", peerIdBytes("abcd").toHex())
    }
}

class MeshMessageTest {
    @Test
    fun roundTripsAllOptionalFields() {
        val m = MeshMessage(
            id = "id-1", sender = "Budi", content = "Tolong, air naik", timestampMs = 1_700_000_000_000,
            isPrivate = true, recipientNickname = "Rina", senderPeerId = "a1b2c3d4e5f60708",
            mentions = listOf("posko"), channel = "#rt3", reactions = mapOf("👍" to listOf("Rina")),
            isVerified = true, replyToId = "id-0", replyToSender = "Rina", replyToContent = "ada kabar?",
            isPinned = true, pubkey = "pk",
        )
        val back = assertNotNull(MeshMessage.decode(m.encode()))
        assertEquals(m, back)
        assertEquals(listOf("posko"), back.mentions)
        assertEquals(mapOf("👍" to listOf("Rina")), back.reactions)
        assertEquals("ada kabar?", back.replyToContent)
        assertEquals("pk", back.pubkey)
        assertTrue(back.isPinned && back.isVerified)
    }

    @Test
    fun sosCancelIsDetectedFromContent() {
        val m = MeshMessage(sender = "A", content = "{\"action\":\"cancel\"}", type = MeshMessageType.SOS_Cancel, timestampMs = 1)
        val back = assertNotNull(MeshMessage.decode(m.encode()))
        assertEquals(MeshMessageType.SOS_Cancel, back.type)
        assertEquals(MessagePriority.Critical, back.priority)
    }

    @Test
    fun keepsOriginalBatteryWithoutPubkeyQuirk() {
        // Perilaku asli Nusa Mesh: tanpa pubkey, byte baterai dibaca sebagai panjang pubkey.
        val m = MeshMessage(sender = "A", content = "x", timestampMs = 1, senderBatteryLevel = 0)
        val back = assertNotNull(MeshMessage.decode(m.encode()))
        assertEquals("", back.pubkey)
        assertNull(back.senderBatteryLevel)
    }

    @Test
    fun uuidLooksLikeV4() {
        val u = randomUuid()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(u), u)
    }
}

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class FilePacketTest {
    @Test
    fun roundTripMatchesNusaMeshLayout() {
        val f = FilePacket("suara.m4a", "audio/mp4", byteArrayOf(1, 2, 3))
        val bytes = f.encode()
        // TLV nama: 0x01, panjang 2 byte, lalu isi.
        kotlin.test.assertEquals(0x01, bytes[0].toInt())
        kotlin.test.assertEquals(9, bytes[2].toInt())
        val back = FilePacket.decode(bytes)!!
        kotlin.test.assertEquals("suara.m4a", back.fileName)
        kotlin.test.assertEquals("audio/mp4", back.mimeType)
        kotlin.test.assertTrue(back.content.contentEquals(byteArrayOf(1, 2, 3)))
    }
}
