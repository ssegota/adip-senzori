package hr.adip.senzori.sensor

/** Jedan red zapisa uzorkovan fiksnom frekvencijom. null = senzor nedostupan. */
data class Sample(
  val tMs: Long,
  val ax: Float,
  val ay: Float,
  val az: Float,
  val lux: Float?,
  /** Infrazvuk: pojasno filtriran signal mikrofona (rel. jedinice ili Pa ako je kalibrirano). */
  val infra: Float?,
  val pHpa: Float?,
) {
  fun toCsvRow(): List<Number?> = listOf(tMs, ax, ay, az, lux, infra, pHpa)

  companion object {
    val COLUMNS = listOf("t_ms", "ax", "ay", "az", "lux", "infra", "p_hpa")
  }
}

/** Kratka povijest (zadnjih ~10 s) za grafove uživo; poravnati nizovi. */
class History(
  val tMs: LongArray,
  val ax: FloatArray,
  val ay: FloatArray,
  val az: FloatArray,
  val lux: FloatArray,
  val infra: FloatArray,
) {
  val size get() = tMs.size

  companion object {
    val EMPTY = History(LongArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
  }
}

data class InfraInfo(
  val available: Boolean = false,
  /** Opis izvora: npr. "UNPROCESSED @ 8000 Hz" ili razlog nedostupnosti. */
  val status: String = "isključeno",
  /** RMS u zadnjoj sekundi (iste jedinice kao Sample.infra). */
  val rms: Float = 0f,
  /** Razina: dB SPL (re 20 µPa) ako je kalibrirano, inače dBFS. */
  val levelDb: Float = Float.NEGATIVE_INFINITY,
  val calibrated: Boolean = false,
  /** Amplitudni spektar 0..maxHz (linearno, normalizirano 0..1 za prikaz). */
  val spectrum: FloatArray = FloatArray(0),
  val spectrumMaxHz: Float = 20f,
  /** Dominantna frekvencija (Hz) u pojasu 0.5–20 Hz. */
  val peakHz: Float = 0f,
)

data class UploadInfo(
  val enabled: Boolean = false,
  val sentRows: Long = 0,
  val pendingRows: Int = 0,
  val lastOkAtMs: Long = 0,
  val lastError: String? = null,
)

data class EngineState(
  val running: Boolean = false,
  val sampleRateHz: Int = 50,
  val latest: Sample? = null,
  val history: History = History.EMPTY,
  /** Niskopropusno filtriran vektor gravitacije za libelu (m/s²), brže osvježavanje. */
  val gravity: FloatArray = floatArrayOf(0f, 0f, 9.81f),
  val hasLight: Boolean = false,
  val hasPressure: Boolean = false,
  val infra: InfraInfo = InfraInfo(),
  /** Sesija (stream i/ili snimanje) je aktivna. */
  val recording: Boolean = false,
  val sessionMode: hr.adip.senzori.data.SessionMode? = null,
  /** Ime CSV datoteke koja se piše (null ako se samo streama). */
  val recordingName: String? = null,
  val recordedRows: Long = 0,
  val recordingStartedMs: Long = 0,
  val upload: UploadInfo = UploadInfo(),
)
