package id.nusamesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import id.nusamesh.app.domain.ConnectionPhase
import id.nusamesh.app.domain.MeshConnection
import id.nusamesh.app.domain.MeshPeripheral
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidMeshTransport(private val context: Context) : MeshTransport {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val serviceUuid = UUID.fromString(NusaGatt.SERVICE_UUID)
    private val characteristicUuid = UUID.fromString(NusaGatt.CHARACTERISTIC_UUID)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connection = MutableStateFlow(MeshConnection())
    override val connection = _connection.asStateFlow()
    private val _nearby = MutableStateFlow<List<MeshPeripheral>>(emptyList())
    override val nearby = _nearby.asStateFlow()
    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    override val incomingPackets = _incomingPackets.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var packetCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var maxWriteBytes = 20
    private var manualDisconnect = false
    private var reconnectAttempts = 0
    private var lastDevice: BluetoothDevice? = null
    private var lastPeripheral: MeshPeripheral? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val peer = result.scanRecord?.getServiceData(ParcelUuid(serviceUuid))
            val peripheral = MeshPeripheral(
                id = result.device.address,
                name = result.scanRecord?.deviceName ?: "NusaNode",
                rssi = result.rssi,
                peerId = peer?.takeIf { it.size >= 8 }?.copyOfRange(0, 8),
            )
            _nearby.value = (_nearby.value.filterNot { it.id == peripheral.id } + peripheral)
                .sortedByDescending { it.rssi }
            if (gatt == null) connect(result.device, peripheral)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _connection.value = MeshConnection(ConnectionPhase.Failed, detail = "Scan BLE gagal ($errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                _connection.value = _connection.value.copy(
                    phase = ConnectionPhase.Connecting,
                    detail = "Membaca service NusaMesh…",
                )
                if (!callbackGatt.requestMtu(NusaGatt.REQUESTED_MTU)) callbackGatt.discoverServices()
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val detail = if (status == BluetoothGatt.GATT_SUCCESS) {
                    "Koneksi BLE terputus"
                } else {
                    "GATT terputus (status $status)"
                }
                handleUnexpectedDisconnect(callbackGatt, detail)
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, mtu: Int, status: Int) {
            maxWriteBytes = if (status == BluetoothGatt.GATT_SUCCESS) (mtu - 3).coerceAtLeast(20) else 20
            callbackGatt.discoverServices()
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            val service: BluetoothGattService? = callbackGatt.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(characteristicUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                failAndClose("Characteristic NusaMesh tidak ditemukan (status $status)")
                return
            }
            packetCharacteristic = characteristic
            if (!callbackGatt.setCharacteristicNotification(characteristic, true)) {
                failAndClose("Notification characteristic ditolak Android")
                return
            }
            val descriptor = characteristic.getDescriptor(UUID.fromString(CCCD_UUID))
            if (descriptor == null) {
                failAndClose("CCCD notification tidak tersedia")
                return
            }
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                callbackGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                callbackGatt.writeDescriptor(descriptor)
            }
            if (!accepted) failAndClose("Android menolak aktivasi notification")
        }

        override fun onDescriptorWrite(callbackGatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                reconnectAttempts = 0
                _connection.value = _connection.value.copy(
                    phase = ConnectionPhase.Connected,
                    detail = "GATT siap: WRITE + NOTIFY",
                )
            } else {
                failAndClose("Gagal mengaktifkan notification ($status)")
            }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == characteristicUuid) _incomingPackets.tryEmit(value.copyOf())
        }

        @Deprecated("Dipakai Android sebelum API 33")
        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == characteristicUuid) {
                @Suppress("DEPRECATION")
                characteristic.value?.copyOf()?.let(_incomingPackets::tryEmit)
            }
        }
    }

    override fun startScanAndConnect() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            _connection.value = MeshConnection(ConnectionPhase.Failed, detail = "Aktifkan Bluetooth dan izin perangkat sekitar")
            return
        }
        if (scanning || gatt != null) return
        manualDisconnect = false
        reconnectAttempts = 0
        mainHandler.removeCallbacksAndMessages(null)
        _nearby.value = emptyList()
        _connection.value = MeshConnection(ConnectionPhase.Scanning, detail = "Mencari service ${NusaGatt.SERVICE_UUID.take(8)}…")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching {
            scanning = true
            scanner.startScan(listOf(filter), settings, scanCallback)
        }.onFailure { error ->
            scanning = false
            _connection.value = MeshConnection(ConnectionPhase.Failed, detail = error.message ?: "Izin Bluetooth ditolak")
        }
    }

    private fun connect(device: BluetoothDevice, peripheral: MeshPeripheral) {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        lastDevice = device
        lastPeripheral = peripheral
        manualDisconnect = false
        _connection.value = MeshConnection(ConnectionPhase.Connecting, peripheral, "Menghubungkan ${peripheral.name}…")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        manualDisconnect = true
        mainHandler.removeCallbacksAndMessages(null)
        closeGatt("Bluetooth belum terhubung", ConnectionPhase.Idle)
    }

    private fun handleUnexpectedDisconnect(callbackGatt: BluetoothGatt, detail: String) {
        if (gatt !== callbackGatt && gatt != null) {
            callbackGatt.close()
            return
        }
        callbackGatt.close()
        if (gatt === callbackGatt) gatt = null
        packetCharacteristic = null
        maxWriteBytes = 20
        if (manualDisconnect) return
        val device = lastDevice
        val peripheral = lastPeripheral
        if (device != null && peripheral != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts += 1
            val attempt = reconnectAttempts
            _connection.value = MeshConnection(
                ConnectionPhase.Connecting,
                peripheral,
                "$detail. Menyambungkan ulang ($attempt/$MAX_RECONNECT_ATTEMPTS)…",
            )
            mainHandler.postDelayed({
                if (!manualDisconnect && gatt == null) {
                    gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }
            }, RECONNECT_DELAY_MS * attempt)
        } else {
            _connection.value = MeshConnection(ConnectionPhase.Failed, peripheral, detail)
        }
    }

    private fun failAndClose(detail: String) {
        manualDisconnect = true
        closeGatt(detail, ConnectionPhase.Failed)
    }

    private fun closeGatt(detail: String, phase: ConnectionPhase) {
        if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        packetCharacteristic = null
        maxWriteBytes = 20
        val activeGatt = gatt
        gatt = null
        runCatching { activeGatt?.disconnect() }
        runCatching { activeGatt?.close() }
        _connection.value = MeshConnection(phase, lastPeripheral, detail)
    }

    override suspend fun send(packet: ByteArray): Result<Unit> = runCatching {
        require(packet.size <= minOf(NusaGatt.MAX_PACKET_BYTES, maxWriteBytes)) {
            "Paket ${packet.size} byte melebihi kapasitas GATT ($maxWriteBytes byte)"
        }
        val activeGatt = checkNotNull(gatt) { "NusaNode belum terhubung" }
        val characteristic = checkNotNull(packetCharacteristic) { "Characteristic belum siap" }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = packet
            @Suppress("DEPRECATION")
            activeGatt.writeCharacteristic(characteristic)
        }
        check(accepted) { "Antrean write GATT menolak paket" }
    }

    private companion object {
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        const val MAX_RECONNECT_ATTEMPTS = 2
        const val RECONNECT_DELAY_MS = 800L
    }
}


