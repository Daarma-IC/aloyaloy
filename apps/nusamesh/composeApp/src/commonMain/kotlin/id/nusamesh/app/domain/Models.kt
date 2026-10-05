package id.nusamesh.app.domain

import id.nusamesh.app.mesh.engine.EngineSnapshot
import id.nusamesh.app.mesh.protocol.GeoPoint
import id.nusamesh.app.mesh.protocol.QuickStatus
import id.nusamesh.app.mesh.protocol.VictimInfo
import id.nusamesh.app.mesh.protocol.WaypointType

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
    /** Nama HP yang mengonfirmasi menerima pesan penting ini (DELIVERY_ACK). */
    val ackedBy: List<String> = emptyList(),
    /** Ditandatangani / dienkripsi kunci operasi: pengirim pasti anggota tim. */
    val verified: Boolean = false,
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

data class TrackedUser(
    val peerId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val updatedAtMs: Long,
    val rssi: Int? = null,
    val direct: Boolean = false,
    val own: Boolean = false,
    val emergency: Boolean = false,
    /** Persen baterai HP unit (dari telemetri lokasi/SOS); null bila versi lama / tidak diketahui. */
    val batteryPercent: Int? = null,
    /** Posisi ditandatangani kunci operasi (anggota tim). */
    val verified: Boolean = false,
)

/** Status cepat terakhir dari sebuah unit (lihat [QuickStatus]). */
data class UnitStatus(val status: QuickStatus, val atMs: Long, val name: String)

enum class RouteKind {
    /** Jejak yang masih direkam: potongan datang berkala. */
    LiveTrack,
    /** Jejak selesai (ringkasan utuh). */
    Track,
    /** Rute rencana yang digambar di peta. */
    Plan,
}

/** Jalur di peta. [segments] per nomor urut potongan; rute utuh disimpan sebagai potongan 0. */
data class SharedRoute(
    val id: String,
    val ownerPeerId: String,
    val ownerName: String,
    val name: String,
    val kind: RouteKind,
    val segments: Map<Int, List<GeoPoint>>,
    val updatedAtMs: Long,
    val own: Boolean = false,
    /** Dikirim anggota tim (bertanda tangan kunci operasi). */
    val verified: Boolean = false,
) {
    /** Titik berurutan (potongan disambung; titik sambungan tidak diulang). */
    val points: List<GeoPoint> get() = segments.entries.sortedBy { it.key }.fold(emptyList()) { acc, (_, segment) ->
        if (acc.isNotEmpty() && segment.firstOrNull() == acc.last()) acc + segment.drop(1) else acc + segment
    }
}

data class MapWaypoint(
    val id: String,
    val ownerPeerId: String,
    val ownerName: String,
    val type: WaypointType,
    val point: GeoPoint,
    val label: String,
    /** Waktu kirim versi terbaru titik ini (dipakai menolak pembaruan yang lebih lama). */
    val createdAtMs: Long,
    val own: Boolean = false,
    /** Nama HP yang mengonfirmasi menerima titik ini (hanya untuk titik milik sendiri). */
    val ackedBy: List<String> = emptyList(),
    /** Detail korban (hanya titik Korban). */
    val victim: VictimInfo? = null,
    /** Nama yang terakhir memperbarui, bila bukan pembuatnya. */
    val updatedBy: String? = null,
    /** Versi terbaru dikirim anggota tim (bertanda tangan kunci operasi). */
    val verified: Boolean = false,
)

data class AppUiState(
    val page: AppPage = AppPage.Home,
    val nickname: String = "",
    val myPeerId: String = "",
    val mesh: MeshStatus = MeshStatus(),
    val chats: List<ChatPreview> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val trackedUsers: List<TrackedUser> = emptyList(),
    val selectedTargetPeerId: String? = null,
    val routes: List<SharedRoute> = emptyList(),
    val waypoints: List<MapWaypoint> = emptyList(),
    /** Id jejak yang sedang direkam perangkat ini. */
    val recordingTrackId: String? = null,
    /** Titik rute rencana yang sedang digambar; null = tidak sedang menggambar. */
    val routeDraft: List<GeoPoint>? = null,
    /** Titik terakhir yang diketuk di peta (lokasi waypoint baru). */
    val pickedPoint: GeoPoint? = null,
    val selectedWaypointId: String? = null,
    val followedRouteId: String? = null,
    /** Status cepat terakhir per peerId. */
    val unitStatuses: Map<String, UnitStatus> = emptyMap(),
    val role: FieldRole = FieldRole.Warga,
    /** Kode kunci operasi aktif (untuk ditampilkan/dibacakan); null = tanpa kunci. */
    val operationCode: String? = null,
    /** Slug tim (mis. "alfa") bila peran Tim SAR. */
    val team: String? = null,
    /** Nama HP yang mengonfirmasi menerima SOS kita (direset tiap SOS dinyalakan). */
    val sosAckedBy: List<String> = emptyList(),
    val headingDegrees: Float? = null,
    val sosActive: Boolean = false,
    /** Pemberitahuan nebeng yang belum ditutup (muncul saat rute nebeng baru terbentuk). */
    val nebengNotice: String? = null,
    val nodeSheetOpen: Boolean = false,
    val notice: String? = null,
)
