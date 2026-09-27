package id.nusamesh.app.ble

import id.nusamesh.app.domain.MeshConnection
import id.nusamesh.app.domain.MeshPeripheral
import id.nusamesh.app.domain.ConnectionPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Web browsers cannot use the phone's native GATT implementation. */
class WebPreviewTransport : MeshTransport {
    private val connectionState = MutableStateFlow(MeshConnection())
    override val connection: StateFlow<MeshConnection> = connectionState
    override val nearby: StateFlow<List<MeshPeripheral>> = MutableStateFlow(emptyList())
    override val incomingPackets: Flow<ByteArray> = emptyFlow()

    override fun startScanAndConnect() {
        connectionState.value = MeshConnection(ConnectionPhase.Failed, detail = "Bluetooth tersedia di aplikasi Android/iOS")
    }
    override fun disconnect() = Unit
    override suspend fun send(packet: ByteArray): Result<Unit> = Result.success(Unit)
}

