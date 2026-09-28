package id.nusamesh.app.protocol

/** Batas lampiran aplikasi. Lampiran dikirim sebagai FILE_TRANSFER (lihat mesh/protocol/FilePacket.kt). */
object MeshMediaCodec {
    /** Batas lewat Bluetooth (dipecah fragmen). Lewat LoRa dibatasi MeshEngine.LORA_FILE_MAX_BYTES. */
    const val MAX_MEDIA_BYTES = 96 * 1024
}
