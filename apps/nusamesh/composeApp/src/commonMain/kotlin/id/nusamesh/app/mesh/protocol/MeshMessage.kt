package id.nusamesh.app.mesh.protocol

import kotlin.random.Random

enum class MeshMessageType { Message, Audio, Image, File, SOS, SOS_Cancel }

enum class MessagePriority { Normal, High, Critical }

/**
 * Isi paket MESSAGE (0x04). Format biner identik dengan NusaMeshMessage.toBinaryPayload() Nusa Mesh
 * Android, termasuk keanehannya (lihat [decode]) supaya pesan antar-aplikasi terbaca sama.
 */
data class MeshMessage(
    val id: String = randomUuid(),
    val sender: String,
    val content: String,
    val type: MeshMessageType = MeshMessageType.Message,
    val timestampMs: Long,
    val isRelay: Boolean = false,
    val originalSender: String? = null,
    val isPrivate: Boolean = false,
    val recipientNickname: String? = null,
    val senderPeerId: String? = null,
    val mentions: List<String>? = null,
    val channel: String? = null,
    val encryptedContent: ByteArray? = null,
    val isEncrypted: Boolean = false,
    val reactions: Map<String, List<String>> = emptyMap(),
    val isVerified: Boolean = false,
    val priority: MessagePriority = MessagePriority.Normal,
    val replyToId: String? = null,
    val replyToSender: String? = null,
    val replyToContent: String? = null,
    val isPinned: Boolean = false,
    val pubkey: String? = null,
    val senderBatteryLevel: Int? = null,
) {
    val hasReactions get() = reactions.isNotEmpty()

    fun encode(): ByteArray {
        var flags = 0
        if (isRelay) flags = flags or 0x0001
        if (isPrivate) flags = flags or 0x0002
        if (originalSender != null) flags = flags or 0x0004
        if (recipientNickname != null) flags = flags or 0x0008
        if (senderPeerId != null) flags = flags or 0x0010
        if (!mentions.isNullOrEmpty()) flags = flags or 0x0020
        if (channel != null) flags = flags or 0x0040
        if (isEncrypted) flags = flags or 0x0080
        if (hasReactions) flags = flags or 0x0100
        if (isVerified) flags = flags or 0x0200
        if (senderBatteryLevel != null) flags = flags or 0x8000
        if (type == MeshMessageType.Image) flags = flags or 0x0400
        if (type == MeshMessageType.Audio) flags = flags or 0x0800
        if (type == MeshMessageType.SOS || type == MeshMessageType.SOS_Cancel) flags = flags or 0x1000
        if (isPinned) flags = flags or 0x2000
        if (replyToId != null) flags = flags or 0x4000

        val w = ByteWriter(64 + content.length)
            .short(flags)
            .long(timestampMs)
            .shortString(id)
            .shortString(sender)
        val body = if (isEncrypted && encryptedContent != null) encryptedContent else content.encodeToByteArray()
        w.int(body.size).bytes(body)
        originalSender?.let { w.shortString(it) }
        recipientNickname?.let { w.shortString(it) }
        senderPeerId?.let { w.shortString(it) }
        mentions?.let { list ->
            w.byte(minOf(list.size, 255))
            list.take(255).forEach { w.shortString(it) }
        }
        channel?.let { w.shortString(it) }
        if (hasReactions) {
            w.byte(minOf(reactions.size, 255))
            reactions.forEach { (emoji, users) ->
                w.shortString(emoji)
                w.byte(minOf(users.size, 255))
                users.take(255).forEach { w.shortString(it) }
            }
        }
        replyToId?.let { rid ->
            w.shortString(rid)
            w.shortString(replyToSender ?: "")
            val rc = (replyToContent ?: "").encodeToByteArray()
            w.int(rc.size).bytes(rc)
        }
        pubkey?.let { w.shortString(it) }
        senderBatteryLevel?.let { w.byte(it) }
        return w.toByteArray()
    }

    companion object {
        private const val MAX_CONTENT = 10 * 1024 * 1024

        /**
         * Kebalikan [encode]. Sama seperti aslinya, pubkey dibaca "best effort" TANPA memeriksa flag —
         * bila pengirim menyertakan baterai tanpa pubkey, byte baterai terbaca sebagai panjang pubkey.
         * Keanehan ini dipertahankan demi kompatibilitas.
         */
        fun decode(data: ByteArray): MeshMessage? = try {
            if (data.size < 14) null else decodeUnchecked(ByteReader(data))
        } catch (e: IndexOutOfBoundsException) {
            null
        }

        private fun decodeUnchecked(r: ByteReader): MeshMessage? {
            val flags = r.u16()
            fun f(bit: Int) = flags and bit != 0
            val isSos = f(0x1000)
            var type = when {
                isSos -> MeshMessageType.SOS
                f(0x0400) -> MeshMessageType.Image
                f(0x0800) -> MeshMessageType.Audio
                else -> MeshMessageType.Message
            }
            val ts = r.s64()
            val id = r.utf8(r.u8())
            val sender = r.utf8(r.u8())
            val contentLen = r.s32()
            if (contentLen < 0 || contentLen > MAX_CONTENT || r.remaining < contentLen) return null
            val contentBytes = r.bytes(contentLen)
            val isEncrypted = f(0x0080)
            val content = if (isEncrypted) "" else contentBytes.decodeToString()
            if (isSos && content.contains("\"action\"") && content.contains("\"cancel\"")) type = MeshMessageType.SOS_Cancel

            fun optString(present: Boolean): String? {
                if (!present || !r.hasRemaining) return null
                val len = r.u8()
                return if (r.remaining >= len) r.utf8(len) else null
            }

            val originalSender = optString(f(0x0004))
            val recipientNickname = optString(f(0x0008))
            val senderPeerId = optString(f(0x0010))
            val mentions = if (f(0x0020) && r.hasRemaining) {
                val list = mutableListOf<String>()
                repeat(r.u8()) {
                    if (r.hasRemaining) {
                        val len = r.u8()
                        if (r.remaining >= len) list += r.utf8(len)
                    }
                }
                list.ifEmpty { null }
            } else null
            val channel = optString(f(0x0040))
            val reactions = if (f(0x0100) && r.hasRemaining) {
                val map = mutableMapOf<String, List<String>>()
                repeat(r.u8()) {
                    if (r.hasRemaining) {
                        val eLen = r.u8()
                        if (r.remaining >= eLen) {
                            val emoji = r.utf8(eLen)
                            if (r.hasRemaining) {
                                val users = mutableListOf<String>()
                                repeat(r.u8()) {
                                    if (r.hasRemaining) {
                                        val uLen = r.u8()
                                        if (r.remaining >= uLen) users += r.utf8(uLen)
                                    }
                                }
                                if (users.isNotEmpty()) map[emoji] = users
                            }
                        }
                    }
                }
                map
            } else emptyMap()
            var replyToId: String? = null
            var replyToSender: String? = null
            var replyToContent: String? = null
            if (f(0x4000) && r.hasRemaining) {
                val a = r.u8(); if (r.remaining >= a) replyToId = r.utf8(a)
                if (r.hasRemaining) { val b = r.u8(); if (r.remaining >= b) replyToSender = r.utf8(b) }
                if (r.hasRemaining) { val c = r.s32(); if (c >= 0 && r.remaining >= c) replyToContent = r.utf8(c) }
            }
            var pubkey: String? = null
            if (r.hasRemaining) { val len = r.u8(); if (r.remaining >= len) pubkey = r.utf8(len) }
            val battery = if (f(0x8000) && r.hasRemaining) r.u8() else null

            return MeshMessage(
                id = id, sender = sender, content = content, type = type, timestampMs = ts,
                isRelay = f(0x0001), originalSender = originalSender, isPrivate = f(0x0002),
                recipientNickname = recipientNickname, senderPeerId = senderPeerId, mentions = mentions,
                channel = channel, encryptedContent = if (isEncrypted) contentBytes else null,
                isEncrypted = isEncrypted, reactions = reactions, isVerified = f(0x0200),
                priority = if (isSos) MessagePriority.Critical else MessagePriority.Normal,
                replyToId = replyToId, replyToSender = replyToSender, replyToContent = replyToContent,
                isPinned = f(0x2000), pubkey = pubkey, senderBatteryLevel = battery,
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is MeshMessage && id == other.id && sender == other.sender && content == other.content &&
            type == other.type && timestampMs == other.timestampMs && isPrivate == other.isPrivate &&
            senderPeerId == other.senderPeerId && isEncrypted == other.isEncrypted &&
            sameBytes(encryptedContent, other.encryptedContent)

    override fun hashCode(): Int = id.hashCode()
}

private fun sameBytes(a: ByteArray?, b: ByteArray?) = if (a == null || b == null) a == b else a.contentEquals(b)

/** UUID v4 acak dalam format teks standar (pengganti java.util.UUID). */
fun randomUuid(random: Random = Random.Default): String {
    val b = random.nextBytes(16)
    b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
    b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
    val h = b.toHex()
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}
