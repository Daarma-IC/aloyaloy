package id.nusamesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.domain.AppUiState

@Composable
fun MapScreen(state: AppUiState, padding: PaddingValues) {
    var sheetFraction by remember { mutableFloatStateOf(0.45f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFFF8FAFC))
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) {
        val density = LocalDensity.current
        val availablePx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f - sheetFraction).background(BrandTint)) {
                LeafletMap(Modifier.fillMaxSize(), state.trackedUsers)
            }
            Column(
                Modifier.fillMaxWidth()
                .weight(sheetFraction)
                .background(Color.White, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(24.dp).pointerInput(availablePx) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        sheetFraction = (sheetFraction - dragAmount / availablePx).coerceIn(0.24f, 0.78f)
                    }
                },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.width(48.dp).height(6.dp).background(Color(0xFFCBD5E1), RoundedCornerShape(3.dp)))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Unit Lapangan", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                StatusPill("${state.trackedUsers.size} Online")
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (state.trackedUsers.isEmpty()) {
                    Text("Belum ada unit dengan koordinat yang diterima.", color = Slate, fontSize = 12.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.trackedUsers.forEach { unit ->
                            Text(
                                "${if (unit.own) "Anda" else unit.name} · ${unit.rssi?.let { "$it dBm" } ?: "RSSI relay"} · ±${unit.accuracyMeters.toInt()} m",
                                color = Slate,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}
