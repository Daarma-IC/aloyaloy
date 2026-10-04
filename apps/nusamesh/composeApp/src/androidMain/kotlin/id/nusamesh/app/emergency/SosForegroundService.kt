package id.nusamesh.app.emergency

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.IBinder
import id.nusamesh.app.MainActivity

class SosForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        EmergencyNotifications.ensureChannels(this)
        startForeground(EmergencyNotifications.OWN_SOS_ID, EmergencyNotifications.ownSos(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}

object EmergencyNotifications {
    const val OWN_SOS_ID = 7100
    private const val SERVICE_CHANNEL = "meshta_sos_active"
    private const val ALERT_CHANNEL = "meshta_sos_incoming"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "SOS aktif", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Menjaga pelacakan dan broadcast SOS tetap aktif saat layar mati"
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

    fun ownSos(context: Context): Notification = Notification.Builder(context, SERVICE_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("SOS NusaMesh aktif")
        .setContentText("Lokasi darurat tetap disiarkan melalui mesh")
        .setContentIntent(openApp(context))
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .build()

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
