package id.nusamesh.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun LeafletMap(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportZoom(true)
                loadDataWithBaseURL("https://nusamesh.local/", leafletHtml, "text/html", "UTF-8", null)
            }
        },
    )
}
