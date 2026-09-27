package id.nusamesh.app.protocol

import kotlin.random.Random

enum class PacketType(val wire: Int) {
    Announce(0x01), Message(0x04), FragmentStart(0x05), FragmentContinue(0x06),
    FragmentEnd(0x07), DeliveryAck(0x0A), ReadReceipt(0x0C), NoiseInit(0x10),
    NoiseResponse(0x11), NoiseEncrypted(0x12), NoiseIdentity(0x13),
    LoraHealth(0x18), File(0x22), Health(0x30), Topology(0x31), Unknown(-1);

    companion object { fun fromWire(value: Int) = entries.firstOrNull { it.wire == value } ?: Unknown }
}

data class NusaPacket(
    val type: PacketType,
    val timestampMs: Long,
    val senderId: ByteArray,
    val payload: ByteArray,
    val ttl: Int = 3,
    val recipientId: ByteArray? = null,
)

object NusaProtocol {
    private const val VERSION = 2
    private const val HEADER_BYTES = 16
    private const val ID_BYTES = 8

    fun newPeerId(): ByteArray = Random.nextBytes(ID_BYTES)

    fun encode(packet: NusaPacket): ByteArray {
        require(packet.type != PacketType.Unknown) { "Tipe paket tidak dikenal" }
        require(packet.senderId.size == ID_BYTES) { "senderId harus 8 byte" }
        require(packet.recipientId == null || packet.recipientId.size == ID_BYTES) { "recipientId harus 8 byte" }

        val flags = if (packet.recipientId != null) 0x01 else 0x00
        val idsSize = if (packet.recipientId != null) ID_BYTES * 2 else ID_BYTES
        return ByteArray(HEADER_BYTES + idsSize + packet.payload.size).also { out ->
            out[0] = VERSION.toByte()
            out[1] = packet.type.wire.toByte()
            out[2] = packet.ttl.coerceIn(0, 255).toByte()
            out.writeLong(3, packet.timestampMs)
            out[11] = flags.toByte()
            out.writeInt(12, packet.payload.size)
            packet.senderId.copyInto(out, HEADER_BYTES)
            packet.recipientId?.copyInto(out, HEADER_BYTES + ID_BYTES)
            packet.payload.copyInto(out, HEADER_BYTES + idsSize)
        }
    }

    fun decode(bytes: ByteArray): Result<NusaPacket> = runCatching {
        require(bytes.size >= HEADER_BYTES + ID_BYTES) { "Paket terlalu pendek" }
        require(bytes[0].toInt() and 0xFF == VERSION) { "Versi protokol tidak didukung" }
        val flags = bytes[11].toInt() and 0xFF
        val hasRecipient = flags and 0x01 != 0
        val idsSize = if (hasRecipient) ID_BYTES * 2 else ID_BYTES
        val payloadSize = bytes.readInt(12)
        require(payloadSize >= 0 && bytes.size >= HEADER_BYTES + idsSize + payloadSize) { "Panjang payload tidak valid" }
        NusaPacket(
            type = PacketType.fromWire(bytes[1].toInt() and 0xFF),
            ttl = bytes[2].toInt() and 0xFF,
            timestampMs = bytes.readLong(3),
            senderId = bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + ID_BYTES),
            recipientId = if (hasRecipient) bytes.copyOfRange(HEADER_BYTES + ID_BYTES, HEADER_BYTES + ID_BYTES * 2) else null,
            payload = bytes.copyOfRange(HEADER_BYTES + idsSize, HEADER_BYTES + idsSize + payloadSize),
        )
    }
}

internal fun ByteArray.writeInt(offset: Int, value: Int) {
    for (index in 0 until 4) this[offset + index] = (value ushr (24 - index * 8)).toByte()
}

internal fun ByteArray.writeLong(offset: Int, value: Long) {
    for (index in 0 until 8) this[offset + index] = (value ushr (56 - index * 8)).toByte()
}

internal fun ByteArray.readInt(offset: Int): Int {
    var value = 0
    for (index in 0 until 4) value = (value shl 8) or (this[offset + index].toInt() and 0xFF)
    return value
}

internal fun ByteArray.readLong(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) value = (value shl 8) or (this[offset + index].toLong() and 0xFF)
    return value
}

fun ByteArray.toPeerId(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

