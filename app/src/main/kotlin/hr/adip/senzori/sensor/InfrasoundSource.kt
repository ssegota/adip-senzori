package hr.adip.senzori.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
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
 *  - koristimo izvor UNPROCESSED (bez AGC/NS/HPF) kad ga uređaj podržava, inače VOICE_RECOGNITION
 *    (minimalna obrada), te nisku frekvenciju uzorkovanja (8 kHz, kao RedVox infrasound app);
 *  - signal decimiramo u dva stupnja na ~100 Hz s anti-aliasing filtrima,
 *  - zatim pojasno filtriramo 0.5 Hz – fc (fc = min(20 Hz, 0.4 × frekvencija zapisa)).
 */
class InfrasoundSource(
  private val context: Context,
  private val recordRateHz: Int,
  private val paPerFs: Float,
) {
  @Volatile var latest: Float = 0f; private set
  @Volatile var info: InfraInfo = InfraInfo(); private set

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
      return
    }
    running = true
    thread = Thread(::loop, "infrazvuk").apply { priority = Thread.MAX_PRIORITY; start() }
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
    else MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION"
  }

  @SuppressLint("MissingPermission")
  private fun openRecord(): Triple<AudioRecord, Int, String>? {
    val (src, srcName) = chooseSource()
    for (rate in intArrayOf(8000, 16000, 48000, 44100)) {
      val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      if (minBuf <= 0) continue
      val rec = try {
        AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate / 5 * 2))
      } catch (e: Exception) { null } ?: continue
      if (rec.state == AudioRecord.STATE_INITIALIZED) return Triple(rec, rate, srcName)
      rec.release()
    }
    return null
  }

  private fun loop() {
    val opened = try { openRecord() } catch (e: SecurityException) { null }
    if (opened == null) {
      info = InfraInfo(available = false, status = "Mikrofon nije dostupan")
      running = false
      return
    }
    val (rec, fs, srcName) = opened
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

    val buf = ShortArray(fs / 20)
    var warmup = (outRate * 3).toInt() // pusti filtre da se smire
    var lastInfo = 0L
    try {
      rec.startRecording()
      info = InfraInfo(available = true, status = status, calibrated = calibrated)
      while (running) {
        val n = rec.read(buf, 0, buf.size)
        if (n <= 0) { if (n < 0) break else continue }
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
        }
      }
    } catch (e: Exception) {
      Log.w("Infrazvuk", "greška", e)
      info = InfraInfo(available = false, status = "Greška: ${e.message}")
    } finally {
      runCatching { rec.stop() }
      rec.release()
    }
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
