package id.nusamesh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.viewinterop.WebElementView
import kotlinx.browser.document
import org.w3c.dom.HTMLIFrameElement

/** Peta di iframe srcdoc (same-origin): ketukan memanggil `parent.nusaMapTap`. */
@JsFun("(f) => { window.nusaMapTap = (lat, lon) => f(lat, lon); }")
private external fun installMapTap(handler: (Double, Double) -> Unit)

@JsFun("() => { delete window.nusaMapTap; }")
private external fun removeMapTap()

/** Data dititipkan di atribut iframe (dibaca peta saat siap) lalu dikirim langsung bila peta sudah jalan. */
@JsFun(
    "(frame, json) => { frame.dataset.nusaData = json; const w = frame.contentWindow;" +
        " if (w && w.setNusaData) w.setNusaData(JSON.parse(json)); }",
)
private external fun pushMapData(frame: HTMLIFrameElement, json: String)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
actual fun LeafletWebView(modifier: Modifier, html: String, dataJson: String, onMapTap: (Double, Double) -> Unit) {
    val currentTap = rememberUpdatedState(onMapTap)
    DisposableEffect(Unit) {
        installMapTap { lat, lon -> currentTap.value(lat, lon) }
        onDispose { removeMapTap() }
    }
    WebElementView(
        factory = {
            (document.createElement("iframe") as HTMLIFrameElement).apply {
                setAttribute("srcdoc", html)
                setAttribute("title", "Peta Meshta")
                setAttribute("style", "border:0;width:100%;height:100%")
            }
        },
        modifier = modifier,
        update = { frame -> pushMapData(frame, dataJson) },
    )
}
