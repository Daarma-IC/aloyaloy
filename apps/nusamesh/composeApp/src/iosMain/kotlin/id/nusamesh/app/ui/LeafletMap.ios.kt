@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package id.nusamesh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/** Menerima ketukan peta dari JavaScript dan meneruskan data peta setelah halaman selesai dimuat. */
private class MapBridge : NSObject(), WKScriptMessageHandlerProtocol, WKNavigationDelegateProtocol {
    var onMapTap: (Double, Double) -> Unit = { _, _ -> }
    var webView: WKWebView? = null
    private var ready = false
    private var dataJson = "null"

    fun setData(json: String) {
        if (json == dataJson) return
        dataJson = json
        if (ready) push()
    }

    private fun push() {
        webView?.evaluateJavaScript("if(window.setNusaData){window.setNusaData($dataJson);}", completionHandler = null)
    }

    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        val body = didReceiveScriptMessage.body as? List<*> ?: return
        val latitude = (body.getOrNull(0) as? NSNumber)?.doubleValue ?: return
        val longitude = (body.getOrNull(1) as? NSNumber)?.doubleValue ?: return
        onMapTap(latitude, longitude)
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        ready = true
        push()
    }
}

@Composable
actual fun LeafletWebView(modifier: Modifier, html: String, dataJson: String, onMapTap: (Double, Double) -> Unit) {
    val bridge = remember { MapBridge() }
    bridge.onMapTap = onMapTap
    UIKitView(
        modifier = modifier,
        factory = {
            val configuration = WKWebViewConfiguration().apply {
                userContentController.addScriptMessageHandler(bridge, name = "nusaTap")
            }
            WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
                customUserAgent = "Mozilla/5.0 (iPhone) Meshta/0.2"
                navigationDelegate = bridge
                bridge.webView = this
                loadHTMLString(html, baseURL = NSURL.URLWithString(MAP_BASE_URL))
            }
        },
        update = { bridge.setData(dataJson) },
    )
}
