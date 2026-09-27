package id.nusamesh.app.data

import id.nusamesh.app.ble.MeshTransport
import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.MeshHealth
import id.nusamesh.app.protocol.MeshMediaCodec
import id.nusamesh.app.protocol.NusaPacket
import id.nusamesh.app.protocol.NusaProtocol
import id.nusamesh.app.protocol.PacketType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.delay

class MeshRepository(private val transport: MeshTransport) {
    val connection = transport.connection
    val nearby = transport.nearby
    val packets: Flow<NusaPacket> = transport.incomingPackets.mapNotNull { NusaProtocol.decode(it).getOrNull() }

    fun connect() = transport.startScanAndConnect()
    fun disconnect() = transport.disconnect()

    suspend fun announce(alias: String, peerId: ByteArray): Result<Unit> = send(
        type = PacketType.Announce,
        peerId = peerId,
        payload = "$alias~".encodeToByteArray(),
    )

    suspend fun sendMessage(text: String, peerId: ByteArray, recipient: ByteArray? = null): Result<Unit> = send(
        type = PacketType.Message,
        peerId = peerId,
        payload = text.encodeToByteArray(),
        recipient = recipient,
    )

    suspend fun sendAttachment(attachment: ChatAttachment, peerId: ByteArray): Result<Unit> = runCatching {
        val chunks = MeshMediaCodec.encode(attachment, currentEpochMillis())
        chunks.forEachIndexed { index, chunk ->
            send(chunk.packetType, peerId, chunk.payload).getOrThrow()
            if (index < chunks.lastIndex) delay(45)
        }
    }

    private suspend fun send(
        type: PacketType,
        peerId: ByteArray,
        payload: ByteArray,
        recipient: ByteArray? = null,
    ): Result<Unit> = transport.send(
        NusaProtocol.encode(
            NusaPacket(type, currentEpochMillis(), peerId, payload, recipientId = recipient),
        ),
    )

    fun healthOf(packet: NusaPacket): MeshHealth? {
        if (packet.type != PacketType.LoraHealth || packet.payload.size < 3) return null
        val rssi = packet.payload[1].toInt()
        val snrRaw = packet.payload[2].toInt()
        return MeshHealth(
            neighborCount = packet.payload[0].toInt() and 0xFF,
            bestRssi = rssi.takeUnless { it == -128 },
            bestSnr = snrRaw.takeUnless { it == -128 }?.div(2f),
        )
    }
}

expect fun currentEpochMillis(): Long
