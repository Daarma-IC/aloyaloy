@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package id.nusamesh.app.ble

import id.nusamesh.app.domain.ConnectionPhase
import id.nusamesh.app.domain.MeshConnection
import id.nusamesh.app.domain.MeshPeripheral
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSObject
import platform.darwin.NSObjectProtocol
import platform.posix.memcpy

class IosMeshTransport : NSObject(), MeshTransport, CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
    private val serviceUuid = CBUUID.UUIDWithString(NusaGatt.SERVICE_UUID)
    private val characteristicUuid = CBUUID.UUIDWithString(NusaGatt.CHARACTERISTIC_UUID)
    private val central = CBCentralManager(delegate = this, queue = null)

    private val _connection = MutableStateFlow(MeshConnection())
    override val connection = _connection.asStateFlow()
    private val _nearby = MutableStateFlow<List<MeshPeripheral>>(emptyList())
    override val nearby = _nearby.asStateFlow()
    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    override val incomingPackets = _incomingPackets.asSharedFlow()

    private var activePeripheral: CBPeripheral? = null
    private var packetCharacteristic: CBCharacteristic? = null

    override fun startScanAndConnect() {
        if (central.state != CBManagerStatePoweredOn) {
            _connection.value = MeshConnection(ConnectionPhase.Failed, detail = "Aktifkan Bluetooth di iPhone")
            return
        }
        _nearby.value = emptyList()
        _connection.value = MeshConnection(ConnectionPhase.Scanning, detail = "Mencari NusaNode…")
        central.scanForPeripheralsWithServices(listOf(serviceUuid), options = null)
    }

    override fun disconnect() {
        activePeripheral?.let { central.cancelPeripheralConnection(it) }
        activePeripheral = null
        packetCharacteristic = null
        _connection.value = MeshConnection(ConnectionPhase.Idle, detail = "Bluetooth belum terhubung")
    }

    override suspend fun send(packet: ByteArray): Result<Unit> = runCatching {
        val peripheral = checkNotNull(activePeripheral) { "NusaNode belum terhubung" }
        val characteristic = checkNotNull(packetCharacteristic) { "Characteristic belum siap" }
        val maxBytes = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse).toInt()
        require(packet.size <= maxBytes) { "Paket ${packet.size} byte melebihi MTU iOS ($maxBytes byte)" }
        peripheral.writeValue(packet.toNSData(), characteristic, CBCharacteristicWriteWithResponse)
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        if (central.state != CBManagerStatePoweredOn) {
            _connection.value = MeshConnection(ConnectionPhase.Idle, detail = "Bluetooth iPhone belum siap")
        }
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val item = MeshPeripheral(
            id = didDiscoverPeripheral.identifier.UUIDString,
            name = didDiscoverPeripheral.name ?: "NusaNode",
            rssi = RSSI.intValue,
        )
        _nearby.value = (_nearby.value.filterNot { it.id == item.id } + item).sortedByDescending { it.rssi }
        if (activePeripheral == null) {
            central.stopScan()
            activePeripheral = didDiscoverPeripheral
            didDiscoverPeripheral.delegate = this
            _connection.value = MeshConnection(ConnectionPhase.Connecting, item, "Menghubungkan ${item.name}…")
            central.connectPeripheral(didDiscoverPeripheral, options = null)
        }
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        didConnectPeripheral.discoverServices(listOf(serviceUuid))
    }

    override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
        fail(error?.localizedDescription ?: "Koneksi CoreBluetooth gagal")
    }

    override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
        fail(error?.localizedDescription ?: "NusaNode terputus")
    }

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        if (didDiscoverServices != null) return fail(didDiscoverServices.localizedDescription)
        val service = peripheral.services?.filterIsInstance<CBService>()?.firstOrNull { it.UUID == serviceUuid }
            ?: return fail("Service NusaMesh tidak ditemukan")
        peripheral.discoverCharacteristics(listOf(characteristicUuid), forService = service)
    }

    override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
        if (error != null) return fail(error.localizedDescription)
        val characteristic = didDiscoverCharacteristicsForService.characteristics
            ?.filterIsInstance<CBCharacteristic>()?.firstOrNull { it.UUID == characteristicUuid }
            ?: return fail("Characteristic NusaMesh tidak ditemukan")
        packetCharacteristic = characteristic
        peripheral.setNotifyValue(true, forCharacteristic = characteristic)
    }

    override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
        if (error != null) return fail(error.localizedDescription)
        if (didUpdateNotificationStateForCharacteristic.isNotifying) {
            _connection.value = _connection.value.copy(phase = ConnectionPhase.Connected, detail = "CoreBluetooth siap: WRITE + NOTIFY")
        }
    }

    override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
        if (error != null) return
        didUpdateValueForCharacteristic.value?.let { _incomingPackets.tryEmit(it.toByteArray()) }
    }

    private fun fail(message: String) {
        packetCharacteristic = null
        activePeripheral = null
        _connection.value = MeshConnection(ConnectionPhase.Failed, detail = message)
    }
}

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { target ->
    target.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
}

