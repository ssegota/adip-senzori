package hr.adip.senzori.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import hr.adip.senzori.sensor.Sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max

data class RecordingInfo(
  val file: File,
  val name: String,
  val startedAtMs: Long,
  val rows: Long,
  val sizeBytes: Long,
  val sampleRateHz: Int,
  val durationS: Double,
  val meta: Map<String, String>,
)

data class RecordingData(
  val info: RecordingInfo,
  val columns: List<String>,
  val t: LongArray,
  /** NaN = vrijednost nedostaje */
  val values: Map<String, FloatArray>,
)

fun isoUtc(ms: Long): String =
  SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

/** Zapisuje CSV: `# ključ=vrijednost` metapodaci, zaglavlje, redovi. */
class CsvRecorder(val file: File, meta: Map<String, String>) {
  private val w: BufferedWriter = file.bufferedWriter(bufferSize = 64 * 1024)
  private var lastFlush = System.currentTimeMillis()
  var rows = 0L; private set

  init {
    meta.forEach { (k, v) -> w.write("# $k=$v\n") }
    w.write(Sample.COLUMNS.joinToString(",")); w.write("\n")
  }

  fun write(s: Sample) {
    val sb = StringBuilder(80)
    s.toCsvRow().forEachIndexed { i, v ->
      if (i > 0) sb.append(',')
      if (v != null && !(v is Float && v.isNaN())) sb.append(v)
    }
    sb.append('\n')
    w.write(sb.toString())
    rows++
    val now = System.currentTimeMillis()
    if (now - lastFlush > 1000) { w.flush(); lastFlush = now }
  }

  fun close() = runCatching { w.flush(); w.close() }
}

class RecordingRepository(private val context: Context) {
  val dir: File = (context.getExternalFilesDir("zapisi") ?: File(context.filesDir, "zapisi")).apply { mkdirs() }

  private val cache = ConcurrentHashMap<String, Pair<Long, RecordingInfo>>()

  fun newFile(startMs: Long): File {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startMs))
    return File(dir, "adip_$stamp.csv")
  }

  suspend fun list(): List<RecordingInfo> = withContext(Dispatchers.IO) {
    dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }.orEmpty()
      .mapNotNull { f -> runCatching { info(f) }.getOrNull() }
      .sortedByDescending { it.startedAtMs }
  }

  fun info(f: File): RecordingInfo {
    val key = f.absolutePath
    val stamp = f.lastModified() xor (f.length() shl 20)
    cache[key]?.let { (s, i) -> if (s == stamp) return i }
    val meta = LinkedHashMap<String, String>()
    var rows = 0L
    var headerSeen = false
    var firstT = 0L
    var lastT = 0L
    f.bufferedReader().useLines { lines ->
      for (line in lines) {
        if (line.startsWith("#")) {
          val kv = line.drop(1).trim().split("=", limit = 2)
          if (kv.size == 2) meta[kv[0].trim()] = kv[1].trim()
        } else if (!headerSeen) headerSeen = true
        else if (line.isNotBlank()) {
          val t = line.substringBefore(',').toLongOrNull() ?: continue
          if (rows == 0L) firstT = t
          lastT = t
          rows++
        }
      }
    }
    val rate = meta["sampleRateHz"]?.toIntOrNull() ?: 0
    val started = meta["startedAtMs"]?.toLongOrNull() ?: if (rows > 0) firstT else f.lastModified()
    val i = RecordingInfo(
      file = f, name = f.nameWithoutExtension, startedAtMs = started, rows = rows, sizeBytes = f.length(),
      sampleRateHz = rate, durationS = if (rows > 1) (lastT - firstT) / 1000.0 else 0.0, meta = meta,
    )
    cache[key] = stamp to i
    return i
  }

  /** Učita zapis za graf; ako ima više od maxPoints redova, svaki blok svodi na min i max (čuva vrhove). */
  suspend fun load(file: File, maxPoints: Int = 4000): RecordingData = withContext(Dispatchers.IO) {
    val info = info(file)
    var columns: List<String> = Sample.COLUMNS
    val bucket = max(1, ceil(info.rows / (maxPoints / 2.0)).toInt())
    val outT = ArrayList<Long>()
    val nVal = columns.size - 1
    val outV = Array(nVal) { ArrayList<Float>() }
    val bMin = FloatArray(nVal); val bMax = FloatArray(nVal)
    var bCount = 0
    var bT0 = 0L; var bT1 = 0L
    fun resetBucket() { bMin.fill(Float.NaN); bMax.fill(Float.NaN); bCount = 0 }
    fun flushBucket() {
      if (bCount == 0) return
      if (bucket == 1) {
        outT.add(bT0); for (c in 0 until nVal) outV[c].add(bMin[c])
      } else {
        outT.add(bT0); for (c in 0 until nVal) outV[c].add(bMin[c])
        outT.add(bT1); for (c in 0 until nVal) outV[c].add(bMax[c])
      }
      resetBucket()
    }
    resetBucket()
    var headerSeen = false
    file.bufferedReader().useLines { lines ->
      for (line in lines) {
        if (line.startsWith("#") || line.isBlank()) continue
        if (!headerSeen) { headerSeen = true; columns = line.split(",").map { it.trim() }; continue }
        val parts = line.split(',')
        val t = parts[0].toLongOrNull() ?: continue
        if (bCount == 0) bT0 = t
        bT1 = t
        for (c in 0 until nVal) {
          val v = parts.getOrNull(c + 1)?.toFloatOrNull() ?: Float.NaN
          if (!v.isNaN()) {
            if (bMin[c].isNaN() || v < bMin[c]) bMin[c] = v
            if (bMax[c].isNaN() || v > bMax[c]) bMax[c] = v
          }
        }
        bCount++
        if (bCount >= bucket) flushBucket()
      }
    }
    flushBucket()
    RecordingData(
      info = info, columns = columns, t = outT.toLongArray(),
      values = (0 until nVal).associate { c -> columns.getOrElse(c + 1) { "c$c" } to outV[c].toFloatArray() },
    )
  }

  suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) {
    cache.remove(file.absolutePath); file.delete()
  }

  fun shareIntent(file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return Intent(Intent.ACTION_SEND).apply {
      type = "text/csv"
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_SUBJECT, file.name)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }
}
