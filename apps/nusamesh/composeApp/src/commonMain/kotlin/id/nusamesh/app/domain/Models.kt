package id.nusamesh.app.domain

import id.nusamesh.app.mesh.engine.EngineSnapshot

enum class AppPage { Chats, Home, Map }

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

/** Jalur yang dipakai pesan (ditampilkan sebagai ikon kecil di gelembung). */
enum class DeliveryPath { Ble, Node, Nebeng }

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
    /** 0..1 selama fragmen attachment keluar dari antrean transport. */
    val transferProgress: Float? = null,
    val path: DeliveryPath = DeliveryPath.Ble,
    /** Nama HP perantara bila [path] = Nebeng. */
    val viaName: String? = null,
)

data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val kind: ChatMessageKind,
    val durationSeconds: Int? = null,
)

/** Status mesh untuk UI: [active] = pengguna menyalakan mesh, sisanya dari engine. */
data class MeshStatus(
    val active: Boolean = false,
    val engine: EngineSnapshot = EngineSnapshot(),
)

data class AppUiState(
    val page: AppPage = AppPage.Home,
    val nickname: String = "",
    val myPeerId: String = "",
    val mesh: MeshStatus = MeshStatus(),
    val chats: List<ChatPreview> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    /** Pemberitahuan nebeng yang belum ditutup (muncul saat rute nebeng baru terbentuk). */
    val nebengNotice: String? = null,
    val nodeSheetOpen: Boolean = false,
    val notice: String? = null,
)
