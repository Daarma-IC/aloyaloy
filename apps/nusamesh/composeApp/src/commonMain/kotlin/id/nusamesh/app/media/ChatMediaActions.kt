package id.nusamesh.app.media

import id.nusamesh.app.domain.ChatAttachment

/** Jembatan picker, recorder, dan player supaya UI bersama tidak bergantung pada platform. */
interface ChatMediaActions {
    fun pickImage(onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit)
    fun pickFile(onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit)
    fun startVoiceNote(onError: (String) -> Unit)
    fun stopVoiceNote(onRecorded: (ChatAttachment) -> Unit, onError: (String) -> Unit)
    fun playVoiceNote(bytes: ByteArray, mimeType: String, onError: (String) -> Unit)
}

object UnavailableChatMediaActions : ChatMediaActions {
    override fun pickImage(onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit) =
        onError("Pemilih gambar tersedia di aplikasi Android/iOS")

    override fun pickFile(onPicked: (ChatAttachment) -> Unit, onError: (String) -> Unit) =
        onError("Pemilih file tersedia di aplikasi Android/iOS")

    override fun startVoiceNote(onError: (String) -> Unit) =
        onError("Perekam suara tersedia di aplikasi Android/iOS")

    override fun stopVoiceNote(onRecorded: (ChatAttachment) -> Unit, onError: (String) -> Unit) =
        onError("Belum ada rekaman aktif")

    override fun playVoiceNote(bytes: ByteArray, mimeType: String, onError: (String) -> Unit) =
        onError("Pemutar voice note belum tersedia di platform ini")
}
