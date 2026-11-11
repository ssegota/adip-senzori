package hr.adip.senzori.sensor

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import hr.adip.senzori.BuildConfig
import hr.adip.senzori.data.AppSettings
import hr.adip.senzori.data.SettingsRepository
import hr.adip.senzori.recording.CsvRecorder
import hr.adip.senzori.recording.RecordingRepository
import hr.adip.senzori.recording.isoUtc
import hr.adip.senzori.service.MeasurementService
import hr.adip.senzori.upload.AdipClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.max

/**
 * Središnji mjerni engine.
 *  - Senzori (akcelerometar, svjetlo, tlak) šalju događaje na zasebnu HandlerThread.
 *  - "Ticker" uzorkuje fiksnom frekvencijom (postavke): akceleracija je prosjek svih očitanja
 *    u intervalu (boxcar anti-aliasing), svjetlo/tlak/infrazvuk su zadnja vrijednost.
 *  - Vremenske oznake su t0 + n·T, tj. točno ekvidistantne.
 *  - Isti uzorak ide u povijest (grafovi), CSV (snimanje) i red za slanje (uživo na server).
 */
class SensorEngine(
  private val context: Context,
  private val settingsRepo: SettingsRepository,
  private val recordings: RecordingRepository,
  private val client: AdipClient,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val sm = context.getSystemService(SensorManager::class.java)
  private val accSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
  private val lightSensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
  private val pressureSensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

  private val _state = MutableStateFlow(EngineState(hasLight = lightSensor != null, hasPressure = pressureSensor != null))
  val state: StateFlow<EngineState> = _state.asStateFlow()

  @Volatile private var settings = AppSettings()

  // --- stanje senzora (zaštićeno lockom) ---
  private val lock = Any()
  private var accSum = DoubleArray(3)
  private var accN = 0
  private var accLast = floatArrayOf(0f, 0f, SensorManager.GRAVITY_EARTH)
  private val gravity = floatArrayOf(0f, 0f, SensorManager.GRAVITY_EARTH)
  @Volatile private var lux: Float? = null
  @Volatile private var pHpa: Float? = null

  private var sensorThread: HandlerThread? = null
  private var infra: InfrasoundSource? = null
  private var tickJob: Job? = null
  private var publishJob: Job? = null
  private var uploadJob: Job? = null
  private var runningRate = 0

  // Povijest (~10 s)
  private var hist = Ring(10 * 50)

  // Snimanje
  @Volatile private var recorder: CsvRecorder? = null
  private var recordingStartMs = 0L
  private var sessionId = ""
  private val uploadQueue = ArrayList<Sample>()
  @Volatile private var uploadInfo = UploadInfo()

  init {
    scope.launch {
      settingsRepo.settings.collect { s ->
        val old = settings
        settings = s
        val needsRestart = old.sampleRateHz != s.sampleRateHz || old.infraEnabled != s.infraEnabled ||
          old.infraCalibrationPaPerFs != s.infraCalibrationPaPerFs
        if (needsRestart && _state.value.running && recorder == null) restart()
      }
    }
  }

  private val listener = object : SensorEventListener {
    override fun onSensorChanged(e: SensorEvent) {
      when (e.sensor.type) {
        Sensor.TYPE_ACCELEROMETER -> synchronized(lock) {
          accSum[0] += e.values[0].toDouble(); accSum[1] += e.values[1].toDouble(); accSum[2] += e.values[2].toDouble()
          accN++
          accLast = floatArrayOf(e.values[0], e.values[1], e.values[2])
          // Niskopropusni filtar za libelu (τ ≈ 0.15 s)
          for (i in 0..2) gravity[i] += 0.1f * (e.values[i] - gravity[i])
        }
        Sensor.TYPE_LIGHT -> lux = e.values[0]
        Sensor.TYPE_PRESSURE -> pHpa = e.values[0]
      }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
  }

  @Synchronized
  fun start() {
    if (_state.value.running) return
    val s = settings
    val rate = s.sampleRateHz.coerceIn(1, 500)
    runningRate = rate
    hist = Ring(max(rate * 10, 100))
    val ht = HandlerThread("senzori").also { it.start() }
    sensorThread = ht
    val h = Handler(ht.looper)
    // Akcelerometar: barem 4× brže od zapisa (prosjek u intervalu), max koliko senzor da.
    val accPeriodUs = (1_000_000 / (rate * 4)).coerceAtLeast(accSensor?.minDelay ?: 0)
    accSensor?.let { sm.registerListener(listener, it, accPeriodUs, h) }
    lightSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, h) }
    pressureSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, h) }

    startInfra()
    _state.update { it.copy(running = true, sampleRateHz = rate) }
    tickJob = scope.launch { tickLoop(rate) }
    publishJob = scope.launch { publishLoop() }
  }

  @Synchronized
  fun stop() {
    if (recorder != null) return // snimanje ima prednost
    stopInternal()
  }

  private fun stopInternal() {
    tickJob?.cancel(); publishJob?.cancel()
    tickJob = null; publishJob = null
    sm.unregisterListener(listener)
    sensorThread?.quitSafely(); sensorThread = null
    infra?.stop(); infra = null
    _state.update { it.copy(running = false) }
  }

  @Synchronized
  private fun restart() { stopInternal(); start() }

  private fun startInfra() {
    infra?.stop(); infra = null
    if (!settings.infraEnabled) {
      _state.update { it.copy(infra = InfraInfo(status = "Isključeno u postavkama")) }
      return
    }
    infra = InfrasoundSource(context, runningRate, settings.infraCalibrationPaPerFs).also { it.start() }
  }

  /** Pozvati nakon odobrene dozvole za mikrofon. */
  @Synchronized
  fun restartInfra() { if (_state.value.running) startInfra() }

  private suspend fun tickLoop(rate: Int) {
    val periodNs = 1_000_000_000L / rate
    val t0Wall = System.currentTimeMillis()
    val t0Mono = SystemClock.elapsedRealtimeNanos()
    var n = 0L
    while (currentCoroutineContext().isActive) {
      n++
      val targetMono = t0Mono + n * periodNs
      val waitNs = targetMono - SystemClock.elapsedRealtimeNanos()
      if (waitNs > 0) delay(waitNs / 1_000_000) // ms rezolucija; vremenska oznaka je ionako idealna
      val ax: Float; val ay: Float; val az: Float
      synchronized(lock) {
        if (accN > 0) {
          ax = (accSum[0] / accN).toFloat(); ay = (accSum[1] / accN).toFloat(); az = (accSum[2] / accN).toFloat()
        } else { ax = accLast[0]; ay = accLast[1]; az = accLast[2] }
        accSum.fill(0.0); accN = 0
      }
      val inf = infra?.takeIf { it.info.available }?.latest
      val s = Sample(t0Wall + n * periodNs / 1_000_000, ax, ay, az, lux, inf, pHpa)
      hist.add(s)
      recorder?.let { r ->
        r.write(s)
        if (settings.liveUploadEnabled) synchronized(uploadQueue) {
          uploadQueue.add(s)
          if (uploadQueue.size > MAX_QUEUE) uploadQueue.subList(0, uploadQueue.size - MAX_QUEUE).clear()
        }
      }
    }
  }

  private suspend fun publishLoop() {
    while (true) {
      delay(66)
      val g: FloatArray
      synchronized(lock) { g = gravity.copyOf() }
      val r = recorder
      _state.update {
        it.copy(
          latest = hist.last(), history = hist.snapshot(), gravity = g,
          infra = infra?.info ?: it.infra,
          recordedRows = r?.rows ?: it.recordedRows,
          upload = uploadInfo.copy(pendingRows = synchronized(uploadQueue) { uploadQueue.size }),
        )
      }
    }
  }

  // ---------------- snimanje ----------------

  /** Pokreće foreground servis koji zatim poziva [beginRecording]. */
  fun startRecording(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, MeasurementService::class.java))
  }

  fun stopRecording(context: Context) {
    endRecording()
    context.stopService(Intent(context, MeasurementService::class.java))
  }

  @Synchronized
  internal fun beginRecording() {
    if (recorder != null) return
    if (_state.value.running && runningRate != settings.sampleRateHz) stopInternal()
    start()
    val now = System.currentTimeMillis()
    val file = recordings.newFile(now)
    recordingStartMs = now
    sessionId = client.sessionIdFor(file.nameWithoutExtension)
    val s = settings
    val meta = linkedMapOf(
      "app" to "ADIP Senzori ${BuildConfig.VERSION_NAME}",
      "device" to client.deviceModel,
      "deviceId" to client.deviceId,
      "sessionId" to sessionId,
      "startedAt" to isoUtc(now),
      "startedAtMs" to now.toString(),
      "sampleRateHz" to runningRate.toString(),
      "units" to "t_ms=unix ms; a=m/s^2; lux=lx; infra=" + (if (s.infraCalibrated) "Pa" else "rel. FS") + "; p_hpa=hPa",
      "infraSource" to (infra?.info?.status ?: "-"),
      "infraBand" to "0.5-${"%.1f".format(minOf(20.0, 0.4 * runningRate))} Hz",
    )
    synchronized(uploadQueue) { uploadQueue.clear() }
    uploadInfo = UploadInfo(enabled = s.liveUploadEnabled)
    recorder = CsvRecorder(file, meta)
    _state.update {
      it.copy(recording = true, recordingName = file.nameWithoutExtension, recordedRows = 0, recordingStartedMs = now, upload = uploadInfo)
    }
    uploadJob = scope.launch { uploadLoop(isoUtc(now)) }
  }

  @Synchronized
  internal fun endRecording() {
    val r = recorder ?: return
    recorder = null
    r.close()
    val job = uploadJob
    uploadJob = null
    // Pošalji ostatak reda prije gašenja
    scope.launch {
      job?.cancel()
      if (settings.liveUploadEnabled) flushUpload(isoUtc(recordingStartMs))
    }
    _state.update { it.copy(recording = false, recordedRows = r.rows) }
  }

  private suspend fun uploadLoop(startedIso: String) {
    while (true) {
      delay(settings.uploadBatchSeconds.coerceIn(1, 60) * 1000L)
      if (settings.liveUploadEnabled) flushUpload(startedIso)
    }
  }

  private suspend fun flushUpload(startedIso: String) {
    val batch = synchronized(uploadQueue) { ArrayList(uploadQueue.take(BATCH_MAX)) }
    if (batch.isEmpty()) return
    val s = settings
    client.sendBatch(s.serverUrl, s.serverToken, sessionId, startedIso, runningRate, batch)
      .onSuccess {
        synchronized(uploadQueue) { uploadQueue.subList(0, minOf(batch.size, uploadQueue.size)).clear() }
        uploadInfo = uploadInfo.copy(enabled = true, sentRows = it, lastOkAtMs = System.currentTimeMillis(), lastError = null)
      }
      .onFailure { uploadInfo = uploadInfo.copy(enabled = true, lastError = it.message) }
  }

  /** Kružni spremnik uzoraka za grafove uživo. */
  private class Ring(val cap: Int) {
    private val t = LongArray(cap)
    private val v = Array(5) { FloatArray(cap) }
    private var pos = 0
    private var count = 0
    @Volatile private var lastSample: Sample? = null

    @Synchronized fun add(s: Sample) {
      t[pos] = s.tMs
      v[0][pos] = s.ax; v[1][pos] = s.ay; v[2][pos] = s.az
      v[3][pos] = s.lux ?: Float.NaN; v[4][pos] = s.infra ?: Float.NaN
      pos = (pos + 1) % cap
      if (count < cap) count++
      lastSample = s
    }

    fun last() = lastSample

    @Synchronized fun snapshot(): History {
      val start = (pos - count + cap) % cap
      fun f(a: FloatArray) = FloatArray(count) { a[(start + it) % cap] }
      return History(LongArray(count) { t[(start + it) % cap] }, f(v[0]), f(v[1]), f(v[2]), f(v[3]), f(v[4]))
    }
  }

  companion object {
    private const val MAX_QUEUE = 200_000
    private const val BATCH_MAX = 5_000
  }
}
