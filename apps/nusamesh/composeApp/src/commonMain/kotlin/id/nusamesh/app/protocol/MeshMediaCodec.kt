package id.nusamesh.app.protocol

import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessageKind

data class MediaChunk(
    val packetType: PacketType,
    val payload: ByteArray,
)

data class DecodedMedia(
    val id: Long,
    val name: String,
    val mimeType: String,
    val kind: ChatMessageKind,
    val bytes: ByteArray,
)

/** Format lampiran aplikasi. Node hanya meneruskan byte dan tidak membaca isi media. */
object MeshMediaCodec {
    const val MAX_MEDIA_BYTES = 96 * 1024
    private const val CHUNK_BYTES = 120
    private const val HEADER_BYTES = 18
    private const val VERSION = 1

    fun encode(attachment: ChatAttachment, messageId: Long): List<MediaChunk> {
        require(attachment.bytes.isNotEmpty()) { "Lampiran kosong" }
        require(attachment.bytes.size <= MAX_MEDIA_BYTES) { "Lampiran maksimal 96 KB untuk jaringan LoRa" }
        val name = attachment.name.encodeToByteArray().take(80).toByteArray()
        val mime = attachment.mimeType.encodeToByteArray().take(60).toByteArray()
        val count = (attachment.bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        return List(count) { index ->
            val start = index * CHUNK_BYTES
            val end = minOf(start + CHUNK_BYTES, attachment.bytes.size)
            val data = attachment.bytes.copyOfRange(start, end)
            val payload = ByteArray(HEADER_BYTES + name.size + mime.size + data.size)
            payload[0] = 'N'.code.toByte()
            payload[1] = 'M'.code.toByte()
            payload[2] = VERSION.toByte()
            payload[3] = attachment.kind.wire.toByte()
            payload.writeLong(4, messageId)
            payload.writeShort(12, index)
            payload.writeShort(14, count)
            payload[16] = name.size.toByte()
            payload[17] = mime.size.toByte()
            name.copyInto(payload, HEADER_BYTES)
            mime.copyInto(payload, HEADER_BYTES + name.size)
            data.copyInto(payload, HEADER_BYTES + name.size + mime.size)
            MediaChunk(
                packetType = when {
                    count == 1 -> PacketType.File
                    index == 0 -> PacketType.FragmentStart
                    index == count - 1 -> PacketType.FragmentEnd
                    else -> PacketType.FragmentContinue
                },
                payload = payload,
            )
        }
    }

    private val ChatMessageKind.wire: Int
        get() = when (this) {
            ChatMessageKind.Image -> 1
            ChatMessageKind.Voice -> 2
            ChatMessageKind.File -> 3
            ChatMessageKind.Text -> 0
        }
}

class MeshMediaReassembler {
    private data class Pending(
        val name: String,
        val mimeType: String,
        val kind: ChatMessageKind,
        val count: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
    )

    private val pending = mutableMapOf<Long, Pending>()

    fun accept(packet: NusaPacket): DecodedMedia? {
        if (packet.type !in setOf(PacketType.File, PacketType.FragmentStart, PacketType.FragmentContinue, PacketType.FragmentEnd)) return null
        val payload = packet.payload
        if (payload.size < 18 || payload[0] != 'N'.code.toByte() || payload[1] != 'M'.code.toByte() || payload[2].toInt() != 1) return null
        val id = payload.readLong(4)
        val index = payload.readUnsignedShort(12)
        val count = payload.readUnsignedShort(14)
        val nameLength = payload[16].toInt() and 0xFF
        val mimeLength = payload[17].toInt() and 0xFF
        val dataOffset = 18 + nameLength + mimeLength
        if (count !in 1..1024 || index !in 0 until count || dataOffset > payload.size) return null
        val name = payload.copyOfRange(18, 18 + nameLength).decodeToString()
        val mime = payload.copyOfRange(18 + nameLength, dataOffset).decodeToString()
        val kind = when (payload[3].toInt() and 0xFF) {
            1 -> ChatMessageKind.Image
            2 -> ChatMessageKind.Voice
            else -> ChatMessageKind.File
        }
        val item = pending.getOrPut(id) { Pending(name, mime, kind, count) }
        if (item.count != count || item.name != name || item.mimeType != mime) {
            pending.remove(id)
            return null
        }
        item.chunks[index] = payload.copyOfRange(dataOffset, payload.size)
        if (item.chunks.size != count) return null
        val bytes = ByteArray(item.chunks.values.sumOf { it.size })
        var offset = 0
        repeat(count) { chunkIndex ->
            val chunk = item.chunks[chunkIndex] ?: return null
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        pending.remove(id)
        return DecodedMedia(id, item.name, item.mimeType, item.kind, bytes)
    }
}

private fun ByteArray.writeShort(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.readUnsignedShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
