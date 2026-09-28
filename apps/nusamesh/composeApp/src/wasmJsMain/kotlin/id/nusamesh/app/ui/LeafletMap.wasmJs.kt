package id.nusamesh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.viewinterop.WebElementView
import kotlinx.browser.document
import org.w3c.dom.HTMLIFrameElement

@Composable
@OptIn(ExperimentalComposeUiApi::class)
actual fun LeafletWebView(modifier: Modifier, html: String) {
    WebElementView(
        factory = {
            (document.createElement("iframe") as HTMLIFrameElement).apply {
                setAttribute("srcdoc", html)
                setAttribute("title", "Peta Meshta")
                setAttribute("style", "border:0;width:100%;height:100%")
            }
        },
        modifier = modifier,
    )
}
