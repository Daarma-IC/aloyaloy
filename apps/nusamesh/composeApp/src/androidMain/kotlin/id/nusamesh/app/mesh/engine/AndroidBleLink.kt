package id.nusamesh.app.mesh.engine

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Radio BLE Android untuk mesh NusaMesh: sekaligus GATT client (menyambung ke HP lain / Nusa Node) dan
 * GATT server (HP lain menyambung ke kita), dengan iklan service UUID + service data peerID — sama dengan
 * Nusa Mesh Android sehingga kedua aplikasi saling menemukan.
 *
 * Android hanya mengizinkan satu operasi GATT berjalan per koneksi, jadi setiap write/notify ditunggu
 * callback-nya (dengan batas waktu) di bawah mutex per perangkat.
 */
@SuppressLint("MissingPermission")
class AndroidBleLink(private val context: Context) : BleLink {
    private companion object {
        const val TAG = "AndroidBleLink"
        const val WRITE_TIMEOUT_MS = 2_500L
        const val NOTIFY_TIMEOUT_MS = 1_500L
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val serviceUuid = UUID.fromString(MeshGatt.SERVICE_UUID)
    private val charUuid = UUID.fromString(MeshGatt.CHARACTERISTIC_UUID)
    private val cccdUuid = UUID.fromString(MeshGatt.CCCD_UUID)

    private val _state = MutableStateFlow(LinkState.Unsupported)
    override val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 512)
    override val events = _events.asSharedFlow()

    private class ClientLink(val gatt: BluetoothGatt) {
        @Volatile var characteristic: BluetoothGattCharacteristic? = null
        @Volatile var mtu = 23
        @Volatile var ready = false
        @Volatile var pendingWrite: CompletableDeferred<Boolean>? = null
    }

    private class ServerLink(val device: BluetoothDevice) {
        @Volatile var mtu = 23
        @Volatile var subscribed = false
        @Volatile var pendingNotify: CompletableDeferred<Boolean>? = null
    }

    private val clients = ConcurrentHashMap<String, ClientLink>()
    private val servers = ConcurrentHashMap<String, ServerLink>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private var localPeerId: ByteArray? = null
    private var started = false

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            refreshState()
            val id = localPeerId
            if (started && id != null && _state.value == LinkState.Ready) {
                stopRadio(); startRadio(id)
            }
        }
    }

    init {
        runCatching { context.registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) }
        refreshState()
    }

    fun refreshState() {
        _state.value = when {
            manager == null || adapter == null ||
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> LinkState.Unsupported
            !hasPermissions() -> LinkState.Unauthorized
            adapter?.isEnabled != true -> LinkState.PoweredOff
            else -> LinkState.Ready
        }
    }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
        ) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    // ------------------------------------------------------------------------------------------
    //  Siklus hidup
    // ------------------------------------------------------------------------------------------
    override fun start(localPeerId: ByteArray) {
        this.localPeerId = localPeerId
        started = true
        refreshState()
        if (_state.value == LinkState.Ready) startRadio(localPeerId)
    }

    override fun stop() {
        started = false
        stopRadio()
    }

    private fun startRadio(peerId: ByteArray) {
        runCatching { openServer() }.onFailure { Log.w(TAG, "GATT server gagal: ${it.message}") }
        runCatching { startAdvertising(peerId) }.onFailure { Log.w(TAG, "Iklan gagal: ${it.message}") }
        runCatching { startScan() }.onFailure { Log.w(TAG, "Scan gagal: ${it.message}") }
    }

    private fun stopRadio() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        clients.values.forEach { runCatching { it.gatt.disconnect(); it.gatt.close() } }
        clients.keys.forEach { _events.tryEmit(LinkEvent.Disconnected(it)) }
        clients.clear()
        servers.keys.filter { servers[it]?.subscribed == true }.forEach { _events.tryEmit(LinkEvent.Disconnected(it)) }
        servers.clear()
        runCatching { gattServer?.close() }
        gattServer = null
        serverCharacteristic = null
    }

    // ------------------------------------------------------------------------------------------
    //  Scan & iklan
    // ------------------------------------------------------------------------------------------
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(ParcelUuid(serviceUuid))
            val peer = data?.takeIf { it.size >= 8 }?.copyOfRange(0, 8)?.joinToString("") { "%02x".format(it) }
            _events.tryEmit(LinkEvent.Discovered(result.device.address, result.scanRecord?.deviceName, result.rssi, peer))
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onScanResult(0, it) }

        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "Scan gagal ($errorCode)") }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.w(TAG, "Iklan gagal ($errorCode)") }
    }

    private fun startAdvertising(peerId: ByteArray) {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(serviceUuid))
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()
        // peerID di scan response (batas 31 byte terpisah) — sama seperti Nusa Mesh.
        val scanResponse = AdvertiseData.Builder().addServiceData(ParcelUuid(serviceUuid), peerId)
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    // ------------------------------------------------------------------------------------------
    //  GATT server (HP lain → kita)
    // ------------------------------------------------------------------------------------------
    private fun openServer() {
        if (gattServer != null) return
        val server = manager?.openGattServer(context, serverCallback) ?: return
        val characteristic = BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        characteristic.addDescriptor(
            BluetoothGattDescriptor(cccdUuid, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        server.addService(service)
        gattServer = server
        serverCharacteristic = characteristic
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                servers[addr] = ServerLink(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val link = servers.remove(addr)
                link?.pendingNotify?.complete(false)
                if (link?.subscribed == true && !clients.containsKey(addr)) _events.tryEmit(LinkEvent.Disconnected(addr))
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            servers[device.address]?.let { it.mtu = mtu }
            if (servers[device.address]?.subscribed == true) _events.tryEmit(LinkEvent.MtuChanged(device.address, mtu))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (characteristic.uuid == charUuid && value != null && value.isNotEmpty()) {
                _events.tryEmit(LinkEvent.Received(device.address, value.copyOf()))
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (descriptor.uuid != cccdUuid) return
            val link = servers.getOrPut(device.address) { ServerLink(device) }
            val enable = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true ||
                value?.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == true
            val wasSubscribed = link.subscribed
            link.subscribed = enable
            // Bila kita sudah tersambung sebagai client ke perangkat yang sama, cukup satu koneksi yang dilaporkan.
            if (enable && !wasSubscribed && !clients.containsKey(device.address)) {
                _events.tryEmit(LinkEvent.Connected(device.address, asCentral = false, mtu = link.mtu))
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            servers[device.address]?.pendingNotify?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    // ------------------------------------------------------------------------------------------
    //  GATT client (kita → HP lain / Nusa Node)
    // ------------------------------------------------------------------------------------------
    override fun connect(deviceId: String) {
        if (clients.containsKey(deviceId)) return
        val device = runCatching { adapter?.getRemoteDevice(deviceId) }.getOrNull()
        if (device == null) {
            _events.tryEmit(LinkEvent.ConnectFailed(deviceId, "alamat tidak valid"))
            return
        }
        val gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            _events.tryEmit(LinkEvent.ConnectFailed(deviceId, "connectGatt null"))
            return
        }
        clients[deviceId] = ClientLink(gatt)
    }

    override fun disconnect(deviceId: String) {
        clients[deviceId]?.gatt?.disconnect()
        servers[deviceId]?.device?.let { runCatching { gattServer?.cancelConnection(it) } }
    }

    private fun closeClient(gatt: BluetoothGatt, reason: String?) {
        val addr = gatt.device.address
        val link = clients.remove(addr)
        runCatching { gatt.close() }
        link?.pendingWrite?.complete(false)
        if (link?.ready == true) {
            // Masih tersambung sebagai server ke perangkat yang sama → koneksi logisnya belum putus.
            if (servers[addr]?.subscribed != true) _events.tryEmit(LinkEvent.Disconnected(addr))
        } else if (link != null && reason != null) {
            _events.tryEmit(LinkEvent.ConnectFailed(addr, reason))
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.requestMtu(MeshGatt.REQUESTED_MTU)) gatt.discoverServices()
            } else {
                closeClient(gatt, "GATT status $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            clients[gatt.device.address]?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            if (clients[gatt.device.address]?.ready == true) _events.tryEmit(LinkEvent.MtuChanged(gatt.device.address, mtu))
            else gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                gatt.disconnect(); return
            }
            clients[gatt.device.address]?.characteristic = characteristic
            gatt.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(cccdUuid) ?: run { gatt.disconnect(); return }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val link = clients[gatt.device.address] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) { gatt.disconnect(); return }
            if (!link.ready) {
                link.ready = true
                // Sudah terhubung sebagai server dengan perangkat yang sama → jangan laporkan ganda.
                if (servers[gatt.device.address]?.subscribed != true) {
                    _events.tryEmit(LinkEvent.Connected(gatt.device.address, asCentral = true, mtu = link.mtu))
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            clients[gatt.device.address]?.pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == charUuid) _events.tryEmit(LinkEvent.Received(gatt.device.address, value.copyOf()))
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == charUuid) {
                @Suppress("DEPRECATION")
                characteristic.value?.copyOf()?.let { _events.tryEmit(LinkEvent.Received(gatt.device.address, it)) }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _events.tryEmit(LinkEvent.RssiRead(gatt.device.address, rssi))
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Kirim
    // ------------------------------------------------------------------------------------------
    override suspend fun send(deviceId: String, data: ByteArray): Boolean {
        val lock = locks.getOrPut(deviceId) { Mutex() }
        return lock.withLock {
            val client = clients[deviceId]
            if (client?.ready == true) writeAsClient(client, data) else notifyAsServer(deviceId, data)
        }
    }

    private suspend fun writeAsClient(link: ClientLink, data: ByteArray): Boolean {
        val characteristic = link.characteristic ?: return false
        if (data.size > link.mtu - 3) return false
        val done = CompletableDeferred<Boolean>()
        link.pendingWrite = done
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            link.gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            link.gatt.writeCharacteristic(characteristic)
        }
        if (!accepted) return false
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } ?: false
    }

    private suspend fun notifyAsServer(deviceId: String, data: ByteArray): Boolean {
        val link = servers[deviceId]?.takeIf { it.subscribed } ?: return false
        val server = gattServer ?: return false
        val characteristic = serverCharacteristic ?: return false
        if (data.size > link.mtu - 3) return false
        val done = CompletableDeferred<Boolean>()
        link.pendingNotify = done
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(link.device, characteristic, false, data) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(link.device, characteristic, false)
        }
        if (!accepted) return false
        return withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { done.await() } ?: false
    }
}
