package id.nusamesh.app.emergency

import android.content.Context
import android.content.Intent
import android.util.Log
import id.nusamesh.app.location.AndroidLocationSource

/**
 * Foreground service & notifikasi SOS dari application context, jadi tetap bisa dipanggil saat Activity
 * sudah tidak ada. Satu service lokasi untuk SOS dan/atau rekam jejak; berhenti bila keduanya mati.
 */
class AndroidEmergencyActions(context: Context, private val location: AndroidLocationSource) : EmergencyActions {
    private val appContext = context.applicationContext
    private var sosActive = false
    private var trackActive = false

    override fun setBroadcastActive(active: Boolean) {
        sosActive = active
        updateService()
    }

    override fun setTrackRecordingActive(active: Boolean) {
        trackActive = active
        location.setHighRate(active)
        updateService()
    }

    private fun updateService() {
        val intent = Intent(appContext, SosForegroundService::class.java)
            .putExtra(SosForegroundService.EXTRA_SOS, sosActive)
            .putExtra(SosForegroundService.EXTRA_TRACK, trackActive)
        if (sosActive || trackActive) {
            // Android 12+ menolak start dari latar belakang; perubahan mode selalu dipicu dari layar, jadi
            // kegagalan di sini hanya terjadi bila proses dipulihkan tanpa UI — catat, jangan crash.
            runCatching { appContext.startForegroundService(intent) }
                .onFailure { Log.w("NusaMesh", "Foreground service gagal dimulai: ${it.message}") }
        } else {
            appContext.stopService(intent)
        }
    }

    override fun showIncoming(peerId: String, name: String) = EmergencyNotifications.showIncoming(appContext, peerId, name)
    override fun clearIncoming(peerId: String) = EmergencyNotifications.clearIncoming(appContext, peerId)
}
