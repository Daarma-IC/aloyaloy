package id.nusamesh.app.media

/** Small, independently encrypted transport chunks; raw codec predictor state spans chunks. */
data class VoiceChunk(val streamId: Long, val sequence: Int, val offset: Int,
                      val samples: Int, val frames: ByteArray, val end: Boolean = false,
                      val cancelled: Boolean = false)

object VoiceStream {
    const val MIME = "application/x-nusa-voice"
    const val HEADER = 22
    const val FIRST_FRAMES = 16 // 640 ms: short first bundle, never one RF packet per codec frame.
    const val NEXT_FRAMES = 36 // 1.44 seconds, fits fallback BLE including long team name + encryption.
    const val MAX_SAMPLES = 8000 * VoiceLog.MAX_SECONDS
    const val MAX_CHUNKS = 64
    const val TIMEOUT_MS = 180_000L

    fun valid(c: VoiceChunk): Boolean {
        if (c.sequence !in 0 until MAX_CHUNKS || c.offset !in 0..MAX_SAMPLES ||
            c.offset % 320 != 0 || c.samples !in 0..12800 || c.offset + c.samples > MAX_SAMPLES ||
            c.frames.size > 240 || c.frames.size != ((c.samples + 319) / 320) * 6) return false
        if (c.cancelled) return c.end && c.samples == 0
        return (c.samples > 0 || c.end) && (c.end || c.samples % 320 == 0)
    }
    fun encode(c: VoiceChunk): ByteArray {
        require(valid(c))
        return ByteArray(HEADER + c.frames.size).also { out ->
            "NVS1".encodeToByteArray().copyInto(out)
            for (i in 0..7) out[4 + i] = (c.streamId ushr (56 - i * 8)).toByte()
            out[12] = (c.sequence ushr 8).toByte(); out[13] = c.sequence.toByte()
            for (i in 0..3) out[14 + i] = (c.offset ushr (24 - i * 8)).toByte()
            out[18] = (c.samples ushr 8).toByte(); out[19] = c.samples.toByte()
            out[20] = ((if(c.end) 1 else 0) or (if(c.cancelled) 2 else 0)).toByte(); out[21] = 2
            c.frames.copyInto(out, HEADER)
        }
    }
    fun decode(bytes: ByteArray): VoiceChunk? {
        if (bytes.size !in HEADER..HEADER+240 || bytes.copyOf(4).decodeToString() != "NVS1" || bytes[21] != 2.toByte()) return null
        fun u8(i: Int) = bytes[i].toInt() and 255
        val flags = u8(20); if (flags and 3 != flags) return null
        val id = (0..7).fold(0L) { n, i -> (n shl 8) or u8(4+i).toLong() }
        val offset = (0..3).fold(0) { n, i -> (n shl 8) or u8(14+i) }
        return VoiceChunk(id, (u8(12) shl 8) or u8(13), offset,
            (u8(18) shl 8) or u8(19), bytes.copyOfRange(HEADER, bytes.size), flags and 1 != 0, flags and 2 != 0).takeIf(::valid)
    }

    fun note(samples: Int, frames: ByteArray): ByteArray {
        require(samples > 0 && frames.size == ((samples + 319) / 320) * 6)
        return ByteArray(12 + frames.size).also { out ->
            "NC2".encodeToByteArray().copyInto(out); out[3] = 2
            for(i in 0..3) { out[4+i]=(samples ushr (24-i*8)).toByte(); out[8+i]=((frames.size/6) ushr (24-i*8)).toByte() }
            frames.copyInto(out, 12)
        }
    }
}

/** One bounded assembler per (sender, streamId), called from the controller dispatcher. */
class VoiceStreamAssembler {
    private val pending = mutableMapOf<Int, VoiceChunk>()
    private val frames = ArrayList<Byte>()
    private var next = 0
    private var offset = 0
    private var id: Long? = null
    var finished = false; private set
    var cancelled = false; private set
    val sampleCount get() = offset
    fun accept(c: VoiceChunk): List<VoiceChunk> {
        if (finished || !VoiceStream.valid(c) || (id != null && id != c.streamId) || c.sequence < next) return emptyList()
        id = c.streamId
        if(c.cancelled) { finished = true; cancelled = true; pending.clear(); return listOf(c) }
        if (pending.containsKey(c.sequence)) return emptyList()
        pending[c.sequence] = c
        val ready = mutableListOf<VoiceChunk>()
        while (true) {
            val chunk = pending.remove(next) ?: break
            if (chunk.offset != offset) { finished = true; cancelled = true; pending.clear(); break }
            next++; offset += chunk.samples; frames.addAll(chunk.frames.toList()); ready += chunk
            if (chunk.end) { finished = true; cancelled = chunk.cancelled; pending.clear(); break }
        }
        return ready
    }
    fun note(): ByteArray? = if (finished && !cancelled && offset > 0) VoiceStream.note(offset, frames.toByteArray()) else null
}

data class IncomingVoiceChunk(val key: String, val chunk: VoiceChunk, val receivedAtMs: Long,
                              val conversationId: String? = null)
