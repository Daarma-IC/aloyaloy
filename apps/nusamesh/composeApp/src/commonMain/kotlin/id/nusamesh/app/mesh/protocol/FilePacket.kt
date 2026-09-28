package id.nusamesh.app.mesh.protocol

/**
 * Payload FILE_TRANSFER (0x22) — TLV sama dengan NusameshFilePacket di Nusa Mesh Android:
 * 0x01 nama (len 2 B), 0x02 ukuran (len 2 B, nilai UInt32), 0x03 MIME (len 2 B), 0x04 isi (len 4 B).
 * Semua big-endian. Paket utuh dipecah lapisan fragmen seperti paket lain.
 */
data class FilePacket(
    val fileName: String,
    val mimeType: String,
    val content: ByteArray,
) {
    fun encode(): ByteArray {
        val name = fileName.encodeToByteArray()
        val mime = mimeType.encodeToByteArray()
        require(name.size <= 0xFFFF && mime.size <= 0xFFFF) { "Nama/MIME terlalu panjang" }
        val out = ByteArray(3 + name.size + 7 + 3 + mime.size + 5 + content.size)
        var o = 0
        fun put8(v: Int) { out[o++] = v.toByte() }
        fun put16(v: Int) { put8(v ushr 8); put8(v) }
        fun put32(v: Int) { put16(v ushr 16); put16(v) }
        fun putBytes(b: ByteArray) { b.copyInto(out, o); o += b.size }
        put8(0x01); put16(name.size); putBytes(name)
        put8(0x02); put16(4); put32(content.size)
        put8(0x03); put16(mime.size); putBytes(mime)
        put8(0x04); put32(content.size); putBytes(content)
        return out
    }

    companion object {
        fun decode(data: ByteArray): FilePacket? {
            var o = 0
            var name: String? = null
            var mime: String? = null
            var content: ByteArray? = null
            fun u8(i: Int) = data[i].toInt() and 0xFF
            while (o < data.size) {
                val t = u8(o++)
                if (t !in 0x01..0x04) break
                val len = if (t == 0x04) {
                    if (o + 4 > data.size) return null
                    (u8(o) shl 24) or (u8(o + 1) shl 16) or (u8(o + 2) shl 8) or u8(o + 3)
                } else {
                    if (o + 2 > data.size) return null
                    (u8(o) shl 8) or u8(o + 1)
                }
                o += if (t == 0x04) 4 else 2
                if (len < 0 || o + len > data.size) return null
                val v = data.copyOfRange(o, o + len)
                o += len
                when (t) {
                    0x01 -> name = v.decodeToString()
                    0x03 -> mime = v.decodeToString()
                    0x04 -> content = content?.plus(v) ?: v
                }
            }
            return FilePacket(name ?: return null, mime ?: "application/octet-stream", content ?: return null)
        }
    }
}
