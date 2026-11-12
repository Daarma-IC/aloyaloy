package id.nusamesh.app.media

/**
 * Waktu pengolahan media (Codec2, kompres gambar LoRa, AI upscale) ke log transmisi. Uji lapangan
 * 12 Nov: parameter "waktu pengolahan" CD-2 kosong karena tidak ada event-nya di log.
 * Platform memanggil [report]; AppController memasang [sink] ke log diagnostik.
 */
object ProcessingEvents {
    const val CODEC2_ENCODE = "CODEC2_ENCODE"
    const val CODEC2_DECODE = "CODEC2_DECODE"
    const val IMAGE_LORA_ENCODE = "IMAGE_LORA_ENCODE"
    const val AI_UPSCALE = "AI_UPSCALE"

    fun interface Sink {
        fun record(name: String, durationMs: Long, bytes: Int?, detail: String)
    }

    var sink: Sink? = null

    fun report(name: String, durationMs: Long, bytes: Int? = null, detail: String = "") {
        sink?.record(name, durationMs, bytes, detail)
    }
}
