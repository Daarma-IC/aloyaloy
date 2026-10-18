package id.nusamesh.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.data.formatClock
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class QosScale(val min: Double, val max: Double) {
    fun fraction(value: Double): Float = ((value-min)/(max-min)).toFloat().coerceIn(0f,1f)
}
internal fun qosScale(points: List<ChartPoint>, zeroBased: Boolean): QosScale {
    val min = if(zeroBased) 0.0 else (points.minOfOrNull { it.value } ?: 0.0)-2.0
    return QosScale(min, maxOf(min+1.0, (points.maxOfOrNull { it.value } ?: 1.0)+if(zeroBased) 1.0 else 2.0))
}
private data class QosCurve(val name: String, val unit: String, val color: Color, val points: List<ChartPoint>, val zeroBased: Boolean = false) {
    val scale = qosScale(points, zeroBased)
}
private fun clock(t:Long)=formatClock(t)+":"+((t/1000)%60).toString().padStart(2,'0')
private fun number(v:Double)=((v*10).roundToInt()/10.0).toString()

/** One time axis, independently labelled scales: unlike units are never silently mixed. */
@Composable
internal fun CombinedQosPlot(charts: TransmissionCharts, onDetails: (() -> Unit)? = null) {
    var selected by remember { mutableStateOf(setOf("BLE RSSI","LoRa RSSI","LoRa SNR","TX BLE")) }
    var cursor by remember { mutableStateOf<Float?>(null) }
    val curves = listOf(
        QosCurve("BLE RSSI","dBm",BrandDeep,charts.bleRssi),
        QosCurve("LoRa RSSI","dBm",Color(0xFF0F9488),charts.loraRssi),
        QosCurve("LoRa SNR","dB",Color(0xFF8B5CF6),charts.loraSnr),
        QosCurve("TX BLE","B/s",Warning,charts.txRate,true),
        QosCurve("RX BLE","B/s",Color(0xFFE044A7),charts.rxRate,true),
        QosCurve("RTT ACK","ms",Color(0xFF6366F1),charts.ackRtt,true),
        QosCurve("Antrean","ms",Color(0xFF64748B),charts.queueWait,true),
        QosCurve("BLE OK","paket/10s",Success,charts.accepted,true),
        QosCurve("BLE gagal","paket/10s",Danger,charts.failed,true),
    )
    val shown=curves.filter { it.name in selected }
    Column(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("QoS LoRa & BLE",fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                Text("5 menit · pilih beberapa parameter",fontSize=10.sp,lineHeight=14.sp,color=Slate)
            }
            if(onDetails!=null) TextButton(onClick=onDetails) { Text("Detail",fontSize=11.sp,lineHeight=15.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            curves.forEach { c -> FilterChip(selected=c.name in selected,onClick={selected=if(c.name in selected) selected-c.name else selected+c.name},label={Text(c.name,fontSize=10.sp,lineHeight=14.sp)},leadingIcon={Box(Modifier.size(6.dp).background(c.color,RoundedCornerShape(3.dp)))}) }
        }
        Text("Sumbu Y: 0–100% rentang tiap kurva (bukan nilai QoS). Skala asli di legenda.",fontSize=9.sp,lineHeight=12.sp,color=Slate)
        val hasData=shown.any { it.points.isNotEmpty() }
        Row(Modifier.fillMaxWidth().height(if(onDetails==null) 180.dp else 130.dp)) {
            Column(Modifier.width(28.dp).fillMaxHeight(),verticalArrangement=Arrangement.SpaceBetween) {
                listOf("100","50","0").forEach { Text(it,color=Slate,fontSize=9.sp,lineHeight=12.sp) }
            }
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF6F8FB),RoundedCornerShape(8.dp))) {
                Canvas(Modifier.fillMaxSize().semantics { contentDescription="Grafik QoS gabungan: ${shown.joinToString { it.name }}" }
                    .pointerInput(charts.startMs,charts.endMs) { detectTapGestures { cursor=(it.x/size.width).coerceIn(0f,1f) } }) {
                    fun x(t:Long)=((t-charts.startMs).toDouble()/(charts.endMs-charts.startMs).coerceAtLeast(1)*size.width).toFloat().coerceIn(0f,size.width)
                    repeat(5) { i -> val y=size.height*i/4;drawLine(Color(0xFFE1E7EF),Offset(0f,y),Offset(size.width,y),1f) }
                    shown.forEach { c ->
                        fun point(p:ChartPoint)=Offset(x(p.timeMs),size.height*(1-c.scale.fraction(p.value)))
                        c.points.zipWithNext().forEach { (a,b) -> if(b.timeMs-a.timeMs<=60_000) drawLine(c.color,point(a),point(b),2.dp.toPx()) }
                        c.points.forEach { drawCircle(c.color,2.dp.toPx(),point(it)) }
                    }
                    cursor?.let { drawLine(Slate,Offset(it*size.width,0f),Offset(it*size.width,size.height),1f) }
                }
                if(!hasData) Text(if(shown.isEmpty()) "Pilih parameter di atas" else "Menunggu data transmisi nyata",Modifier.align(Alignment.Center).padding(12.dp),color=Slate,fontSize=11.sp,lineHeight=15.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start=28.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(clock(charts.startMs),color=Slate,fontSize=9.sp,lineHeight=12.sp)
            Text(clock(charts.endMs),color=Slate,fontSize=9.sp,lineHeight=12.sp)
        }
        shown.forEach { c ->
            val at=cursor?.let { charts.startMs+((charts.endMs-charts.startMs)*it).toLong() }
            val sample=if(at==null) c.points.lastOrNull() else c.points.minByOrNull { abs(it.timeMs-at) }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(6.dp).background(c.color,RoundedCornerShape(3.dp)))
                Text(c.name,Modifier.weight(1f),fontSize=10.sp,lineHeight=14.sp,color=Ink)
                Text(sample?.let { "${number(it.value)} ${c.unit} · ${clock(it.timeMs)}" } ?: "Belum diukur",fontSize=9.sp,lineHeight=12.sp,color=Slate)
            }
            if(c.points.isNotEmpty()) Text("Skala ${c.name}: ${number(c.scale.min)}…${number(c.scale.max)} ${c.unit}",fontSize=8.sp,lineHeight=11.sp,color=Muted)
        }
        Text("Ketuk plot untuk membaca sampel terdekat tiap kurva; geser pilihan parameter untuk lainnya.",fontSize=9.sp,lineHeight=12.sp,color=Slate)
        if(charts.truncated) Text("Riwayat terbatas pada event yang masih tersimpan.",fontSize=9.sp,lineHeight=12.sp,color=Warning)
    }
}
