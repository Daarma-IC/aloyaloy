package id.nusamesh.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.data.currentEpochMillis
import id.nusamesh.app.data.formatClock
import id.nusamesh.app.domain.ChatMessage
import id.nusamesh.app.domain.DeliveryPath
import id.nusamesh.app.domain.DeliveryState
import kotlinx.coroutines.delay

/** Visible immediately after Stop recording, until the platform encoder returns real bytes. */
@Composable
internal fun VoicePreparationPanel() {
    Column(Modifier.fillMaxWidth().background(BrandTint,RoundedCornerShape(18.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).background(Color.White,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                AppIcon(IconKind.Mic,BrandDeep,Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text("Rekaman selesai",color=Ink,fontSize=14.sp,lineHeight=19.sp,fontWeight=FontWeight.SemiBold)
                Text("Menyiapkan & mengompresi suara…",color=Slate,fontSize=11.sp,lineHeight=15.sp)
            }
            CircularProgressIndicator(Modifier.size(18.dp),color=BrandDeep,strokeWidth=2.dp)
        }
        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),color=BrandDeep,trackColor=Color.White)
        Text("Pengiriman dimulai setelah suara siap.",color=Slate,fontSize=10.sp,lineHeight=14.sp)
    }
}

/** Uses the transport callbacks and receiver receipt already stored on the message. */
@Composable
internal fun VoiceTransferCard(message: ChatMessage) {
    val sending=message.delivery==DeliveryState.Sending
    val received=message.ackedBy.isNotEmpty()
    val failed=message.delivery==DeliveryState.Failed && !received
    val fraction=if(message.delivery==DeliveryState.Sent) 1f else (message.transferProgress ?: 0f).coerceIn(0f,1f)
    val animated by animateFloatAsState(fraction,tween(300))
    var now by remember(message.id) { mutableStateOf(currentEpochMillis()) }
    LaunchedEffect(message.id,sending) { if(sending) while(true) { now=currentEpochMillis();delay(1000) } }
    val title=when {
        sending && fraction==0f -> "Menunggu giliran kirim"
        sending -> "Mengirim pesan suara"
        received -> "Suara diterima"
        failed -> "Pengiriman gagal"
        else -> "Menunggu penerima"
    }
    val tone=if(failed) Color(0xFFFECACA) else Color.White
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha=.12f),RoundedCornerShape(14.dp))
        .border(1.dp,Color.White.copy(alpha=.22f),RoundedCornerShape(14.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            if(sending) CircularProgressIndicator(Modifier.size(14.dp),color=Color.White,strokeWidth=2.dp)
            else AppIcon(if(received) IconKind.DoubleCheck else if(failed) IconKind.Alert else IconKind.Clock,tone,Modifier.size(16.dp))
            Text(title,Modifier.weight(1f),color=tone,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.SpaceBetween) {
            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                val codec=if(message.attachmentMimeType=="audio/x-codec2") "Codec2" else "Audio"
                Text(codec,Modifier.background(Color.White.copy(alpha=.16f),RoundedCornerShape(6.dp)).padding(horizontal=7.dp,vertical=3.dp),color=Color.White,fontSize=9.sp,lineHeight=12.sp,fontWeight=FontWeight.Medium)
                Text(when(message.path) { DeliveryPath.Ble -> "Bluetooth langsung";DeliveryPath.Node -> "LoRa via Nusa Node";DeliveryPath.Nebeng -> "Via HP perantara" },color=Color.White.copy(alpha=.8f),fontSize=9.sp,lineHeight=13.sp)
            }
            Text("${(fraction*100).toInt()}%",color=tone,fontSize=26.sp,lineHeight=30.sp,fontWeight=FontWeight.Bold)
        }
        LinearProgressIndicator(progress={animated},modifier=Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).semantics { contentDescription="Progres pengiriman pesan suara" },color=tone,trackColor=Color.White.copy(alpha=.22f))
        if(sending) {
            Text(transferRemainingLabel(message.transferEstimate,message.transferEstimatedAtMs,now),color=Color.White,fontSize=12.sp,lineHeight=17.sp,fontWeight=FontWeight.SemiBold)
            val elapsed=message.transferStartedAtMs?.let { "${(now-it).coerceAtLeast(0)/1000} dtk berjalan" }
            val fragments=message.transferEstimate?.let { "${it.completedFragments}/${it.totalFragments} fragmen" }
            Text(listOfNotNull(fragments,elapsed).joinToString(" · "),color=Color.White.copy(alpha=.8f),fontSize=9.sp,lineHeight=13.sp)
            if(message.transferEstimate?.assumedSf==true) Text("SF belum tersedia; perkiraan memakai SF7.",color=Color.White.copy(alpha=.8f),fontSize=9.sp,lineHeight=13.sp)
            Text(if(message.path==DeliveryPath.Nebeng) "Perkiraan sampai HP perantara." else "Perkiraan selesai kirim dari HP, belum waktu tiba.",color=Color.White.copy(alpha=.8f),fontSize=9.sp,lineHeight=13.sp)
        }
        if(received) {
            Text(ackLabel(message.ackedBy),color=Color.White,fontSize=11.sp,lineHeight=15.sp,fontWeight=FontWeight.SemiBold)
            Text("Berkas utuh${message.acknowledgedAtMs?.let { " · konfirmasi ${voiceClock(it)}" } ?: ""}",color=Color.White.copy(alpha=.8f),fontSize=9.sp,lineHeight=13.sp)
        } else if(!sending) {
            Text(if(failed) "Suara belum berhasil dikirim dari HP." else "Kirim dari HP selesai${message.transferCompletedAtMs?.let { " · ${voiceClock(it)}" } ?: ""}",color=tone,fontSize=10.sp,lineHeight=14.sp)
            if(!failed) Text("Konfirmasi muncul jika app penerima mendukungnya.",color=Color.White.copy(alpha=.8f),fontSize=9.sp,lineHeight=13.sp)
        }
    }
}

private fun voiceClock(ms:Long)=formatClock(ms)+":"+((ms/1000)%60).toString().padStart(2,'0')
