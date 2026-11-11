package hr.adip.senzori

import android.app.Application
import android.content.Context
import hr.adip.senzori.data.SettingsRepository
import hr.adip.senzori.recording.RecordingRepository
import hr.adip.senzori.sensor.SensorEngine
import hr.adip.senzori.upload.AdipClient
import java.util.UUID

class AdipApp : Application() {
  lateinit var settings: SettingsRepository
  lateinit var engine: SensorEngine
  lateinit var recordings: RecordingRepository
  lateinit var client: AdipClient

  override fun onCreate() {
    super.onCreate()
    settings = SettingsRepository(this)
    recordings = RecordingRepository(this)
    client = AdipClient(deviceId())
    engine = SensorEngine(this, settings, recordings, client)
  }

  /** Nasumični ID instalacije (ne koristi hardverske identifikatore). */
  private fun deviceId(): String {
    val p = getSharedPreferences("uredaj", MODE_PRIVATE)
    return p.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").also { p.edit().putString("id", it).apply() }
  }

  companion object {
    fun get(context: Context): AdipApp = context.applicationContext as AdipApp
  }
}
