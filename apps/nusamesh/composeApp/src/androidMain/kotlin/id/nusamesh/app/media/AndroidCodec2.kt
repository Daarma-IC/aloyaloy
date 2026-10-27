package id.nusamesh.app.media

object AndroidCodec2 {
    const val MIME = "audio/x-codec2"
    const val EXTENSION = "c2"
    const val SAMPLE_RATE = 8_000

    init { System.loadLibrary("nusamesh_codec2") }

    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.isNotEmpty()) { "PCM kosong" }
        return requireNotNull(encodeNative(pcm)) { "Codec2 encode gagal" }.also {
            require(it.size == Codec2Format.encodedSize(pcm.size) && Codec2Format.samples(it) == pcm.size)
        }
    }

    fun decode(encoded: ByteArray): ShortArray {
        val count = requireNotNull(Codec2Format.samples(encoded)) { "Data Codec2 tidak valid" }
        return requireNotNull(decodeNative(encoded)) { "Codec2 decode gagal" }.also { require(it.size == count) }
    }

    private external fun encodeNative(pcm: ShortArray): ByteArray?
    private external fun decodeNative(encoded: ByteArray): ShortArray?
    private external fun streamCreate(): Long
    private external fun streamDestroy(handle: Long)
    private external fun streamEncode(handle: Long, pcm: ShortArray): ByteArray?
    private external fun streamDecode(handle: Long, data: ByteArray): ShortArray?

    /** Owned by one capture/playback worker. Never reset the predictor between chunks. */
    class Session : AutoCloseable {
        private var handle = streamCreate().also { check(it != 0L) { "Codec2 session gagal" } }
        @Synchronized fun encode(pcm: ShortArray): ByteArray {
            check(handle != 0L)
            require(pcm.isNotEmpty() && pcm.size % Codec2Format.SAMPLES_PER_FRAME == 0)
            return requireNotNull(streamEncode(handle, pcm)) { "Codec2 stream encode gagal" }
        }
        @Synchronized fun decode(frames: ByteArray): ShortArray {
            check(handle != 0L)
            require(frames.isNotEmpty() && frames.size % 6 == 0 && frames.size <= 240)
            return requireNotNull(streamDecode(handle, frames)) { "Codec2 stream decode gagal" }
        }
        @Synchronized override fun close() { if (handle != 0L) { streamDestroy(handle); handle = 0 } }
    }
}
