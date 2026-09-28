package id.nusamesh.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun LeafletWebView(modifier: Modifier, html: String) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportZoom(true)
                // Kebijakan ubin OSM meminta identitas aplikasi yang jelas.
                settings.userAgentString = settings.userAgentString + " Meshta/0.2"
                loadDataWithBaseURL(MAP_BASE_URL, html, "text/html", "UTF-8", null)
            }
        },
    )
}
