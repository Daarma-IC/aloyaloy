package id.nusamesh.app.media

/** NC2 voice-note geometry; codec frames are bundled, never sent one LoRa packet per frame. */
object Codec2Format {
    const val SAMPLE_RATE = 8000
    const val HEADER_BYTES = 12
    const val SAMPLES_PER_FRAME = 320
    const val BYTES_PER_FRAME = 6
    const val BYTES_PER_SECOND = 150

    fun encodedSize(samples: Int): Int {
        require(samples > 0)
        return (HEADER_BYTES + ((samples.toLong() + SAMPLES_PER_FRAME - 1) / SAMPLES_PER_FRAME) * BYTES_PER_FRAME).toInt()
    }

    /** Returns exact sample count, rejecting truncation, extra frames and oversized declarations. */
    fun samples(bytes: ByteArray): Int? {
        if (bytes.size < HEADER_BYTES || bytes[0] != 'N'.code.toByte() ||
            bytes[1] != 'C'.code.toByte() || bytes[2] != '2'.code.toByte()) return null
        val (spf, bpf) = when (bytes[3].toInt()) { 1 -> 160 to 8; 2 -> 320 to 6; else -> return null }
        fun u32(at: Int): Long = (0..3).fold(0L) { n, i -> (n shl 8) or (bytes[at + i].toLong() and 255) }
        val count = u32(4)
        val frames = u32(8)
        if (count !in 1..Int.MAX_VALUE.toLong() || frames != (count + spf - 1) / spf ||
            HEADER_BYTES + frames * bpf != bytes.size.toLong()) return null
        return count.toInt()
    }
}
