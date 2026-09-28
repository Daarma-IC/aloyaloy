package id.nusamesh.app.mesh.protocol

import kotlin.random.Random

/**
 * Tipe paket mesh — nilai wire identik dengan Nusa Mesh Android (BinaryProtocol.kt) dan firmware
 * NusaNode (nusa_appproto.h), sehingga HP lama, HP baru, dan node saling mengerti.
 */
enum class MessageType(val value: Int) {
    ANNOUNCE(0x01), LEAVE(0x03), MESSAGE(0x04),
    FRAGMENT_START(0x05), FRAGMENT_CONTINUE(0x06), FRAGMENT_END(0x07),
    CHANNEL_ANNOUNCE(0x08), CHANNEL_RETENTION(0x09),
    DELIVERY_ACK(0x0A), DELIVERY_STATUS_REQUEST(0x0B), READ_RECEIPT(0x0C), DELETION(0x0D),
    NOISE_HANDSHAKE_INIT(0x10), NOISE_HANDSHAKE_RESP(0x11), NOISE_ENCRYPTED(0x12), NOISE_IDENTITY_ANNOUNCE(0x13),
    CHANNEL_KEY_VERIFY_REQUEST(0x14), CHANNEL_KEY_VERIFY_RESPONSE(0x15), CHANNEL_PASSWORD_UPDATE(0x16),
    CHANNEL_METADATA(0x17), NODE_LORA_HEALTH(0x18),
    VERSION_HELLO(0x20), VERSION_ACK(0x21), FILE_TRANSFER(0x22),
    HEALTH_BROADCAST(0x30), TOPOLOGY_GOSSIP(0x31),
    NODE_REGISTER(0x40), NODE_REGISTER_ACK(0x41);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromValue(v: Int): MessageType? = byValue[v and 0xFF]
    }
}

private fun sameBytes(a: ByteArray?, b: ByteArray?) = if (a == null || b == null) a == b else a.contentEquals(b)

object SpecialRecipients {
    val BROADCAST = ByteArray(8) { 0xFF.toByte() }
    fun isBroadcast(id: ByteArray?) = id == null || id.contentEquals(BROADCAST)
}

/**
 * Paket mesh. [type] disimpan sebagai Int mentah supaya tipe yang belum dikenal tetap bisa di-relay
 * tanpa hilang (sama seperti perilaku UByte di Nusa Mesh).
 */
class WirePacket(
    val version: Int = 2,
    val type: Int,
    val senderId: ByteArray,
    val recipientId: ByteArray? = null,
    val timestamp: Long,
    val payload: ByteArray,
    val signature: ByteArray? = null,
    val ttl: Int,
) {
    val messageType: MessageType? get() = MessageType.fromValue(type)
    val senderHex: String get() = senderId.toHex()
    val recipientHex: String? get() = recipientId?.toHex()

    fun withTtl(newTtl: Int) = WirePacket(version, type, senderId, recipientId, timestamp, payload, signature, newTtl)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WirePacket) return false
        return version == other.version && type == other.type && ttl == other.ttl && timestamp == other.timestamp &&
            senderId.contentEquals(other.senderId) && sameBytes(recipientId, other.recipientId) &&
            payload.contentEquals(other.payload) && sameBytes(signature, other.signature)
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + type
        r = 31 * r + senderId.contentHashCode()
        r = 31 * r + (recipientId?.contentHashCode() ?: 0)
        r = 31 * r + timestamp.hashCode()
        r = 31 * r + payload.contentHashCode()
        r = 31 * r + (signature?.contentHashCode() ?: 0)
        r = 31 * r + ttl
        return r
    }
}

/**
 * Padding PKCS#7 ke blok 256/512/1024/2048 (tahan analisis trafik) — identik dengan MessagePadding.kt.
 * Firmware NusaNode melepas padding ini sebelum memancar ke LoRa (nusaStripPadding).
 */
object MessagePadding {
    val blockSizes = listOf(256, 512, 1024, 2048)

    fun optimalBlockSize(dataSize: Int): Int {
        val total = dataSize + 16
        return blockSizes.firstOrNull { total <= it } ?: dataSize
    }

    fun pad(data: ByteArray, targetSize: Int, random: Random = Random.Default): ByteArray {
        if (data.size >= targetSize) return data
        val needed = targetSize - data.size
        if (needed > 255) return data
        val out = ByteArray(targetSize)
        data.copyInto(out)
        random.nextBytes(out, data.size, targetSize - 1)
        out[targetSize - 1] = needed.toByte()
        return out
    }

    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty() || data.size !in blockSizes) return data
        val padLen = data.last().toInt() and 0xFF
        if (padLen <= 0 || padLen > data.size) return data
        return data.copyOfRange(0, data.size - padLen)
    }
}

/** Kompresi payload — zlib (Deflater/Inflater di Android asli). */
object Compression {
    private const val THRESHOLD = 100

    fun shouldCompress(data: ByteArray): Boolean {
        if (data.size < THRESHOLD) return false
        val unique = data.toSet().size
        return unique.toDouble() / minOf(data.size, 256).toDouble() < 0.9
    }

    fun compress(data: ByteArray): ByteArray? {
        if (data.size < THRESHOLD) return null
        return Zlib.deflate(data)?.takeIf { it.isNotEmpty() && it.size < data.size }
    }

    fun decompress(data: ByteArray, originalSize: Int): ByteArray? = Zlib.inflate(data, originalSize)
}

/** zlib per platform: java.util.zip (Android), libz sistem (iOS). Web preview: tanpa kompresi. */
expect object Zlib {
    /** Kompresi level tercepat (setara Deflater.BEST_SPEED). null bila tidak didukung/gagal. */
    fun deflate(data: ByteArray): ByteArray?
    fun inflate(data: ByteArray, originalSize: Int): ByteArray?
}

/** Encode/decode paket — format wire 100% sama dengan BinaryProtocol.kt Nusa Mesh. */
object WireProtocol {
    const val HEADER_V1 = 14
    const val HEADER_V2 = 16
    const val ID_SIZE = 8
    const val SIGNATURE_SIZE = 64

    const val FLAG_HAS_RECIPIENT = 0x01
    const val FLAG_HAS_SIGNATURE = 0x02
    const val FLAG_IS_COMPRESSED = 0x04

    fun encode(packet: WirePacket, pad: Boolean = true, random: Random = Random.Default): ByteArray {
        var payload = packet.payload
        var originalSize: Int? = null
        if (Compression.shouldCompress(payload)) {
            Compression.compress(payload)?.let {
                originalSize = payload.size
                payload = it
            }
        }
        var flags = 0
        if (packet.recipientId != null) flags = flags or FLAG_HAS_RECIPIENT
        if (packet.signature != null) flags = flags or FLAG_HAS_SIGNATURE
        if (originalSize != null) flags = flags or FLAG_IS_COMPRESSED

        val w = ByteWriter(HEADER_V2 + 2 * ID_SIZE + payload.size + SIGNATURE_SIZE + 2)
            .byte(packet.version).byte(packet.type).byte(packet.ttl)
            .long(packet.timestamp)
            .byte(flags)
        val dataSize = payload.size + if (originalSize != null) 2 else 0
        if (packet.version >= 2) w.int(dataSize) else w.short(dataSize)
        w.bytes(fixed(packet.senderId))
        packet.recipientId?.let { w.bytes(fixed(it)) }
        originalSize?.let { w.short(it) }
        w.bytes(payload)
        packet.signature?.let { w.bytes(if (it.size > SIGNATURE_SIZE) it.copyOf(SIGNATURE_SIZE) else it) }

        val raw = w.toByteArray()
        return if (pad) MessagePadding.pad(raw, MessagePadding.optimalBlockSize(raw.size), random) else raw
    }

    fun decode(data: ByteArray): WirePacket? = try {
        val d = MessagePadding.unpad(data)
        if (d.isEmpty()) null else {
            val version = d[0].toInt() and 0xFF
            val headerSize = if (version >= 2) HEADER_V2 else HEADER_V1
            if (d.size < headerSize + ID_SIZE) null else {
                val r = ByteReader(d, 1)
                val type = r.u8()
                val ttl = r.u8()
                val ts = r.s64()
                val flags = r.u8()
                val hasRecipient = flags and FLAG_HAS_RECIPIENT != 0
                val hasSignature = flags and FLAG_HAS_SIGNATURE != 0
                val compressed = flags and FLAG_IS_COMPRESSED != 0
                val payloadLen = if (version >= 2) r.s32() else r.u16()
                var expected = headerSize + ID_SIZE + payloadLen
                if (hasRecipient) expected += ID_SIZE
                if (hasSignature) expected += SIGNATURE_SIZE
                if (payloadLen < 0 || d.size < expected) null else {
                    val sender = r.bytes(ID_SIZE)
                    val recipient = if (hasRecipient) r.bytes(ID_SIZE) else null
                    val payload = if (compressed) {
                        if (payloadLen < 2) return null
                        val original = r.s16()
                        Compression.decompress(r.bytes(payloadLen - 2), original) ?: return null
                    } else r.bytes(payloadLen)
                    val signature = if (hasSignature) r.bytes(SIGNATURE_SIZE) else null
                    WirePacket(version, type, sender, recipient, ts, payload, signature, ttl)
                }
            }
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun fixed(id: ByteArray): ByteArray = when {
        id.size == ID_SIZE -> id
        id.size > ID_SIZE -> id.copyOf(ID_SIZE)
        else -> id.copyOf(ID_SIZE)
    }
}
