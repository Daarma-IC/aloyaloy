package id.nusamesh.app.mesh.engine

import id.nusamesh.app.mesh.protocol.FilePacket
import id.nusamesh.app.mesh.protocol.MeshMessage
import id.nusamesh.app.mesh.protocol.MeshMessageType
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageKindsTest {
    private fun message(content: String, type: MeshMessageType = MeshMessageType.Message) =
        MeshMessage(sender = "a", content = content, type = type, timestampMs = 0)

    @Test
    fun labelsMessageKinds() {
        assertEquals("teks", MessageKinds.of(message("halo")))
        assertEquals("sos", MessageKinds.of(message("tolong", MeshMessageType.SOS)))
        assertEquals("telemetri-qos", MessageKinds.of(message("@meshta-qos-v1|run|1|10|7|||uji")))
    }

    @Test
    fun labelsFileKinds() {
        assertEquals("suara", MessageKinds.ofFile(FilePacket("v.c2", "audio/x-codec2", ByteArray(1))))
        assertEquals("gambar", MessageKinds.ofFile(FilePacket("lora_1.webp", "image/webp", ByteArray(1))))
        assertEquals("file", MessageKinds.ofFile(FilePacket("a.pdf", "application/pdf", ByteArray(1))))
    }
}
