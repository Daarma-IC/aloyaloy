package id.nusamesh.app

import id.nusamesh.app.data.KeyValueStore
import id.nusamesh.app.data.MeshRepository
import id.nusamesh.app.data.MobilityLogEntry
import id.nusamesh.app.data.currentEpochMillis
import id.nusamesh.app.data.formatClock
import id.nusamesh.app.domain.AppPage
import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessage
import id.nusamesh.app.domain.ChatMessageKind
import id.nusamesh.app.domain.ChatPreview
import id.nusamesh.app.domain.DeliveryPath
import id.nusamesh.app.domain.DeliveryState
import id.nusamesh.app.domain.MeshStatus
import id.nusamesh.app.domain.TrackedUser
import id.nusamesh.app.location.DeviceLocation
import id.nusamesh.app.mesh.engine.EngineSnapshot
import id.nusamesh.app.mesh.engine.IncomingFile
import id.nusamesh.app.mesh.engine.IncomingMessage
import id.nusamesh.app.mesh.mobility.MobilityConfig
import id.nusamesh.app.mesh.protocol.LocationTelemetry
import id.nusamesh.app.mesh.protocol.EmergencyTelemetry
import id.nusamesh.app.mesh.protocol.MeshMessageType
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

class AppController(
    private val repository: MeshRepository,
    private val store: KeyValueStore,
    initialPage: AppPage = AppPage.Home,
    initialConversationId: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(
        AppUiState(
            page = initialPage,
            nickname = repository.nickname,
            myPeerId = repository.myPeerId,
            activeConversationId = initialConversationId,
            sosActive = store.get(KEY_SOS_ACTIVE) == "1",
            chats = listOf(globalPreview("Chat bersama semua pengguna di mesh", 0, "")),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    val mobilityLog: StateFlow<List<MobilityLogEntry>> = repository.mobilityLog

    /** true bila pengguna terakhir kali menyalakan mesh: aplikasi menyalakannya lagi saat dibuka. */
    val resumeMeshOnLaunch: Boolean get() = store.get(KEY_ACTIVE) == "1"

    init {
        scope.launch { repository.snapshot.collect(::onSnapshot) }
        scope.launch { repository.messages.collect(::onIncomingMessage) }
        scope.launch { repository.files.collect(::onIncomingFile) }
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
                .filter { currentEpochMillis() - it.updatedAtMs <= LOCATION_STALE_MS }
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
        val m = incoming.message
        if (m.type == MeshMessageType.SOS || m.type == MeshMessageType.SOS_Cancel) {
            receiveEmergency(incoming)
            return
        }
        if (m.content.startsWith("${LocationTelemetry.PREFIX}|")) {
            receiveLocation(incoming)
            return
        }
        if (_state.value.messages.any { it.id == m.id }) return
        val message = ChatMessage(
            id = m.id,
            conversationId = GLOBAL_CHAT_ID,
            senderName = m.sender.ifBlank { repository.nicknameOf(incoming.fromPeerId) ?: shortId(incoming.fromPeerId) },
            body = m.content,
            time = formatClock(m.timestampMs),
            outgoing = false,
            delivery = DeliveryState.Received,
            path = if (incoming.viaNode) DeliveryPath.Node else DeliveryPath.Ble,
        )
        appendIncoming(message, m.content)
    }

    private fun onIncomingFile(incoming: IncomingFile) {
        val file = incoming.file
        val kind = kindOf(file.mimeType)
        val sender = repository.nicknameOf(incoming.fromPeerId) ?: shortId(incoming.fromPeerId)
        val message = ChatMessage(
            id = "file-${incoming.timestampMs}-${incoming.fromPeerId}",
            conversationId = GLOBAL_CHAT_ID,
            senderName = sender,
            body = labelOf(kind, file.fileName),
            time = formatClock(incoming.timestampMs),
            outgoing = false,
            kind = kind,
            delivery = DeliveryState.Received,
            attachmentName = file.fileName,
            attachmentBytes = file.content.size,
            attachmentMimeType = file.mimeType,
            attachmentData = file.content,
            path = if (incoming.viaNode) DeliveryPath.Node else DeliveryPath.Ble,
        )
        if (_state.value.messages.any { it.id == message.id }) return
        appendIncoming(message, message.body)
    }

    private fun appendIncoming(message: ChatMessage, preview: String) = _state.update { current ->
        val unread = if (current.activeConversationId == GLOBAL_CHAT_ID) 0 else 1
        current.copy(
            chats = upsertGlobalPreview(current.chats, "${message.senderName}: $preview", unread, message.time),
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

    fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!canSend()) return
        val sent = repository.sendText(clean)
        val (path, via) = currentPath()
        val outgoing = ChatMessage(
            id = sent.id,
            conversationId = GLOBAL_CHAT_ID,
            senderName = repository.nickname,
            body = clean,
            time = formatClock(sent.timestampMs),
            outgoing = true,
            delivery = DeliveryState.Sent,
            path = path,
            viaName = via,
        )
        _state.update { current ->
            current.copy(
                chats = upsertGlobalPreview(current.chats, clean, 0, outgoing.time),
                messages = current.messages + outgoing,
            )
        }
    }

    fun sendAttachment(attachment: ChatAttachment) {
        if (!canSend()) return
        val (path, via) = currentPath()
        val loraSkipped = path != DeliveryPath.Ble && attachment.bytes.size > id.nusamesh.app.mesh.engine.MeshEngine.LORA_FILE_MAX_BYTES
        val now = currentEpochMillis()
        val messageId = "file-$now-${repository.myPeerId}"
        val outgoing = ChatMessage(
            id = messageId,
            conversationId = GLOBAL_CHAT_ID,
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
            path = if (loraSkipped) DeliveryPath.Ble else path,
            viaName = if (loraSkipped) null else via,
        )
        _state.update { current ->
            current.copy(
                chats = upsertGlobalPreview(current.chats, outgoing.body, 0, outgoing.time),
                messages = current.messages + outgoing,
                notice = if (loraSkipped) "Lampiran di atas 96 KB hanya dikirim lewat Bluetooth, tidak lewat LoRa" else current.notice,
            )
        }
        repository.sendAttachment(
            attachment,
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
        )
        _state.update { current -> current.copy(trackedUsers = current.trackedUsers.filterNot { it.peerId == mine.peerId } + mine) }
        if (_state.value.mesh.active && location.timestampMs - lastLocationSentAt >= 30_000L) {
            lastLocationSentAt = location.timestampMs
            repository.sendLocation(location.latitude, location.longitude, location.accuracyMeters, location.timestampMs)
        }
        if (_state.value.mesh.active && _state.value.sosActive && location.timestampMs - lastSosSentAt >= 15_000L) {
            lastSosSentAt = location.timestampMs
            repository.sendEmergency(
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
    }

    fun toggleSos() {
        val current = _state.value
        val own = current.trackedUsers.firstOrNull { it.own }
        if (!current.mesh.active) {
            showNotice("Aktifkan mesh sebelum mengirim SOS")
            return
        }
        if (own == null) {
            showNotice("Menunggu koordinat GPS sebelum mengirim SOS")
            return
        }
        val activating = !current.sosActive
        val now = currentEpochMillis()
        repository.sendEmergency(
            EmergencyTelemetry(
                if (activating) EmergencyTelemetry.Action.Alert else EmergencyTelemetry.Action.Cancel,
                own.latitude,
                own.longitude,
                own.accuracyMeters,
                now,
            ),
        )
        lastSosSentAt = if (activating) now else 0L
        store.put(KEY_SOS_ACTIVE, if (activating) "1" else "0")
        _state.update { state ->
            state.copy(
                sosActive = activating,
                trackedUsers = state.trackedUsers.map { if (it.own) it.copy(emergency = activating, updatedAtMs = now) else it },
                notice = if (activating) "SOS aktif dan disiarkan lewat mesh" else "SOS dibatalkan",
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
                repository.sendEmergency(
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

    fun updateHeading(degrees: Float?) = _state.update { it.copy(headingDegrees = degrees) }

    fun selectNavigationTarget(peerId: String?) = _state.update { current ->
        current.copy(selectedTargetPeerId = peerId?.takeIf { id -> current.trackedUsers.any { it.peerId == id && !it.own } })
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

    private fun upsertGlobalPreview(chats: List<ChatPreview>, message: String, unreadDelta: Int, time: String): List<ChatPreview> {
        val existing = chats.firstOrNull { it.peerId == GLOBAL_CHAT_ID }
        return listOf(globalPreview(message, (existing?.unread ?: 0) + unreadDelta, time)) +
            chats.filterNot { it.peerId == GLOBAL_CHAT_ID }
    }

    companion object {
        const val GLOBAL_CHAT_ID = "global"
        /** Penanda [ChatMessage.viaName] untuk pesan sistem (ditampilkan sebagai kartu info, bukan gelembung). */
        const val SYSTEM = "\u0000system"
        private const val KEY_ACTIVE = "mesh_active"
        private const val KEY_SOS_ACTIVE = "sos_active"
        private const val LOCATION_STALE_MS = 3 * 60_000L

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
