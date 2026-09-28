package id.nusamesh.app.media

import id.nusamesh.app.domain.ChatAttachment

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
