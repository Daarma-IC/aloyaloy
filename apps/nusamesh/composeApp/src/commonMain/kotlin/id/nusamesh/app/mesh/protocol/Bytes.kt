package id.nusamesh.app.mesh.protocol

/** Penulis byte big-endian (pengganti java.nio.ByteBuffer yang tidak tersedia di commonMain). */
class ByteWriter(initialCapacity: Int = 256) {
    private var buf = ByteArray(initialCapacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size.coerceAtLeast(16)
        while (cap < size + extra) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun byte(v: Int): ByteWriter { ensure(1); buf[size++] = v.toByte(); return this }

    fun short(v: Int): ByteWriter { ensure(2); buf[size++] = (v ushr 8).toByte(); buf[size++] = v.toByte(); return this }

    fun int(v: Int): ByteWriter {
        ensure(4)
        for (i in 3 downTo 0) buf[size++] = (v ushr (8 * i)).toByte()
        return this
    }

    fun long(v: Long): ByteWriter {
        ensure(8)
        for (i in 7 downTo 0) buf[size++] = (v ushr (8 * i)).toByte()
        return this
    }

    fun bytes(b: ByteArray): ByteWriter { ensure(b.size); b.copyInto(buf, size); size += b.size; return this }

    /** String UTF-8 dengan prefiks panjang 1 byte (maks. 255 byte, dipotong seperti aslinya). */
    fun shortString(s: String): ByteWriter {
        val b = s.encodeToByteArray().let { if (it.size > 255) it.copyOf(255) else it }
        return byte(b.size).bytes(b)
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Pembaca byte big-endian. Semua pembacaan melempar [IndexOutOfBoundsException] bila data kurang. */
class ByteReader(private val data: ByteArray, private var pos: Int = 0) {
    val remaining get() = data.size - pos
    val hasRemaining get() = remaining > 0

    private fun need(n: Int) { if (n < 0 || remaining < n) throw IndexOutOfBoundsException("butuh $n, sisa $remaining") }

    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun s16(): Int { need(2); val v = ((data[pos].toInt() shl 8) or (data[pos + 1].toInt() and 0xFF)).toShort().toInt(); pos += 2; return v }
    fun u16(): Int = s16() and 0xFFFF
    fun s32(): Int {
        need(4)
        var v = 0
        repeat(4) { v = (v shl 8) or (data[pos++].toInt() and 0xFF) }
        return v
    }
    fun s64(): Long {
        need(8)
        var v = 0L
        repeat(8) { v = (v shl 8) or (data[pos++].toLong() and 0xFF) }
        return v
    }
    fun bytes(n: Int): ByteArray { need(n); return data.copyOfRange(pos, pos + n).also { pos += n } }
    fun utf8(n: Int): String = bytes(n).decodeToString()
}

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Sama dengan hexStringToByteArray Nusa Mesh: selalu 8 byte, sisa diisi nol, byte tak valid dilewati. */
fun peerIdBytes(hex: String): ByteArray {
    val out = ByteArray(8)
    var i = 0
    var s = hex
    while (s.length >= 2 && i < 8) {
        s.substring(0, 2).toIntOrNull(16)?.let { out[i] = it.toByte() }
        s = s.substring(2)
        i++
    }
    return out
}
