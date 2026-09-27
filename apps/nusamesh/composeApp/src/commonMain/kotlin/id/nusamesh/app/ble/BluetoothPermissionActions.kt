package id.nusamesh.app.ble

/** Memisahkan dialog izin Android dari alur UI bersama KMP. */
interface BluetoothPermissionActions {
    fun runWithPermission(onGranted: () -> Unit, onDenied: (String) -> Unit)
}

object ImmediateBluetoothPermission : BluetoothPermissionActions {
    override fun runWithPermission(onGranted: () -> Unit, onDenied: (String) -> Unit) = onGranted()
}
