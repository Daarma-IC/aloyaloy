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

// Palet Meshta: biru langit (#74B9FF) sebagai merek, teal untuk jalur Nusa Node/LoRa, amber untuk nebeng.
val Ink = Color(0xFF0F172A)
val Brand = Color(0xFF74B9FF)
val BrandDeep = Color(0xFF0984E3)
val BrandBright = Color(0xFFA6D1FF)
val BrandSoft = Color(0xFFD6EAFF)
val BrandTint = Color(0xFFEEF6FF)
val Accent = Color(0xFF0D9488)
val AccentTint = Color(0xFFE6F6F4)
val Warning = Color(0xFFB45309)
val WarningTint = Color(0xFFFFF4E5)
val Canvas = Color(0xFFF5F7FC)
val Slate = Color(0xFF64748B)
val Muted = Color(0xFF94A3B8)
val Border = Color(0xFFE3E8F2)
val Success = Color(0xFF16A34A)
val SuccessTint = Color(0xFFE8F7EE)
val Danger = Color(0xFFE11D48)
val DangerTint = Color(0xFFFFEEF2)

private val colors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Accent,
    background = Canvas,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
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
