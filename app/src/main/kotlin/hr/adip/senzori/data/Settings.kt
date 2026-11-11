package hr.adip.senzori.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Sve korisničke postavke aplikacije. */
data class AppSettings(
  /** Fiksna frekvencija uzorkovanja zapisa (Hz). */
  val sampleRateHz: Int = 50,
  /** Uključi mjerenje infrazvuka mikrofonom (traži dozvolu RECORD_AUDIO). */
  val infraEnabled: Boolean = true,
  /** Kalibracija: Pa po jedinici punog raspona (1.0 = nekalibrirano, relativne jedinice). */
  val infraCalibrationPaPerFs: Float = 0f,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val keepScreenOn: Boolean = true,
  /** Web servis (adip.hr) */
  val serverUrl: String = "https://adip.hr",
  val serverToken: String = "",
  /** Šalji podatke uživo na server tijekom snimanja. */
  val liveUploadEnabled: Boolean = false,
  val uploadBatchSeconds: Int = 5,
  /** Kalibracija libele (offset kutova u stupnjevima). */
  val levelOffsetPitch: Float = 0f,
  val levelOffsetRoll: Float = 0f,
) {
  val infraCalibrated: Boolean get() = infraCalibrationPaPerFs > 0f

  companion object {
    val SAMPLE_RATES = listOf(10, 25, 50, 100, 200)
  }
}

private val Context.dataStore by preferencesDataStore(name = "postavke")

class SettingsRepository(private val context: Context) {
  private object K {
    val rate = intPreferencesKey("sample_rate")
    val infra = booleanPreferencesKey("infra_enabled")
    val infraCal = floatPreferencesKey("infra_cal")
    val theme = stringPreferencesKey("theme")
    val keepOn = booleanPreferencesKey("keep_screen_on")
    val url = stringPreferencesKey("server_url")
    val token = stringPreferencesKey("server_token")
    val live = booleanPreferencesKey("live_upload")
    val batch = intPreferencesKey("upload_batch_s")
    val lvlP = floatPreferencesKey("level_off_pitch")
    val lvlR = floatPreferencesKey("level_off_roll")
  }

  val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

  suspend fun update(transform: (AppSettings) -> AppSettings) {
    context.dataStore.edit { p ->
      val s = transform(p.toSettings())
      p[K.rate] = s.sampleRateHz
      p[K.infra] = s.infraEnabled
      p[K.infraCal] = s.infraCalibrationPaPerFs
      p[K.theme] = s.themeMode.name
      p[K.keepOn] = s.keepScreenOn
      p[K.url] = s.serverUrl
      p[K.token] = s.serverToken
      p[K.live] = s.liveUploadEnabled
      p[K.batch] = s.uploadBatchSeconds
      p[K.lvlP] = s.levelOffsetPitch
      p[K.lvlR] = s.levelOffsetRoll
    }
  }

  private fun Preferences.toSettings(): AppSettings {
    val d = AppSettings()
    return AppSettings(
      sampleRateHz = this[K.rate] ?: d.sampleRateHz,
      infraEnabled = this[K.infra] ?: d.infraEnabled,
      infraCalibrationPaPerFs = this[K.infraCal] ?: d.infraCalibrationPaPerFs,
      themeMode = runCatching { ThemeMode.valueOf(this[K.theme] ?: "") }.getOrDefault(d.themeMode),
      keepScreenOn = this[K.keepOn] ?: d.keepScreenOn,
      serverUrl = this[K.url] ?: d.serverUrl,
      serverToken = this[K.token] ?: d.serverToken,
      liveUploadEnabled = this[K.live] ?: d.liveUploadEnabled,
      uploadBatchSeconds = this[K.batch] ?: d.uploadBatchSeconds,
      levelOffsetPitch = this[K.lvlP] ?: d.levelOffsetPitch,
      levelOffsetRoll = this[K.lvlR] ?: d.levelOffsetRoll,
    )
  }
}
