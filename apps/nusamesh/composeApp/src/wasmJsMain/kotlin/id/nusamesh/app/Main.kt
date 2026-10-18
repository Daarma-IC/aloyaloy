package id.nusamesh.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import id.nusamesh.app.data.WebKeyValueStore
import id.nusamesh.app.mesh.engine.WebBleLink
import id.nusamesh.app.domain.AppPage
import id.nusamesh.app.media.WebDocumentActions
import kotlinx.browser.document
import kotlinx.browser.window

/** Browser entry point: same shared Compose screens as Android and iOS. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val initialPage = when (window.location.hash.lowercase()) {
        "#chat", "#message", "#global" -> AppPage.Chats
        "#map" -> AppPage.Map
        "#monitor" -> AppPage.Chats
        else -> AppPage.Home
    }
    ComposeViewport(viewportContainer = document.getElementById("app")!!) {
        NusaMeshApp(
            link = WebBleLink(),
            store = WebKeyValueStore(),
            documentActions = WebDocumentActions,
            initialPage = initialPage,
            initialConversationId = if (window.location.hash.lowercase() == "#global") "global" else null,
        )
    }
}
