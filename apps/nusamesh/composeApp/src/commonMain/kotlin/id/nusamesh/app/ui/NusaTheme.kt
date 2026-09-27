package id.nusamesh.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import nusamesh.composeapp.generated.resources.Res
import nusamesh.composeapp.generated.resources.inter_light
import nusamesh.composeapp.generated.resources.inter_medium
import nusamesh.composeapp.generated.resources.inter_regular
import nusamesh.composeapp.generated.resources.inter_semibold
import org.jetbrains.compose.resources.Font

val Navy = Color(0xFF1E293B)
val Cyan = Color(0xFF0284C7)
val CyanSoft = Color(0xFFC9FAFA)
val CyanPale = Color(0xFFE7FAFB)
val Slate = Color(0xFF64748B)
val Border = Color(0xFFD7E2ED)
val Success = Color(0xFF10B981)
val Danger = Color(0xFFEF4444)

private val colors = lightColorScheme(
    primary = Cyan,
    onPrimary = Color.White,
    secondary = Color(0xFF16B8C7),
    background = Color.White,
    surface = Color.White,
    onBackground = Navy,
    onSurface = Navy,
    outline = Border,
)

@Composable
fun NusaTheme(content: @Composable () -> Unit) {
    val inter = FontFamily(
        Font(Res.font.inter_light, weight = FontWeight.Light),
        Font(Res.font.inter_regular, weight = FontWeight.Normal),
        Font(Res.font.inter_medium, weight = FontWeight.Medium),
        Font(Res.font.inter_semibold, weight = FontWeight.SemiBold),
    )
    val defaults = Typography()
    val typography = Typography(
        displayLarge = defaults.displayLarge.copy(fontFamily = inter),
        displayMedium = defaults.displayMedium.copy(fontFamily = inter),
        displaySmall = defaults.displaySmall.copy(fontFamily = inter),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = inter),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = inter),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = inter),
        titleLarge = defaults.titleLarge.copy(fontFamily = inter),
        titleMedium = defaults.titleMedium.copy(fontFamily = inter),
        titleSmall = defaults.titleSmall.copy(fontFamily = inter),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = inter),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = inter),
        bodySmall = defaults.bodySmall.copy(fontFamily = inter),
        labelLarge = defaults.labelLarge.copy(fontFamily = inter),
        labelMedium = defaults.labelMedium.copy(fontFamily = inter),
        labelSmall = defaults.labelSmall.copy(fontFamily = inter),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
