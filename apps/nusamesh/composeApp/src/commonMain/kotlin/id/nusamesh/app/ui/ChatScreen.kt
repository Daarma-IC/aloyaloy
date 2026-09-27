package id.nusamesh.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.domain.ChatAttachment
import id.nusamesh.app.domain.ChatMessage
import id.nusamesh.app.domain.ChatMessageKind
import id.nusamesh.app.domain.ChatPreview
import id.nusamesh.app.domain.ConnectionPhase
import id.nusamesh.app.domain.DeliveryState
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
        Modifier.fillMaxSize().background(Color.White).padding(
            start = 30.dp,
            end = 30.dp,
            top = padding.calculateTopPadding() + 24.dp,
            bottom = padding.calculateBottomPadding(),
        ),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Pesan", color = Navy, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                Text("Jaringan komunikasi mesh", color = Color(0xFF94A3B8), fontSize = 10.sp)
            }
            RoundIconButton(IconKind.Plus, onCreateGlobal, background = CyanPale, tint = Cyan)
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
        Modifier.fillMaxWidth().height(44.dp).background(CyanSoft.copy(alpha = 0.48f), RoundedCornerShape(22.dp)).padding(4.dp),
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
        Text(text, color = if (selected) Navy else Slate, fontSize = 10.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun EmptyChat(modifier: Modifier, onCreateGlobal: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(66.dp).background(CyanPale, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(IconKind.Mesh, Cyan, Modifier.size(29.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Mulai percakapan mesh", color = Navy, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Semua pengguna yang terhubung ke node\nakan menerima pesan global.", color = Slate, fontSize = 11.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.height(42.dp).background(Cyan, RoundedCornerShape(21.dp)).padding(horizontal = 20.dp).pressableClick(onCreateGlobal),
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
        Modifier.fillMaxWidth().height(76.dp).background(Color(0xFFF8FAFC), RoundedCornerShape(20.dp))
            .border(1.dp, Color(0xFFE8EEF4), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp).pressableClick(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).background(Cyan, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(IconKind.Mesh, Color.White, Modifier.size(22.dp))
            Box(Modifier.align(Alignment.BottomEnd).size(11.dp).background(Success, CircleShape).border(2.dp, Color.White, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(chat.name, color = Navy, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(chat.time, color = Color(0xFF94A3B8), fontSize = 8.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(chat.message, color = Slate, fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f))
                if (chat.unread > 0) Box(Modifier.size(20.dp).background(Cyan, CircleShape), contentAlignment = Alignment.Center) {
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
) {
    var message by remember { mutableStateOf("") }
    var attachmentOpen by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val messages = state.messages.filter { it.conversationId == state.activeConversationId }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF7FAFC)).imePadding().padding(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding(),
        ),
    ) {
        ConversationHeader(state, onBack)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { GlobalWelcomeCard() }
            items(messages, key = { it.id }) { message ->
                Bubble(message) {
                    val audio = message.attachmentData
                    if (audio == null) onNotice("Data voice note tidak tersedia")
                    else mediaActions.playVoiceNote(audio, message.attachmentMimeType ?: "audio/mp4", onNotice)
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
                    onSend(message)
                    message = ""
                    attachmentOpen = false
                }
            },
        )
    }
}

@Composable
private fun ConversationHeader(state: AppUiState, onBack: () -> Unit) {
    val connected = state.connection.phase == ConnectionPhase.Connected
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RoundIconButton(IconKind.Back, onBack, background = Color(0xFFF1F5F9), tint = Navy, size = 38.dp)
        Box(Modifier.size(40.dp).background(Cyan, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(IconKind.Mesh, Color.White, Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Global Mesh", color = Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${state.health.neighborCount} node aktif • pesan broadcast", color = Slate, fontSize = 9.sp)
        }
        StatusPill(
            text = if (connected) "Terhubung" else "Offline",
            color = if (connected) Success else Slate,
            background = if (connected) Color(0xFFECFDF5) else Color(0xFFF1F5F9),
        )
    }
}

@Composable
private fun GlobalWelcomeCard() {
    Column(
        Modifier.fillMaxWidth().background(CyanPale, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Global Mesh", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text("Pesan di ruang ini disiarkan ke pengguna yang terhubung melalui jaringan node.", color = Slate, fontSize = 9.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun Bubble(message: ChatMessage, onPlayVoice: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
            if (!message.outgoing) Text(message.senderName, color = Slate, fontSize = 8.sp, modifier = Modifier.padding(start = 10.dp, bottom = 3.dp))
            Column(
                Modifier.widthIn(max = 270.dp).background(
                    if (message.outgoing) Cyan else Color.White,
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (message.outgoing) 18.dp else 5.dp,
                        bottomEnd = if (message.outgoing) 5.dp else 18.dp,
                    ),
                ).then(if (message.outgoing) Modifier else Modifier.border(1.dp, Color(0xFFE5EDF4), RoundedCornerShape(18.dp))).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                when (message.kind) {
                    ChatMessageKind.Text -> Text(message.body, color = if (message.outgoing) Color.White else Navy, fontSize = 11.sp, lineHeight = 16.sp)
                    ChatMessageKind.Image -> AttachmentPreview(IconKind.Image, "Gambar", message.attachmentBytes, message.outgoing)
                    ChatMessageKind.Voice -> VoicePreview(message.durationSeconds ?: 0, message.outgoing, onPlayVoice)
                    ChatMessageKind.File -> AttachmentPreview(IconKind.File, message.attachmentName ?: "File", message.attachmentBytes, message.outgoing)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(message.time, color = if (message.outgoing) Color.White.copy(alpha = .75f) else Color(0xFF94A3B8), fontSize = 7.sp)
                    if (message.outgoing) Text(
                        when (message.delivery) {
                            DeliveryState.Sending -> "•"
                            DeliveryState.Sent -> "✓"
                            DeliveryState.Failed -> "!"
                            DeliveryState.Received -> ""
                        },
                        color = if (message.delivery == DeliveryState.Failed) Color(0xFFFECACA) else Color.White,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreview(icon: IconKind, name: String, bytes: Int?, outgoing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp).background(if (outgoing) Color.White.copy(alpha = .18f) else CyanPale, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            AppIcon(icon, if (outgoing) Color.White else Cyan, Modifier.size(19.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = if (outgoing) Color.White else Navy, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(formatBytes(bytes), color = if (outgoing) Color.White.copy(alpha = .72f) else Slate, fontSize = 8.sp)
        }
    }
}

@Composable
private fun VoicePreview(duration: Int, outgoing: Boolean, onPlay: () -> Unit) {
    Row(
        modifier = Modifier.pressableClick(onPlay).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(34.dp).background(if (outgoing) Color.White.copy(alpha = .18f) else CyanPale, CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(IconKind.Play, if (outgoing) Color.White else Cyan, Modifier.size(15.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(8, 15, 11, 19, 13, 9, 16, 7).forEach { height ->
                Box(Modifier.width(2.dp).height(height.dp).background(if (outgoing) Color.White.copy(alpha = .75f) else Cyan, RoundedCornerShape(1.dp)))
            }
        }
        Text("${duration}s", color = if (outgoing) Color.White else Slate, fontSize = 8.sp)
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
) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (attachmentOpen) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AttachmentAction(IconKind.Image, "Gambar", onImage)
                AttachmentAction(IconKind.File, "File", onFile)
            }
        }
        if (recording) {
            Row(
                Modifier.fillMaxWidth().height(42.dp).background(Color(0xFFFFF1F2), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
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
                RoundIconButton(if (attachmentOpen) IconKind.Close else IconKind.Plus, onToggleAttachment, background = if (attachmentOpen) CyanPale else Color(0xFFF1F5F9), tint = Cyan, size = 42.dp)
                Row(
                    Modifier.weight(1f).height(42.dp).background(Color(0xFFF1F5F9), RoundedCornerShape(21.dp)).padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = message,
                        onValueChange = onMessageChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Navy, fontSize = 11.sp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        decorationBox = { inner ->
                            if (message.isEmpty()) Text("Tulis pesan ke jaringan…", color = Color(0xFF94A3B8), fontSize = 10.sp)
                            inner()
                        },
                    )
                }
                RoundIconButton(
                    if (message.isBlank()) IconKind.Mic else IconKind.Send,
                    if (message.isBlank()) onVoice else onSend,
                    background = Cyan,
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
        Modifier.weight(1f).height(48.dp).background(CyanPale, RoundedCornerShape(16.dp)).pressableClick(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AppIcon(icon, Cyan, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Navy, fontSize = 10.sp, fontWeight = FontWeight.Normal)
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

private enum class IconKind { Plus, Close, Back, Mesh, Image, File, Mic, Send, Play }

@Composable
private fun AppIcon(kind: IconKind, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * .09f, cap = StrokeCap.Round)
        val center = Offset(size.width / 2f, size.height / 2f)
        when (kind) {
            IconKind.Plus -> {
                drawLine(color, Offset(center.x, size.height * .22f), Offset(center.x, size.height * .78f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .22f, center.y), Offset(size.width * .78f, center.y), stroke.width, StrokeCap.Round)
            }
            IconKind.Close -> {
                drawLine(color, Offset(size.width * .26f, size.height * .26f), Offset(size.width * .74f, size.height * .74f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .74f, size.height * .26f), Offset(size.width * .26f, size.height * .74f), stroke.width, StrokeCap.Round)
            }
            IconKind.Back -> {
                drawLine(color, Offset(size.width * .68f, size.height * .2f), Offset(size.width * .32f, center.y), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .32f, center.y), Offset(size.width * .68f, size.height * .8f), stroke.width, StrokeCap.Round)
            }
            IconKind.Mesh -> {
                drawCircle(color, size.minDimension * .12f, center)
                listOf(Offset(center.x, size.height * .16f), Offset(size.width * .2f, size.height * .72f), Offset(size.width * .8f, size.height * .72f)).forEach { point ->
                    drawLine(color, center, point, stroke.width * .65f, StrokeCap.Round)
                    drawCircle(color, size.minDimension * .10f, point)
                }
            }
            IconKind.Image -> {
                drawRoundRect(color, style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .12f))
                drawCircle(color, size.minDimension * .08f, Offset(size.width * .68f, size.height * .32f))
                val path = Path().apply {
                    moveTo(size.width * .14f, size.height * .76f)
                    lineTo(size.width * .39f, size.height * .5f)
                    lineTo(size.width * .55f, size.height * .65f)
                    lineTo(size.width * .7f, size.height * .5f)
                    lineTo(size.width * .87f, size.height * .7f)
                }
                drawPath(path, color, style = stroke)
            }
            IconKind.File -> {
                drawRoundRect(color, topLeft = Offset(size.width * .2f, size.height * .08f), size = Size(size.width * .6f, size.height * .84f), style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .08f))
                drawLine(color, Offset(size.width * .34f, size.height * .48f), Offset(size.width * .67f, size.height * .48f), stroke.width * .7f, StrokeCap.Round)
                drawLine(color, Offset(size.width * .34f, size.height * .65f), Offset(size.width * .61f, size.height * .65f), stroke.width * .7f, StrokeCap.Round)
            }
            IconKind.Mic -> {
                drawRoundRect(color, topLeft = Offset(size.width * .35f, size.height * .1f), size = Size(size.width * .3f, size.height * .48f), style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .16f))
                drawArc(color, 0f, 180f, false, topLeft = Offset(size.width * .22f, size.height * .3f), size = Size(size.width * .56f, size.height * .42f), style = stroke)
                drawLine(color, Offset(center.x, size.height * .72f), Offset(center.x, size.height * .9f), stroke.width, StrokeCap.Round)
            }
            IconKind.Send -> {
                val path = Path().apply {
                    moveTo(size.width * .12f, size.height * .48f)
                    lineTo(size.width * .87f, size.height * .13f)
                    lineTo(size.width * .66f, size.height * .87f)
                    lineTo(size.width * .48f, size.height * .59f)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawLine(color, Offset(size.width * .48f, size.height * .59f), Offset(size.width * .87f, size.height * .13f), stroke.width, StrokeCap.Round)
            }
            IconKind.Play -> {
                val path = Path().apply {
                    moveTo(size.width * .34f, size.height * .22f)
                    lineTo(size.width * .76f, center.y)
                    lineTo(size.width * .34f, size.height * .78f)
                    close()
                }
                drawPath(path, color)
            }
        }
    }
}

private fun formatBytes(bytes: Int?): String = when {
    bytes == null -> "Lampiran mesh"
    bytes < 1024 -> "$bytes B"
    else -> "${bytes / 1024} KB"
}





