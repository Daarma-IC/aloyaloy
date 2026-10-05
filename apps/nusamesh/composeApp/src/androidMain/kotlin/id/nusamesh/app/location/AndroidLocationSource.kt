package id.nusamesh.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS tingkat proses (pakai application context): tetap mengalir setelah Activity ditutup, selama proses
 * dijaga foreground service. Activity hanya meminta izin lalu memanggil [start].
 */
class AndroidLocationSource(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    private val mutableLocation = MutableStateFlow<DeviceLocation?>(null)
    val location: StateFlow<DeviceLocation?> = mutableLocation
    private var started = false
    private var highRate = false
    /** Dipanggil saat provider dinyalakan/dimatikan pengguna (Activity memperbarui layar izin). */
    var onProviderChanged: (() -> Unit)? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(value: Location) {
            mutableLocation.value = DeviceLocation(
                value.latitude, value.longitude, value.accuracy, value.time.coerceAtLeast(System.currentTimeMillis() - 60_000L),
            )
        }
        override fun onProviderEnabled(provider: String) { onProviderChanged?.invoke() }
        override fun onProviderDisabled(provider: String) { onProviderChanged?.invoke() }
    }

    val permitted: Boolean
        get() = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val enabled: Boolean get() = manager?.isLocationEnabled == true

    @SuppressLint("MissingPermission")
    fun start() {
        if (started || manager == null || !permitted) return
        started = true
        // Jejak di hutan/lereng butuh titik lebih rapat dari pelacakan biasa (15 dtk / 10 m).
        val (intervalMs, distanceM) = if (highRate) 5_000L to 5f else 15_000L to 10f
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            if (manager.isProviderEnabled(provider)) {
                manager.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
                manager.requestLocationUpdates(provider, intervalMs, distanceM, listener, Looper.getMainLooper())
            }
        }
    }

    fun setHighRate(active: Boolean) {
        if (highRate == active) return
        highRate = active
        if (!started) return
        manager?.removeUpdates(listener)
        started = false
        start()
    }
}
