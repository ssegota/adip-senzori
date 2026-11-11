package hr.adip.senzori.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Infrazvuk iz mikrofona.
 *
 * Pozadina: infrazvuk je < 20 Hz. Mikrofoni mobitela imaju fizički i/ili softverski
 * visokopropusni filtar (tipično s padom ispod ~10–20 Hz), pa su vrijednosti relativne,
 * osim ako se ne kalibriraju referentnim mikrobarometrom. Zato:
 *  - koristimo izvor UNPROCESSED (bez AGC/NS/HPF) kad ga uređaj podržava, inače MIC uz isključene
 *    audio efekte (AGC, potiskivanje šuma, poništavanje jeke), te nisku frekvenciju uzorkovanja
 *    (8 kHz, kao RedVox infrasound app);
 *  - signal decimiramo u dva stupnja na ~100 Hz s anti-aliasing filtrima,
 *  - zatim pojasno filtriramo 0.5 Hz – fc (fc = min(20 Hz, 0.4 × frekvencija zapisa)).
 */
class InfrasoundSource(
  private val context: Context,
  private val recordRateHz: Int,
  private val paPerFs: Float,
  /** Računaj infrazvuk (inače se mikrofon koristi samo za razinu zvuka / WAV). */
  private val infraEnabled: Boolean = true,
  /** Željena frekvencija mikrofona (Hz); null = 8 kHz (dovoljno za infrazvuk). */
  private val preferredRate: Int? = null,
  private val audioEnabled: Boolean = false,
) {
  @Volatile var latest: Float = 0f; private set
  @Volatile var info: InfraInfo = InfraInfo(); private set
  @Volatile var audioInfo: AudioInfo = AudioInfo(enabled = audioEnabled); private set

  /** Ciljna WAV datoteka: dok je postavljena, sirovi PCM16 iz mikrofona zapisuje se u nju (postavlja engine). */
  @Volatile var wavTarget: java.io.File? = null
  private var wav: hr.adip.senzori.recording.WavWriter? = null
  @Volatile var sampleRate: Int = 0; private set

  // Akumulator razine zvuka za jedan interval uzorka (čita ga ticker)
  private val tickLock = Any()
  private var tickSumSq = 0.0
  private var tickN = 0
  @Volatile private var lastTickDb: Float? = null

  /** RMS razina zvuka (dBFS) od zadnjeg poziva; null ako mikrofon ne radi. */
  fun takeTickLevelDb(): Float? {
    if (!running || sampleRate == 0) return null
    synchronized(tickLock) {
      if (tickN == 0) return lastTickDb
      val rms = kotlin.math.sqrt(tickSumSq / tickN) / 32768.0
      tickSumSq = 0.0; tickN = 0
      val db = if (rms > 0) (20 * log10(rms)).toFloat().coerceAtLeast(-120f) else -120f
      lastTickDb = db
      return db
    }
  }

  private var thread: Thread? = null
  @Volatile private var running = false

  // Kružni spremnik izlaza (~100 Hz) za RMS i spektar
  private val ring = FloatArray(1024)
  private var ringPos = 0
  private var ringCount = 0
  private var outRate = 100.0
  private var bandHigh = 20.0

  fun hasPermission() =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  fun start() {
    if (running) return
    if (!hasPermission()) {
      info = InfraInfo(available = false, status = "Potrebna dozvola za mikrofon")
      audioInfo = AudioInfo(enabled = audioEnabled, status = "Potrebna dozvola za mikrofon")
      return
    }
    running = true
    thread = Thread(::loop, "mikrofon").apply { priority = Thread.MAX_PRIORITY; start() }
  }

  fun stop() {
    running = false
    thread?.join(500)
    thread = null
  }

  private fun chooseSource(): Pair<Int, String> {
    val am = context.getSystemService(AudioManager::class.java)
    val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED"
    else MediaRecorder.AudioSource.MIC to "MIC"
  }

  @SuppressLint("MissingPermission")
  private fun openRecord(): Triple<AudioRecord, Int, String>? {
    val (src, srcName) = chooseSource()
    val rates = (listOfNotNull(preferredRate) + listOf(8000, 16000, 48000, 44100)).distinct()
    for (rate in rates) {
      val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      if (minBuf <= 0) continue
      val rec = try {
        AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate / 5 * 2))
      } catch (e: Exception) { null } ?: continue
      if (rec.state == AudioRecord.STATE_INITIALIZED) {
        val off = disableEffects(rec.audioSessionId)
        return Triple(rec, rate, if (off.isEmpty()) srcName else "$srcName, isklj. ${off.joinToString("/")}")
      }
      rec.release()
    }
    return null
  }

  /** Isključi obradu signala koju neki uređaji uključuju na izvoru MIC. Vraća popis isključenih efekata. */
  private fun disableEffects(session: Int): List<String> {
    val off = ArrayList<String>()
    fun tryOff(name: String, available: Boolean, create: () -> android.media.audiofx.AudioEffect?) {
      if (!available) return
      runCatching { create()?.let { e -> if (e.enabled) { e.enabled = false; off.add(name) }; effects.add(e) } }
    }
    tryOff("AGC", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(session) }
    tryOff("NS", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(session) }
    tryOff("AEC", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(session) }
    return off
  }

  private val effects = ArrayList<android.media.audiofx.AudioEffect>()

  private fun loop() {
    val opened = try { openRecord() } catch (e: SecurityException) { null }
    if (opened == null) {
      info = InfraInfo(available = false, status = "Mikrofon nije dostupan")
      audioInfo = AudioInfo(enabled = audioEnabled, status = "Mikrofon nije dostupan")
      running = false
      return
    }
    val (rec, fs, srcName) = opened
    sampleRate = fs
    // Stupanj 1: fs -> ~800 Hz, stupanj 2: -> ~100 Hz
    val d1 = max(1, (fs / 800.0).roundToInt())
    val r1 = fs.toDouble() / d1
    val st1 = Decimator(fs.toDouble(), d1, min(0.4 * r1, 300.0))
    val d2 = max(1, (r1 / 100.0).roundToInt())
    val st2 = Decimator(r1, d2, 40.0)
    outRate = r1 / d2
    bandHigh = min(20.0, 0.4 * recordRateHz)
    val lp = FilterChain.butterLowPass4(outRate, bandHigh)
    val hp = FilterChain.butterHighPass2(outRate, 0.5)
    val scale = if (paPerFs > 0f) paPerFs.toDouble() else 1.0
    val calibrated = paPerFs > 0f
    val status = "$srcName @ $fs Hz → ${"%.0f".format(outRate)} Hz, pojas 0.5–${"%.1f".format(bandHigh)} Hz"
    val audioStatus = "$srcName @ $fs Hz, mono 16-bit"
    var sumSq = 0.0
    var peakAbs = 0
    var levelN = 0

    val buf = ShortArray(max(64, fs / 200)) // 5 ms blokovi, da razina zvuka prati interval uzorka
    var warmup = (outRate * 3).toInt() // pusti filtre da se smire
    var lastInfo = 0L
    try {
      rec.startRecording()
      info = if (infraEnabled) InfraInfo(available = true, status = status, calibrated = calibrated)
        else InfraInfo(available = false, status = "Isključeno u postavkama")
      audioInfo = AudioInfo(enabled = audioEnabled, available = true, sampleRateHz = fs, status = audioStatus)
      while (running) {
        val n = rec.read(buf, 0, buf.size)
        if (n <= 0) { if (n < 0) break else continue }
        val target = wavTarget
        if (target != null && wav?.file != target) {
          wav?.close()
          wav = runCatching { hr.adip.senzori.recording.WavWriter(target, fs) }.getOrNull()
        } else if (target == null && wav != null) {
          wav?.close(); wav = null
        }
        val w = wav
        if (w != null) runCatching { w.write(buf, n) }
        var bufSq = 0.0
        for (i in 0 until n) {
          val v = buf[i].toInt()
          bufSq += v.toDouble() * v
          val a = if (v < 0) -v else v
          if (a > peakAbs) peakAbs = a
        }
        sumSq += bufSq
        levelN += n
        synchronized(tickLock) { tickSumSq += bufSq; tickN += n }
        if (!infraEnabled) {
          val now = System.currentTimeMillis()
          if (now - lastInfo > 250) {
            lastInfo = now
            audioInfo = levelInfo(sumSq, peakAbs, levelN, fs, audioStatus, w != null)
            sumSq = 0.0; peakAbs = 0; levelN = 0
          }
          continue
        }
        for (i in 0 until n) {
          val a = st1.push(buf[i] / 32768.0)
          if (a.isNaN()) continue
          val b = st2.push(a)
          if (b.isNaN()) continue
          val y = hp.process(lp.process(b)) * scale
          if (warmup > 0) { warmup--; continue }
          val f = y.toFloat()
          latest = f
          synchronized(ring) {
            ring[ringPos] = f
            ringPos = (ringPos + 1) % ring.size
            if (ringCount < ring.size) ringCount++
          }
        }
        val now = System.currentTimeMillis()
        if (now - lastInfo > 250) {
          lastInfo = now
          info = analyse(status, calibrated)
          audioInfo = levelInfo(sumSq, peakAbs, levelN, fs, audioStatus, w != null)
          sumSq = 0.0; peakAbs = 0; levelN = 0
        }
      }
    } catch (e: Exception) {
      Log.w("Infrazvuk", "greška", e)
      info = InfraInfo(available = false, status = "Greška: ${e.message}")
    } finally {
      runCatching { rec.stop() }
      rec.release()
      wav?.close(); wav = null
      effects.forEach { runCatching { it.release() } }
      effects.clear()
    }
  }

  private fun levelInfo(sumSq: Double, peakAbs: Int, n: Int, fs: Int, status: String, recording: Boolean): AudioInfo {
    val rms = if (n > 0) sqrt(sumSq / n) / 32768.0 else 0.0
    val pk = peakAbs / 32768.0
    return AudioInfo(
      enabled = audioEnabled, available = true,
      levelDb = if (rms > 0) (20 * log10(rms)).toFloat() else Float.NEGATIVE_INFINITY,
      peakDb = if (pk > 0) (20 * log10(pk)).toFloat() else Float.NEGATIVE_INFINITY,
      sampleRateHz = fs, recording = recording, status = status,
    )
  }

  private fun analyse(status: String, calibrated: Boolean): InfraInfo {
    val n = 512
    val snap: FloatArray
    val count: Int
    synchronized(ring) {
      count = ringCount
      if (count < 64) return InfraInfo(true, status, calibrated = calibrated)
      snap = FloatArray(n)
      val take = min(n, count)
      for (i in 0 until take) {
        val idx = (ringPos - take + i + ring.size) % ring.size
        snap[n - take + i] = ring[idx]
      }
    }
    // RMS zadnje sekunde
    val sec = min(outRate.toInt(), min(n, count))
    var ss = 0.0
    for (i in n - sec until n) ss += snap[i].toDouble() * snap[i]
    val rms = sqrt(ss / sec).toFloat()
    val db = if (rms <= 0f) Float.NEGATIVE_INFINITY
    else if (calibrated) (20 * log10(rms / 20e-6)).toFloat()
    else (20 * log10(rms.toDouble())).toFloat()

    val mag = Fft.magnitude(snap)
    val binHz = outRate / n
    val maxBin = min(mag.size - 1, (bandHigh / binHz).toInt())
    val spec = mag.copyOfRange(0, maxBin + 1)
    val minBin = (0.5 / binHz).toInt().coerceAtLeast(1)
    var peak = minBin
    for (k in minBin..maxBin) if (spec[k] > spec[peak]) peak = k
    val mx = spec.drop(minBin).maxOrNull()?.takeIf { it > 0f } ?: 1f
    for (k in spec.indices) spec[k] = if (k < minBin) 0f else spec[k] / mx
    return InfraInfo(
      available = true, status = status, rms = rms, levelDb = db, calibrated = calibrated,
      spectrum = spec, spectrumMaxHz = (maxBin * binHz).toFloat(), peakHz = (peak * binHz).toFloat(),
    )
  }
}
