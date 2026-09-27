package id.nusamesh.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Area tekan tanpa highlight persegi, dengan feedback tekan yang mengikuti bentuk konten. */
fun Modifier.pressableClick(onClick: () -> Unit) = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 90),
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = if (pressed) 0.78f else 1f
    }.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

@Composable
fun StatusPill(text: String, color: Color = Success, background: Color = Color(0xFFECFDF5)) {
    Row(
        modifier = Modifier.background(background, RoundedCornerShape(11.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, color = color, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
    }
}

fun PaddingValues.contentTop() = calculateTopPadding() + 16.dp
fun PaddingValues.contentBottom() = calculateBottomPadding() + 8.dp
