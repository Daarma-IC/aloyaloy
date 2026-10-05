package id.nusamesh.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessage
import id.nusamesh.app.domain.ChatMessageKind
import id.nusamesh.app.domain.ChatPreview
import id.nusamesh.app.AppController
import id.nusamesh.app.domain.DeliveryPath
import id.nusamesh.app.domain.DeliveryState
import id.nusamesh.app.domain.FieldChannels
import id.nusamesh.app.mesh.protocol.ImportantMessage
import id.nusamesh.app.mesh.protocol.QuickStatus
import id.nusamesh.app.media.ChatMediaActions

@Composable
fun ChatScreen(
    state: AppUiState,
    padding: PaddingValues,
    mediaActions: ChatMediaActions,
    onCreateGlobal: () -> Unit,
    onOpenChat: (String) -> Unit,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSendAttachment: (ChatAttachment) -> Unit,
    onNotice: (String) -> Unit,
    onDismissNebeng: () -> Unit,
) {
    if (state.activeConversationId == null) {
        ChatListScreen(state, padding, onCreateGlobal, onOpenChat)
    } else {
        GlobalConversationScreen(
            state = state,
            padding = padding,
            mediaActions = mediaActions,
            onBack = onBack,
            onSend = onSend,
            onSendAttachment = onSendAttachment,
            onNotice = onNotice,
            onDismissNebeng = onDismissNebeng,
        )
    }
}

private enum class ChatFilter { All, Unread, Read }

@Composable
private fun ChatListScreen(
    state: AppUiState,
    padding: PaddingValues,
    onCreateGlobal: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    var selectedFilter by remember { mutableStateOf(ChatFilter.All) }
    val visibleChats = state.chats.filter { chat ->
        when (selectedFilter) {
            ChatFilter.All -> true
            ChatFilter.Unread -> chat.unread > 0
            ChatFilter.Read -> chat.unread == 0
        }
    }
    Column(
        Modifier.fillMaxSize().background(Canvas).padding(
            start = 30.dp,
            end = 30.dp,
            top = padding.calculateTopPadding() + 24.dp,
            bottom = padding.calculateBottomPadding(),
        ),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Pesan", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                Text("Jaringan komunikasi mesh", color = Muted, fontSize = 10.sp)
            }
            RoundIconButton(IconKind.Plus, onCreateGlobal, background = BrandTint, tint = Brand)
        }
        Spacer(Modifier.height(18.dp))
        ChatFilters(selectedFilter) { selectedFilter = it }
        Spacer(Modifier.height(18.dp))
        if (visibleChats.isEmpty()) {
            EmptyChat(Modifier.weight(1f), onCreateGlobal)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visibleChats, key = { it.peerId }) { chat -> ChatRow(chat) { onOpenChat(chat.peerId) } }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun ChatFilters(selected: ChatFilter, onSelect: (ChatFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(BrandSoft.copy(alpha = 0.48f), RoundedCornerShape(22.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Filter("Semua", selected == ChatFilter.All) { onSelect(ChatFilter.All) }
        Filter("Belum dibaca", selected == ChatFilter.Unread) { onSelect(ChatFilter.Unread) }
        Filter("Dibaca", selected == ChatFilter.Read) { onSelect(ChatFilter.Read) }
    }
}

@Composable
private fun RowScope.Filter(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxSize()
            .background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(18.dp))
            .pressableClick(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Ink else Slate, fontSize = 10.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun EmptyChat(modifier: Modifier, onCreateGlobal: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(66.dp).background(BrandTint, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(IconKind.Mesh, Brand, Modifier.size(29.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Mulai percakapan mesh", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Semua HP di jaringan mesh, termasuk\nyang lewat Nusa Node, menerima pesan global.", color = Slate, fontSize = 11.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.height(42.dp).background(Brand, RoundedCornerShape(21.dp)).padding(horizontal = 20.dp).pressableClick(onCreateGlobal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppIcon(IconKind.Plus, Color.White, Modifier.size(16.dp))
            Text("Buat Chat Global", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ChatRow(chat: ChatPreview, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(76.dp)
            .shadow(6.dp, RoundedCornerShape(22.dp), ambientColor = Ink.copy(alpha = .08f), spotColor = Ink.copy(alpha = .08f))
            .background(Color.White, RoundedCornerShape(22.dp)).pressableClick(onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).background(Brand, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(IconKind.Mesh, Color.White, Modifier.size(22.dp))
            Box(Modifier.align(Alignment.BottomEnd).size(11.dp).background(Success, CircleShape).border(2.dp, Color.White, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(chat.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(chat.time, color = Muted, fontSize = 8.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(chat.message, color = Slate, fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f))
                if (chat.unread > 0) Box(Modifier.size(20.dp).background(Brand, CircleShape), contentAlignment = Alignment.Center) {
                    Text(chat.unread.toString(), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun GlobalConversationScreen(
    state: AppUiState,
    padding: PaddingValues,
    mediaActions: ChatMediaActions,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSendAttachment: (ChatAttachment) -> Unit,
    onNotice: (String) -> Unit,
    onDismissNebeng: () -> Unit,
) {
    var message by remember { mutableStateOf("") }
    var important by remember { mutableStateOf(false) }
    var attachmentOpen by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val messages = state.messages.filter { it.conversationId == state.activeConversationId }
    var playback by remember { mutableStateOf<VoicePlayback?>(null) }
    DisposableEffect(Unit) { onDispose { mediaActions.stopVoicePlayback() } }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(
        Modifier.fillMaxSize().background(Canvas).imePadding().padding(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding(),
        ),
    ) {
        ConversationHeader(state, onBack)
        state.nebengNotice?.let { notice ->
            Box(Modifier.background(Color.White).padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) { NebengBanner(notice, onDismissNebeng) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { GlobalWelcomeCard() }
            items(messages, key = { it.id }) { message ->
                if (message.viaName == AppController.SYSTEM) SystemCard(message.body)
                else Bubble(message, playback.takeIf { it?.id == message.id }) {
                    val audio = message.attachmentData
                    when {
                        playback?.id == message.id -> { mediaActions.stopVoicePlayback(); playback = null }
                        audio == null -> onNotice("Data voice note tidak tersedia")
                        else -> {
                            mediaActions.stopVoicePlayback()
                            playback = VoicePlayback(message.id, 0L)
                            mediaActions.playVoiceNote(
                                bytes = audio,
                                mimeType = message.attachmentMimeType ?: "audio/mp4",
                                onStarted = { duration -> if (playback?.id == message.id) playback = VoicePlayback(message.id, duration) },
                                onFinished = { if (playback?.id == message.id) playback = null },
                                onError = { if (playback?.id == message.id) playback = null; onNotice(it) },
                            )
                        }
                    }
                }
            }
        }
        Composer(
            message = message,
            onMessageChange = { message = it },
            attachmentOpen = attachmentOpen,
            onToggleAttachment = { attachmentOpen = !attachmentOpen },
            recording = recording,
            onImage = {
                mediaActions.pickImage(
                    loraProfile = state.mesh.engine.loraPathAvailable,
                    onPicked = { attachmentOpen = false; onSendAttachment(it) },
                    onError = onNotice,
                )
            },
            onFile = {
                mediaActions.pickFile(
                    onPicked = { attachmentOpen = false; onSendAttachment(it) },
                    onError = onNotice,
                )
            },
            onVoice = {
                if (recording) {
                    mediaActions.stopVoiceNote(
                        onRecorded = { recording = false; onSendAttachment(it) },
                        onError = { recording = false; onNotice(it) },
                    )
                } else {
                    recording = true
                    mediaActions.startVoiceNote { recording = false; onNotice(it) }
                }
            },
            onSend = {
                if (message.isNotBlank()) {
                    onSend(if (important) ImportantMessage.PREFIX + message.trim() else message)
                    message = ""
                    important = false
                    attachmentOpen = false
                }
            },
            important = important,
            onToggleImportant = { important = !important },
        )
    }
}

@Composable
private fun ConversationHeader(state: AppUiState, onBack: () -> Unit) {
    val snapshot = state.mesh.engine
    val node = snapshot.servingNode
    val phones = snapshot.peers.count { it.direct }
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RoundIconButton(IconKind.Back, onBack, background = Color(0xFFF1F5F9), tint = Ink, size = 38.dp)
        Box(Modifier.size(40.dp).background(Brand, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(IconKind.Mesh, Color.White, Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val conversation = state.activeConversationId ?: AppController.GLOBAL_CHAT_ID
            Text(FieldChannels.title(conversation, "Global Mesh"), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                when {
                    conversation == FieldChannels.SAR_CHAT_ID -> "Hanya tim SAR & posko · tidak terenkripsi"
                    FieldChannels.teamSlugOf(conversation) != null -> "Hanya anggota tim & posko · tidak terenkripsi"
                    else -> "$phones HP tersambung · ${snapshot.peers.size} di jaringan"
                },
                color = Slate, fontSize = 9.sp, maxLines = 1,
            )
        }
        when {
            !state.mesh.active -> StatusPill("Offline", Slate, Color(0xFFF1F5F9))
            node != null -> StatusPill("Via Node", Success, SuccessTint)
            snapshot.nebeng != null -> StatusPill("Nebeng", Warning, WarningTint)
            phones > 0 -> StatusPill("BLE", Brand, BrandTint)
            else -> StatusPill("Mencari", Brand, BrandTint)
        }
    }
}

@Composable
private fun SystemCard(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.background(BrandTint, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIcon(IconKind.Node, Brand, Modifier.size(12.dp))
            Text(text, color = Slate, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun PathTag(message: ChatMessage) {
    val tint = if (message.outgoing) Color.White.copy(alpha = .8f) else Muted
    when (message.path) {
        DeliveryPath.Ble -> return
        DeliveryPath.Node -> {
            AppIcon(IconKind.Node, tint, Modifier.size(10.dp))
            Text(if (message.outgoing) "via ${message.viaName ?: "Nusa Node"}" else "lewat LoRa", color = tint, fontSize = 7.sp)
        }
        DeliveryPath.Nebeng -> {
            AppIcon(IconKind.Relay, tint, Modifier.size(10.dp))
            Text("nebeng ${message.viaName ?: ""}".trim(), color = tint, fontSize = 7.sp)
        }
    }
}

@Composable
private fun GlobalWelcomeCard() {
    Column(
        Modifier.fillMaxWidth().background(BrandTint, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Global Mesh", color = Brand, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text("Pesan diteruskan antar-HP lewat Bluetooth, dan ke desa lain lewat Nusa Node (LoRa) bila tersedia.", color = Slate, fontSize = 9.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun Bubble(message: ChatMessage, playback: VoicePlayback?, onPlayVoice: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
            if (!message.outgoing) Text(
                // ✓ Tim = ditandatangani kunci operasi: pengirim pasti anggota tim, bukan peniru.
                message.senderName + if (message.verified) "  ✓ Tim" else "",
                color = if (message.verified) Success else Slate, fontSize = 8.sp,
                fontWeight = if (message.verified) FontWeight.SemiBold else null,
                modifier = Modifier.padding(start = 10.dp, bottom = 3.dp),
            )
            val bubbleShape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (message.outgoing) 20.dp else 6.dp,
                bottomEnd = if (message.outgoing) 6.dp else 20.dp,
            )
            Column(
                Modifier.widthIn(max = 280.dp)
                    .shadow(if (message.outgoing) 8.dp else 3.dp, bubbleShape, ambientColor = (if (message.outgoing) Brand else Ink).copy(alpha = .18f), spotColor = (if (message.outgoing) Brand else Ink).copy(alpha = .18f))
                    .background(
                        if (message.outgoing) Brush.linearGradient(listOf(Brand, BrandBright)) else Brush.linearGradient(listOf(Color.White, Color.White)),
                        bubbleShape,
                    ).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                when (message.kind) {
                    ChatMessageKind.Text -> {
                        val status = QuickStatus.decode(message.body)
                        val important = ImportantMessage.isImportant(message.body)
                        if (important) Text(
                            "PENTING", fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            color = if (message.outgoing) Color.White else Danger,
                        )
                        Text(
                            status?.let { "Status: ${it.label}" } ?: message.body.removePrefix(ImportantMessage.PREFIX),
                            color = when {
                                message.outgoing -> Color.White
                                status?.urgent == true -> Danger
                                else -> Ink
                            },
                            fontSize = 11.sp, lineHeight = 16.sp,
                            fontWeight = if (status != null || important) FontWeight.Bold else null,
                        )
                    }
                    ChatMessageKind.Image -> ImagePreview(message)
                    ChatMessageKind.Voice -> VoicePreview(message.durationSeconds, message.outgoing, playback, onPlayVoice)
                    ChatMessageKind.File -> AttachmentPreview(IconKind.File, message.attachmentName ?: "File", message.attachmentBytes, message.outgoing)
                }
                if (message.outgoing && message.kind != ChatMessageKind.Text && message.delivery == DeliveryState.Sending) {
                    val transfer = (message.transferProgress ?: 0f).coerceIn(0f, 1f)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { transfer },
                            modifier = Modifier.weight(1f).height(4.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = .22f),
                        )
                        Text("${(transfer * 100).toInt()}%", color = Color.White.copy(alpha = .85f), fontSize = 7.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    PathTag(message)
                    Text(message.time, color = if (message.outgoing) Color.White.copy(alpha = .75f) else Muted, fontSize = 7.sp)
                    if (message.outgoing) DeliveryStatus(message)
                }
            }
        }
    }
}

/** "Diterima Budi, Sari +2" — siapa saja yang mengonfirmasi menerima pesan penting. */
internal fun ackLabel(names: List<String>): String =
    "Diterima " + names.take(2).joinToString(", ") + if (names.size > 2) " +${names.size - 2}" else ""

/**
 * Status kirim: jam = masih antre (lewat LoRa bisa puluhan detik karena airtime), centang = sudah keluar
 * dari HP/node ke jaringan, tanda seru = gagal.
 */
@Composable
private fun DeliveryStatus(message: ChatMessage) {
    val viaLora = message.path != DeliveryPath.Ble
    val (icon, label, color) = when {
        message.ackedBy.isNotEmpty() -> Triple(IconKind.DoubleCheck, ackLabel(message.ackedBy), Color.White)
        else -> when (message.delivery) {
        DeliveryState.Sending -> Triple(IconKind.Clock, if (viaLora) "Antre LoRa" else "Mengirim", Color.White.copy(alpha = .85f))
        DeliveryState.Sent -> Triple(IconKind.Check, if (ImportantMessage.wantsAck(message.body)) "Terkirim, menunggu konfirmasi" else "Terkirim", Color.White)
        DeliveryState.Failed -> Triple(IconKind.Alert, "Gagal", Color(0xFFFECACA))
        DeliveryState.Received -> return
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        AppIcon(icon, color, Modifier.size(9.dp))
        Text(label, color = color, fontSize = 7.sp)
    }
}

@Composable
private fun ImagePreview(message: ChatMessage) {
    val bitmap = remember(message.id) {
        message.attachmentData?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
    }
    if (bitmap == null) {
        AttachmentPreview(IconKind.Image, "Gambar", message.attachmentBytes, message.outgoing)
        return
    }
    var fullscreen by remember { mutableStateOf(false) }
    if (fullscreen) {
        ImageViewer(bitmap, "${message.senderName} · ${message.time} · ${formatBytes(message.attachmentBytes)}") { fullscreen = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Image(
            bitmap = bitmap,
            contentDescription = "Gambar dari ${message.senderName}",
            contentScale = ContentScale.Fit,
            // Gambar profil LoRa hanya 128 px: tampil lebih besar dengan filter halus.
            filterQuality = FilterQuality.Medium,
            modifier = Modifier.widthIn(min = 150.dp, max = 230.dp).aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                .clip(RoundedCornerShape(12.dp)).pressableClick { fullscreen = true },
        )
        if (message.attachmentName?.startsWith("lora_") == true) {
            Text(
                "Profil LoRa · ${formatBytes(message.attachmentBytes)}",
                color = if (message.outgoing) Color.White.copy(alpha = .75f) else Slate,
                fontSize = 7.sp,
            )
        }
    }
}

@Composable
private fun AttachmentPreview(icon: IconKind, name: String, bytes: Int?, outgoing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp).background(if (outgoing) Color.White.copy(alpha = .18f) else BrandTint, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            AppIcon(icon, if (outgoing) Color.White else Brand, Modifier.size(19.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = if (outgoing) Color.White else Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(formatBytes(bytes), color = if (outgoing) Color.White.copy(alpha = .72f) else Slate, fontSize = 8.sp)
        }
    }
}

/** Voice note yang sedang diputar; [durationMs] 0 selama pemutar masih menyiapkan berkas. */
private data class VoicePlayback(val id: String, val durationMs: Long)

private val waveform = listOf(8, 15, 11, 19, 13, 9, 16, 7, 12, 17, 10, 14)

@Composable
private fun VoicePreview(durationSeconds: Int?, outgoing: Boolean, playback: VoicePlayback?, onToggle: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(playback) {
        progress.snapTo(0f)
        if (playback != null && playback.durationMs > 0) {
            progress.animateTo(1f, tween(playback.durationMs.toInt(), easing = LinearEasing))
        }
    }
    val base = if (outgoing) Color.White else Brand
    val shownSeconds = when {
        playback != null && playback.durationMs > 0 -> ((1f - progress.value) * playback.durationMs / 1000f).toInt() + 1
        durationSeconds != null -> durationSeconds
        playback != null -> null
        else -> null
    }
    Row(
        modifier = Modifier.pressableClick(onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(34.dp).background(if (outgoing) Color.White.copy(alpha = .2f) else BrandTint, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(if (playback != null) IconKind.Stop else IconKind.Play, base, Modifier.size(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            waveform.forEachIndexed { i, height ->
                val played = playback != null && (i + 0.5f) / waveform.size <= progress.value
                Box(
                    Modifier.width(2.dp).height(height.dp)
                        .background(if (played || playback == null) base.copy(alpha = if (played) 1f else .7f) else base.copy(alpha = .35f), RoundedCornerShape(1.dp)),
                )
            }
        }
        Text(shownSeconds?.let { "${it}s" } ?: "…", color = if (outgoing) Color.White else Slate, fontSize = 8.sp)
    }
}

@Composable
private fun Composer(
    message: String,
    onMessageChange: (String) -> Unit,
    attachmentOpen: Boolean,
    onToggleAttachment: () -> Unit,
    recording: Boolean,
    onImage: () -> Unit,
    onFile: () -> Unit,
    onVoice: () -> Unit,
    onSend: () -> Unit,
    important: Boolean = false,
    onToggleImportant: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (message.isNotBlank() && !recording) {
            // Pesan penting (perintah posko dll.) meminta konfirmasi terima dari tiap HP penerima.
            Text(
                if (important) "PENTING · minta konfirmasi terima (ketuk untuk batal)" else "Tandai penting (minta konfirmasi terima)",
                color = if (important) Color.White else Danger,
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
                    .background(if (important) Danger else DangerTint, RoundedCornerShape(12.dp))
                    .pressableClick(onToggleImportant)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (attachmentOpen) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AttachmentAction(IconKind.Image, "Gambar", onImage)
                AttachmentAction(IconKind.File, "File", onFile)
            }
        }
        if (recording) {
            Row(
                Modifier.fillMaxWidth().height(42.dp).background(DangerTint, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(Danger, CircleShape))
                    Text("Merekam voice note…", color = Danger, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
                Text("Selesai", color = Danger, fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.pressableClick(onVoice))
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIconButton(if (attachmentOpen) IconKind.Close else IconKind.Plus, onToggleAttachment, background = if (attachmentOpen) BrandTint else Color(0xFFF1F5F9), tint = Brand, size = 42.dp)
                Row(
                    Modifier.weight(1f).height(42.dp).background(Color(0xFFF1F5F9), RoundedCornerShape(21.dp)).padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = message,
                        onValueChange = onMessageChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 11.sp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        decorationBox = { inner ->
                            if (message.isEmpty()) Text("Tulis pesan ke jaringan…", color = Muted, fontSize = 10.sp)
                            inner()
                        },
                    )
                }
                RoundIconButton(
                    if (message.isBlank()) IconKind.Mic else IconKind.Send,
                    if (message.isBlank()) onVoice else onSend,
                    background = Brand,
                    tint = Color.White,
                    size = 42.dp,
                )
            }
        }
    }
}

@Composable
private fun RowScope.AttachmentAction(icon: IconKind, label: String, onClick: () -> Unit) {
    Row(
        Modifier.weight(1f).height(48.dp).background(BrandTint, RoundedCornerShape(16.dp)).pressableClick(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AppIcon(icon, Brand, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun RoundIconButton(
    icon: IconKind,
    onClick: () -> Unit,
    background: Color,
    tint: Color,
    size: androidx.compose.ui.unit.Dp = 42.dp,
) {
    Box(Modifier.size(size).background(background, CircleShape).pressableClick(onClick), contentAlignment = Alignment.Center) {
        AppIcon(icon, tint, Modifier.size(size * .46f))
    }
}

private fun formatBytes(bytes: Int?): String = when {
    bytes == null -> "Lampiran mesh"
    bytes < 1024 -> "$bytes B"
    else -> "${bytes / 1024} KB"
}





