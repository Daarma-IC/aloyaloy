package id.nusamesh.app.media

import id.nusamesh.app.domain.ChatAttachment

/** Hasil AI super-resolution: gambar ×4 (PNG), model yang dipakai, dan waktu prosesnya di HP ini. */
class EnhancedImage(val bytes: ByteArray, val model: String, val millis: Long)

/** Waktu proses satu model di HP ini (null bila gagal dimuat). */
data class SrSpeed(val model: String, val millis: Long?, val sizeKb: Int, val error: String? = null)

/**
 * Pilihan kualitas gambar lewat Nusa Node: sisi terpanjang dan batas byte hasil kompresi.
 * [estimate] dihitung dari airtime SF7 dengan jatah 4% per HP, satu hop.
 */
enum class LoraImageQuality(val label: String, val maxSide: Int, val maxBytes: Int, val estimate: String) {
    Fast("Cepat", 160, 2000, "±1 menit"),
    Balanced("Seimbang", 256, 4000, "±2 menit"),
    Detail("Detail", 384, 8000, "±5 menit"),
}

/** Jembatan picker, recorder, dan player supaya UI bersama tidak bergantung pada platform. */
interface ChatMediaActions {
    val supportsVoiceStreaming: Boolean get() = false
    fun startVoiceStream(onChunk: (VoiceChunk) -> Unit, onError: (String) -> Unit) = onError("Stream suara belum didukung perangkat ini")
    fun stopVoiceStream(onRecorded: (ChatAttachment) -> Unit, onError: (String) -> Unit) = onError("Tidak ada stream aktif")
    fun cancelVoiceStream() = Unit
    fun setLiveVoiceListening(enabled: Boolean) = Unit
    fun receiveVoiceChunk(event: IncomingVoiceChunk) = Unit
    /** [loraQuality] diisi bila pesan akan lewat Nusa Node: gambar dikecilkan sesuai profil itu; null = jalur Bluetooth. */
    fun pickImage(loraQuality: LoraImageQuality?, onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit)
    fun pickFile(onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit)

    /** Ubah gambar biasa ke profil LoRa [quality]; dipakai bila Nusa Node tersambung saat gambar dipilih. */
    fun toLoraImage(attachment: ChatAttachment, quality: LoraImageQuality, onResult: (ChatAttachment) -> Unit, onError: (String) -> Unit) = onResult(attachment)
    fun startVoiceNote(onError: (String) -> Unit)
    fun stopVoiceNote(onRecorded: (ChatAttachment) -> Unit, onError: (String) -> Unit)
    /** Putar voice note. [onStarted] membawa durasi (ms), [onFinished] dipanggil saat selesai/dihentikan. */
    fun playVoiceNote(
        bytes: ByteArray,
        mimeType: String,
        onStarted: (durationMs: Long) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit,
    )
    fun stopVoicePlayback()

    /** CSV catatan encode/decode Codec2 di HP ini (lihat [VoiceLog]); kosong bila tidak didukung. */
    fun voiceLogCsv(): String = VoiceLog.HEADER + "\n"

    /**
     * Perjelas gambar KECIL ×4 dengan AI di HP ini (profil LoRa ≤128 px sekali jalan; ≤384 px dipotong-potong
     * dengan [onProgress]). Gambar besar ditolak: sudah tajam, AI hanya mengarang. Model sesuai kecepatan HP.
     * Hasilnya tebakan model — UI wajib menandainya "diperjelas AI".
     */
    fun enhanceImage(
        bytes: ByteArray,
        onProgress: (done: Int, total: Int) -> Unit,
        onResult: (EnhancedImage) -> Unit,
        onError: (String) -> Unit,
    ) = onError("AI perjelas gambar tersedia di aplikasi Android")

    /** Ukur waktu semua model AI di HP ini (data uji TA); [onResult] membawa model yang dipilih otomatis. */
    fun benchmarkSuperResolution(onResult: (List<SrSpeed>, String) -> Unit, onError: (String) -> Unit) =
        onError("Uji AI tersedia di aplikasi Android")
}

object UnavailableChatMediaActions : ChatMediaActions {
    override fun pickImage(loraQuality: LoraImageQuality?, onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit) =
        onError("Pemilih gambar tersedia di aplikasi Android/iOS")

    override fun pickFile(onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit) =
        onError("Pemilih file tersedia di aplikasi Android/iOS")

    override fun startVoiceNote(onError: (String) -> Unit) =
        onError("Perekam suara tersedia di aplikasi Android/iOS")

    override fun stopVoiceNote(onRecorded: (ChatAttachment) -> Unit, onError: (String) -> Unit) =
        onError("Belum ada rekaman aktif")

    override fun playVoiceNote(
        bytes: ByteArray,
        mimeType: String,
        onStarted: (durationMs: Long) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit,
    ) = onError("Pemutar voice note tersedia di aplikasi Android/iOS")

    override fun stopVoicePlayback() = Unit
}
