package hr.adip.senzori.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import hr.adip.senzori.AdipApp
import hr.adip.senzori.MainActivity
import hr.adip.senzori.R

/**
 * Foreground servis koji drži mjerenje živim dok se snima (i kad je ekran ugašen).
 * Drži djelomični wake lock da CPU ne zaspe i da ticker uzorkuje fiksnom frekvencijom.
 */
class MeasurementService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      AdipApp.get(this).engine.stopRecording(this)
      return START_NOT_STICKY
    }
    val nm = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Snimanje mjerenja", NotificationManager.IMPORTANCE_LOW))
    }
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val stop = PendingIntent.getService(this, 1, Intent(this, MeasurementService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
    val n: Notification = NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_stat_rec)
      .setContentTitle("ADIP Senzori snima")
      .setContentText("Akceleracija, svjetlo i infrazvuk se zapisuju u CSV")
      .setUsesChronometer(true)
      .setOngoing(true)
      .setContentIntent(open)
      .addAction(0, "Zaustavi", stop)
      .build()
    val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val type = if (Build.VERSION.SDK_INT >= 30 && mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
    ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    if (wakeLock == null) {
      wakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adip:snimanje").apply { acquire(24 * 3600_000L) }
    }
    AdipApp.get(this).engine.beginRecording()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    AdipApp.get(this).engine.endRecording()
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "snimanje"
    private const val NOTIF_ID = 42
    private const val ACTION_STOP = "hr.adip.senzori.STOP"
  }
}
