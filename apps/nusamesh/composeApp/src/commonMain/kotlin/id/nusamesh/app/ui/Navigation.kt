package id.nusamesh.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusamesh.app.domain.AppPage
import nusamesh.composeapp.generated.resources.Res
import nusamesh.composeapp.generated.resources.nav_home
import nusamesh.composeapp.generated.resources.nav_map
import nusamesh.composeapp.generated.resources.nav_message
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun AppBottomBar(selected: AppPage, onSelect: (AppPage) -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding()
            .padding(start = 30.dp, end = 30.dp, bottom = 17.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .background(CyanSoft, RoundedCornerShape(28.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(AppPage.Chats, "Message", Res.drawable.nav_message, selected, onSelect)
            NavItem(AppPage.Home, "Home", Res.drawable.nav_home, selected, onSelect)
            NavItem(AppPage.Map, "Map", Res.drawable.nav_map, selected, onSelect)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    page: AppPage,
    label: String,
    icon: DrawableResource,
    selected: AppPage,
    onSelect: (AppPage) -> Unit,
) {
    val active = page == selected
    Box(
        Modifier.weight(1f).fillMaxHeight().pressableClick { onSelect(page) },
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Row(
                Modifier.wrapContentWidth().height(30.dp)
                    .background(Color.White, RoundedCornerShape(15.dp)).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(icon), null, Modifier.size(16.dp), contentScale = ContentScale.Fit)
                Box(Modifier.width(7.dp))
                Text(label, color = Navy, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Light)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Image(painterResource(icon), null, Modifier.size(16.dp), contentScale = ContentScale.Fit)
                Text(label, color = Color(0xFF64748B), fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}
