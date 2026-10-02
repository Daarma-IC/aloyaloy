package id.nusamesh.app.media

object AndroidCodec2 {
    const val MIME = "audio/x-codec2"
    const val EXTENSION = "c2"
    const val SAMPLE_RATE = 8_000

    init { System.loadLibrary("nusamesh_codec2") }

    external fun encode(pcm: ShortArray): ByteArray
    external fun decode(encoded: ByteArray): ShortArray
}
