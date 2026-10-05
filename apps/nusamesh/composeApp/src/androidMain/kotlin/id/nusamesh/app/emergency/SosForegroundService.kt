package id.nusamesh.app.emergency

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import id.nusamesh.app.MainActivity

/**
 * Foreground service bertipe lokasi yang menjaga GPS dan mesh tetap jalan saat layar mati, untuk SOS
 * dan/atau perekaman jejak. Mode dikirim lewat extra; notifikasi mengikuti mode yang aktif.
 */
class SosForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        EmergencyNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sos = intent?.getBooleanExtra(EXTRA_SOS, false) ?: false
        val track = intent?.getBooleanExtra(EXTRA_TRACK, false) ?: false
        // Restart sistem (START_STICKY, intent null) tanpa aplikasi yang memberi lokasi tidak berguna.
        if (!sos && !track) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = EmergencyNotifications.foreground(this, sos, track)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(EmergencyNotifications.OWN_SOS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(EmergencyNotifications.OWN_SOS_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_SOS = "sos"
        const val EXTRA_TRACK = "track"
    }
}

object EmergencyNotifications {
    const val OWN_SOS_ID = 7100
    private const val SERVICE_CHANNEL = "meshta_sos_active"
    private const val TRACK_CHANNEL = "meshta_track_recording"
    private const val ALERT_CHANNEL = "meshta_sos_incoming"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "SOS aktif", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Menjaga pelacakan dan broadcast SOS tetap aktif saat layar mati"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(TRACK_CHANNEL, "Rekam jejak", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Menjaga perekaman jejak tetap berjalan saat layar mati"
                setShowBadge(false)
            },
        )
        val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "SOS masuk", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Peringatan darurat dari pengguna NusaMesh"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 900)
                enableLights(true)
                lightColor = Color.RED
                setSound(alarm, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            },
        )
    }

    fun foreground(context: Context, sos: Boolean, track: Boolean): Notification {
        val (title, text) = when {
            sos && track -> "SOS aktif · merekam jejak" to "Lokasi darurat dan jejak tetap disiarkan melalui mesh"
            sos -> "SOS NusaMesh aktif" to "Lokasi darurat tetap disiarkan melalui mesh"
            else -> "Merekam jejak" to "Jalur Anda direkam dan dibagikan lewat mesh, juga saat layar mati"
        }
        return Notification.Builder(context, if (sos) SERVICE_CHANNEL else TRACK_CHANNEL)
            .setSmallIcon(if (sos) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun showIncoming(context: Context, peerId: String, name: String) {
        ensureChannels(context)
        val notification = Notification.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS diterima dari $name")
            .setContentText("Buka Peta untuk melihat posisi dan arah menuju korban")
            .setContentIntent(openApp(context))
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(incomingId(peerId), notification)
    }

    fun clearIncoming(context: Context, peerId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(incomingId(peerId))
    }

    private fun incomingId(peerId: String) = 7200 + (peerId.hashCode() and 0x0FFF)

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
