package id.nusamesh.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
                setBackgroundColor(Color.rgb(238, 242, 255))
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(
                            "window.dispatchEvent(new Event('resize'));" +
                                "if(window.nusaMap){window.nusaMap.invalidateSize(true);}",
                            null,
                        )
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            Log.e("MeshtaMap", "Map page failed: ${error.description}")
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d(
                            "MeshtaMap",
                            "${message.message()} (${message.sourceId()}:${message.lineNumber()})",
                        )
                        return true
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.blockNetworkImage = false
                settings.allowContentAccess = true
                settings.setSupportZoom(true)
                // Kebijakan ubin OSM meminta identitas aplikasi yang jelas.
                settings.userAgentString = settings.userAgentString + " Meshta/0.2"
            }
        },
        update = { webView ->
            val contentVersion = html.hashCode()
            if (webView.tag != contentVersion) {
                webView.tag = contentVersion
                webView.loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            } else {
                webView.post {
                    webView.evaluateJavascript(
                        "if(window.nusaMap){window.nusaMap.invalidateSize(false);}",
                        null,
                    )
                }
            }
        },
    )
}
