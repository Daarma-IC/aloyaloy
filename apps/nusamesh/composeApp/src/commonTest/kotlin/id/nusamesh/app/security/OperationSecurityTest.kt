package id.nusamesh.app.security

import id.nusamesh.app.mesh.protocol.EmergencyTelemetry
import id.nusamesh.app.mesh.protocol.FilePacket
import id.nusamesh.app.mesh.protocol.MeshMessage
import id.nusamesh.app.mesh.protocol.MeshMessageType
import id.nusamesh.app.mesh.protocol.WaypointTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationSecurityTest {
    private val key = OperationKey.generate()
    private val otherKey = OperationKey.generate()
    private val sender = "0a0a0a0a0a0a0a0a"

    /** Lewat format wire sungguhan: encode → decode, seperti diterima HP lain. */
    private fun overTheAir(m: MeshMessage) = assertNotNull(MeshMessage.decode(m.encode()))

    private fun msg(content: String, channel: String? = null, type: MeshMessageType = MeshMessageType.Message) =
        MeshMessage(sender = "Ayu", content = content, timestampMs = 1_759_650_000_000L, senderPeerId = sender, channel = channel, type = type)

    @Test
    fun codesAreReadableAndNormalized() {
        assertEquals(OperationKey.CODE_LENGTH, key.code.length)
        assertTrue(key.code.all { it in OperationKey.ALPHABET })
        assertEquals(24, key.displayCode.length) // 5 kelompok × 4 + 4 tanda hubung
        val typed = assertNotNull(OperationKey.fromCode(key.displayCode.lowercase().replace("-", " ")))
        assertEquals(key.keyIdHex, typed.keyIdHex, "kode diketik huruf kecil / spasi tetap kunci yang sama")
        assertNull(OperationKey.fromCode("ABCD-EFGH"))
        assertTrue(key.keyIdHex != otherKey.keyIdHex)
    }

    @Test
    fun signedMessageVerifiesAndTamperingIsDetected() {
        val original = msg(WaypointTelemetry.PREFIX + "|w1|bahaya|-754078|11044572|jembatan putus")
        val sealed = overTheAir(OperationSecurity.seal(original, key, sender))
        val opened = OperationSecurity.open(sealed, key, sender)
        assertTrue(opened.verified)
        assertEquals(original.content, opened.message.content)

        // Penyerang mengubah isi ("jembatan aman") tanpa kunci → tanda tangan gagal → ditandai palsu.
        val tampered = sealed.copy(content = sealed.content.replace("jembatan putus", "jembatan aman"))
        val forged = OperationSecurity.open(tampered, key, sender)
        assertFalse(forged.verified)
        assertTrue(forged.forged)

        // Pesan asli tapi diaku pengirim lain (replay dengan id pengirim berbeda) → gagal.
        assertTrue(OperationSecurity.open(sealed, key, "0b0b0b0b0b0b0b0b").forged)

        // HP dengan kunci operasi lain: isi tetap terbaca tapi tidak terverifikasi (bukan dianggap palsu).
        val foreign = OperationSecurity.open(sealed, otherKey, sender)
        assertFalse(foreign.verified || foreign.forged)
        assertEquals(original.content, foreign.message.content)

        // Pesan biasa (warga tanpa kunci) tetap diterima apa adanya.
        val plain = OperationSecurity.open(overTheAir(msg("halo")), key, sender)
        assertFalse(plain.verified || plain.forged)
    }

    @Test
    fun teamChannelIsEncrypted() {
        val secret = "Tim Alfa tunggu di jembatan 2"
        val sealed = overTheAir(OperationSecurity.seal(msg(secret, channel = "#tim-alfa"), key, sender))
        assertTrue(sealed.isEncrypted)
        assertFalse(sealed.encode().decodeToString(throwOnInvalidSequence = false).contains("jembatan"), "isi tidak boleh terbaca di udara")

        val opened = OperationSecurity.open(sealed, key, sender)
        assertTrue(opened.verified)
        assertEquals(secret, opened.message.content)

        val locked = OperationSecurity.open(sealed, otherKey, sender)
        assertTrue(locked.locked && !locked.verified)
        assertEquals(OperationSecurity.LOCKED_TEXT, locked.message.content)
        assertTrue(OperationSecurity.open(sealed, null, sender).locked)

        // Channel dipindah oleh penyerang (#tim-alfa → #sar) → data terautentikasi berubah → ditolak.
        assertTrue(OperationSecurity.open(sealed.copy(channel = "#sar"), key, sender).forged)
    }

    @Test
    fun signedSosStaysReadableForOldVersions() {
        val sos = EmergencyTelemetry(EmergencyTelemetry.Action.Alert, -7.54, 110.44, 8f, 1L)
        val sealed = overTheAir(OperationSecurity.seal(msg(sos.encode(), type = MeshMessageType.SOS), key, sender))
        // Versi lama membaca SOS dengan regex → tetap menemukan koordinat di dalam bungkus tanda tangan.
        assertEquals(sos.latitude, EmergencyTelemetry.decode(sealed.content)?.latitude)
        assertTrue(OperationSecurity.open(sealed, key, sender).verified)
    }

    @Test
    fun teamAttachmentsAreEncrypted() {
        val file = FilePacket("#tim-alfa~voice.c2", "audio/x-codec2", ByteArray(200) { it.toByte() })
        val sealed = OperationSecurity.sealFile(file, key)
        assertTrue(OperationSecurity.isEncryptedFile(sealed.content))
        assertTrue(sealed.content.size > file.content.size)
        val decoded = assertNotNull(FilePacket.decode(sealed.encode()))
        assertTrue(file.content.contentEquals(OperationSecurity.openFile(decoded, key)?.content))
        assertNull(OperationSecurity.openFile(decoded, otherKey))
        assertNull(OperationSecurity.openFile(decoded.copy(fileName = "#tim-bravo~voice.c2"), key), "nama file terautentikasi")
        assertEquals(file, OperationSecurity.openFile(file, key), "file biasa tidak diubah")
    }

    @Test
    fun base64UrlRoundTrip() {
        for (size in 0..20) {
            val bytes = ByteArray(size) { (it * 37 + 11).toByte() }
            val text = OperationSecurity.base64Url(bytes)
            assertFalse('|' in text || '=' in text)
            assertTrue(bytes.contentEquals(OperationSecurity.base64UrlDecode(text)))
        }
        assertNull(OperationSecurity.base64UrlDecode("ab|c"))
    }
}
