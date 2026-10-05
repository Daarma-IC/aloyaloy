package id.nusamesh.app.security

import id.nusamesh.app.mesh.protocol.FilePacket
import id.nusamesh.app.mesh.protocol.MeshMessage

/**
 * Kunci operasi: kode acak ±100 bit yang dibuat posko dan dibagikan LANGSUNG saat briefing (dibacakan /
 * ditulis), tidak pernah lewat jaringan. Semua HP tim dengan kode yang sama bisa:
 *  - menandatangani pesannya (HMAC) → penerima tahu pesan benar dari tim, bukan orang luar;
 *  - mengenkripsi channel tim & operasional (ChaCha20-Poly1305) → orang luar tidak bisa membaca.
 *
 * Batasan: kunci bersama, jadi sesama pemegang kunci bisa saling menyamar; bila HP tim hilang, posko
 * membuat kunci baru dan membagikannya ulang.
 */
class OperationKey private constructor(val code: String, private val authKey: ByteArray, private val encKey: ByteArray, val keyId: ByteArray) {
    /** "K7Q2-M9XA-P3RT-W8YH-C4NB": dibacakan per kelompok 4. */
    val displayCode: String get() = code.chunked(4).joinToString("-")
    val keyIdHex: String get() = keyId.toHex()

    internal fun mac(data: ByteArray) = Sha256.hmac(authKey, data).copyOf(TAG_BYTES)
    internal fun seal(nonce: ByteArray, plaintext: ByteArray, aad: ByteArray) = ChaCha20Poly1305.seal(encKey, nonce, plaintext, aad)
    internal fun open(nonce: ByteArray, sealed: ByteArray, aad: ByteArray) = ChaCha20Poly1305.open(encKey, nonce, sealed, aad)

    companion object {
        /** Tanpa I, O, 0, 1 supaya tidak tertukar saat dibacakan lewat HT. */
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 20 // 20 × 5 bit = 100 bit
        const val TAG_BYTES = 12

        fun generate(): OperationKey {
            val bytes = secureRandomBytes(CODE_LENGTH)
            return fromCode(CharArray(CODE_LENGTH) { ALPHABET[bytes[it].toInt() and 31] }.concatToString())!!
        }

        /** null bila kode terlalu pendek (setelah membuang spasi/tanda hubung). */
        fun fromCode(input: String): OperationKey? {
            val code = input.uppercase().filter { it in ALPHABET }
            if (code.length < CODE_LENGTH) return null
            val master = Sha256.hash("nusamesh-op-v1:$code".encodeToByteArray())
            return OperationKey(
                code,
                authKey = Sha256.hmac(master, "auth".encodeToByteArray()),
                encKey = Sha256.hmac(master, "enc".encodeToByteArray()),
                keyId = Sha256.hmac(master, "id".encodeToByteArray()).copyOf(3),
            )
        }
    }
}

/** Hasil membuka pesan masuk. */
data class OpenedMessage(
    val message: MeshMessage,
    /** Tanda tangan / enkripsi valid dengan kunci operasi kita. */
    val verified: Boolean,
    /** Mengaku bertanda tangan kunci kita tapi tidak valid: kemungkinan pemalsuan → buang. */
    val forged: Boolean = false,
    /** Terenkripsi dengan kunci yang tidak kita punya. */
    val locked: Boolean = false,
)

object OperationSecurity {
    const val AUTH_PREFIX = "@meshta-auth-v1"
    private val ENCRYPTED_MAGIC = "NME1".encodeToByteArray()
    private val FILE_MAGIC = "NMF1".encodeToByteArray()
    const val LOCKED_TEXT = "Pesan terenkripsi — perlu kunci operasi yang sama"

    /**
     * Siapkan pesan keluar: channel tim/operasional dienkripsi, selebihnya ditandatangani. Tanpa kunci,
     * pesan dikirim apa adanya (perilaku lama).
     */
    fun seal(message: MeshMessage, key: OperationKey?, senderPeerId: String): MeshMessage {
        key ?: return message
        if (message.channel != null) {
            val nonce = secureRandomBytes(ChaCha20Poly1305.NONCE_SIZE)
            val sealed = key.seal(nonce, message.content.encodeToByteArray(), aad(senderPeerId, message))
            return message.copy(content = "", isEncrypted = true, encryptedContent = ENCRYPTED_MAGIC + key.keyId + nonce + sealed)
        }
        val tag = key.mac(macInput(senderPeerId, message, message.content))
        return message.copy(content = "$AUTH_PREFIX|${key.keyIdHex}|${base64Url(tag)}|${message.content}")
    }

    fun open(message: MeshMessage, key: OperationKey?, senderPeerId: String): OpenedMessage {
        val encrypted = message.encryptedContent
        if (message.isEncrypted && encrypted != null && encrypted.startsWith(ENCRYPTED_MAGIC)) {
            val header = ENCRYPTED_MAGIC.size + 3 + ChaCha20Poly1305.NONCE_SIZE
            val keyId = encrypted.copyOfRange(ENCRYPTED_MAGIC.size, ENCRYPTED_MAGIC.size + 3)
            val plaintext = if (key != null && encrypted.size > header && keyId.contentEquals(key.keyId)) {
                key.open(encrypted.copyOfRange(header - ChaCha20Poly1305.NONCE_SIZE, header), encrypted.copyOfRange(header, encrypted.size), aad(senderPeerId, message))
            } else null
            return when {
                plaintext != null -> OpenedMessage(message.copy(content = plaintext.decodeToString(), isEncrypted = false, encryptedContent = null), verified = true)
                key != null && keyId.contentEquals(key.keyId) -> OpenedMessage(message, verified = false, forged = true)
                else -> OpenedMessage(message.copy(content = LOCKED_TEXT), verified = false, locked = true)
            }
        }
        if (!message.content.startsWith("$AUTH_PREFIX|")) return OpenedMessage(message, verified = false)
        val parts = message.content.split('|', limit = 4)
        if (parts.size != 4) return OpenedMessage(message, verified = false)
        val inner = message.content.substring(parts[0].length + parts[1].length + parts[2].length + 3)
        val unwrapped = message.copy(content = inner)
        if (key == null || parts[1] != key.keyIdHex) return OpenedMessage(unwrapped, verified = false)
        val valid = constantTimeEquals(key.mac(macInput(senderPeerId, message, inner)), base64UrlDecode(parts[2]) ?: ByteArray(0))
        return OpenedMessage(unwrapped, verified = valid, forged = !valid)
    }

    /** Lampiran di channel tim: isi file dienkripsi; nama (berisi tag channel) jadi data terautentikasi. */
    fun sealFile(file: FilePacket, key: OperationKey?): FilePacket {
        key ?: return file
        val nonce = secureRandomBytes(ChaCha20Poly1305.NONCE_SIZE)
        return file.copy(content = FILE_MAGIC + key.keyId + nonce + key.seal(nonce, file.content, file.fileName.encodeToByteArray()))
    }

    /** null = file terenkripsi yang tidak bisa dibuka (kunci beda / rusak); file biasa dikembalikan apa adanya. */
    fun openFile(file: FilePacket, key: OperationKey?): FilePacket? {
        val content = file.content
        if (!content.startsWith(FILE_MAGIC)) return file
        val header = FILE_MAGIC.size + 3 + ChaCha20Poly1305.NONCE_SIZE
        if (key == null || content.size <= header || !content.copyOfRange(FILE_MAGIC.size, FILE_MAGIC.size + 3).contentEquals(key.keyId)) return null
        val plain = key.open(content.copyOfRange(header - ChaCha20Poly1305.NONCE_SIZE, header), content.copyOfRange(header, content.size), file.fileName.encodeToByteArray())
        return plain?.let { file.copy(content = it) }
    }

    fun isEncryptedFile(content: ByteArray) = content.startsWith(FILE_MAGIC)

    private fun aad(senderPeerId: String, m: MeshMessage) = "${senderPeerId.lowercase()}|${m.id}|${m.timestampMs}|${m.channel}".encodeToByteArray()
    private fun macInput(senderPeerId: String, m: MeshMessage, content: String) =
        "${senderPeerId.lowercase()}|${m.id}|${m.timestampMs}|${m.channel}|${m.type}|$content".encodeToByteArray()

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Base64url tanpa padding (tag 12 byte → 16 karakter, tanpa '|'). */
    fun base64Url(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            append(B64[b0 ushr 2]); append(B64[((b0 and 3) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) append(B64[((b1 and 15) shl 2) or (b2 ushr 6)])
            if (i + 2 < bytes.size) append(B64[b2 and 63])
            i += 3
        }
    }

    fun base64UrlDecode(text: String): ByteArray? {
        val values = text.map { B64.indexOf(it).takeIf { v -> v >= 0 } ?: return null }
        if (values.size % 4 == 1) return null
        val out = ArrayList<Byte>(values.size * 3 / 4)
        var i = 0
        while (i < values.size) {
            val v0 = values[i]; val v1 = values.getOrElse(i + 1) { 0 }; val v2 = values.getOrElse(i + 2) { 0 }; val v3 = values.getOrElse(i + 3) { 0 }
            out += ((v0 shl 2) or (v1 ushr 4)).toByte()
            if (i + 2 < values.size) out += (((v1 and 15) shl 4) or (v2 ushr 2)).toByte()
            if (i + 3 < values.size) out += (((v2 and 3) shl 6) or v3).toByte()
            i += 4
        }
        return out.toByteArray()
    }
}

internal fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
