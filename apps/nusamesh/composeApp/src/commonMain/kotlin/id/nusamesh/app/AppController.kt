package id.nusamesh.app

import id.nusamesh.app.media.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import id.nusamesh.app.data.KeyValueStore
import id.nusamesh.app.data.GpxCodec
import id.nusamesh.app.data.MapPersistence
import id.nusamesh.app.data.QosRecords
import id.nusamesh.app.data.MeshRepository
import id.nusamesh.app.data.MobilityLogEntry
import id.nusamesh.app.data.currentEpochMillis
import id.nusamesh.app.data.formatClock
import id.nusamesh.app.domain.AppPage
import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessage
import id.nusamesh.app.domain.ChatMessageKind
import id.nusamesh.app.protocol.MeshMediaCodec
import id.nusamesh.app.domain.ChatPreview
import id.nusamesh.app.domain.DeliveryPath
import id.nusamesh.app.domain.DeliveryState
import id.nusamesh.app.domain.FieldChannels
import id.nusamesh.app.domain.FieldRole
import id.nusamesh.app.domain.MapWaypoint
import id.nusamesh.app.domain.MeshStatus
import id.nusamesh.app.domain.RouteKind
import id.nusamesh.app.domain.SharedRoute
import id.nusamesh.app.domain.TrackedUser
import id.nusamesh.app.domain.QosRun
import id.nusamesh.app.domain.RoadRouteInfo
import id.nusamesh.app.routing.RoadGraph
import id.nusamesh.app.routing.TravelMode
import id.nusamesh.app.routing.SarNavigation
import id.nusamesh.app.routing.SarRequest
import id.nusamesh.app.routing.SarRouteCodec
import id.nusamesh.app.routing.sarProgress
import kotlinx.serialization.json.*
import id.nusamesh.app.domain.QosSample
import id.nusamesh.app.domain.QosSending
import id.nusamesh.app.domain.RxMeasurement
import id.nusamesh.app.domain.UnitStatus
import id.nusamesh.app.location.DeviceLocation
import id.nusamesh.app.mesh.engine.EngineSnapshot
import id.nusamesh.app.mesh.engine.IncomingFile
import id.nusamesh.app.mesh.engine.IncomingMessage
import id.nusamesh.app.mesh.mobility.MobilityConfig
import id.nusamesh.app.mesh.protocol.LocationTelemetry
import id.nusamesh.app.mesh.protocol.EmergencyTelemetry
import id.nusamesh.app.mesh.protocol.GeoPoint
import id.nusamesh.app.mesh.protocol.ImportantMessage
import id.nusamesh.app.mesh.protocol.MeshMessageType
import id.nusamesh.app.mesh.protocol.QuickStatus
import id.nusamesh.app.mesh.protocol.QosProbe
import id.nusamesh.app.mesh.protocol.LoraRxMeta
import id.nusamesh.app.mesh.protocol.RoutePolyline
import id.nusamesh.app.mesh.protocol.RouteTelemetry
import id.nusamesh.app.mesh.protocol.TrackSegmentTelemetry
import id.nusamesh.app.mesh.protocol.VictimInfo
import id.nusamesh.app.mesh.protocol.WaypointTelemetry
import id.nusamesh.app.mesh.protocol.WaypointType
import id.nusamesh.app.security.OperationKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

class AppController(
    private val repository: MeshRepository,
    private val store: KeyValueStore,
    initialPage: AppPage = AppPage.Home,
    initialConversationId: String? = null,
    /** Persen baterai HP ini (ikut telemetri lokasi & SOS); null bila platform tidak menyediakan. */
    private val batteryLevel: () -> Int? = { null },
    /** Isi file graf jalan offline (dibaca sekali saat rute pertama diminta); null = tidak tersedia. */
    private val roadGraph: () -> ByteArray? = { null },
    appScope: CoroutineScope? = null,
) {
    private val scope = appScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(
        AppUiState(
            page = initialPage,
            nickname = repository.nickname,
            myPeerId = repository.myPeerId,
            activeConversationId = initialConversationId,
            sosActive = store.get(KEY_SOS_ACTIVE) == "1",
            loraOnly = store.get(KEY_LORA_ONLY) == "1",
            role = FieldRole.entries.firstOrNull { it.name == store.get(KEY_ROLE) } ?: FieldRole.Warga,
            team = store.get(KEY_TEAM)?.let(FieldChannels::teamSlug),
            chats = listOf(globalPreview("Chat bersama semua pengguna di mesh", 0, "")),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    val mobilityLog: StateFlow<List<MobilityLogEntry>> = repository.mobilityLog
    val transmissionDiagnostics = repository.transmissionDiagnostics
    fun clearTransmissionLog() = repository.clearTransmissionLog()
    fun transmissionLogCsv() = repository.transmissionLogCsv()
    fun transmissionSummaryCsv() = repository.transmissionSummaryCsv()

    /** true bila pengguna terakhir kali menyalakan mesh: aplikasi menyalakannya lagi saat dibuka. */
    val resumeMeshOnLaunch: Boolean get() = store.get(KEY_ACTIVE) == "1"

    private var saveMapJob: Job? = null
    private var saveQosJob: Job? = null

    init {
        repository.loraOnly = _state.value.loraOnly
        restoreOperationKey()
        restoreMapData()
        scope.launch { while (isActive) { delay(2000); _state.update(::refreshSarStatus) } }
        runCatching { store.get("sar-navigation-v1")?.takeIf { it.isNotEmpty() }?.let(SarRouteCodec::restore) }.getOrNull()?.let { route ->
            _state.update { it.copy(sarNavigation = SarNavigation(route = route, status = "PAUSED", message = "Rute dipulihkan; tujuan memakai koordinat terakhir")) }
        }
        _state.update { it.copy(qosRuns = QosRecords.decode(store.get(KEY_QOS))) }
        _state.update { it.copy(chats = withChannelPreviews(it.chats, it.role, it.team)) }
        scope.launch { repository.snapshot.collect(::onSnapshot) }
        // Jalur & titik disimpan permanen; ditunda sedikit supaya update GPS beruntun cukup satu tulis.
        scope.launch {
            // Posisi terakhir korban SOS: tetap ada walau aplikasi tim ditutup, sampai korban membatalkan.
            var savedEmergencies = store.get(KEY_EMERGENCIES).orEmpty()
            _state.collect { current ->
                val encoded = MapPersistence.encodeEmergencies(current.trackedUsers.filter { it.emergency && !it.own })
                if (encoded != savedEmergencies) {
                    savedEmergencies = encoded
                    store.put(KEY_EMERGENCIES, encoded)
                }
            }
        }
        // Hasil uji QoS (data TA) disimpan permanen, ditunda sedikit supaya tiap paket tak memicu tulis.
        scope.launch {
            var savedRuns = _state.value.qosRuns
            _state.collect { current ->
                if (current.qosRuns == savedRuns) return@collect
                savedRuns = current.qosRuns
                saveQosJob?.cancel()
                saveQosJob = scope.launch {
                    delay(MAP_SAVE_DELAY_MS)
                    store.put(KEY_QOS, QosRecords.encode(savedRuns))
                }
            }
        }
        scope.launch {
            var saved = _state.value.routes to _state.value.waypoints
            _state.collect { current ->
                val next = current.routes to current.waypoints
                if (next == saved) return@collect
                saved = next
                saveMapJob?.cancel()
                saveMapJob = scope.launch {
                    delay(MAP_SAVE_DELAY_MS)
                    store.put(KEY_ROUTES, MapPersistence.encodeRoutes(next.first))
                    store.put(KEY_WAYPOINTS, MapPersistence.encodeWaypoints(next.second))
                }
            }
        }
        scope.launch { repository.messages.collect(::onIncomingMessage) }
        scope.launch { repository.files.collect(::onIncomingFile) }
        scope.launch { while(isActive) { delay(1000); expireVoiceStreams() } }
        scope.launch { repository.acks.collect { ack -> onAck(ack.messageId, ack.fromPeerId) } }
    }

    // ------------------------------------------------------------------------------------------
    //  Mesh
    // ------------------------------------------------------------------------------------------
    fun startMesh() {
        store.put(KEY_ACTIVE, "1")
        repository.start()
        _state.update { it.copy(mesh = it.mesh.copy(active = true)) }
    }

    fun stopMesh() {
        if (_state.value.sosActive) {
            showNotice("Batalkan SOS sebelum mematikan mesh")
            return
        }
        store.put(KEY_ACTIVE, "0")
        repository.stop()
        _state.update { it.copy(mesh = it.mesh.copy(active = false), nebengNotice = null) }
    }

    /**
     * Layar ditutup tanpa SOS/rekam jejak: matikan radio tapi ingat bahwa mesh aktif, supaya menyala lagi
     * saat aplikasi dibuka (beda dengan [stopMesh] yang dipilih pengguna).
     */
    fun suspendMesh() {
        repository.stop()
        _state.update { it.copy(mesh = it.mesh.copy(active = false), nebengNotice = null) }
    }

    fun setNickname(value: String) {
        repository.nickname = value
        _state.update { it.copy(nickname = repository.nickname) }
    }

    fun openNodeSheet() = _state.update { it.copy(nodeSheetOpen = true) }
    fun closeNodeSheet() = _state.update { it.copy(nodeSheetOpen = false) }

    /** Kunci node pilihan pengguna; null = kembali ke pemilihan otomatis. */
    fun lockNode(peerId: String?) = repository.lockNode(peerId)

    var mobilityConfig: MobilityConfig
        get() = repository.mobilityConfig
        set(value) { repository.mobilityConfig = value }

    fun clearMobilityLog() = repository.clearMobilityLog()

    fun dismissNebengNotice() = _state.update { it.copy(nebengNotice = null) }

    private var lastNebengRelay: String? = null
    private var announcedNodes = HashSet<String>()
    private var lastLocationSentAt = 0L
    private var lastSosSentAt = 0L
    /** Koordinat SOS terakhir yang terkirim; dipakai untuk pembatalan bila GPS sedang hilang. */
    private var lastSosPoint: GeoPoint? = null
    private var sosRepeatJob: Job? = null

    private fun onSnapshot(snapshot: EngineSnapshot) {
        val nebeng = snapshot.nebeng
        val nebengNotice = when {
            nebeng == null -> null
            nebeng.relayPeerId != lastNebengRelay ->
                "Tidak ada Nusa Node dalam jangkauan. Pesan dititipkan lewat ${nebeng.relayName} ke ${nebeng.nodeName}."
            else -> _state.value.nebengNotice
        }
        lastNebengRelay = nebeng?.relayPeerId
        // Node yang baru terdengar diberitahukan sekali di chat global (tidak otomatis dipilih ulang).
        val fresh = snapshot.nodes.filter { it.peerId !in announcedNodes }
        announcedNodes += fresh.map { it.peerId }
        _state.update { current ->
            val telemetry = current.trackedUsers
                // Korban SOS tidak pernah dibuang: posisi terakhirnya justru paling penting bila HP-nya mati.
                .filter { it.emergency || currentEpochMillis() - it.updatedAtMs <= LOCATION_STALE_MS }
                .map { user ->
                val peer = snapshot.peers.firstOrNull { it.peerId == user.peerId }
                if (peer == null) user else user.copy(rssi = peer.rssi, direct = peer.direct)
            }
            var next = current.copy(
                mesh = MeshStatus(current.mesh.active, snapshot),
                nebengNotice = nebengNotice,
                trackedUsers = telemetry,
                selectedTargetPeerId = current.selectedTargetPeerId?.takeIf { id -> telemetry.any { it.peerId == id } },
            )
            fresh.forEach { node ->
                next = next.copy(messages = next.messages + systemMessage("Nusa Node tersedia: ${node.name}. Atur lewat menu Nusa Node."))
            }
            next
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Pesan masuk
    // ------------------------------------------------------------------------------------------
    private fun onIncomingMessage(incoming: IncomingMessage) {
        if (incoming.forged) return rejectForged(incoming.fromPeerId)
        val m = incoming.message
        if (m.type == MeshMessageType.SOS || m.type == MeshMessageType.SOS_Cancel) {
            receiveEmergency(incoming)
            return
        }
        if (m.content.startsWith("${LocationTelemetry.PREFIX}|")) {
            receiveLocation(incoming)
            return
        }
        if (receiveRouteTelemetry(incoming)) return
        QosProbe.decode(m.content)?.let { probe -> return receiveQos(incoming, probe) }
        if (_state.value.messages.any { it.id == m.id }) return
        // Chat channel tim/operasional yang bukan untuk peran kita disembunyikan (tetap diteruskan mesh).
        val conversationId = FieldChannels.conversationFor(m.channel, _state.value.role, _state.value.team, GLOBAL_CHAT_ID) ?: return
        val message = ChatMessage(
            id = m.id,
            conversationId = conversationId,
            senderName = m.sender.ifBlank { repository.nicknameOf(incoming.fromPeerId) ?: shortId(incoming.fromPeerId) },
            body = m.content,
            time = formatClock(m.timestampMs),
            outgoing = false,
            delivery = DeliveryState.Received,
            path = if (incoming.viaNode) DeliveryPath.Node else DeliveryPath.Ble,
            verified = incoming.verified,
            rx = measure(incoming.fromPeerId, incoming.viaNode, incoming.loraRx, incoming.bleRssi, m.timestampMs),
        )
        appendIncoming(message, QuickStatus.display(m.content))
        if (ImportantMessage.wantsAck(m.content)) acknowledge(m.id, incoming.fromPeerId)
        QuickStatus.decode(m.content)?.let { status ->
            _state.update { it.copy(unitStatuses = it.unitStatuses + (incoming.fromPeerId to UnitStatus(status, m.timestampMs, message.senderName))) }
            if (status.urgent) showNotice("${message.senderName}: ${status.label}")
        }
    }

    private fun onIncomingFile(incoming: IncomingFile) {
        val file = incoming.file
        if (file.mimeType == VoiceStream.MIME) { onIncomingVoice(incoming); return }
        val kind = kindOf(file.mimeType)
        val sender = repository.nicknameOf(incoming.fromPeerId) ?: shortId(incoming.fromPeerId)
        val (channel, fileName) = FieldChannels.splitAttachmentName(file.fileName)
        val conversationId = FieldChannels.conversationFor(channel, _state.value.role, _state.value.team, GLOBAL_CHAT_ID) ?: return
        if (incoming.locked) {
            val locked = systemMessage("$sender mengirim lampiran terenkripsi — perlu kunci operasi yang sama").copy(conversationId = conversationId)
            appendIncoming(locked, "Lampiran terenkripsi")
            return
        }
        val message = ChatMessage(
            id = id.nusamesh.app.mesh.engine.fileTransferId(incoming.fromPeerId, incoming.timestampMs),
            conversationId = conversationId,
            senderName = sender,
            body = labelOf(kind, fileName),
            time = formatClock(incoming.timestampMs),
            outgoing = false,
            kind = kind,
            delivery = DeliveryState.Received,
            attachmentName = fileName,
            verified = incoming.verified,
            rx = measure(incoming.fromPeerId, incoming.viaNode, incoming.loraRx, incoming.bleRssi, incoming.timestampMs),
            attachmentBytes = file.content.size,
            attachmentMimeType = file.mimeType,
            attachmentData = file.content,
            path = if (incoming.viaNode) DeliveryPath.Node else DeliveryPath.Ble,
        )
        if (_state.value.messages.any { it.id == message.id }) return
        appendIncoming(message, message.body)
        // Only acknowledge after reassembly, successful opening and channel acceptance.
        if(kind == ChatMessageKind.Voice) acknowledge(message.id, incoming.fromPeerId)
    }

    private fun appendIncoming(message: ChatMessage, preview: String) = _state.update { current ->
        val unread = if (current.activeConversationId == message.conversationId) 0 else 1
        current.copy(
            chats = upsertPreview(current.chats, message.conversationId, "${message.senderName}: $preview", unread, message.time),
            messages = current.messages + message,
        )
    }

    // ------------------------------------------------------------------------------------------
    //  Navigasi & chat
    // ------------------------------------------------------------------------------------------
    fun navigate(page: AppPage) = _state.update { it.copy(page = page) }

    fun createGlobalChat() = _state.update { it.copy(page = AppPage.Chats, activeConversationId = GLOBAL_CHAT_ID) }

    fun openChat(conversationId: String) = _state.update { current ->
        current.copy(
            activeConversationId = conversationId,
            chats = current.chats.map { if (it.peerId == conversationId) it.copy(unread = 0) else it },
        )
    }

    fun closeChat() = _state.update { it.copy(activeConversationId = null) }

    /** Pesan ke percakapan yang sedang dibuka (global / operasional SAR / tim). */
    fun sendMessage(text: String) = sendChat(text, activeConversation())

    private fun activeConversation(): String =
        _state.value.activeConversationId?.takeIf { id -> _state.value.chats.any { it.peerId == id } } ?: GLOBAL_CHAT_ID

    private fun sendChat(text: String, conversationId: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!canSend()) return
        var messageId = ""
        // Engine berjalan di thread lain: kembali ke Main (setelah pesan masuk state) sebelum menandai.
        val sent = repository.sendText(clean, FieldChannels.wireChannel(conversationId)) { success ->
            scope.launch { markDelivery(messageId, success) }
        }
        messageId = sent.id
        if (ImportantMessage.wantsAck(clean)) rememberAckTarget(sent.id, AckTarget.Chat(sent.id))
        val (path, via) = currentPath()
        val outgoing = ChatMessage(
            id = sent.id,
            conversationId = conversationId,
            senderName = repository.nickname,
            body = clean,
            time = formatClock(sent.timestampMs),
            outgoing = true,
            delivery = DeliveryState.Sending,
            verified = hasKey,
            path = path,
            viaName = via,
        )
        _state.update { current ->
            current.copy(
                chats = upsertPreview(current.chats, conversationId, QuickStatus.display(clean), 0, outgoing.time),
                messages = current.messages + outgoing,
            )
        }
    }

    /** Pesan teks tetap "Mengirim" (ikon jam) selama masih antre airtime LoRa; baru centang setelah keluar. */
    private fun markDelivery(messageId: String, success: Boolean) = _state.update { current ->
        current.copy(messages = current.messages.map { message ->
            if (message.id == messageId && message.delivery == DeliveryState.Sending) {
                message.copy(delivery = if (success) DeliveryState.Sent else DeliveryState.Failed)
            } else message
        })
    }

    private class OutgoingVoice(val conversation: String, val timestamp: Long, val key: OperationKey?) {
        var streamId: Long? = null
        var next = 0
        var offset = 0
        var waiting = 0
        var endQueued = false
        var recorded = false
        var failed = false
    }
    private var outgoingVoice: OutgoingVoice? = null
    private var lastVoiceTimestamp = 0L
    private class ReceivingVoice(val original: IncomingFile, val conversation: String, val base: Long,
                                  val assembler: VoiceStreamAssembler = VoiceStreamAssembler(), var last: Long = currentEpochMillis())
    private val receivingVoices = mutableMapOf<String, ReceivingVoice>()
    private val finishedVoices = linkedSetOf<String>()
    private val lockedVoices = linkedSetOf<String>()
    private val _voiceChunks = MutableSharedFlow<IncomingVoiceChunk>(extraBufferCapacity = VoiceStream.MAX_CHUNKS)
    val voiceChunks = _voiceChunks.asSharedFlow()
    fun recordVoiceEvent(event: String, detail: String = "") = repository.recordVoiceEvent(event,detail)

    fun beginVoiceStream(): Boolean {
        if (!canSend()) return false
        if (outgoingVoice != null) { showNotice("Tunggu pengiriman suara sebelumnya selesai"); return false }
        val conv = activeConversation(); val now = maxOf(currentEpochMillis(),lastVoiceTimestamp + VoiceStream.MAX_CHUNKS)
        lastVoiceTimestamp = now
        outgoingVoice = OutgoingVoice(conv, now, repository.operationKey)
        recordVoiceEvent("VOICE_CAPTURE_STARTED", "timestamp=$now; conversation=$conv")
        val (path,via) = currentPath()
        val message = ChatMessage(id = id.nusamesh.app.mesh.engine.fileTransferId(repository.myPeerId, now),
            conversationId = conv, senderName = repository.nickname, body = "Suara sedang direkam", time = formatClock(now),
            outgoing = true, kind = ChatMessageKind.Voice, delivery = DeliveryState.Sending,
            attachmentMimeType = "audio/x-codec2", transferStartedAtMs = now, path = path, viaName = via)
        appendIncoming(message, "Suara")
        rememberAckTarget(message.id, AckTarget.Chat(message.id))
        return true
    }
    fun sendVoiceChunk(chunk: VoiceChunk) {
        val ctx = outgoingVoice ?: return
        if (!VoiceStream.valid(chunk) || ctx.endQueued || chunk.sequence != ctx.next || chunk.offset != ctx.offset ||
            (ctx.streamId != null && ctx.streamId != chunk.streamId)) return
        ctx.streamId = chunk.streamId; ctx.next++; ctx.waiting++; ctx.endQueued = chunk.end
        ctx.offset = chunk.offset + chunk.samples
        recordVoiceEvent("VOICE_CHUNK_QUEUED", "stream=${chunk.streamId}; seq=${chunk.sequence}; samples=${chunk.samples}; codec_bytes=${chunk.frames.size}; end=${chunk.end}")
        val name = FieldChannels.tagAttachmentName(ctx.conversation, "v${chunk.streamId.toULong().toString(16)}")
        val attachment = ChatAttachment(name, VoiceStream.MIME, VoiceStream.encode(chunk), ChatMessageKind.Voice)
        repository.sendAttachment(attachment, encrypt = ctx.conversation != GLOBAL_CHAT_ID,
            operationKeyOverride = ctx.key, timestampMs = ctx.timestamp + chunk.sequence,
            onComplete = { success -> scope.launch {
                ctx.waiting--; ctx.failed = ctx.failed || !success || chunk.cancelled
                settleVoice(ctx)
            } })
        val id = id.nusamesh.app.mesh.engine.fileTransferId(repository.myPeerId,ctx.timestamp)
        _state.update { state -> state.copy(messages = state.messages.map {
            if(it.id == id) it.copy(body = if(chunk.end) "Mengirim suara" else "Suara sedang direkam",
                durationSeconds = (chunk.offset+chunk.samples+7999)/8000) else it
        }) }
    }
    fun finishVoiceStream(attachment: ChatAttachment) {
        val ctx = outgoingVoice ?: return
        ctx.recorded = true
        ctx.failed = ctx.failed || !ctx.endQueued || Codec2Format.samples(attachment.bytes) != ctx.offset
        val id = id.nusamesh.app.mesh.engine.fileTransferId(repository.myPeerId,ctx.timestamp)
        _state.update { state -> state.copy(messages = state.messages.map {
            if(it.id == id) it.copy(body = "Pesan suara", attachmentName = attachment.name,
                attachmentBytes = attachment.bytes.size, attachmentData = attachment.bytes,
                durationSeconds = attachment.durationSeconds) else it
        }) }
        settleVoice(ctx)
    }
    private fun settleVoice(ctx: OutgoingVoice) {
        if (!ctx.endQueued || !ctx.recorded || ctx.waiting != 0) return
        val id = id.nusamesh.app.mesh.engine.fileTransferId(repository.myPeerId,ctx.timestamp)
        markDelivery(id,!ctx.failed)
        if(outgoingVoice === ctx) outgoingVoice = null
    }
    fun cancelVoiceStream() {
        val ctx = outgoingVoice ?: return
        ctx.failed = true; ctx.recorded = true
        if(!ctx.endQueued && ctx.streamId != null) sendVoiceChunk(
            VoiceChunk(ctx.streamId!!,ctx.next,ctx.offset,0,ByteArray(0),end=true,cancelled=true))
        markDelivery(id.nusamesh.app.mesh.engine.fileTransferId(repository.myPeerId,ctx.timestamp),false)
        // Cancel is sent via the same channel/security path as data chunks.
        if(ctx.endQueued || ctx.next == 0) outgoingVoice = null
    }
    private fun onIncomingVoice(incoming: IncomingFile) {
        if(incoming.locked) {
            if(lockedVoices.add("${incoming.fromPeerId}/${incoming.file.fileName}")) showNotice("Suara terenkripsi memerlukan kunci operasi yang sama")
            if(lockedVoices.size > 64) lockedVoices.remove(lockedVoices.first())
            return
        }
        val chunk = VoiceStream.decode(incoming.file.content) ?: return
        val (channel,_) = FieldChannels.splitAttachmentName(incoming.file.fileName)
        val conv = FieldChannels.conversationFor(channel,_state.value.role,_state.value.team,GLOBAL_CHAT_ID) ?: return
        if(channel != null && repository.operationKey != null && !incoming.verified) return
        val key = "${incoming.fromPeerId}/${chunk.streamId}"
        val now = currentEpochMillis()
        if(key in finishedVoices) return
        val old = receivingVoices[key]
        if(old == null && receivingVoices.size >= 8) return
        val ctx = old ?: ReceivingVoice(incoming,conv,incoming.timestampMs-chunk.sequence).also { receivingVoices[key] = it }
        if(ctx.conversation != conv || ctx.original.file.fileName != incoming.file.fileName ||
            ctx.base != incoming.timestampMs-chunk.sequence || ctx.original.verified != incoming.verified) return
        ctx.last = now
        val ready = ctx.assembler.accept(chunk)
        recordVoiceEvent("VOICE_CHUNK_RECEIVED", "stream=$key; seq=${chunk.sequence}; ready=${ready.size}; via_node=${incoming.viaNode}")
        val id = id.nusamesh.app.mesh.engine.fileTransferId(incoming.fromPeerId,ctx.base)
        if(_state.value.messages.none { it.id == id }) {
            val sender = repository.nicknameOf(incoming.fromPeerId) ?: shortId(incoming.fromPeerId)
            appendIncoming(ChatMessage(id=id, conversationId=conv, senderName=sender, body="Menerima suara",
                time=formatClock(ctx.base), outgoing=false, kind=ChatMessageKind.Voice, delivery=DeliveryState.Sending,
                attachmentMimeType="audio/x-codec2", verified=incoming.verified,
                path=if(incoming.viaNode) DeliveryPath.Node else DeliveryPath.Ble),"Suara")
        }
        ready.forEach { _voiceChunks.tryEmit(IncomingVoiceChunk(key,it,now,conv)) }
        if(!ctx.assembler.finished) return
        if(ctx.assembler.cancelled && ready.none { it.cancelled }) _voiceChunks.tryEmit(
            IncomingVoiceChunk(key,VoiceChunk(chunk.streamId,0,0,0,ByteArray(0),end=true,cancelled=true),now,conv))
        val note = ctx.assembler.note()
        _state.update { state -> state.copy(messages=state.messages.map {
            if(it.id == id) it.copy(body=if(note != null) "Pesan suara" else "Suara tidak lengkap",
                delivery=if(note != null) DeliveryState.Received else DeliveryState.Failed,
                attachmentData=note, attachmentBytes=note?.size,
                attachmentName="voice-${chunk.streamId}.c2", durationSeconds=(ctx.assembler.sampleCount+7999)/8000) else it
        }) }
        if(note != null) {
            recordVoiceEvent("VOICE_STREAM_COMPLETE", "stream=$key; samples=${ctx.assembler.sampleCount}; bytes=${note.size}")
            acknowledge(id,incoming.fromPeerId)
        }
        receivingVoices.remove(key); finishedVoices += key
        if(finishedVoices.size > 64) finishedVoices.remove(finishedVoices.first())
    }
    private fun expireVoiceStreams() {
        val now = currentEpochMillis()
        receivingVoices.filterValues { now-it.last > VoiceStream.TIMEOUT_MS }.toMap().forEach { (key,ctx) ->
            receivingVoices.remove(key); finishedVoices += key
            val id = id.nusamesh.app.mesh.engine.fileTransferId(ctx.original.fromPeerId,ctx.base)
            _state.update { state -> state.copy(messages=state.messages.map {
                if(it.id == id) it.copy(body="Suara tidak lengkap",delivery=DeliveryState.Failed) else it
            }) }
            _voiceChunks.tryEmit(IncomingVoiceChunk(key,VoiceChunk(0,0,0,0,ByteArray(0),end=true,cancelled=true),now,ctx.conversation))
        }
        while(finishedVoices.size > 64) finishedVoices.remove(finishedVoices.first())
    }

    fun sendAttachment(attachment: ChatAttachment) {
        if (!canSend()) return
        val conversationId = activeConversation()
        val (path, via) = currentPath()
        // Gambar resolusi penuh (JPEG ≤960 px) bisa jadi ratusan paket LoRa: hanya profil LoRa yang boleh lewat node.
        val fullImage = attachment.kind == ChatMessageKind.Image && !MeshMediaCodec.isLoraImage(attachment.name, attachment.bytes.size)
        val loraSkipped = path != DeliveryPath.Ble &&
            (fullImage || attachment.bytes.size > id.nusamesh.app.mesh.engine.MeshEngine.LORA_FILE_MAX_BYTES)
        val now = currentEpochMillis()
        val messageId = id.nusamesh.app.mesh.engine.fileTransferId(repository.myPeerId, now)
        val outgoing = ChatMessage(
            id = messageId,
            conversationId = conversationId,
            senderName = repository.nickname,
            body = labelOf(attachment.kind, attachment.name),
            time = formatClock(now),
            outgoing = true,
            kind = attachment.kind,
            delivery = DeliveryState.Sending,
            attachmentName = attachment.name,
            attachmentBytes = attachment.bytes.size,
            attachmentMimeType = attachment.mimeType,
            attachmentData = attachment.bytes,
            durationSeconds = attachment.durationSeconds,
            transferProgress = 0f,
            transferStartedAtMs = now,
            path = if (loraSkipped) DeliveryPath.Ble else path,
            viaName = if (loraSkipped) null else via,
        )
        _state.update { current ->
            current.copy(
                chats = upsertPreview(current.chats, conversationId, outgoing.body, 0, outgoing.time),
                messages = current.messages + outgoing,
                notice = when {
                    !loraSkipped -> current.notice
                    fullImage -> "Gambar resolusi penuh hanya dikirim lewat Bluetooth. Pilih ulang gambar saat Nusa Node tersambung untuk versi LoRa."
                    else -> "Lampiran di atas 96 KB hanya dikirim lewat Bluetooth, tidak lewat LoRa"
                },
            )
        }
        if(attachment.kind == ChatMessageKind.Voice) rememberAckTarget(messageId, AckTarget.Chat(messageId))
        repository.sendAttachment(
            // Paket file tidak punya field channel: channel ikut di nama file.
            attachment.copy(name = FieldChannels.tagAttachmentName(conversationId, attachment.name)),
            encrypt = conversationId != GLOBAL_CHAT_ID,
            timestampMs = now,
            onEstimate = { estimate ->
                val at = currentEpochMillis()
                _state.update { current -> current.copy(messages = current.messages.map { message ->
                    if(message.id == messageId && message.delivery == DeliveryState.Sending)
                        message.copy(transferEstimate = estimate, transferEstimatedAtMs = at) else message
                }) }
            },
            loraAllowed = !fullImage,
            onProgress = { progress ->
                _state.update { current ->
                    current.copy(messages = current.messages.map { message ->
                        if (message.id == messageId) message.copy(transferProgress = progress.coerceIn(0f, 1f)) else message
                    })
                }
            },
            onComplete = { success ->
                _state.update { current ->
                    current.copy(messages = current.messages.map { message ->
                        if (message.id == messageId) message.copy(
                            delivery = if (success) DeliveryState.Sent else DeliveryState.Failed,
                            transferProgress = if (success) 1f else message.transferProgress,
                            transferCompletedAtMs = currentEpochMillis(),
                            transferEstimate = null,
                            transferEstimatedAtMs = null,
                        ) else message
                    })
                }
            },
        )
    }

    fun updateOwnLocation(location: DeviceLocation) {
        val mine = TrackedUser(
            repository.myPeerId, repository.nickname, location.latitude, location.longitude,
            location.accuracyMeters, location.timestampMs, own = true, emergency = _state.value.sosActive,
            batteryPercent = batteryLevel(),
            verified = hasKey,
        )
        _state.update { current -> current.copy(trackedUsers = current.trackedUsers.filterNot { it.peerId == mine.peerId } + mine) }
        if (_state.value.mesh.active && location.timestampMs - lastLocationSentAt >= 30_000L) {
            lastLocationSentAt = location.timestampMs
            repository.sendLocation(location.latitude, location.longitude, location.accuracyMeters, location.timestampMs, batteryLevel())
        }
        if (_state.value.mesh.active && _state.value.sosActive && location.timestampMs - lastSosSentAt >= 15_000L) {
            lastSosSentAt = location.timestampMs
            lastSosPoint = GeoPoint(location.latitude, location.longitude)
            sendEmergency(
                EmergencyTelemetry(
                    EmergencyTelemetry.Action.Alert,
                    location.latitude,
                    location.longitude,
                    location.accuracyMeters,
                    location.timestampMs,
                ),
            )
        }
        if (_state.value.sosActive && sosRepeatJob == null) startSosRepeater()
        recordTrackPoint(location)
    }

    /**
     * SOS manual: aktif sampai korban sendiri membatalkan. Tanpa GPS pun SOS tetap menyala; teks
     * peringatan dikirim segera dan koordinat menyusul otomatis begitu GPS dapat sinyal
     * (lihat [updateOwnLocation]).
     */
    fun toggleSos() {
        val current = _state.value
        val own = current.trackedUsers.firstOrNull { it.own }
        if (!current.mesh.active) {
            showNotice("Aktifkan mesh sebelum mengirim SOS")
            return
        }
        val activating = !current.sosActive
        val now = currentEpochMillis()
        val point = own?.let { GeoPoint(it.latitude, it.longitude) } ?: lastSosPoint
        if (point != null) {
            sendEmergency(
                EmergencyTelemetry(
                    if (activating) EmergencyTelemetry.Action.Alert else EmergencyTelemetry.Action.Cancel,
                    point.latitude,
                    point.longitude,
                    own?.accuracyMeters ?: 10_000f,
                    now,
                ),
            )
            if (activating) lastSosPoint = point
        } else {
            // Belum pernah ada koordinat: tetap kabari semua orang lewat chat (juga terbaca versi lama).
            repository.sendText(
                if (activating) "SOS DARURAT dari ${repository.nickname}: lokasi GPS belum didapat, koordinat menyusul."
                else "SOS ${repository.nickname} dibatalkan.",
            )
        }
        lastSosSentAt = if (activating && point != null) now else 0L
        store.put(KEY_SOS_ACTIVE, if (activating) "1" else "0")
        _state.update { state ->
            state.copy(
                sosActive = activating,
                sosAckedBy = emptyList(),
                trackedUsers = state.trackedUsers.map { if (it.own) it.copy(emergency = activating, updatedAtMs = now) else it },
                notice = when {
                    !activating -> "SOS dibatalkan"
                    point == null -> "SOS aktif. Menunggu GPS, koordinat dikirim otomatis begitu didapat"
                    else -> "SOS aktif dan disiarkan lewat mesh"
                },
            )
        }
        if (activating) startSosRepeater() else {
            sosRepeatJob?.cancel()
            sosRepeatJob = null
        }
    }

    private fun startSosRepeater() {
        sosRepeatJob?.cancel()
        sosRepeatJob = scope.launch {
            while (isActive && _state.value.sosActive) {
                delay(30_000L)
                val current = _state.value
                val own = current.trackedUsers.firstOrNull { it.own } ?: continue
                if (!current.mesh.active || !current.sosActive) continue
                val now = currentEpochMillis()
                lastSosPoint = GeoPoint(own.latitude, own.longitude)
                sendEmergency(
                    EmergencyTelemetry(
                        EmergencyTelemetry.Action.Alert,
                        own.latitude,
                        own.longitude,
                        own.accuracyMeters,
                        now,
                    ),
                )
                lastSosSentAt = now
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Jejak, rute, dan titik penting (semua lewat pesan teks kecil supaya muat LoRa)
    // ------------------------------------------------------------------------------------------
    private fun restoreMapData() {
        val routes = MapPersistence.decodeRoutes(store.get(KEY_ROUTES)).map { route ->
            // Perekaman tidak bisa dilanjutkan setelah aplikasi ditutup: jejak sendiri jadi jejak selesai.
            if (route.own && route.kind == RouteKind.LiveTrack) route.copy(kind = RouteKind.Track) else route
        }.takeLast(MAX_ROUTES)
        val waypoints = MapPersistence.decodeWaypoints(store.get(KEY_WAYPOINTS)).takeLast(MAX_WAYPOINTS)
        val victims = MapPersistence.decodeEmergencies(store.get(KEY_EMERGENCIES))
        _state.update { it.copy(routes = routes, waypoints = waypoints, trackedUsers = it.trackedUsers + victims) }
    }

    /** Hapus jalur dari HP ini saja (tidak menghapus di HP lain). */
    fun deleteRoute(id: String) = _state.update { current ->
        if (id == current.recordingTrackId) return@update current
        current.copy(
            routes = current.routes.filterNot { it.id == id },
            followedRouteId = current.followedRouteId?.takeIf { it != id },
        )
    }

    /**
     * Tim menandai korban sudah ditangani: penanda SOS hilang dari HP ini saja (SOS korban tidak dibatalkan).
     * Bila HP korban masih menyiarkan SOS, penanda muncul lagi — korban mungkin masih butuh bantuan.
     */
    fun resolveEmergency(peerId: String) {
        val unit = _state.value.trackedUsers.firstOrNull { it.peerId == peerId && it.emergency && !it.own } ?: return
        _state.update { current ->
            current.copy(
                trackedUsers = current.trackedUsers.map { if (it.peerId == peerId) it.copy(emergency = false) else it },
                messages = current.messages + systemMessage("SOS ${unit.name} ditandai sudah ditangani di HP ini."),
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    //  GPX: laporan pascaoperasi & rute rencana dari posko
    // ------------------------------------------------------------------------------------------
    fun exportGpx(): String {
        val current = _state.value
        return GpxCodec.export(current.routes, current.waypoints, current.trackedUsers.filter { it.emergency && !it.own }, currentEpochMillis())
    }

    /**
     * Rute & titik dari GPX ditambahkan di HP ini saja; dibagikan per item lewat tombol "Bagikan" supaya
     * file berisi ratusan titik tidak membanjiri LoRa.
     */
    fun importGpx(text: String) {
        val imported = GpxCodec.parse(text) ?: return showNotice("File bukan GPX atau tidak berisi koordinat")
        val now = currentEpochMillis()
        val routes = imported.routes.take(MAX_ROUTES).map { (name, points) ->
            SharedRoute(newTelemetryId(), repository.myPeerId, repository.nickname, name.take(40), RouteKind.Plan,
                mapOf(0 to points.take(MAX_IMPORTED_POINTS)), now, own = true)
        }
        val waypoints = imported.waypoints.take(MAX_WAYPOINTS).map { wp ->
            MapWaypoint(newTelemetryId(), repository.myPeerId, repository.nickname, wp.type, wp.point, wp.label, now, own = true, victim = wp.victim)
        }
        _state.update { current ->
            current.copy(
                routes = (current.routes + routes).takeLast(MAX_ROUTES),
                waypoints = (current.waypoints + waypoints).takeLast(MAX_WAYPOINTS),
            )
        }
        showNotice("Diimpor ${routes.size} rute & ${waypoints.size} titik. Ketuk \"Bagikan\" untuk mengirim ke tim.")
    }

    /** Kirim (ulang) rute milik sendiri ke mesh; disederhanakan sampai muat beberapa fragmen LoRa. */
    fun shareRoute(id: String) {
        val route = _state.value.routes.firstOrNull { it.id == id && it.own } ?: return
        if (route.id == _state.value.recordingTrackId || !canSendTelemetry()) return
        var tolerance = TRACK_SIMPLIFY_M
        var points = RoutePolyline.simplify(route.points, tolerance)
        while (points.size > MAX_SHARED_POINTS) {
            tolerance *= 2
            points = RoutePolyline.simplify(route.points, tolerance)
        }
        if (points.size < 2) return
        val kind = if (route.kind == RouteKind.Plan) RouteTelemetry.Kind.Plan else RouteTelemetry.Kind.Track
        repository.sendTelemetry(RouteTelemetry(route.id, kind, route.name, points).encode())
        showNotice("\"${route.name}\" dibagikan (${points.size} titik)")
    }

    fun shareWaypoint(id: String) {
        val wp = _state.value.waypoints.firstOrNull { it.id == id } ?: return
        if (!canSendTelemetry()) return
        sendWaypoint(wp.id, wp.type, wp.point, wp.label, wp.victim, wp)
        showNotice("${wp.type.label} dibagikan lewat mesh")
    }

    /** Hapus titik dari HP ini saja (tidak menghapus di HP lain). */
    fun deleteWaypoint(id: String) = _state.update { current ->
        current.copy(
            waypoints = current.waypoints.filterNot { it.id == id },
            selectedWaypointId = current.selectedWaypointId?.takeIf { it != id },
        )
    }

    private var trackPoints = ArrayList<GeoPoint>()
    /** Indeks titik pertama yang belum terkirim di [trackPoints]. */
    private var trackUnsentFrom = 0
    private var trackSeq = 0
    private var lastTrackFlushAt = 0L

    fun startTrackRecording() {
        if (_state.value.recordingTrackId != null) return
        val id = newTelemetryId()
        val now = currentEpochMillis()
        trackPoints = ArrayList()
        trackUnsentFrom = 0
        trackSeq = 0
        lastTrackFlushAt = now
        _state.value.trackedUsers.firstOrNull { it.own }?.let { trackPoints += GeoPoint(it.latitude, it.longitude) }
        val route = SharedRoute(
            id, repository.myPeerId, repository.nickname, "Jejak ${repository.nickname}", RouteKind.LiveTrack,
            mapOf(0 to trackPoints.toList()), now, own = true, verified = hasKey,
        )
        _state.update { it.copy(recordingTrackId = id, routes = (it.routes + route).takeLast(MAX_ROUTES)) }
        showNotice(
            if (_state.value.mesh.active) "Perekaman jejak dimulai. Jalur dibagikan tiap menit lewat mesh."
            else "Perekaman jejak dimulai. Aktifkan mesh agar jalur ikut dibagikan.",
        )
    }

    fun stopTrackRecording() {
        val id = _state.value.recordingTrackId ?: return
        flushTrack(force = true)
        val summary = RoutePolyline.simplify(trackPoints, TRACK_SIMPLIFY_M)
        val now = currentEpochMillis()
        if (summary.size >= 2) {
            // Ringkasan utuh menggantikan potongan yang mungkin hilang di penerima & sampai ke yang baru bergabung.
            if (_state.value.mesh.active) {
                repository.sendTelemetry(RouteTelemetry(id, RouteTelemetry.Kind.Track, "Jejak ${repository.nickname}", summary).encode())
            }
            _state.update { current ->
                current.copy(
                    recordingTrackId = null,
                    routes = current.routes.map {
                        if (it.id == id) it.copy(kind = RouteKind.Track, segments = mapOf(0 to summary), updatedAtMs = now) else it
                    },
                )
            }
            showNotice("Jejak disimpan: ${formatDistance(RoutePolyline.lengthMeters(summary))}")
        } else {
            _state.update { current -> current.copy(recordingTrackId = null, routes = current.routes.filterNot { it.id == id }) }
            showNotice("Jejak terlalu pendek, tidak disimpan")
        }
        trackPoints = ArrayList()
    }

    private fun recordTrackPoint(location: DeviceLocation) {
        val id = _state.value.recordingTrackId ?: return
        if (location.accuracyMeters > TRACK_MAX_ACCURACY_M) return
        val point = GeoPoint(location.latitude, location.longitude)
        val last = trackPoints.lastOrNull()
        if (last != null && RoutePolyline.distanceMeters(last, point) < TRACK_MIN_STEP_M) return
        trackPoints += point
        val points = trackPoints.toList()
        _state.update { current ->
            current.copy(routes = current.routes.map {
                if (it.id == id) it.copy(segments = mapOf(0 to points), updatedAtMs = location.timestampMs) else it
            })
        }
        flushTrack(force = false)
    }

    /** Kirim titik yang belum terkirim sebagai satu potongan (berkala, atau paksa saat berhenti). */
    private fun flushTrack(force: Boolean) {
        val id = _state.value.recordingTrackId ?: return
        val pending = trackPoints.size - trackUnsentFrom
        if (pending <= 0 || !_state.value.mesh.active) return
        val now = currentEpochMillis()
        if (!force && pending < TRACK_FLUSH_POINTS && now - lastTrackFlushAt < TRACK_FLUSH_MS) return
        val segment = trackPoints.subList(maxOf(0, trackUnsentFrom - 1), trackPoints.size).toList()
        repository.sendTelemetry(
            TrackSegmentTelemetry(id, trackSeq++, repository.nickname, RoutePolyline.simplify(segment, TRACK_SIMPLIFY_M)).encode(),
        )
        trackUnsentFrom = trackPoints.size
        lastTrackFlushAt = now
    }

    fun startRouteDraft() = _state.update { it.copy(routeDraft = emptyList(), pickedPoint = null, roadRoute = null) }
    fun cancelRouteDraft() = _state.update { it.copy(routeDraft = null, roadRoute = null) }

    // ------------------------------------------------------------------------------------------
    //  Rute jalan otomatis (offline): A* di graf jalan OSM yang ikut APK
    // ------------------------------------------------------------------------------------------
    private var graph: RoadGraph? = null
    private var graphLoadFailed = false

    /**
     * Cari rute mengikuti jalan dari posisi sendiri ke titik yang diketuk di peta, atau ke tujuan navigasi
     * aktif (unit/titik). Hasilnya jadi draft rute: bisa diikuti (kompas per titik) atau dikirim ke tim.
     */
    fun planRoadRoute(mode: TravelMode) {
        if (mode == TravelMode.Foot) { planSarNavigation(); return }
        val current = _state.value
        if (current.routing) return
        val own = current.trackedUsers.firstOrNull { it.own } ?: return showNotice("Menunggu GPS untuk titik awal rute")
        val target = current.pickedPoint
            ?: current.waypoints.firstOrNull { it.id == current.selectedWaypointId }?.point
            ?: current.trackedUsers.firstOrNull { it.peerId == current.selectedTargetPeerId }?.let { GeoPoint(it.latitude, it.longitude) }
            ?: return showNotice("Ketuk tujuan di peta (atau pilih titik/unit untuk diarahkan) dulu")
        if (graphLoadFailed) return showNotice("Data jalan offline tidak tersedia di aplikasi ini")
        _state.update { it.copy(routing = true) }
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val g = graph ?: roadGraph()?.let(RoadGraph::parse)?.also { graph = it }
                        ?: error("Data jalan offline tidak tersedia di aplikasi ini")
                    g.route(GeoPoint(own.latitude, own.longitude), target, mode)
                }
            }
            result.exceptionOrNull()?.let { graphLoadFailed = graph == null }
            val route = result.getOrNull()
            _state.update { state ->
                if (route == null) state.copy(routing = false)
                else state.copy(
                    routing = false,
                    pickedPoint = null,
                    routeDraft = simplifyForSharing(route.points),
                    roadRoute = RoadRouteInfo(route.distanceMeters, route.durationSeconds, route.mode.label, route.offRoadMeters),
                )
            }
            when {
                result.isFailure -> showNotice(result.exceptionOrNull()?.message ?: "Gagal menghitung rute")
                route == null -> showNotice("Rute tidak ditemukan: titik terlalu jauh dari jalan (>2 km) atau jalan tidak terhubung")
            }
        }
    }

    /** Rute jalan bisa ribuan titik; disederhanakan (mulai 3 m) supaya muat dikirim lewat LoRa & ringan diikuti. */
    private fun simplifyForSharing(points: List<GeoPoint>): List<GeoPoint> {
        var tolerance = 3.0
        var out = RoutePolyline.simplify(points, tolerance)
        while (out.size > MAX_SHARED_POINTS) {
            tolerance *= 2
            out = RoutePolyline.simplify(points, tolerance)
        }
        return out
    }

    /** Simpan draft (mis. rute jalan) sebagai rute sendiri lalu langsung ikuti — tanpa mengirim ke tim. */
    fun followRouteDraft(name: String) {
        val draft = _state.value.routeDraft ?: return
        if (draft.size < 2) return
        val id = newTelemetryId()
        val title = name.trim().ifBlank { _state.value.roadRoute?.let { "Rute ${it.modeLabel.lowercase()}" } ?: "Rute ${repository.nickname}" }
        val route = SharedRoute(id, repository.myPeerId, repository.nickname, title, RouteKind.Plan, mapOf(0 to draft), currentEpochMillis(), own = true, verified = hasKey)
        _state.update {
            it.copy(
                routeDraft = null, roadRoute = null,
                routes = (it.routes + route).takeLast(MAX_ROUTES),
                followedRouteId = id, selectedTargetPeerId = null, selectedWaypointId = null,
            )
        }
        showNotice("Mengikuti \"$title\". Bagikan ke tim lewat tombol Bagikan bila perlu.")
    }
    fun undoRouteDraftPoint() = _state.update { it.copy(routeDraft = it.routeDraft?.dropLast(1)) }

    fun sendRouteDraft(name: String) {
        val draft = _state.value.routeDraft ?: return
        if (draft.size < 2) return showNotice("Ketuk minimal 2 titik di peta untuk membuat rute")
        if (!canSendTelemetry()) return
        val id = newTelemetryId()
        val title = name.trim().ifBlank { "Rute ${repository.nickname}" }
        repository.sendTelemetry(RouteTelemetry(id, RouteTelemetry.Kind.Plan, title, draft).encode())
        val route = SharedRoute(id, repository.myPeerId, repository.nickname, title, RouteKind.Plan, mapOf(0 to draft), currentEpochMillis(), own = true, verified = hasKey)
        _state.update { it.copy(routeDraft = null, roadRoute = null, routes = (it.routes + route).takeLast(MAX_ROUTES)) }
        showNotice("Rute \"$title\" dikirim (${formatDistance(RoutePolyline.lengthMeters(draft))})")
    }

    /** Ketukan di peta: menambah titik rute saat menggambar, selain itu menandai lokasi waypoint. */
    fun onMapTap(point: GeoPoint) {
        if (!point.valid) return
        _state.update { current ->
            // Rute jalan otomatis tidak ditambah titik manual; ketukan baru = tujuan baru.
            if (current.routeDraft != null && current.roadRoute == null) current.copy(routeDraft = (current.routeDraft + point).takeLast(MAX_DRAFT_POINTS))
            else current.copy(pickedPoint = point)
        }
    }

    fun clearPickedPoint() = _state.update { it.copy(pickedPoint = null) }

    /** Tandai titik di lokasi yang diketuk di peta, atau di posisi GPS sendiri bila belum ada. */
    /** Tandai titik baru, di lokasi yang diketuk atau di posisi GPS sendiri. */
    fun addWaypoint(type: WaypointType, label: String, victim: VictimInfo? = null) {
        val current = _state.value
        val point = current.pickedPoint
            ?: current.trackedUsers.firstOrNull { it.own }?.let { GeoPoint(it.latitude, it.longitude) }
            ?: return showNotice("Ketuk peta atau tunggu GPS untuk menandai titik")
        if (!canSendTelemetry()) return
        sendWaypoint(newTelemetryId(), type, point, label, victim.takeIf { type == WaypointType.Korban }, existing = null)
        _state.update { it.copy(pickedPoint = null) }
        showNotice("${type.label} ditandai dan dikirim lewat mesh")
    }

    /**
     * Perbarui detail titik yang sudah ada (mis. triase memburuk, korban sudah dievakuasi). Id sama, jadi
     * semua HP mengganti versi lamanya; siapa pun di lapangan boleh memperbarui.
     */
    fun updateWaypoint(id: String, label: String, victim: VictimInfo?) {
        val existing = _state.value.waypoints.firstOrNull { it.id == id } ?: return
        if (!canSendTelemetry()) return
        sendWaypoint(id, existing.type, existing.point, label, victim.takeIf { existing.type == WaypointType.Korban }, existing)
        showNotice("${existing.type.label} diperbarui dan dikirim lewat mesh")
    }

    private fun sendWaypoint(id: String, type: WaypointType, point: GeoPoint, label: String, victim: VictimInfo?, existing: MapWaypoint?) {
        val text = label.trim().ifBlank { if (victim != null) "" else type.label }
        val telemetry = WaypointTelemetry(id, type, point, VictimInfo.compose(victim, text))
        val messageId = repository.sendTelemetry(telemetry.encode())
        if (type in ACKED_WAYPOINTS) rememberAckTarget(messageId, AckTarget.Waypoint(id))
        val waypoint = MapWaypoint(
            id = id,
            ownerPeerId = existing?.ownerPeerId ?: repository.myPeerId,
            ownerName = existing?.ownerName ?: repository.nickname,
            type = type, point = point, label = text.take(WaypointTelemetry.MAX_LABEL),
            createdAtMs = currentEpochMillis(), own = existing?.own ?: true, victim = victim,
            updatedBy = if (existing != null && !existing.own) repository.nickname else null,
            verified = hasKey,
        )
        _state.update { current ->
            current.copy(waypoints = (current.waypoints.filterNot { it.id == id } + waypoint).takeLast(MAX_WAYPOINTS))
        }
    }


    fun selectWaypoint(id: String?) = _state.update { current ->
        val valid = id?.takeIf { wanted -> current.waypoints.any { it.id == wanted } }
        current.copy(
            selectedWaypointId = valid,
            selectedTargetPeerId = if (valid != null) null else current.selectedTargetPeerId,
            followedRouteId = if (valid != null) null else current.followedRouteId,
            sarNavigation = SarNavigation(),
            pickedPoint = if (valid != null) null else current.pickedPoint,
        )
    }.also { store.put("sar-navigation-v1", ""); if (id != null) planSarNavigation() }

    fun followRoute(id: String?) = _state.update { current ->
        val valid = id?.takeIf { wanted -> current.routes.any { it.id == wanted } }
        current.copy(
            followedRouteId = valid,
            selectedTargetPeerId = if (valid != null) null else current.selectedTargetPeerId,
            selectedWaypointId = if (valid != null) null else current.selectedWaypointId,
            sarNavigation = SarNavigation(),
            pickedPoint = if (valid != null) null else current.pickedPoint,
        )
    }.also { store.put("sar-navigation-v1", "") }

    private fun canSendTelemetry(): Boolean {
        if (!_state.value.mesh.active) {
            showNotice("Aktifkan mesh supaya rute/titik bisa dikirim")
            return false
        }
        return true
    }

    /** true bila pesan adalah telemetri jejak/rute/waypoint (sudah ditangani, jangan masuk chat). */
    private fun receiveRouteTelemetry(incoming: IncomingMessage): Boolean {
        val content = incoming.message.content
        if (!content.startsWith("@meshta-")) return false
        val peerId = incoming.fromPeerId
        val sender = incoming.message.sender.ifBlank { repository.nicknameOf(peerId) ?: shortId(peerId) }
        val now = currentEpochMillis()
        TrackSegmentTelemetry.decode(content)?.let { segment ->
            val isNew = _state.value.routes.none { it.id == segment.trackId }
            upsertRoute(segment.trackId) { existing ->
                // Ringkasan akhir (Track) lebih lengkap dari potongan yang telat datang; sumber tak
                // terverifikasi tidak boleh menyusupkan potongan ke jejak tim yang terverifikasi.
                if (existing?.kind == RouteKind.Track || (existing?.verified == true && !incoming.verified)) existing
                else SharedRoute(
                    segment.trackId, peerId, sender, "Jejak $sender", RouteKind.LiveTrack,
                    ((existing?.segments ?: emptyMap()) + (segment.seq to segment.points)).entries
                        .sortedBy { it.key }.takeLast(MAX_SEGMENTS).associate { it.key to it.value },
                    now,
                    verified = incoming.verified,
                )
            }
            if (isNew) appendSystem("$sender mulai membuka jalur. Jejaknya tampil di peta.")
            return true
        }
        RouteTelemetry.decode(content)?.let { route ->
            val isNew = _state.value.routes.none { it.id == route.routeId }
            val kind = if (route.kind == RouteTelemetry.Kind.Plan) RouteKind.Plan else RouteKind.Track
            upsertRoute(route.routeId) { existing ->
                if (existing?.verified == true && !incoming.verified) existing
                else SharedRoute(route.routeId, peerId, sender, route.name.ifBlank { "Rute $sender" }, kind, mapOf(0 to route.points), now, verified = incoming.verified)
            }
            if (isNew) {
                val what = if (kind == RouteKind.Plan) "rute \"${route.name}\"" else "jejak lengkap"
                appendSystem("$sender membagikan $what (${formatDistance(RoutePolyline.lengthMeters(route.points))}).")
            }
            return true
        }
        WaypointTelemetry.decode(content)?.let { wp ->
            val sentAt = incoming.message.timestampMs
            val existing = _state.value.waypoints.firstOrNull { it.id == wp.waypointId }
            // Duplikat atau pembaruan yang lebih lama dari yang sudah kita punya: abaikan.
            if (existing != null && sentAt <= existing.createdAtMs) return true
            // Titik tim yang terverifikasi tidak boleh diubah orang tanpa kunci (mis. "jembatan aman" palsu).
            if (existing?.verified == true && !incoming.verified) return true
            val (victim, label) = if (wp.type == WaypointType.Korban) VictimInfo.parse(wp.label) else null to wp.label
            val waypoint = MapWaypoint(
                id = wp.waypointId,
                ownerPeerId = existing?.ownerPeerId ?: peerId,
                ownerName = existing?.ownerName ?: sender,
                type = wp.type, point = wp.point, label = label, createdAtMs = sentAt,
                own = existing?.own ?: false, ackedBy = existing?.ackedBy.orEmpty(), victim = victim,
                updatedBy = if (existing != null) sender else null,
                verified = incoming.verified,
            )
            _state.update { current ->
                current.copy(waypoints = (current.waypoints.filterNot { it.id == wp.waypointId } + waypoint).takeLast(MAX_WAYPOINTS))
            }
            val detail = listOfNotNull(victim?.summary(), label.ifBlank { null }).joinToString(" · ")
            appendSystem(if (existing == null) "$sender menandai ${wp.type.label}: $detail" else "$sender memperbarui ${wp.type.label}: $detail")
            if (wp.type in ACKED_WAYPOINTS) acknowledge(incoming.message.id, peerId)
            return true
        }
        return false
    }

    private fun upsertRoute(id: String, build: (SharedRoute?) -> SharedRoute) = _state.update { current ->
        val existing = current.routes.firstOrNull { it.id == id }
        val updated = build(existing)
        current.copy(routes = (current.routes.filterNot { it.id == id } + updated).takeLast(MAX_ROUTES))
    }

    private fun appendSystem(text: String) = _state.update { it.copy(messages = it.messages + systemMessage(text)) }

    private fun newTelemetryId() = Random.nextLong(0, 36L * 36 * 36 * 36 * 36 * 36 * 36 * 36).toString(36)

    private fun sendEmergency(telemetry: EmergencyTelemetry) {
        val id = repository.sendEmergency(telemetry.copy(batteryPercent = batteryLevel()))
        if (telemetry.action == EmergencyTelemetry.Action.Alert) rememberAckTarget(id, AckTarget.Sos)
    }

    // ------------------------------------------------------------------------------------------
    //  Konfirmasi terima (DELIVERY_ACK) untuk pesan penting
    // ------------------------------------------------------------------------------------------
    private sealed interface AckTarget {
        data class Chat(val messageId: String) : AckTarget
        data object Sos : AckTarget
        data class Waypoint(val waypointId: String) : AckTarget
    }

    /** id pesan kita yang menunggu konfirmasi → apa yang diperbarui saat konfirmasi datang. */
    private val ackTargets = LinkedHashMap<String, AckTarget>()
    /** id pesan orang lain yang sudah kita konfirmasi (sekali saja per pesan). */
    private val ackedIncoming = LinkedHashSet<String>()

    private fun rememberAckTarget(messageId: String, target: AckTarget) {
        ackTargets[messageId] = target
        while (ackTargets.size > MAX_ACK_TRACKED) ackTargets.remove(ackTargets.keys.first())
    }

    private fun onAck(messageId: String, fromPeerId: String) {
        val target = ackTargets[messageId] ?: return
        val name = repository.nicknameOf(fromPeerId) ?: shortId(fromPeerId)
        fun List<String>.with(name: String) = if (name in this) this else this + name
        _state.update { current ->
            when (target) {
                is AckTarget.Chat -> current.copy(messages = current.messages.map {
                    if (it.id == target.messageId) it.copy(ackedBy = it.ackedBy.with(name),
                        acknowledgedAtMs = it.acknowledgedAtMs ?: currentEpochMillis()) else it
                })
                AckTarget.Sos -> if (current.sosActive) current.copy(sosAckedBy = current.sosAckedBy.with(name)) else current
                is AckTarget.Waypoint -> current.copy(waypoints = current.waypoints.map {
                    if (it.id == target.waypointId) it.copy(ackedBy = it.ackedBy.with(name)) else it
                })
            }
        }
    }

    /**
     * Balas konfirmasi ke pengirim asli, dengan jeda acak supaya balasan dari banyak HP tidak bertabrakan
     * di antrean node yang sama.
     */
    private fun acknowledge(messageId: String, toPeerId: String) {
        if (toPeerId == repository.myPeerId || !ackedIncoming.add(messageId)) return
        while (ackedIncoming.size > MAX_ACK_TRACKED) ackedIncoming.remove(ackedIncoming.first())
        scope.launch {
            delay(Random.nextLong(ACK_JITTER_MIN_MS, ACK_JITTER_MAX_MS))
            if (_state.value.mesh.active) repository.sendAck(toPeerId, messageId)
        }
    }

    /**
     * Status cepat: dikirim sebagai pesan chat (terbaca juga di versi lama) lalu langsung disusul posisi
     * terkini, supaya tim tahu di mana status itu berlaku.
     */
    fun sendQuickStatus(status: QuickStatus) {
        if (!canSend()) return
        sendChat(status.encode(), GLOBAL_CHAT_ID)
        val now = currentEpochMillis()
        _state.value.trackedUsers.firstOrNull { it.own }?.let { own ->
            lastLocationSentAt = now
            repository.sendLocation(own.latitude, own.longitude, own.accuracyMeters, now, batteryLevel())
        }
        _state.update { it.copy(unitStatuses = it.unitStatuses + (repository.myPeerId to UnitStatus(status, now, repository.nickname))) }
    }

    fun updateHeading(degrees: Float?) = _state.update { it.copy(headingDegrees = degrees) }

    fun selectNavigationTarget(peerId: String?) = _state.update { current ->
        val valid = peerId?.takeIf { id -> current.trackedUsers.any { it.peerId == id && !it.own } }
        current.copy(
            selectedTargetPeerId = valid,
            selectedWaypointId = if (valid != null) null else current.selectedWaypointId,
            followedRouteId = if (valid != null) null else current.followedRouteId,
            sarNavigation = SarNavigation(),
            pickedPoint = if (valid != null) null else current.pickedPoint,
        )
    }.also { store.put("sar-navigation-v1", ""); if (peerId != null) planSarNavigation() }

    fun planSarNavigation() {
        val current = _state.value
        val own = current.trackedUsers.firstOrNull { it.own } ?: return failSarNavigation("Menunggu GPS untuk awal navigasi")
        if (currentEpochMillis() - own.updatedAtMs > 60_000) return failSarNavigation("Posisi GPS sudah lama; tunggu pembaruan posisi")
        val unit = current.trackedUsers.firstOrNull { it.peerId == current.selectedTargetPeerId }
        val wp = current.waypoints.firstOrNull { it.id == current.selectedWaypointId }
        val saved = current.sarNavigation.route?.request
        val to = current.pickedPoint ?: wp?.point ?: unit?.let { GeoPoint(it.latitude, it.longitude) } ?: saved?.to
            ?: return failSarNavigation("Pilih korban/unit, titik, atau tujuan di peta dulu")
        val key = if (current.pickedPoint != null) "point:${currentEpochMillis()}" else wp?.let { "waypoint:${it.id}" } ?: unit?.let { "unit:${it.peerId}" } ?: saved?.targetKey ?: "point:${currentEpochMillis()}"
        val title = if (current.pickedPoint != null) "Titik peta" else wp?.let { "${it.type.label}: ${it.label}" } ?: unit?.name ?: saved?.title ?: "Titik peta"
        if (RoutePolyline.distanceMeters(GeoPoint(own.latitude, own.longitude), to) < 1) {
            _state.update { it.copy(sarNavigation = SarNavigation(status = "ARRIVED", message = "Sudah di koordinat tujuan; temuan belum dikonfirmasi")) }; return
        }
        val request = SarRequest("sar-${currentEpochMillis()}-${Random.nextInt()}", GeoPoint(own.latitude, own.longitude), to, key, title)
        _state.update { it.copy(sarNavigation = SarNavigation(request, it.sarNavigation.route, "CALCULATING"), followedRouteId = null) }
    }

    fun receiveSarNavigation(text: String) {
        val envelope = runCatching { SarRouteCodec.objectOf(text) }.getOrNull() ?: return
        fun field(key: String) = (envelope[key] as? JsonPrimitive)?.contentOrNull
        if (field("action") == "select") {
            if (field("kind") == "point") {
                val lat = (envelope["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return
                val lon = (envelope["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return
                val point = GeoPoint(lat, lon)
                if (!point.valid) return
                _state.update { it.copy(pickedPoint = point, selectedTargetPeerId = null, selectedWaypointId = null, followedRouteId = null, routeDraft = null, roadRoute = null, sarNavigation = SarNavigation()) }
                store.put("sar-navigation-v1", "")
                planSarNavigation()
                return
            }
            val id = field("targetId") ?: return
            when (field("kind")) { "unit" -> selectNavigationTarget(id); "waypoint" -> selectWaypoint(id) }
            return
        }
        val pending = _state.value.sarNavigation.request ?: return
        if (field("id") != pending.id) return
        val result = runCatching { SarRouteCodec.decode(text, pending) }
        val own = _state.value.trackedUsers.firstOrNull { it.own }
        val unit = _state.value.trackedUsers.firstOrNull { pending.targetKey == "unit:${it.peerId}" }
        val wp = _state.value.waypoints.firstOrNull { pending.targetKey == "waypoint:${it.id}" }
        val newTo = wp?.point ?: unit?.let { GeoPoint(it.latitude, it.longitude) } ?: pending.to
        if (own == null || RoutePolyline.distanceMeters(GeoPoint(own.latitude, own.longitude), pending.from) > 15 || RoutePolyline.distanceMeters(newTo, pending.to) > 1) {
            _state.update { it.copy(sarNavigation = it.sarNavigation.copy(request = null, status = "STALE", message = "Posisi berubah saat perhitungan; hitung ulang")) }; return
        }
        result.fold(onSuccess = { route ->
            _state.update { it.copy(sarNavigation = SarNavigation(route = route, status = "READY"), pickedPoint = null) }
            runCatching { store.put("sar-navigation-v1", SarRouteCodec.save(text, pending)) }.onFailure { showNotice("Rute tersedia tetapi penyimpanan gagal") }
        }, onFailure = {
            val message = ((envelope["result"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Hasil routing tidak valid"
            _state.update { it.copy(sarNavigation = it.sarNavigation.copy(request = null, status = if (it.sarNavigation.route != null) "STALE" else "UNAVAILABLE", message = message)) }
            showNotice(message)
        })
    }
    private fun failSarNavigation(message: String) {
        _state.update { it.copy(sarNavigation = it.sarNavigation.copy(request = null, status = if (it.sarNavigation.route == null) "UNAVAILABLE" else "STALE", message = message)) }
        showNotice(message)
    }

    fun setSarNavigationActive(active: Boolean) = _state.update { current ->
        val checked = refreshSarStatus(current); val nav = checked.sarNavigation
        val own = checked.trackedUsers.firstOrNull { it.own }
        if (nav.route == null || active && (nav.status !in listOf("READY", "PAUSED") || own == null || currentEpochMillis() - own.updatedAtMs > 60_000)) checked
        else checked.copy(sarNavigation = nav.copy(status = if (active) "ACTIVE" else "PAUSED"))
    }
    fun stopSarNavigation() { _state.update { it.copy(sarNavigation = SarNavigation(), selectedTargetPeerId = null, selectedWaypointId = null, followedRouteId = null) }; store.put("sar-navigation-v1", "") }
    private fun refreshSarStatus(current: AppUiState): AppUiState {
        val nav = current.sarNavigation; val route = nav.route ?: return current
        if (nav.status !in listOf("READY", "PAUSED", "ACTIVE")) return current
        val own = current.trackedUsers.firstOrNull { it.own }
        val unit = current.trackedUsers.firstOrNull { route.request.targetKey == "unit:${it.peerId}" }
        val wp = current.waypoints.firstOrNull { route.request.targetKey == "waypoint:${it.id}" }
        val destination = wp?.point ?: unit?.let { GeoPoint(it.latitude, it.longitude) }
        val checked = id.nusamesh.app.routing.checkedSarNavigation(nav,
            own?.let { GeoPoint(it.latitude, it.longitude) },
            own?.let { currentEpochMillis() - it.updatedAtMs } ?: Long.MAX_VALUE,
            own?.accuracyMeters?.toDouble() ?: 0.0, destination)
        if (checked != nav) return current.copy(sarNavigation = checked)
        return current
    }

    private fun receiveLocation(incoming: IncomingMessage) {
        val telemetry = LocationTelemetry.decode(incoming.message.content) ?: return
        val peerId = incoming.fromPeerId
        val peer = _state.value.mesh.engine.peers.firstOrNull { it.peerId == peerId }
        val tracked = TrackedUser(
            peerId = peerId,
            name = incoming.message.sender.ifBlank { peer?.nickname ?: shortId(peerId) },
            latitude = telemetry.latitude,
            longitude = telemetry.longitude,
            accuracyMeters = telemetry.accuracyMeters,
            updatedAtMs = telemetry.timestampMs,
            rssi = peer?.rssi,
            direct = peer?.direct == true,
            emergency = _state.value.trackedUsers.firstOrNull { it.peerId == peerId }?.emergency == true,
            batteryPercent = telemetry.batteryPercent ?: _state.value.trackedUsers.firstOrNull { it.peerId == peerId }?.batteryPercent,
            verified = incoming.verified,
        )
        _state.update { current ->
            current.copy(trackedUsers = current.trackedUsers.filterNot { it.peerId == peerId } + tracked)
        }
    }

    private fun receiveEmergency(incoming: IncomingMessage) {
        val telemetry = EmergencyTelemetry.decode(incoming.message.content) ?: return
        val peerId = incoming.fromPeerId
        val peer = _state.value.mesh.engine.peers.firstOrNull { it.peerId == peerId }
        val active = telemetry.action == EmergencyTelemetry.Action.Alert
        // Konfirmasi hanya untuk siaran SOS pertama, bukan tiap pengulangan 15–30 detik.
        if (active && _state.value.trackedUsers.firstOrNull { it.peerId == peerId }?.emergency != true) {
            acknowledge(incoming.message.id, peerId)
        }
        _state.update { current ->
            val previous = current.trackedUsers.firstOrNull { it.peerId == peerId }
            val unit = TrackedUser(
                peerId = peerId,
                name = incoming.message.sender.ifBlank { peer?.nickname ?: shortId(peerId) },
                latitude = telemetry.latitude,
                longitude = telemetry.longitude,
                accuracyMeters = telemetry.accuracyMeters,
                updatedAtMs = telemetry.timestampMs,
                rssi = peer?.rssi,
                direct = peer?.direct == true,
                own = false,
                emergency = active,
                batteryPercent = telemetry.batteryPercent ?: previous?.batteryPercent,
                verified = incoming.verified,
            )
            current.copy(
                trackedUsers = current.trackedUsers.filterNot { it.peerId == peerId } +
                    if (active || previous == null) unit else unit.copy(emergency = false),
                notice = if (active) "SOS diterima dari ${unit.name}" else "SOS ${unit.name} dibatalkan",
            )
        }
    }

    private fun canSend(): Boolean {
        val status = _state.value.mesh
        val linked = status.engine.peers.any { it.direct } || status.engine.nodes.any { it.connected }
        if (!status.active) {
            showNotice("Aktifkan mesh dulu di halaman Beranda")
            return false
        }
        if (!linked) {
            showNotice("Belum ada HP atau Nusa Node yang tersambung")
            return false
        }
        return true
    }

    private fun currentPath(): Pair<DeliveryPath, String?> {
        val snapshot = _state.value.mesh.engine
        return when {
            snapshot.servingNode != null -> DeliveryPath.Node to snapshot.servingNode?.name
            snapshot.nebeng != null -> DeliveryPath.Nebeng to snapshot.nebeng.relayName
            else -> DeliveryPath.Ble to null
        }
    }

    /** Mode uji "LoRa saja" (disimpan): untuk menguji suara/teks lewat radio walau HP penerima dekat. */
    fun setLoraOnly(on: Boolean) {
        repository.loraOnly = on
        store.put(KEY_LORA_ONLY, if (on) "1" else "0")
        _state.update { it.copy(loraOnly = on) }
        showNotice(
            when {
                !on -> "Mode LoRa saja mati: pesan kembali lewat BLE + LoRa"
                _state.value.mesh.engine.servingNode == null -> "Mode LoRa saja aktif, tapi belum ada Nusa Node tersambung: kiriman akan gagal"
                else -> "Mode LoRa saja aktif: kiriman Anda hanya lewat Nusa Node"
            },
        )
    }

    fun showNotice(message: String) = _state.update { it.copy(notice = message) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun systemMessage(text: String) = ChatMessage(
        id = "sys-${currentEpochMillis()}-${text.hashCode()}",
        conversationId = GLOBAL_CHAT_ID,
        senderName = "Sistem",
        body = text,
        time = formatClock(currentEpochMillis()),
        outgoing = false,
        delivery = DeliveryState.Received,
        path = DeliveryPath.Ble,
        viaName = SYSTEM,
    )

    private fun globalPreview(message: String, unread: Int, time: String) = ChatPreview(
        peerId = GLOBAL_CHAT_ID,
        initials = "GM",
        name = "Global Mesh",
        message = message,
        time = time,
        unread = unread,
        accent = 0xFF0891B2,
        global = true,
    )

    /** Percakapan dengan pesan terbaru naik ke atas daftar. */
    private fun upsertPreview(chats: List<ChatPreview>, conversationId: String, message: String, unreadDelta: Int, time: String): List<ChatPreview> {
        val existing = chats.firstOrNull { it.peerId == conversationId }
        val unread = (existing?.unread ?: 0) + unreadDelta
        val preview = if (conversationId == GLOBAL_CHAT_ID) globalPreview(message, unread, time) else channelPreview(conversationId, message, unread, time)
        return listOf(preview) + chats.filterNot { it.peerId == conversationId }
    }

    private fun channelPreview(conversationId: String, message: String, unread: Int, time: String) = ChatPreview(
        peerId = conversationId,
        initials = if (conversationId == FieldChannels.SAR_CHAT_ID) "OP" else "TM",
        name = FieldChannels.title(conversationId, "Global Mesh"),
        message = message,
        time = time,
        unread = unread,
        accent = if (conversationId == FieldChannels.SAR_CHAT_ID) 0xFFDC2626 else 0xFF0D9488,
    )

    /** Pastikan percakapan channel sesuai peran ada di daftar; buang yang tidak lagi berlaku. */
    private fun withChannelPreviews(chats: List<ChatPreview>, role: FieldRole, team: String?): List<ChatPreview> {
        val wanted = buildList {
            if (role != FieldRole.Warga) add(FieldChannels.SAR_CHAT_ID)
            if (role == FieldRole.Tim && team != null) add(FieldChannels.teamChatId(team))
        }
        val kept = chats.filter { chat ->
            chat.peerId == GLOBAL_CHAT_ID || chat.peerId in wanted ||
                (role == FieldRole.Posko && FieldChannels.teamSlugOf(chat.peerId) != null)
        }
        return kept + wanted.filter { id -> kept.none { it.peerId == id } }.map { channelPreview(it, "Belum ada pesan", 0, "") }
    }

    // ------------------------------------------------------------------------------------------
    //  Uji QoS (TA): RSSI vs jarak, Packet Success Probability vs jarak per SF
    // ------------------------------------------------------------------------------------------
    /**
     * Data ukur penerimaan. Posisi pengirim: dari paket uji (bila ada) atau lokasi rutin terakhirnya.
     * Latensi memakai jam dua HP yang berbeda — sinkronkan jam (jaringan/GPS) sebelum uji.
     */
    private fun measure(
        fromPeerId: String,
        viaNode: Boolean,
        lora: LoraRxMeta?,
        bleRssi: Int?,
        sentAtMs: Long,
        senderPoint: GeoPoint? = null,
    ): RxMeasurement {
        val now = currentEpochMillis()
        val units = _state.value.trackedUsers
        val own = units.firstOrNull { it.own }
        val sender = units.firstOrNull { it.peerId == fromPeerId && !it.own }
        val from = senderPoint ?: sender?.let { GeoPoint(it.latitude, it.longitude) }
        return RxMeasurement(
            receivedAtMs = now,
            path = if (viaNode) DeliveryPath.Node else DeliveryPath.Ble,
            loraRssiDbm = lora?.rssiDbm,
            loraSnrDb = lora?.snrDb,
            spreadingFactor = lora?.spreadingFactor,
            bandwidthKHz = lora?.bandwidthKHz,
            hopLeft = lora?.hopLeft,
            metaMatched = lora?.matched,
            bleRssiDbm = bleRssi,
            distanceMeters = if (own != null && from != null) RoutePolyline.distanceMeters(GeoPoint(own.latitude, own.longitude), from) else null,
            senderPositionAgeMs = if (senderPoint != null) 0L else sender?.let { now - it.updatedAtMs },
            latencyMs = (now - sentAtMs).takeIf { it in -60_000L..3_600_000L },
        )
    }

    private var qosJob: Job? = null

    /** Kirim [total] paket uji bernomor tiap [intervalSeconds]; penerima menghitung PSP & RSSI. */
    fun startQosTest(total: Int, intervalSeconds: Int, label: String) {
        if (qosJob != null || !canSendTelemetry()) return
        val runId = newTelemetryId()
        val count = total.coerceIn(1, QosProbe.MAX_TOTAL)
        val intervalMs = intervalSeconds.coerceIn(2, 600) * 1000L
        val name = label.trim().ifBlank { "Uji ${formatClock(currentEpochMillis())}" }
        _state.update { it.copy(qosSending = QosSending(runId, name, 0, count, intervalMs)) }
        qosJob = scope.launch {
            for (seq in 1..count) {
                val own = _state.value.trackedUsers.firstOrNull { it.own }
                val sf = _state.value.mesh.engine.servingNode?.spreadingFactor
                repository.sendTelemetry(QosProbe(runId, seq, count, sf, own?.let { GeoPoint(it.latitude, it.longitude) }, name).encode())
                _state.update { it.copy(qosSending = it.qosSending?.copy(sent = seq)) }
                if (seq < count) delay(intervalMs)
            }
            qosJob = null
            _state.update { it.copy(qosSending = null) }
            showNotice("Uji QoS \"$name\" selesai: $count paket dikirim")
        }
    }

    fun stopQosTest() {
        qosJob?.cancel()
        qosJob = null
        _state.update { it.copy(qosSending = null) }
    }

    fun clearQosRuns() = _state.update { it.copy(qosRuns = emptyList()) }

    fun qosPacketsCsv() = QosRecords.packetsCsv(_state.value.qosRuns)
    fun qosSummaryCsv() = QosRecords.summaryCsv(_state.value.qosRuns)

    /** Ganti SF Nusa Node yang melayani HP ini (hanya diterima node saat mode uji jarak aktif). */
    fun setNodeSpreadingFactor(sf: Int) {
        val node = _state.value.mesh.engine.servingNode ?: return showNotice("Belum tersambung ke Nusa Node")
        if (!node.testMode) return showNotice("Tekan tombol BOOT di node untuk menyalakan mode uji, lalu coba lagi")
        repository.sendRadioConfig(node.peerId, sf)
        showNotice("Permintaan SF$sf dikirim ke ${node.name}. Ganti juga di node lain — beda SF tidak saling dengar.")
    }

    private fun receiveQos(incoming: IncomingMessage, probe: QosProbe) {
        val sender = incoming.message.sender.ifBlank { repository.nicknameOf(incoming.fromPeerId) ?: shortId(incoming.fromPeerId) }
        val rx = measure(incoming.fromPeerId, incoming.viaNode, incoming.loraRx, incoming.bleRssi, incoming.message.timestampMs, probe.senderPoint)
        _state.update { current ->
            val existing = current.qosRuns.firstOrNull { it.runId == probe.runId && it.senderPeerId == incoming.fromPeerId }
            if (existing?.samples?.any { it.seq == probe.seq } == true) return@update current // duplikat lewat jalur lain
            val run = (existing ?: QosRun(probe.runId, incoming.fromPeerId, sender, probe.label, probe.total, probe.senderSf, startedAtMs = rx.receivedAtMs))
                .let { it.copy(samples = it.samples + QosSample(probe.seq, rx)) }
            current.copy(qosRuns = (current.qosRuns.filterNot { it === existing } + run).takeLast(MAX_QOS_RUNS))
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Kunci operasi (keamanan)
    // ------------------------------------------------------------------------------------------
    private val hasKey get() = repository.operationKey != null

    private fun restoreOperationKey() {
        val key = store.get(KEY_OP_CODE)?.let(OperationKey::fromCode) ?: return
        repository.operationKey = key
        _state.update { it.copy(operationCode = key.displayCode) }
    }

    /** Posko membuat kunci baru; kodenya dibacakan/ditulis saat briefing, JANGAN dikirim lewat chat. */
    fun generateOperationKey() = applyOperationKey(OperationKey.generate())

    fun enterOperationKey(code: String) {
        val key = OperationKey.fromCode(code)
            ?: return showNotice("Kode kunci harus ${OperationKey.CODE_LENGTH} huruf/angka (tanpa I, O, 0, 1)")
        applyOperationKey(key)
    }

    private fun applyOperationKey(key: OperationKey) {
        repository.operationKey = key
        store.put(KEY_OP_CODE, key.code)
        _state.update { it.copy(operationCode = key.displayCode) }
        showNotice("Kunci operasi aktif. Pesan tim kini ditandatangani & channel tim dienkripsi.")
    }

    fun clearOperationKey() {
        repository.operationKey = null
        store.put(KEY_OP_CODE, "")
        _state.update { it.copy(operationCode = null) }
        showNotice("Kunci operasi dihapus dari HP ini")
    }

    private var lastForgedNoticeAt = 0L

    /** Pesan yang mengaku bertanda tangan kunci kita tapi tidak valid dibuang; tim diberi tahu (dibatasi). */
    private fun rejectForged(fromPeerId: String) {
        val now = currentEpochMillis()
        if (now - lastForgedNoticeAt < FORGED_NOTICE_INTERVAL_MS) return
        lastForgedNoticeAt = now
        val name = repository.nicknameOf(fromPeerId) ?: shortId(fromPeerId)
        appendSystem("Peringatan: pesan dari $name memakai tanda tangan kunci operasi yang tidak valid dan diabaikan (kemungkinan palsu).")
    }

    /** Ganti peran/tim: menentukan channel chat yang terlihat. Pesan channel lama tetap tersimpan. */
    fun setRole(role: FieldRole, teamName: String?) {
        val team = teamName?.let(FieldChannels::teamSlug)
        if (role == FieldRole.Tim && team == null) return showNotice("Isi nama tim, mis. Alfa")
        store.put(KEY_ROLE, role.name)
        store.put(KEY_TEAM, team.orEmpty())
        _state.update { current ->
            val chats = withChannelPreviews(current.chats, role, team.takeIf { role == FieldRole.Tim })
            current.copy(
                role = role,
                team = team.takeIf { role == FieldRole.Tim },
                chats = chats,
                activeConversationId = current.activeConversationId?.takeIf { id -> chats.any { it.peerId == id } },
            )
        }
        showNotice(if (role == FieldRole.Tim) "Peran: Tim SAR · ${FieldChannels.title(FieldChannels.teamChatId(team!!), "")}" else "Peran: ${role.label}")
    }

    companion object {
        const val GLOBAL_CHAT_ID = "global"
        /** Penanda [ChatMessage.viaName] untuk pesan sistem (ditampilkan sebagai kartu info, bukan gelembung). */
        const val SYSTEM = "\u0000system"
        private const val KEY_ACTIVE = "mesh_active"
        private const val KEY_SOS_ACTIVE = "sos_active"
        private const val KEY_LORA_ONLY = "lora_only_test"
        private const val KEY_ROLE = "field_role"
        private const val KEY_OP_CODE = "operation_key"
        private const val KEY_QOS = "qos_runs"
        private const val MAX_QOS_RUNS = 100
        private const val FORGED_NOTICE_INTERVAL_MS = 60_000L
        private const val KEY_TEAM = "field_team"
        private const val KEY_ROUTES = "map_routes"
        private const val KEY_WAYPOINTS = "map_waypoints"
        private const val KEY_EMERGENCIES = "sos_victims"
        private const val MAP_SAVE_DELAY_MS = 3_000L
        private const val LOCATION_STALE_MS = 3 * 60_000L
        /** Titik GPS lebih kabur dari ini tidak dipakai untuk jejak. */
        private const val TRACK_MAX_ACCURACY_M = 30f
        private const val TRACK_MIN_STEP_M = 8.0
        private const val TRACK_SIMPLIFY_M = 4.0
        /** Potongan jejak dikirim tiap menit atau tiap sekian titik baru: ±1 fragmen LoRa per potongan. */
        private const val TRACK_FLUSH_MS = 60_000L
        private const val TRACK_FLUSH_POINTS = 12
        private const val MAX_ROUTES = 40
        private const val MAX_SEGMENTS = 600
        private const val MAX_WAYPOINTS = 200
        private const val MAX_DRAFT_POINTS = 200
        private const val MAX_IMPORTED_POINTS = 5_000
        /** ±4 B per titik → ±1,2 KB: beberapa fragmen LoRa, bukan puluhan. */
        private const val MAX_SHARED_POINTS = 300
        private const val MAX_ACK_TRACKED = 300
        private const val ACK_JITTER_MIN_MS = 300L
        private const val ACK_JITTER_MAX_MS = 3_000L
        /** Titik yang menyangkut keselamatan: penerima membalas konfirmasi. */
        private val ACKED_WAYPOINTS = setOf(WaypointType.Korban, WaypointType.Bahaya)

        fun formatDistance(meters: Double) =
            if (meters < 1_000) "${meters.toInt()} m" else "${(meters / 100).toInt() / 10.0} km"

        fun kindOf(mime: String) = when {
            mime.startsWith("image/") -> ChatMessageKind.Image
            mime.startsWith("audio/") -> ChatMessageKind.Voice
            else -> ChatMessageKind.File
        }

        fun labelOf(kind: ChatMessageKind, name: String) = when (kind) {
            ChatMessageKind.Image -> "Gambar"
            ChatMessageKind.Voice -> "Voice note"
            else -> name
        }

        fun shortId(peerId: String) = "HP ${peerId.takeLast(4).uppercase()}"
    }
}
