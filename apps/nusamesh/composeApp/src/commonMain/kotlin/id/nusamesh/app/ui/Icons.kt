package id.nusamesh.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Ikon garis sederhana (digambar, bukan emoji) agar gaya ikon seragam di semua platform. */
internal enum class IconKind { Plus, Close, Back, Mesh, Image, File, Mic, Send, Play, Node, Relay, Lock, Signal, Chevron, Stop }

@Composable
internal fun AppIcon(kind: IconKind, color: Color, modifier: Modifier = Modifier) {
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
            IconKind.Node -> {
                // Menara pemancar: tiang + dua gelombang.
                drawLine(color, Offset(center.x, size.height * .42f), Offset(center.x, size.height * .9f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(center.x, size.height * .55f), Offset(size.width * .3f, size.height * .9f), stroke.width * .8f, StrokeCap.Round)
                drawLine(color, Offset(center.x, size.height * .55f), Offset(size.width * .7f, size.height * .9f), stroke.width * .8f, StrokeCap.Round)
                drawCircle(color, size.minDimension * .08f, Offset(center.x, size.height * .36f))
                drawArc(color, 200f, 140f, false, topLeft = Offset(size.width * .24f, size.height * .1f), size = Size(size.width * .52f, size.height * .52f), style = stroke)
                drawArc(color, 200f, 140f, false, topLeft = Offset(size.width * .06f, size.height * -.06f), size = Size(size.width * .88f, size.height * .84f), style = stroke)
            }
            IconKind.Relay -> {
                // Nebeng: dua HP dengan panah titip ke kanan.
                drawRoundRect(color, topLeft = Offset(size.width * .06f, size.height * .22f), size = Size(size.width * .26f, size.height * .56f), style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .06f))
                drawRoundRect(color, topLeft = Offset(size.width * .68f, size.height * .22f), size = Size(size.width * .26f, size.height * .56f), style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .06f))
                drawLine(color, Offset(size.width * .38f, center.y), Offset(size.width * .62f, center.y), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .53f, size.height * .4f), Offset(size.width * .62f, center.y), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .53f, size.height * .6f), Offset(size.width * .62f, center.y), stroke.width, StrokeCap.Round)
            }
            IconKind.Lock -> {
                drawRoundRect(color, topLeft = Offset(size.width * .2f, size.height * .45f), size = Size(size.width * .6f, size.height * .45f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .08f))
                drawArc(color, 180f, 180f, false, topLeft = Offset(size.width * .32f, size.height * .12f), size = Size(size.width * .36f, size.height * .66f), style = stroke)
            }
            IconKind.Signal -> {
                listOf(.3f, .5f, .7f, .9f).forEachIndexed { i, h ->
                    val x = size.width * (.18f + i * .21f)
                    drawLine(color, Offset(x, size.height * .9f), Offset(x, size.height * (1f - h) + size.height * .08f), stroke.width * 1.2f, StrokeCap.Round)
                }
            }
            IconKind.Chevron -> {
                drawLine(color, Offset(size.width * .38f, size.height * .22f), Offset(size.width * .66f, center.y), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .66f, center.y), Offset(size.width * .38f, size.height * .78f), stroke.width, StrokeCap.Round)
            }
            IconKind.Stop -> drawRoundRect(
                color,
                topLeft = Offset(size.width * .28f, size.height * .28f),
                size = Size(size.width * .44f, size.height * .44f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .06f),
            )
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
