package id.nusamesh.app.mesh.protocol

/**
 * Envelope potongan voice note. Satu voice note boleh lebih besar dari batas satu FILE_TRANSFER;
 * pengirim mengirim potongan secara berurutan dan penerima menyatukannya sebelum ditampilkan.
 */
data class VoiceSegment(
    val transferId: String,
    val fileName: String,
    val mimeType: String,
    val durationSeconds: Int,
    val index: Int,
    val total: Int,
    val totalBytes: Int,
    val content: ByteArray,
) {
    fun encode(): ByteArray {
        val id = transferId.encodeToByteArray()
        val name = fileName.encodeToByteArray()
        val mime = mimeType.encodeToByteArray()
        require(id.isNotEmpty() && id.size <= 64)
        require(name.isNotEmpty() && name.size <= 0xffff)
        require(mime.isNotEmpty() && mime.size <= 0xffff)
        require(index in 0 until total && total in 1..MAX_SEGMENTS)
        require(totalBytes > 0 && totalBytes <= MAX_ASSEMBLED_BYTES)
        val out = ByteArray(HEADER_FIXED_BYTES + id.size + name.size + mime.size + content.size)
        MAGIC.copyInto(out)
        var p = MAGIC.size
        out[p++] = id.size.toByte()
        p = out.putU16(p, name.size)
        p = out.putU16(p, mime.size)
        p = out.putI32(p, durationSeconds.coerceAtLeast(0))
        p = out.putI32(p, index)
        p = out.putI32(p, total)
        p = out.putI32(p, totalBytes)
        id.copyInto(out, p); p += id.size
        name.copyInto(out, p); p += name.size
        mime.copyInto(out, p); p += mime.size
        content.copyInto(out, p)
        return out
    }

    companion object {
        const val MIME = "application/x-nusamesh-voice-segment"
        /** Aman di bawah batas LoRa 96 KB, termasuk metadata dan enkripsi channel. */
        const val CHUNK_BYTES = 48 * 1024
        const val MAX_SEGMENTS = 4096
        const val MAX_ASSEMBLED_BYTES = 128 * 1024 * 1024
        private val MAGIC = byteArrayOf(0x4e, 0x53, 0x56, 0x32) // NSV2
        private const val HEADER_FIXED_BYTES = 4 + 1 + 2 + 2 + 4 + 4 + 4 + 4

        fun split(
            transferId: String,
            fileName: String,
            mimeType: String,
            durationSeconds: Int,
            bytes: ByteArray,
        ): List<VoiceSegment> {
            require(bytes.isNotEmpty())
            require(bytes.size <= MAX_ASSEMBLED_BYTES) { "Voice note melebihi kapasitas aman perangkat" }
            val total = (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
            require(total <= MAX_SEGMENTS)
            return List(total) { index ->
                val start = index * CHUNK_BYTES
                val end = minOf(start + CHUNK_BYTES, bytes.size)
                VoiceSegment(transferId, fileName, mimeType, durationSeconds, index, total, bytes.size, bytes.copyOfRange(start, end))
            }
        }

        fun decode(bytes: ByteArray): VoiceSegment? = runCatching {
            if (bytes.size < HEADER_FIXED_BYTES || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
            var p = MAGIC.size
            val idSize = bytes[p++].toInt() and 0xff
            val nameSize = bytes.u16(p); p += 2
            val mimeSize = bytes.u16(p); p += 2
            val duration = bytes.i32(p); p += 4
            val index = bytes.i32(p); p += 4
            val total = bytes.i32(p); p += 4
            val totalBytes = bytes.i32(p); p += 4
            require(idSize in 1..64 && nameSize > 0 && mimeSize > 0)
            require(index in 0 until total && total in 1..MAX_SEGMENTS)
            require(totalBytes > 0 && totalBytes <= MAX_ASSEMBLED_BYTES)
            require(p + idSize + nameSize + mimeSize <= bytes.size)
            val id = bytes.copyOfRange(p, p + idSize).decodeToString(); p += idSize
            val name = bytes.copyOfRange(p, p + nameSize).decodeToString(); p += nameSize
            val mime = bytes.copyOfRange(p, p + mimeSize).decodeToString(); p += mimeSize
            val content = bytes.copyOfRange(p, bytes.size)
            require(content.isNotEmpty() && content.size <= CHUNK_BYTES)
            VoiceSegment(id, name, mime, duration, index, total, totalBytes, content)
        }.getOrNull()
    }
}

data class AssembledVoice(val file: FilePacket, val durationSeconds: Int, val timestampMs: Long)

/** Penyatu toleran urutan dan duplikat; transfer mangkrak dibuang setelah 24 jam. */
class VoiceTransferAssembler(private val now: () -> Long) {
    private data class Pending(
        val prototype: VoiceSegment,
        val timestampMs: Long,
        var updatedAtMs: Long,
        val parts: Array<ByteArray?>,
        var receivedBytes: Int = 0,
    )

    private val pending = mutableMapOf<String, Pending>()

    fun accept(fromPeerId: String, segment: VoiceSegment, timestampMs: Long): AssembledVoice? {
        val current = now()
        pending.entries.removeAll { current - it.value.updatedAtMs > EXPIRE_MS }
        val key = "$fromPeerId:${segment.transferId}"
        val state = pending.getOrPut(key) {
            Pending(segment, timestampMs, current, arrayOfNulls(segment.total))
        }
        if (!state.prototype.compatibleWith(segment)) {
            pending.remove(key)
            return null
        }
        state.updatedAtMs = current
        if (state.parts[segment.index] == null) {
            state.parts[segment.index] = segment.content
            state.receivedBytes += segment.content.size
        }
        if (state.parts.any { it == null }) return null
        if (state.receivedBytes != segment.totalBytes) {
            pending.remove(key)
            return null
        }
        val all = ByteArray(segment.totalBytes)
        var offset = 0
        state.parts.forEach { part ->
            part!!.copyInto(all, offset)
            offset += part.size
        }
        pending.remove(key)
        return AssembledVoice(
            FilePacket(segment.fileName, segment.mimeType, all),
            segment.durationSeconds,
            state.timestampMs,
        )
    }

    private fun VoiceSegment.compatibleWith(other: VoiceSegment) =
        transferId == other.transferId && fileName == other.fileName && mimeType == other.mimeType &&
            durationSeconds == other.durationSeconds && total == other.total && totalBytes == other.totalBytes

    private companion object { const val EXPIRE_MS = 24 * 60 * 60 * 1000L }
}

private fun ByteArray.putU16(offset: Int, value: Int): Int {
    this[offset] = (value ushr 8).toByte(); this[offset + 1] = value.toByte(); return offset + 2
}
private fun ByteArray.putI32(offset: Int, value: Int): Int {
    this[offset] = (value ushr 24).toByte(); this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte(); this[offset + 3] = value.toByte(); return offset + 4
}
private fun ByteArray.u16(offset: Int) = ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
private fun ByteArray.i32(offset: Int) =
    ((this[offset].toInt() and 0xff) shl 24) or ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or (this[offset + 3].toInt() and 0xff)
