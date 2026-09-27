package id.nusamesh.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.domain.AppUiState
import id.nusamesh.app.domain.ConnectionPhase
import id.nusamesh.app.domain.FieldUnit
import nusamesh.composeapp.generated.resources.Res
import nusamesh.composeapp.generated.resources.avatar
import nusamesh.composeapp.generated.resources.bluetooth
import nusamesh.composeapp.generated.resources.device_phone
import nusamesh.composeapp.generated.resources.reboot
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun HomeScreen(
    state: AppUiState,
    padding: PaddingValues,
    onConnectionClick: () -> Unit,
    onRebootClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.White),
        contentPadding = PaddingValues(
            start = 30.dp,
            end = 30.dp,
            top = padding.calculateTopPadding() + 28.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        item { HomeHeader() }
        item { Spacer(Modifier.height(20.dp)) }
        item { ModuleQualityCard(state, onConnectionClick, onRebootClick) }
        item { Spacer(Modifier.height(25.dp)) }
        item { DevicesHeader(state.fieldUnits.size) }
        item { Spacer(Modifier.height(12.dp)) }
        if (state.fieldUnits.isEmpty()) {
            item { EmptyDevices(state.connection.phase) }
        } else {
            items(state.fieldUnits, key = { it.id }) { unit ->
                DeviceCard(unit)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Welcome Back", color = Navy, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium)
            Text("Nusa Mobile", color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 14.sp)
        }
        Box(Modifier.size(48.dp).background(Color(0xFFF8FAFC), CircleShape).border(2.dp, CyanSoft, CircleShape).padding(3.dp)) {
            Image(painterResource(Res.drawable.avatar), "Profil Nusa Mobile", Modifier.fillMaxSize().clip(CircleShape))
            Box(Modifier.align(Alignment.BottomEnd).size(10.dp).background(Success, CircleShape).border(2.dp, Color.White, CircleShape))
        }
    }
}

@Composable
private fun ModuleQualityCard(state: AppUiState, onConnectionClick: () -> Unit, onRebootClick: () -> Unit) {
    val connected = state.connection.phase == ConnectionPhase.Connected
    val busy = state.connection.phase == ConnectionPhase.Scanning || state.connection.phase == ConnectionPhase.Connecting
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, CyanSoft, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Module Quality", color = Navy, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
                Text(
                    "NodeID : ${state.connection.peripheral?.id?.takeLast(8) ?: "--------"}",
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                )
            }
            StatusPill(
                if (connected) "Optimal" else if (busy) "Mencari" else if (state.connection.phase == ConnectionPhase.Failed) "Gagal" else "Offline",
                if (connected) Success else if (state.connection.phase == ConnectionPhase.Failed) Danger else if (busy) Cyan else Slate,
                if (connected) Color(0xFFECFDF5) else if (state.connection.phase == ConnectionPhase.Failed) Color(0xFFFFF1F2) else if (busy) CyanPale else Color(0xFFF1F5F9),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .background(CyanSoft.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                .border(1.dp, CyanSoft, RoundedCornerShape(16.dp))
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Metric("RSSI", state.connection.peripheral?.rssi?.let { "$it dBm" } ?: "-- dBm", "BLE")
            Metric("SNR", state.health.bestSnr?.let { "$it dB" } ?: "-- dB", "LoRa")
            Metric("TX POWER", "-- dBm", "Node")
        }
        Spacer(Modifier.height(13.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                label = when (state.connection.phase) {
                    ConnectionPhase.Scanning -> "Mencari"
                    ConnectionPhase.Connecting -> "Menghubungkan"
                    ConnectionPhase.Connected -> "Putuskan"
                    ConnectionPhase.Failed -> "Coba Lagi"
                    else -> "Hubungkan"
                },
                icon = Res.drawable.bluetooth,
                onClick = onConnectionClick,
                modifier = Modifier.weight(1f),
                busy = busy,
                emphasized = connected,
            )
            ActionButton("Reboot", Res.drawable.reboot, onRebootClick, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: DrawableResource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    emphasized: Boolean = false,
) {
    val shape = RoundedCornerShape(19.dp)
    Row(
        modifier.heightIn(min = 38.dp)
            .background(if (emphasized) CyanPale else Color.White, shape)
            .border(1.dp, if (emphasized) CyanSoft else Border, shape)
            .then(if (busy) Modifier else Modifier.pressableClick(onClick)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Cyan, strokeWidth = 1.5.dp)
        } else {
            Image(painterResource(icon), null, Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = Navy, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun Metric(label: String, value: String, hint: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color(0xFF8090A6), fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Navy, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
        Text(hint, color = Cyan, fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DevicesHeader(count: Int) {
    Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("PERANGKAT SALING TERHUBUNG", color = Cyan, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
        StatusPill("$count Aktif", Cyan, CyanSoft)
    }
}

@Composable
private fun EmptyDevices(phase: ConnectionPhase) {
    Box(
        Modifier.fillMaxWidth().height(70.dp).border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            if (phase == ConnectionPhase.Scanning) "Sedang mencari perangkat NusaMesh…"
            else if (phase == ConnectionPhase.Connecting) "Perangkat ditemukan, sedang menghubungkan…"
            else if (phase == ConnectionPhase.Failed) "Koneksi gagal. Tekan Coba Lagi untuk memindai ulang."
            else "Belum ada perangkat terdeteksi. Tekan Hubungkan untuk mulai mencari.",
            color = Slate,
            fontSize = 10.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun DeviceCard(unit: FieldUnit) {
    Row(
        Modifier.fillMaxWidth().height(70.dp).border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp)).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(39.dp).background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp)).border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Image(painterResource(Res.drawable.device_phone), null, Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(unit.name, color = Navy, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp)
            Text(unit.subtitle, color = Color(0xFF94A3B8), fontSize = 8.sp, lineHeight = 11.sp)
        }
        StatusPill(unit.status, Success)
    }
}




