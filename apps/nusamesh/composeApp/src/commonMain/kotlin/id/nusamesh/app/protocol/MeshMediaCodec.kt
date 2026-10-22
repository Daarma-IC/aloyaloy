package id.nusamesh.app.protocol

/** Batas lampiran aplikasi. Lampiran dikirim sebagai FILE_TRANSFER (lihat mesh/protocol/FilePacket.kt). */
object MeshMediaCodec {
    /** Batas media untuk Bluetooth maupun LoRa; keduanya dikirim sebagai rangkaian fragmen. */
    const val MAX_MEDIA_BYTES = 96 * 1024

    /** Gambar profil LoRa (WebP ≤128 px, ≤1,2 KB) bernama "lora_…"; hanya gambar ini yang boleh lewat Nusa Node. */
    const val LORA_IMAGE_PREFIX = "lora_"
    const val LORA_IMAGE_MAX_BYTES = 2 * 1024

    fun isLoraImage(name: String, size: Int) = name.startsWith(LORA_IMAGE_PREFIX) && size <= LORA_IMAGE_MAX_BYTES
}
