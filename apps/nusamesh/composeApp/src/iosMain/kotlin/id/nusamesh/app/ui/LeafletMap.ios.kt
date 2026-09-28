package id.nusamesh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import platform.Foundation.NSURL
import platform.WebKit.WKWebView

@Composable
actual fun LeafletWebView(modifier: Modifier, html: String) {
    UIKitView(
        modifier = modifier,
        factory = {
            WKWebView().apply {
                customUserAgent = "Mozilla/5.0 (iPhone) Meshta/0.2"
                loadHTMLString(html, baseURL = NSURL.URLWithString(MAP_BASE_URL))
            }
        },
    )
}
