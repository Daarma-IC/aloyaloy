package id.nusamesh.app.media

import id.nusamesh.app.domain.ChatAttachment

/** Hasil AI super-resolution: gambar ×4 (PNG), model yang dipakai, dan waktu prosesnya di HP ini. */
class EnhancedImage(val bytes: ByteArray, val model: String, val millis: Long)

/** Waktu proses satu model di HP ini (null bila gagal dimuat). */
data class SrSpeed(val model: String, val millis: Long?, val sizeKb: Int, val error: String? = null)

/** Jembatan picker, recorder, dan player supaya UI bersama tidak bergantung pada platform. */
interface ChatMediaActions {
    /** [loraProfile] = true bila pesan akan lewat Nusa Node: gambar dikecilkan ke profil LoRa (≤ 1,2 KB). */
    fun pickImage(loraProfile: Boolean, onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit)
    fun pickFile(onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit)
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

    /**
     * Perjelas gambar KECIL ×4 dengan AI di HP ini (profil LoRa ≤128 px sekali jalan; ≤256 px dipotong-potong
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
    override fun pickImage(loraProfile: Boolean, onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit) =
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
