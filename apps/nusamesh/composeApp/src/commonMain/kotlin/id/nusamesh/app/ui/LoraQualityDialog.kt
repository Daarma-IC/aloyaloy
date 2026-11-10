package id.nusamesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import id.nusamesh.app.media.LoraImageQuality

/** Pilih kualitas gambar sebelum galeri dibuka; hanya muncul bila gambar akan lewat Nusa Node. */
@Composable
fun LoraQualityDialog(onPick: (LoraImageQuality) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Kualitas gambar LoRa", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Makin detail, makin lama terkirim dan makin rentan gagal.", color = Slate, fontSize = 11.sp)
            LoraImageQuality.entries.forEach { quality ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(BrandTint, RoundedCornerShape(12.dp))
                        .pressableClick { onPick(quality) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(quality.label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${quality.maxSide} px · maks ${quality.maxBytes / 1000} KB · ${quality.estimate}",
                        color = Slate, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
