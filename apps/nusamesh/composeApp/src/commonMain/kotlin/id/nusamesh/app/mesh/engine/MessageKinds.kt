package id.nusamesh.app.mesh.engine

import id.nusamesh.app.mesh.protocol.FilePacket
import id.nusamesh.app.mesh.protocol.MeshMessage
import id.nusamesh.app.mesh.protocol.MeshMessageType

/**
 * Label jenis dan prioritas pesan untuk log. Uji lapangan 12 Nov: log hanya membedakan pesan vs file,
 * jadi suara/gambar/lokasi/SOS dan prioritas teks tidak bisa dinilai (Tabel 1.3 CD-2).
 */
internal object MessageKinds {
    private const val TELEMETRY_PREFIX = "@meshta-"

    /** teks, sos, sos_batal, atau telemetri-<nama> (mis. telemetri-loc dari "@meshta-loc-v1|..."). */
    fun of(message: MeshMessage): String = when {
        message.type == MeshMessageType.SOS -> "sos"
        message.type == MeshMessageType.SOS_Cancel -> "sos_batal"
        message.content.startsWith(TELEMETRY_PREFIX) ->
            "telemetri-" + message.content.removePrefix(TELEMETRY_PREFIX).substringBefore('|').substringBefore("-v")
        else -> "teks"
    }

    fun ofFile(file: FilePacket): String = when {
        file.mimeType.startsWith("audio/") -> "suara"
        file.mimeType.startsWith("image/") -> "gambar"
        else -> "file"
    }

    fun detail(message: MeshMessage): String =
        "kind=${of(message)}; priority=${message.priority.name.lowercase()}"

    fun fileDetail(file: FilePacket): String =
        "kind=${ofFile(file)}; mime=${file.mimeType}"
}
