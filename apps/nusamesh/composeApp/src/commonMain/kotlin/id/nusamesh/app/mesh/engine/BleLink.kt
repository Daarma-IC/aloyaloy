package id.nusamesh.app.mesh.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** UUID GATT mesh — identik dengan Nusa Mesh Android dan firmware NusaNode (nusa_ble.cpp). */
object MeshGatt {
    const val SERVICE_UUID = "A1B2C3D4-E5F6-7890-1234-567890ABCDEF"
    const val CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    const val REQUESTED_MTU = 517
}

enum class LinkState { Unsupported, Unauthorized, PoweredOff, Ready }

sealed interface LinkEvent {
    /**
     * Perangkat mesh terdengar lewat scan. [peerId] berasal dari service data iklan (8 byte, hex) — Android
     * dan NusaNode menyertakannya; iOS tidak bisa beriklan dengan service data, jadi null sampai ANNOUNCE.
     */
    data class Discovered(val deviceId: String, val name: String?, val rssi: Int, val peerId: String?) : LinkEvent
    /** Koneksi siap bertukar data (notify aktif / sudah subscribe). */
    data class Connected(val deviceId: String, val asCentral: Boolean, val mtu: Int) : LinkEvent
    data class Disconnected(val deviceId: String) : LinkEvent
    data class Received(val deviceId: String, val data: ByteArray) : LinkEvent
    data class MtuChanged(val deviceId: String, val mtu: Int) : LinkEvent
    data class RssiRead(val deviceId: String, val rssi: Int) : LinkEvent
    data class ConnectFailed(val deviceId: String, val reason: String) : LinkEvent
}

/**
 * Radio BLE per platform: HANYA memindahkan byte. Semua keputusan (siapa disambung, relay, node, handover)
 * ada di [MeshEngine] di commonMain supaya Android dan iOS berperilaku sama persis.
 *
 * Satu paket = satu GATT write / notify (tanpa framing tambahan), seperti Nusa Mesh dan firmware.
 */
interface BleLink {
    val state: StateFlow<LinkState>
    val events: Flow<LinkEvent>

    /** Mulai beriklan (service UUID + service data [localPeerId]) dan memindai perangkat mesh. */
    fun start(localPeerId: ByteArray)
    fun stop()
    fun connect(deviceId: String)
    fun disconnect(deviceId: String)
    /** true bila paket diterima tumpukan BLE untuk dikirim. Menunggu giliran bila antrean GATT penuh. */
    suspend fun send(deviceId: String, data: ByteArray): Boolean
}
