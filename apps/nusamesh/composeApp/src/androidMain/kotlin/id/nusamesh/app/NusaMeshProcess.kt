package id.nusamesh.app

import android.content.Context
import android.os.BatteryManager
import id.nusamesh.app.data.AndroidKeyValueStore
import id.nusamesh.app.emergency.AndroidEmergencyActions
import id.nusamesh.app.location.AndroidLocationSource
import id.nusamesh.app.mesh.engine.AndroidBleLink

/**
 * Pemegang objek tingkat proses. Activity bisa mati dan dibuat ulang (rotasi, di-swipe dari Recent),
 * tapi BLE link, GPS, dan [AppRuntime] tetap satu — jadi SOS/jejak tidak putus dan membuka aplikasi lagi
 * tidak membuat mesh kedua.
 */
object NusaMeshProcess {
    private var instance: Holder? = null

    class Holder(context: Context) {
        val link = AndroidBleLink(context.applicationContext)
        val location = AndroidLocationSource(context)
        val emergency = AndroidEmergencyActions(context, location)
        private val battery = context.applicationContext.getSystemService(BatteryManager::class.java)
        val runtime = AppRuntime(
            link, AndroidKeyValueStore(context.applicationContext), location.location, emergency,
            batteryLevel = { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } },
            // Graf jalan offline (tools/offline-map/build_graph.py), ikut APK bersama peta bawaan.
            roadGraph = { runCatching { context.applicationContext.assets.open("routing/bandung.nmrg").use { it.readBytes() } }.getOrNull() },
        )
    }

    fun get(context: Context): Holder = instance ?: Holder(context.applicationContext).also { instance = it }
}
