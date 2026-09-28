package id.nusamesh.app.mesh.protocol

/** Preview web tidak berbicara BLE; kompresi tidak diperlukan. */
actual object Zlib {
    actual fun deflate(data: ByteArray): ByteArray? = null
    actual fun inflate(data: ByteArray, originalSize: Int): ByteArray? = null
}
