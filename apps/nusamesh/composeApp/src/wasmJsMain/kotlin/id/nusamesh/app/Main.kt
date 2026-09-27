package id.nusamesh.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import id.nusamesh.app.ble.WebPreviewTransport
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
            transport = WebPreviewTransport(),
            initialPage = initialPage,
            initialConversationId = if (window.location.hash.lowercase() == "#global") "global" else null,
        )
    }
}
