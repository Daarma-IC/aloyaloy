package id.nusamesh.app.data

import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.mesh.engine.BleLink
import id.nusamesh.app.mesh.engine.EngineSnapshot
import id.nusamesh.app.mesh.engine.IncomingAck
import id.nusamesh.app.mesh.engine.IncomingFile
import id.nusamesh.app.mesh.engine.IncomingMessage
import id.nusamesh.app.mesh.engine.MeshEngine
import id.nusamesh.app.mesh.engine.MobilityLogSink
import id.nusamesh.app.mesh.mobility.MobilityConfig
import id.nusamesh.app.mesh.protocol.FilePacket
import id.nusamesh.app.mesh.protocol.MeshMessage
import id.nusamesh.app.mesh.protocol.LocationTelemetry
import id.nusamesh.app.mesh.protocol.EmergencyTelemetry
import id.nusamesh.app.mesh.protocol.MeshMessageType
import id.nusamesh.app.security.OperationKey
import id.nusamesh.app.security.OperationSecurity
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/** Satu baris log mobility untuk panel uji (juga bisa diekspor sebagai CSV). */
data class MobilityLogEntry(
    val timeMs: Long,
    val event: String,
    val from: String?,
    val to: String?,
    val servingRssi: Int?,
    val targetRssi: Int?,
    val valueMs: Long?,
    val detail: String,
) {
    fun csv() = listOf(timeMs, event, from.orEmpty(), to.orEmpty(), servingRssi ?: "", targetRssi ?: "", valueMs ?: "", detail)
        .joinToString(",")

    companion object { const val CSV_HEADER = "time_ms,event,from,to,serving_rssi,target_rssi,value_ms,detail" }
}

/**
 * Pintu aplikasi ke [MeshEngine]: identitas persisten (peerID, nama panggilan) dan penerjemahan
 * lampiran ke FILE_TRANSFER. Engine berjalan di dispatcher satu-jalur sendiri.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshRepository(link: BleLink, private val store: KeyValueStore) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val myPeerId: String = store.get(KEY_PEER_ID)?.takeIf { it.length == 16 } ?: randomPeerId().also { store.put(KEY_PEER_ID, it) }

    private val _mobilityLog = MutableStateFlow<List<MobilityLogEntry>>(emptyList())
    val mobilityLog: StateFlow<List<MobilityLogEntry>> = _mobilityLog.asStateFlow()

    private val engine = MeshEngine(
        link = link,
        myPeerId = myPeerId,
        scope = engineScope,
        now = ::currentEpochMillis,
        mobilityLog = MobilityLogSink { event, from, to, servingRssi, targetRssi, valueMs, detail ->
            if (!mobilityConfig.loggingEnabled) return@MobilityLogSink
            val entry = MobilityLogEntry(currentEpochMillis(), event, from, to, servingRssi, targetRssi, valueMs, detail)
            _mobilityLog.update { (it + entry).takeLast(MAX_LOG) }
        },
    )

    val snapshot: StateFlow<EngineSnapshot> = engine.snapshot
    /**
     * Kunci operasi aktif (null = tanpa keamanan, perilaku lama). Pesan keluar ditandatangani / channel
     * tim dienkripsi; pesan masuk dibuka & diperiksa sebelum sampai ke controller.
     */
    var operationKey: OperationKey? = null

    val messages: Flow<IncomingMessage> = engine.messages.map { incoming ->
        val opened = OperationSecurity.open(incoming.message, operationKey, incoming.fromPeerId)
        incoming.copy(message = opened.message, verified = opened.verified, forged = opened.forged)
    }
    val files: Flow<IncomingFile> = engine.files.map { incoming ->
        if (!OperationSecurity.isEncryptedFile(incoming.file.content)) return@map incoming
        val opened = OperationSecurity.openFile(incoming.file, operationKey)
        if (opened != null) incoming.copy(file = opened, verified = true) else incoming.copy(locked = true)
    }

    private fun publish(message: MeshMessage, onComplete: (Boolean) -> Unit = {}) =
        engine.sendPublic(OperationSecurity.seal(message, operationKey, myPeerId), onComplete)
    val acks: Flow<IncomingAck> = engine.acks

    fun sendAck(toPeerId: String, messageId: String) { engine.sendAck(toPeerId, messageId) }

    fun sendRadioConfig(nodePeerId: String, spreadingFactor: Int) { engine.sendRadioConfig(nodePeerId, spreadingFactor) }

    var nickname: String = store.get(KEY_NICKNAME) ?: "Meshta-${myPeerId.takeLast(4).uppercase()}"
        set(value) {
            field = value.trim().take(24).ifBlank { field }
            store.put(KEY_NICKNAME, field)
            engine.nickname = field
        }

    var mobilityConfig: MobilityConfig
        get() = engine.mobilityConfig
        set(value) { engine.mobilityConfig = value }

    init { engine.nickname = nickname }

    fun start() { engine.start() }
    fun stop() { engine.stop() }
    fun lockNode(peerId: String?) { engine.lockNode(peerId) }
    fun clearMobilityLog() = _mobilityLog.update { emptyList() }

    /** [channel] null = global; "#sar" / "#tim-…" = channel operasi (lihat FieldChannels). */
    fun sendText(text: String, channel: String? = null, onComplete: (Boolean) -> Unit = {}): MeshMessage {
        val message = MeshMessage(sender = nickname, content = text, timestampMs = currentEpochMillis(), senderPeerId = myPeerId, channel = channel)
        publish(message, onComplete)
        return message
    }

    /** [encrypt] = lampiran channel tim: isi dienkripsi kunci operasi (bila ada). */
    fun sendAttachment(
        attachment: ChatAttachment,
        encrypt: Boolean = false,
        onProgress: (Float) -> Unit = {},
        onComplete: (Boolean) -> Unit = {},
    ) {
        val file = FilePacket(attachment.name, attachment.mimeType, attachment.bytes)
        engine.sendFile(
            if (encrypt) OperationSecurity.sealFile(file, operationKey) else file,
            onProgress,
            onComplete,
        )
    }

    fun sendLocation(latitude: Double, longitude: Double, accuracyMeters: Float, timestampMs: Long, batteryPercent: Int? = null) {
        val content = LocationTelemetry(latitude, longitude, accuracyMeters, timestampMs, batteryPercent).encode()
        publish(MeshMessage(sender = nickname, content = content, timestampMs = timestampMs, senderPeerId = myPeerId))
    }

    /** Telemetri teks berawalan (jejak, rute, waypoint): pesan publik biasa, jadi ikut lewat LoRa. */
    /** @return id pesan (untuk mencocokkan konfirmasi terima). */
    fun sendTelemetry(content: String): String {
        val message = MeshMessage(sender = nickname, content = content, timestampMs = currentEpochMillis(), senderPeerId = myPeerId)
        publish(message)
        return message.id
    }

    /** @return id pesan SOS (untuk mencocokkan konfirmasi terima). */
    fun sendEmergency(telemetry: EmergencyTelemetry): String {
        val message = MeshMessage(
            sender = nickname,
            content = telemetry.encode(),
            type = if (telemetry.action == EmergencyTelemetry.Action.Alert) MeshMessageType.SOS else MeshMessageType.SOS_Cancel,
            timestampMs = telemetry.timestampMs,
            senderPeerId = myPeerId,
        )
        publish(message)
        return message.id
    }

    fun nicknameOf(peerId: String): String? = snapshot.value.peers.firstOrNull { it.peerId == peerId }?.nickname

    private companion object {
        const val KEY_PEER_ID = "peer_id"
        const val KEY_NICKNAME = "nickname"
        const val MAX_LOG = 200

        fun randomPeerId() = Random.nextBytes(8).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

expect fun currentEpochMillis(): Long
expect fun formatClock(epochMs: Long): String
