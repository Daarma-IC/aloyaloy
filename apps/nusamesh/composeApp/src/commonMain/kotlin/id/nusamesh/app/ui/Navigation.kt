package id.nusamesh.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import id.nusamesh.app.domain.AppPage
import nusamesh.composeapp.generated.resources.Res
import nusamesh.composeapp.generated.resources.nav_home
import nusamesh.composeapp.generated.resources.nav_map
import nusamesh.composeapp.generated.resources.nav_message
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Tinggi total bar + jarak bawah; konten halaman memberi ruang sebesar ini agar tidak tertutup. */
val BottomBarSpace = 96.dp

private data class NavTab(val page: AppPage, val label: String, val icon: DrawableResource)

private val tabs = listOf(
    NavTab(AppPage.Chats, "Pesan", Res.drawable.nav_message),
    NavTab(AppPage.Home, "Beranda", Res.drawable.nav_home),
    NavTab(AppPage.Map, "Peta", Res.drawable.nav_map),
)

/**
 * Bar navigasi "kaca" melayang ala iOS: latar di belakangnya diburamkan (haze), tepi berkilau tipis,
 * dan lensa pilihan meluncur ke tab aktif.
 */
@Composable
fun AppBottomBar(selected: AppPage, hazeState: HazeState, onSelect: (AppPage) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(32.dp)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, bottom = 14.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(64.dp)
                .shadow(elevation = 18.dp, shape = shape, ambientColor = Brand.copy(alpha = .25f), spotColor = Brand.copy(alpha = .25f))
                .clip(shape)
                .hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = Canvas,
                        tint = HazeTint(Color.White.copy(alpha = .55f)),
                        blurRadius = 22.dp,
                        noiseFactor = 0f,
                    ),
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = .95f), Color.White.copy(alpha = .25f))),
                    shape,
                ),
        ) {
            val index = tabs.indexOfFirst { it.page == selected }.coerceAtLeast(0)
            val tabWidth = maxWidth / tabs.size
            val lensX by animateDpAsState(
                targetValue = tabWidth * index,
                animationSpec = spring(dampingRatio = .72f, stiffness = Spring.StiffnessMediumLow),
            )
            // Lensa pilihan.
            Box(
                Modifier.offset(x = lensX).width(tabWidth).fillMaxHeight().padding(6.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = .95f), BrandTint.copy(alpha = .9f))),
                        RoundedCornerShape(26.dp),
                    )
                    .border(1.dp, Color.White, RoundedCornerShape(26.dp)),
            )
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab -> NavItem(tab, tab.page == selected, Modifier.weight(1f)) { onSelect(tab.page) } }
            }
        }
    }
}

@Composable
private fun NavItem(tab: NavTab, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(if (active) Brand else Slate)
    Box(modifier.fillMaxHeight().pressableClick(onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(painterResource(tab.icon), contentDescription = tab.label, tint = tint, modifier = Modifier.size(20.dp))
            Text(tab.label, color = tint, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
