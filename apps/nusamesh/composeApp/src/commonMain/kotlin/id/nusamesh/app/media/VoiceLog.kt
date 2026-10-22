package id.nusamesh.app.media

/**
 * Catatan pengolahan voice note untuk pengujian Codec2 (Tabel 3.2): ukuran PCM vs Codec2, waktu buang-hening,
 * encode, dan decode dicatat terpisah dari waktu kirim, plus SHA-256 berkas Codec2 untuk mencocokkan keutuhan
 * di HP pengirim dan penerima.
 */
data class VoiceLogEntry(
    val timeUtc: String,
    /** "encode" (HP pengirim) atau "decode" (saat diputar). */
    val event: String,
    val recordedMs: Long?,
    val voicedMs: Long,
    val pcmBytes: Int,
    val codec2Bytes: Int,
    val trimMs: Long?,
    val processMs: Long,
    val sha256: String,
)

object VoiceLog {
    /** Profil suara: maksimal 10 detik rekaman (PCM 8 kHz 16 bit = 160.000 byte). */
    const val MAX_SECONDS = 10
    const val HEADER = "time_utc,event,recorded_ms,voiced_ms,pcm_bytes,codec2_bytes,compression_ratio,trim_ms,process_ms,sha256"

    fun csvLine(e: VoiceLogEntry): String = listOf(
        e.timeUtc, e.event, e.recordedMs ?: "", e.voicedMs, e.pcmBytes, e.codec2Bytes,
        if (e.codec2Bytes > 0) ((e.pcmBytes.toDouble() / e.codec2Bytes) * 10).toLong() / 10.0 else "",
        e.trimMs ?: "", e.processMs, e.sha256,
    ).joinToString(",")
}
