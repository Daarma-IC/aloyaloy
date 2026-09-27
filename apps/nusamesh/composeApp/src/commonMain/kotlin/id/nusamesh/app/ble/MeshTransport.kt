package id.nusamesh.app.ble

import id.nusamesh.app.domain.MeshConnection
import id.nusamesh.app.domain.MeshPeripheral
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Kontrak bersama. Implementasinya memakai GATT Android atau CoreBluetooth iOS. */
interface MeshTransport {
    val connection: StateFlow<MeshConnection>
    val nearby: StateFlow<List<MeshPeripheral>>
    val incomingPackets: Flow<ByteArray>

    fun startScanAndConnect()
    fun disconnect()
    suspend fun send(packet: ByteArray): Result<Unit>
}

object NusaGatt {
    const val SERVICE_UUID = "A1B2C3D4-E5F6-7890-1234-567890ABCDEF"
    const val CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"
    const val REQUESTED_MTU = 517
    const val MAX_PACKET_BYTES = 512
}

