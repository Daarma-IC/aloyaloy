package id.nusamesh.app

import id.nusamesh.app.data.MeshRepository
import id.nusamesh.app.data.currentEpochMillis
import id.nusamesh.app.domain.AppPage
import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessage
import id.nusamesh.app.domain.ChatMessageKind
import id.nusamesh.app.domain.ChatPreview
import id.nusamesh.app.domain.ConnectionPhase
import id.nusamesh.app.domain.DeliveryState
import id.nusamesh.app.protocol.NusaProtocol
import id.nusamesh.app.protocol.MeshMediaReassembler
import id.nusamesh.app.protocol.PacketType
import id.nusamesh.app.protocol.toPeerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppController(
    private val repository: MeshRepository,
    initialPage: AppPage = AppPage.Home,
    initialConversationId: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val peerId = NusaProtocol.newPeerId()
    private val mediaReassembler = MeshMediaReassembler()
    private val _state = MutableStateFlow(
        AppUiState(
            page = initialPage,
            activeConversationId = initialConversationId,
            chats = if (initialConversationId == GLOBAL_CHAT_ID) listOf(
                ChatPreview(
                    peerId = GLOBAL_CHAT_ID,
                    initials = "GM",
                    name = "Global Mesh",
                    message = "Chat bersama semua pengguna di node",
                    time = "sekarang",
                    accent = 0xFF0891B2,
                    global = true,
                ),
            ) else emptyList(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        scope.launch { repository.connection.collect { connection ->
            _state.update {
                it.copy(
                    connection = connection,
                    notice = if (connection.phase == ConnectionPhase.Failed) connection.detail else it.notice,
                )
            }
            if (connection.phase == ConnectionPhase.Connected) repository.announce("Nusa Mobile", peerId)
        } }
        scope.launch { repository.nearby.collect { nearby ->
            _state.update { current ->
                current.copy(
                    nearby = nearby,
                    fieldUnits = nearby.map { peripheral ->
                        id.nusamesh.app.domain.FieldUnit(
                            id = peripheral.id,
                            name = peripheral.name,
                            subtitle = "BLE · RSSI ${peripheral.rssi} dBm",
                            status = if (current.connection.peripheral?.id == peripheral.id &&
                                current.connection.phase == ConnectionPhase.Connected) "Terhubung" else "Terdeteksi",
                            rssi = peripheral.rssi,
                        )
                    },
                )
            }
        } }
        scope.launch { repository.packets.collect { packet ->
            repository.healthOf(packet)?.let { health -> _state.update { it.copy(health = health) } }
            if (packet.type == PacketType.Message) {
                val text = packet.payload.decodeToString()
                val sender = packet.senderId.toPeerId()
                val incoming = ChatMessage(
                    id = "${packet.timestampMs}-$sender",
                    conversationId = GLOBAL_CHAT_ID,
                    senderName = "Node ${sender.takeLast(4).uppercase()}",
                    body = text,
                    time = "baru",
                    outgoing = false,
                    delivery = DeliveryState.Received,
                )
                _state.update { current ->
                    current.copy(
                        chats = upsertGlobalPreview(current.chats, text, if (current.activeConversationId == GLOBAL_CHAT_ID) 0 else 1),
                        messages = current.messages + incoming,
                    )
                }
            }
            mediaReassembler.accept(packet)?.let { media ->
                val sender = packet.senderId.toPeerId()
                val label = when (media.kind) {
                    ChatMessageKind.Image -> "Gambar"
                    ChatMessageKind.Voice -> "Voice note"
                    ChatMessageKind.File -> media.name
                    ChatMessageKind.Text -> media.name
                }
                val incoming = ChatMessage(
                    id = "media-${media.id}-$sender",
                    conversationId = GLOBAL_CHAT_ID,
                    senderName = "Node ${sender.takeLast(4).uppercase()}",
                    body = label,
                    time = "baru",
                    outgoing = false,
                    kind = media.kind,
                    delivery = DeliveryState.Received,
                    attachmentName = media.name,
                    attachmentBytes = media.bytes.size,
                    attachmentMimeType = media.mimeType,
                    attachmentData = media.bytes,
                )
                _state.update { current ->
                    current.copy(
                        chats = upsertGlobalPreview(current.chats, label, if (current.activeConversationId == GLOBAL_CHAT_ID) 0 else 1),
                        messages = current.messages + incoming,
                    )
                }
            }
        } }
    }

    fun navigate(page: AppPage) = _state.update { it.copy(page = page) }

    fun createGlobalChat() = _state.update { current ->
        current.copy(
            page = AppPage.Chats,
            activeConversationId = GLOBAL_CHAT_ID,
            chats = upsertGlobalPreview(current.chats, "Chat bersama semua pengguna di node", 0),
        )
    }

    fun openChat(conversationId: String) = _state.update { current ->
        current.copy(
            activeConversationId = conversationId,
            chats = current.chats.map { if (it.peerId == conversationId) it.copy(unread = 0) else it },
        )
    }

    fun closeChat() = _state.update { it.copy(activeConversationId = null) }

    fun toggleConnection() {
        if (_state.value.connection.phase == ConnectionPhase.Connected) repository.disconnect()
        else repository.connect()
    }

    fun requestReboot() = _state.update {
        it.copy(notice = "Firmware NusaNode belum menyediakan perintah reboot lewat BLE")
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val cleanText = text.trim()
        val messageId = "local-${currentEpochMillis()}"
        val outgoing = ChatMessage(
            id = messageId,
            conversationId = GLOBAL_CHAT_ID,
            senderName = "Saya",
            body = cleanText,
            time = "sekarang",
            outgoing = true,
        )
        _state.update { current ->
            current.copy(
                chats = upsertGlobalPreview(current.chats, cleanText, 0),
                messages = current.messages + outgoing,
            )
        }
        scope.launch {
            val result = repository.sendMessage(cleanText, peerId)
            finishMessage(messageId, result)
        }
    }

    fun sendAttachment(attachment: ChatAttachment) {
        val messageId = "media-${currentEpochMillis()}"
        val description = when (attachment.kind) {
            ChatMessageKind.Image -> "Gambar"
            ChatMessageKind.Voice -> "Voice note"
            ChatMessageKind.File -> attachment.name
            ChatMessageKind.Text -> attachment.name
        }
        val outgoing = ChatMessage(
            id = messageId,
            conversationId = GLOBAL_CHAT_ID,
            senderName = "Saya",
            body = description,
            time = "sekarang",
            outgoing = true,
            kind = attachment.kind,
            attachmentName = attachment.name,
            attachmentBytes = attachment.bytes.size,
            attachmentMimeType = attachment.mimeType,
            attachmentData = attachment.bytes,
            durationSeconds = attachment.durationSeconds,
        )
        _state.update { current ->
            current.copy(
                chats = upsertGlobalPreview(current.chats, description, 0),
                messages = current.messages + outgoing,
            )
        }
        scope.launch { finishMessage(messageId, repository.sendAttachment(attachment, peerId)) }
    }

    fun showNotice(message: String) = _state.update { it.copy(notice = message) }

    private fun finishMessage(messageId: String, result: Result<Unit>) {
        _state.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    if (message.id == messageId) message.copy(
                        delivery = if (result.isSuccess) DeliveryState.Sent else DeliveryState.Failed,
                    ) else message
                },
                notice = result.exceptionOrNull()?.message,
            )
        }
    }

    private fun upsertGlobalPreview(chats: List<ChatPreview>, message: String, unreadDelta: Int): List<ChatPreview> {
        val existing = chats.firstOrNull { it.peerId == GLOBAL_CHAT_ID }
        val global = ChatPreview(
            peerId = GLOBAL_CHAT_ID,
            initials = "GM",
            name = "Global Mesh",
            message = message,
            time = "sekarang",
            unread = (existing?.unread ?: 0) + unreadDelta,
            accent = 0xFF0891B2,
            global = true,
        )
        return listOf(global) + chats.filterNot { it.peerId == GLOBAL_CHAT_ID }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    private companion object {
        const val GLOBAL_CHAT_ID = "global"
    }
}



