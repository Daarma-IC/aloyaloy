package id.nusamesh.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject

private const val LOCAL_MAP_ORIGIN = "https://appassets.androidplatform.net"
private const val LOCAL_MAP_PAGE = "$LOCAL_MAP_ORIGIN/map.html"

/**
 * Basemap native yang tidak bergantung Android System WebView. Leaflet tetap diletakkan di atasnya
 * untuk interaksi/detail online, tetapi kegagalan JavaScript tidak lagi menghasilkan bidang kosong.
 */
private class NativeOfflineBasemap(context: Context) : View(context) {
    private val polygons = mutableListOf<List<Pair<Double, Double>>>()
    private val land = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 250, 252); style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(130, 150, 173); style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density
    }

    init { setBackgroundColor(Color.rgb(207, 232, 246)) }

    fun loadFromHtml(html: String) {
        if (polygons.isNotEmpty()) return
        val marker = "const offlineRegion = "
        val start = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return
        val end = html.indexOf(";\n    L.geoJSON", start).takeIf { it > start } ?: return
        runCatching {
            val features = JSONObject(html.substring(start, end)).getJSONArray("features")
            for (i in 0 until features.length()) readGeometry(features.getJSONObject(i).getJSONObject("geometry"))
        }.onFailure { Log.e("MeshtaMap", "Native basemap parse failed", it) }
        invalidate()
    }

    private fun readGeometry(geometry: JSONObject) {
        val coordinates = geometry.getJSONArray("coordinates")
        when (geometry.getString("type")) {
            "Polygon" -> readPolygon(coordinates)
            "MultiPolygon" -> for (i in 0 until coordinates.length()) readPolygon(coordinates.getJSONArray(i))
        }
    }

    private fun readPolygon(rings: JSONArray) {
        if (rings.length() == 0) return
        val ring = rings.getJSONArray(0)
        val points = ArrayList<Pair<Double, Double>>(ring.length())
        for (i in 0 until ring.length()) {
            val point = ring.getJSONArray(i)
            points += point.getDouble(0) to point.getDouble(1)
        }
        if (points.size >= 3) polygons += points
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val leftLon = 91.0
        val rightLon = 144.0
        val topLat = 12.0
        val bottomLat = -15.0
        fun x(lon: Double) = ((lon - leftLon) / (rightLon - leftLon) * width).toFloat()
        fun y(lat: Double) = ((topLat - lat) / (topLat - bottomLat) * height).toFloat()
        for (polygon in polygons) {
            val path = Path()
            polygon.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(point.first), y(point.second)) else path.lineTo(x(point.first), y(point.second))
            }
            path.close()
            canvas.drawPath(path, land)
            canvas.drawPath(path, border)
        }
    }
}

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
    private var mapReady = false
    private var dataJson = "null"
    var onMapReady: (() -> Unit)? = null
    var onMapTap: ((Double, Double) -> Unit)? = null

    init {
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val current = document
                val path = request.url.path.orEmpty()
                // Ubin OSM lewat cache offline; paket MBTiles di /mbtiles/{z}/{x}/{y}. Ubin yang tidak ada
                // dijawab 404 cepat supaya Leaflet langsung jatuh ke peta wilayah kasar, bukan menunggu timeout.
                // Berjalan di thread IO WebView: exception di sini mematikan seluruh aplikasi, jadi semua
                // kegagalan penyajian ubin dijawab 404 (peta jatuh ke peta wilayah kasar), bukan crash.
                if (request.url.host == "tile.openstreetmap.org") {
                    return runCatching {
                        TileMath.parse(path)?.let(OfflineTileStore::osmTile)?.let { tileResponse(it, "image/png") }
                    }.onFailure { Log.w("MeshtaMap", "Ubin OSM gagal disajikan", it) }.getOrNull() ?: notFound()
                }
                if (path.startsWith("/mbtiles/")) {
                    return runCatching {
                        TileMath.parse(path.removePrefix("/mbtiles"))?.let(OfflineTileStore::packTile)?.let { (bytes, mime) -> tileResponse(bytes, mime) }
                    }.onFailure { Log.w("MeshtaMap", "Ubin paket gagal disajikan", it) }.getOrNull() ?: notFound()
                }
                return when (path) {
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
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.allowContentAccess = true
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        settings.setSupportZoom(true)
        settings.userAgentString = settings.userAgentString + " Meshta/0.2"
        addJavascriptInterface(TapBridge(), "NusaBridge")
        OfflineTileStore.init(context)
    }

    /** Dipanggil JavaScript (thread binder WebView) saat peta diketuk. */
    private inner class TapBridge {
        @JavascriptInterface
        fun tap(latitude: Double, longitude: Double) {
            post { onMapTap?.invoke(latitude, longitude) }
        }
    }

    /** Kirim data peta terbaru tanpa memuat ulang halaman; ditunda sampai peta siap. */
    fun setData(json: String) {
        if (json == dataJson) return
        dataJson = json
        if (mapReady) pushData()
    }

    private fun pushData() {
        evaluateJavascript("if(window.setNusaData){window.setNusaData($dataJson);}", null)
    }

    private fun markReady() {
        mapReady = true
        pushData()
        onMapReady?.invoke()
    }

    fun render(html: String) {
        val version = html.hashCode()
        if (contentVersion == version) {
            post { evaluateJavascript("if(window.nusaMap){window.nusaMap.invalidateSize(false);}", null) }
            return
        }
        document = splitScripts(html)
        contentVersion = version
        mapReady = false
        loadUrl("$LOCAL_MAP_PAGE?v=$version")
    }

    private fun ensureMapStarted(view: WebView) {
        view.evaluateJavascript(
            "(function(){if(window.nusaMap)return 'map';if(window.L)return 'leaflet';return 'missing';})()",
        ) { state ->
            when {
                state.contains("map") -> {
                    markReady()
                    resizeMap(view)
                }
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
                    if (ready == "true") {
                        markReady()
                    } else {
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

    private fun tileResponse(bytes: ByteArray, mime: String) = WebResourceResponse(mime, null, ByteArrayInputStream(bytes))

    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun localResponse(mimeType: String, content: String) = WebResourceResponse(
        mimeType,
        "UTF-8",
        ByteArrayInputStream(content.encodeToByteArray()),
    )
}

private class OfflineMapContainer(context: Context) : FrameLayout(context) {
    private val backdrop = NativeOfflineBasemap(context)
    private val webView = LocalMapWebView(context)

    init {
        webView.onMapReady = { backdrop.visibility = View.GONE }
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun render(html: String, dataJson: String, onMapTap: (Double, Double) -> Unit) {
        backdrop.loadFromHtml(html)
        webView.onMapTap = onMapTap
        webView.render(html)
        webView.setData(dataJson)
    }
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
actual fun LeafletWebView(modifier: Modifier, html: String, dataJson: String, onMapTap: (Double, Double) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context -> OfflineMapContainer(context) },
        update = { container -> container.render(html, dataJson, onMapTap) },
    )
}
