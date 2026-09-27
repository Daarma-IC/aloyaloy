package id.nusamesh.app.domain

enum class AppPage { Chats, Home, Map }

enum class ConnectionPhase { Idle, Scanning, Connecting, Connected, Failed }

data class MeshPeripheral(
    val id: String,
    val name: String,
    val rssi: Int,
    val peerId: ByteArray? = null,
)

data class MeshConnection(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val peripheral: MeshPeripheral? = null,
    val detail: String = "Bluetooth belum aktif",
)

data class FieldUnit(
    val id: String,
    val name: String,
    val subtitle: String,
    val status: String,
    val batteryPercent: Int? = null,
    val distanceMeters: Int? = null,
    val rssi: Int? = null,
)

data class ChatPreview(
    val peerId: String,
    val initials: String,
    val name: String,
    val message: String,
    val time: String,
    val location: String? = null,
    val unread: Int = 0,
    val accent: Long,
    val global: Boolean = false,
)

enum class ChatMessageKind { Text, Image, Voice, File }

enum class DeliveryState { Sending, Sent, Failed, Received }

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val senderName: String,
    val body: String,
    val time: String,
    val outgoing: Boolean,
    val kind: ChatMessageKind = ChatMessageKind.Text,
    val delivery: DeliveryState = DeliveryState.Sending,
    val attachmentName: String? = null,
    val attachmentBytes: Int? = null,
    val attachmentMimeType: String? = null,
    val attachmentData: ByteArray? = null,
    val durationSeconds: Int? = null,
)

data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val kind: ChatMessageKind,
    val durationSeconds: Int? = null,
)

data class MeshHealth(
    val neighborCount: Int = 0,
    val bestRssi: Int? = null,
    val bestSnr: Float? = null,
)

data class AppUiState(
    val page: AppPage = AppPage.Home,
    val connection: MeshConnection = MeshConnection(),
    val nearby: List<MeshPeripheral> = emptyList(),
    val health: MeshHealth = MeshHealth(),
    val fieldUnits: List<FieldUnit> = emptyList(),
    val chats: List<ChatPreview> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val notice: String? = null,
)


