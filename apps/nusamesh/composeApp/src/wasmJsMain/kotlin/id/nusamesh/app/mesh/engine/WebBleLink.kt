package id.nusamesh.app.mesh.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Browser tidak punya GATT server/advertising: pratinjau web hanya menampilkan UI. */
class WebBleLink : BleLink {
    override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.Unsupported)
    override val events: Flow<LinkEvent> = emptyFlow()
    override fun start(localPeerId: ByteArray) = Unit
    override fun stop() = Unit
    override fun connect(deviceId: String) = Unit
    override fun disconnect(deviceId: String) = Unit
    override suspend fun send(deviceId: String, data: ByteArray) = false
}
