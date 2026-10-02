package id.nusamesh.app.protocol

/** Batas lampiran aplikasi. Lampiran dikirim sebagai FILE_TRANSFER (lihat mesh/protocol/FilePacket.kt). */
object MeshMediaCodec {
    /** Batas media untuk Bluetooth maupun LoRa; keduanya dikirim sebagai rangkaian fragmen. */
    const val MAX_MEDIA_BYTES = 96 * 1024
}
