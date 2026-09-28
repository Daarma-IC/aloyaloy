package id.nusamesh.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import id.nusamesh.app.data.WebKeyValueStore
import id.nusamesh.app.mesh.engine.WebBleLink
import id.nusamesh.app.domain.AppPage
import kotlinx.browser.document
import kotlinx.browser.window

/** Browser entry point: same shared Compose screens as Android and iOS. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val initialPage = when (window.location.hash.lowercase()) {
        "#chat", "#message", "#global" -> AppPage.Chats
        "#map" -> AppPage.Map
        else -> AppPage.Home
    }
    ComposeViewport(viewportContainer = document.getElementById("app")!!) {
        NusaMeshApp(
            link = WebBleLink(),
            store = WebKeyValueStore(),
            initialPage = initialPage,
            initialConversationId = if (window.location.hash.lowercase() == "#global") "global" else null,
        )
    }
}
