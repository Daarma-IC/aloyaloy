@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package id.nusamesh.app.mesh.engine

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

/**
 * Radio BLE iOS untuk mesh NusaMesh: CBCentralManager (menyambung ke HP lain / Nusa Node) dan
 * CBPeripheralManager (HP lain menyambung ke iPhone ini).
 *
 * iOS tidak mengizinkan service data dalam iklan, jadi peerID iPhone diketahui peer lain lewat ANNOUNCE
 * setelah tersambung. Semua panggilan CoreBluetooth dijalankan di main queue (tempat delegate dipanggil).
 */
class IosBleLink : BleLink {
    private companion object {
        const val WRITE_TIMEOUT_MS = 2_500L
        const val NOTIFY_TIMEOUT_MS = 1_500L
    }

    private val serviceUuid = CBUUID.UUIDWithString(MeshGatt.SERVICE_UUID)
    private val charUuid = CBUUID.UUIDWithString(MeshGatt.CHARACTERISTIC_UUID)

    private val _state = MutableStateFlow(LinkState.Unsupported)
    override val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 512)
    override val events = _events.asSharedFlow()

    private val centralDelegate = CentralDelegate(this)
    private val peripheralDelegate = PeripheralDelegate(this)
    private val central = CBCentralManager(delegate = centralDelegate, queue = null)
    private val peripheralManager = CBPeripheralManager(delegate = peripheralDelegate, queue = null)

    private class Remote(val peripheral: CBPeripheral) {
        var characteristic: CBCharacteristic? = null
        var ready = false
        var pendingWrite: CompletableDeferred<Boolean>? = null
    }

    private val discovered = HashMap<String, CBPeripheral>()      // wajib dipegang agar tidak dilepas ARC
    private val remotes = HashMap<String, Remote>()
    private val centrals = HashMap<String, CBCentral>()             // perangkat yang subscribe ke kita
    private var serverCharacteristic: CBMutableCharacteristic? = null
    private var readyToUpdate: CompletableDeferred<Unit>? = null
    private val locks = HashMap<String, Mutex>()
    private var started = false
    private var serviceAdded = false

    private fun main(block: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { block() }

    // ------------------------------------------------------------------------------------------
    //  Siklus hidup
    // ------------------------------------------------------------------------------------------
    override fun start(localPeerId: ByteArray) = main {
        started = true
        startIfReady()
    }

    override fun stop() = main {
        started = false
        if (central.state == CBManagerStatePoweredOn) central.stopScan()
        peripheralManager.stopAdvertising()
        remotes.values.forEach { central.cancelPeripheralConnection(it.peripheral) }
    }

    private fun startIfReady() {
        if (!started) return
        if (central.state == CBManagerStatePoweredOn) {
            central.scanForPeripheralsWithServices(
                listOf(serviceUuid), options = mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true),
            )
        }
        if (peripheralManager.state == CBManagerStatePoweredOn) {
            if (!serviceAdded) {
                val characteristic = CBMutableCharacteristic(
                    type = charUuid,
                    properties = CBCharacteristicPropertyRead or CBCharacteristicPropertyWrite or
                        CBCharacteristicPropertyWriteWithoutResponse or CBCharacteristicPropertyNotify,
                    value = null,
                    permissions = CBAttributePermissionsReadable or CBAttributePermissionsWriteable,
                )
                val service = CBMutableService(type = serviceUuid, primary = true)
                service.setCharacteristics(listOf(characteristic))
                peripheralManager.addService(service)
                serverCharacteristic = characteristic
                serviceAdded = true
            }
            if (!peripheralManager.isAdvertising) {
                peripheralManager.startAdvertising(mapOf(CBAdvertisementDataServiceUUIDsKey to listOf(serviceUuid)))
            }
        }
    }

    internal fun onStateChanged() {
        _state.value = when (central.state) {
            CBManagerStatePoweredOn -> LinkState.Ready
            CBManagerStateUnauthorized -> LinkState.Unauthorized
            CBManagerStatePoweredOff -> LinkState.PoweredOff
            CBManagerStateUnsupported -> LinkState.Unsupported
            else -> LinkState.PoweredOff
        }
        startIfReady()
    }

    // ------------------------------------------------------------------------------------------
    //  Central: scan dan koneksi keluar
    // ------------------------------------------------------------------------------------------
    internal fun onDiscovered(peripheral: CBPeripheral, advertisement: Map<Any?, *>, rssi: NSNumber) {
        val id = peripheral.identifier.UUIDString
        discovered[id] = peripheral
        @Suppress("UNCHECKED_CAST")
        val serviceData = advertisement[CBAdvertisementDataServiceDataKey] as? Map<Any?, *>
        val peerBytes = (serviceData?.get(serviceUuid) as? NSData)?.toByteArray()
        val peerId = peerBytes?.takeIf { it.size >= 8 }?.copyOfRange(0, 8)?.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        val name = advertisement[CBAdvertisementDataLocalNameKey] as? String ?: peripheral.name
        _events.tryEmit(LinkEvent.Discovered(id, name, rssi.intValue, peerId))
    }

    override fun connect(deviceId: String) = main {
        val peripheral = discovered[deviceId]
        if (peripheral == null || remotes.containsKey(deviceId)) {
            if (peripheral == null) _events.tryEmit(LinkEvent.ConnectFailed(deviceId, "belum terdeteksi"))
            return@main
        }
        remotes[deviceId] = Remote(peripheral)
        peripheral.delegate = centralDelegate
        central.connectPeripheral(peripheral, options = null)
    }

    override fun disconnect(deviceId: String) = main {
        remotes[deviceId]?.let { central.cancelPeripheralConnection(it.peripheral) }
    }

    internal fun onConnected(peripheral: CBPeripheral) = peripheral.discoverServices(listOf(serviceUuid))

    internal fun onServices(peripheral: CBPeripheral, error: NSError?) {
        val service = peripheral.services?.filterIsInstance<CBService>()?.firstOrNull { it.UUID == serviceUuid }
        if (error != null || service == null) return central.cancelPeripheralConnection(peripheral)
        peripheral.discoverCharacteristics(listOf(charUuid), forService = service)
    }

    internal fun onCharacteristics(peripheral: CBPeripheral, service: CBService, error: NSError?) {
        val c = service.characteristics?.filterIsInstance<CBCharacteristic>()?.firstOrNull { it.UUID == charUuid }
        if (error != null || c == null) return central.cancelPeripheralConnection(peripheral)
        remotes[peripheral.identifier.UUIDString]?.characteristic = c
        peripheral.setNotifyValue(true, forCharacteristic = c)
    }

    internal fun onNotifyState(peripheral: CBPeripheral, characteristic: CBCharacteristic, error: NSError?) {
        val id = peripheral.identifier.UUIDString
        val remote = remotes[id] ?: return
        if (error != null) return central.cancelPeripheralConnection(peripheral)
        if (characteristic.isNotifying && !remote.ready) {
            remote.ready = true
            val mtu = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse).toInt() + 3
            if (!centrals.containsKey(id)) _events.tryEmit(LinkEvent.Connected(id, asCentral = true, mtu = mtu))
        }
    }

    internal fun onValue(peripheral: CBPeripheral, characteristic: CBCharacteristic, error: NSError?) {
        if (error != null || characteristic.UUID != charUuid) return
        characteristic.value?.toByteArray()?.let { _events.tryEmit(LinkEvent.Received(peripheral.identifier.UUIDString, it)) }
    }

    internal fun onWritten(peripheral: CBPeripheral, error: NSError?) {
        remotes[peripheral.identifier.UUIDString]?.pendingWrite?.complete(error == null)
    }

    internal fun onDisconnected(peripheral: CBPeripheral, failed: Boolean, reason: String?) {
        val id = peripheral.identifier.UUIDString
        val remote = remotes.remove(id)
        remote?.pendingWrite?.complete(false)
        when {
            remote?.ready == true -> if (!centrals.containsKey(id)) _events.tryEmit(LinkEvent.Disconnected(id))
            failed || remote != null -> _events.tryEmit(LinkEvent.ConnectFailed(id, reason ?: "koneksi gagal"))
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Peripheral: HP lain → iPhone ini
    // ------------------------------------------------------------------------------------------
    internal fun onSubscribed(central: CBCentral) {
        val id = central.identifier.UUIDString
        val first = !centrals.containsKey(id)
        centrals[id] = central
        if (first && remotes[id]?.ready != true) {
            _events.tryEmit(LinkEvent.Connected(id, asCentral = false, mtu = central.maximumUpdateValueLength.toInt() + 3))
        }
    }

    internal fun onUnsubscribed(central: CBCentral) {
        val id = central.identifier.UUIDString
        if (centrals.remove(id) != null && remotes[id]?.ready != true) _events.tryEmit(LinkEvent.Disconnected(id))
    }

    internal fun onWriteRequests(requests: List<*>) {
        val list = requests.filterIsInstance<CBATTRequest>()
        list.forEach { r ->
            val data = r.value?.toByteArray() ?: return@forEach
            if (data.isNotEmpty()) _events.tryEmit(LinkEvent.Received(r.central.identifier.UUIDString, data))
        }
        list.firstOrNull()?.let { peripheralManager.respondToRequest(it, withResult = CBATTErrorSuccess) }
    }

    internal fun onReadRequest(request: CBATTRequest) {
        peripheralManager.respondToRequest(request, withResult = CBATTErrorSuccess)
    }

    internal fun onReadyToUpdate() { readyToUpdate?.complete(Unit) }

    // ------------------------------------------------------------------------------------------
    //  Kirim
    // ------------------------------------------------------------------------------------------
    override suspend fun send(deviceId: String, data: ByteArray): Boolean = withContext(Dispatchers.Main) {
        val lock = locks.getOrPut(deviceId) { Mutex() }
        lock.withLock {
            val remote = remotes[deviceId]
            if (remote?.ready == true) writeAsCentral(remote, data) else notifyAsPeripheral(deviceId, data)
        }
    }

    private suspend fun writeAsCentral(remote: Remote, data: ByteArray): Boolean {
        val c = remote.characteristic ?: return false
        if (data.size > remote.peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse).toInt()) return false
        val done = CompletableDeferred<Boolean>()
        remote.pendingWrite = done
        remote.peripheral.writeValue(data.toNSData(), c, CBCharacteristicWriteWithResponse)
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } ?: false
    }

    private suspend fun notifyAsPeripheral(deviceId: String, data: ByteArray): Boolean {
        val target = centrals[deviceId] ?: return false
        val c = serverCharacteristic ?: return false
        if (data.size > target.maximumUpdateValueLength.toInt()) return false
        repeat(3) {
            if (peripheralManager.updateValue(data.toNSData(), forCharacteristic = c, onSubscribedCentrals = listOf(target))) return true
            // Antrean kirim penuh: tunggu peripheralManagerIsReadyToUpdateSubscribers.
            val ready = CompletableDeferred<Unit>()
            readyToUpdate = ready
            withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { ready.await() } ?: return false
        }
        return false
    }
}

private class CentralDelegate(private val link: IosBleLink) :
    NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) = link.onStateChanged()

    override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) =
        link.onDiscovered(didDiscoverPeripheral, advertisementData, RSSI)

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) = link.onConnected(didConnectPeripheral)

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) =
        link.onDisconnected(didFailToConnectPeripheral, failed = true, reason = error?.localizedDescription)

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) =
        link.onDisconnected(didDisconnectPeripheral, failed = false, reason = error?.localizedDescription)

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) = link.onServices(peripheral, didDiscoverServices)

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) =
        link.onCharacteristics(peripheral, didDiscoverCharacteristicsForService, error)

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) =
        link.onNotifyState(peripheral, didUpdateNotificationStateForCharacteristic, error)

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) =
        link.onValue(peripheral, didUpdateValueForCharacteristic, error)

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) =
        link.onWritten(peripheral, error)
}

private class PeripheralDelegate(private val link: IosBleLink) : NSObject(), CBPeripheralManagerDelegateProtocol {
    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) = link.onStateChanged()

    @ObjCSignatureOverride
    override fun peripheralManager(peripheral: CBPeripheralManager, central: CBCentral, didSubscribeToCharacteristic: CBCharacteristic) =
        link.onSubscribed(central)

    @ObjCSignatureOverride
    override fun peripheralManager(peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFromCharacteristic: CBCharacteristic) =
        link.onUnsubscribed(central)

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) =
        link.onWriteRequests(didReceiveWriteRequests)

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveReadRequest: CBATTRequest) =
        link.onReadRequest(didReceiveReadRequest)

    override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) = link.onReadyToUpdate()
}

private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { target ->
    if (target.isNotEmpty()) target.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
}
