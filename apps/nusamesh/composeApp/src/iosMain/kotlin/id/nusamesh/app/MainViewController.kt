package id.nusamesh.app

import androidx.compose.ui.window.ComposeUIViewController
import id.nusamesh.app.data.IosKeyValueStore
import id.nusamesh.app.media.IosMediaActions
import id.nusamesh.app.mesh.engine.IosBleLink
import id.nusamesh.app.location.IosLocationAccess
import platform.UIKit.UIViewController

private val link by lazy { IosBleLink() }
private val store by lazy { IosKeyValueStore() }
private val media by lazy { IosMediaActions() }
private val location by lazy { IosLocationAccess() }

fun MainViewController(): UIViewController {
    val controller = ComposeUIViewController {
        NusaMeshApp(link = link, store = store, mediaActions = media, locationAccess = location)
    }
    media.presenter = controller
    return controller
}
