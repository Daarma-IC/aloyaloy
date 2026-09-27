package id.nusamesh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import platform.Foundation.NSURL
import platform.WebKit.WKWebView

@Composable
actual fun LeafletMap(modifier: Modifier) {
    UIKitView(
        modifier = modifier,
        factory = {
            WKWebView().apply {
                loadHTMLString(leafletHtml, baseURL = NSURL.URLWithString("https://nusamesh.local/"))
            }
        },
    )
}
