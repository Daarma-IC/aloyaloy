package id.nusamesh.app

import androidx.compose.ui.window.ComposeUIViewController
import id.nusamesh.app.ble.IosMeshTransport

fun MainViewController() = ComposeUIViewController {
    NusaMeshApp(transport = IosMeshTransport())
}

