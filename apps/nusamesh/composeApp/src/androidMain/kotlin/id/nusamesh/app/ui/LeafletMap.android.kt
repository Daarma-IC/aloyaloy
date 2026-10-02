package id.nusamesh.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

private const val LOCAL_MAP_ORIGIN = "https://appassets.androidplatform.net"
private const val LOCAL_MAP_PAGE = "$LOCAL_MAP_ORIGIN/map.html"

/**
 * Menyajikan HTML dan JavaScript peta sebagai resource lokal sungguhan.
 *
 * Beberapa versi Android System WebView tidak mengeksekusi bundle Leaflet besar ketika bundle
 * tersebut disisipkan sebagai inline script lewat loadDataWithBaseURL. Resource interception ini
 * membuat WebView memuat map.html, leaflet.js, dan map.js seperti halaman web normal tanpa server.
 */
private class LocalMapWebView(context: Context) : WebView(context) {
    @Volatile private var document = MapDocument("", "", "")
    private var contentVersion: Int? = null

    init {
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val current = document
                return when (request.url.path) {
                    "/map.html" -> localResponse("text/html", current.html)
                    "/leaflet.js" -> localResponse("application/javascript", current.leafletJs)
                    "/map.js" -> localResponse("application/javascript", current.mapJs)
                    else -> super.shouldInterceptRequest(view, request)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                ensureMapStarted(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) Log.e("MeshtaMap", "Map page failed: ${error.description}")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("MeshtaMap", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
        setBackgroundColor(Color.rgb(238, 242, 255))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.allowContentAccess = true
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        settings.setSupportZoom(true)
        settings.userAgentString = settings.userAgentString + " Meshta/0.2"
    }

    fun render(html: String) {
        val version = html.hashCode()
        if (contentVersion == version) {
            post { evaluateJavascript("if(window.nusaMap){window.nusaMap.invalidateSize(false);}", null) }
            return
        }
        document = splitScripts(html)
        contentVersion = version
        loadUrl("$LOCAL_MAP_PAGE?v=$version")
    }

    private fun ensureMapStarted(view: WebView) {
        view.evaluateJavascript(
            "(function(){if(window.nusaMap)return 'map';if(window.L)return 'leaflet';return 'missing';})()",
        ) { state ->
            when {
                state.contains("map") -> resizeMap(view)
                state.contains("leaflet") -> runMapScript(view)
                else -> {
                    // Fallback untuk Android System WebView yang gagal mengambil script lokal
                    // melalui shouldInterceptRequest.
                    view.evaluateJavascript(document.leafletJs) { runMapScript(view) }
                }
            }
        }
    }

    private fun runMapScript(view: WebView) {
        view.evaluateJavascript(
            "if(!window.nusaMap){${document.mapJs}}",
        ) {
            resizeMap(view)
            view.postDelayed({
                view.evaluateJavascript("Boolean(window.nusaMap)") { ready ->
                    if (ready != "true") {
                        view.evaluateJavascript(
                            "(function(){var e=document.getElementById('map-status');" +
                                "if(e){e.style.display='block';e.textContent='Peta gagal dimulai. Perbarui Android System WebView.';}})()",
                            null,
                        )
                    }
                }
            }, 1_500)
        }
    }

    private fun resizeMap(view: WebView) {
        view.evaluateJavascript(
            "window.dispatchEvent(new Event('resize'));" +
                "if(window.nusaMap){window.nusaMap.invalidateSize(true);}",
            null,
        )
    }

    private fun localResponse(mimeType: String, content: String) = WebResourceResponse(
        mimeType,
        "UTF-8",
        ByteArrayInputStream(content.encodeToByteArray()),
    )
}

private data class MapDocument(val html: String, val leafletJs: String, val mapJs: String)

private fun splitScripts(source: String): MapDocument {
    val scripts = mutableListOf<String>()
    val externalHtml = Regex("<script>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).replace(source) { match ->
        val index = scripts.size
        scripts += match.groupValues[1]
        when (index) {
            0 -> "<script src=\"$LOCAL_MAP_ORIGIN/leaflet.js\"></script>"
            else -> "<script src=\"$LOCAL_MAP_ORIGIN/map.js\"></script>"
        }
    }
    require(scripts.size == 2) { "Dokumen peta harus memiliki bundle Leaflet dan script aplikasi" }
    return MapDocument(externalHtml, scripts[0], scripts[1])
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun LeafletWebView(modifier: Modifier, html: String) {
    AndroidView(
        modifier = modifier,
        factory = { context -> LocalMapWebView(context) },
        update = { webView -> webView.render(html) },
    )
}
