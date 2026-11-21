package hr.adip.senzori.upload

import hr.adip.senzori.sensor.Sample

/**
 * Kružni spremnik zadnjih [windowSeconds] sekundi uzoraka za stream (paket se šalje jednom na sat).
 *
 * Uzorci su u primitivnim nizovima (bez objekta po uzorku): pri 50 Hz ~7 MB, pri 200 Hz ~27 MB.
 * Spremnik je za [marginSeconds] veći od prozora, pa [writeCsv] može čitati u malim komadima
 * bez zaustavljanja uzorkovanja: novi uzorci za to vrijeme prepisuju samo stare uzorke izvan prozora.
 */
class StreamWindow(
  val sampleRateHz: Int,
  val windowSeconds: Int,
  marginSeconds: Int = 120,
) {
  private val windowCap = sampleRateHz.coerceAtLeast(1) * windowSeconds.coerceAtLeast(1)
  private val cap = windowCap + sampleRateHz.coerceAtLeast(1) * marginSeconds.coerceAtLeast(1)
  private val t = LongArray(cap)
  private val v = Array(VALUE_COLUMNS) { FloatArray(cap) }
  /** Ukupno dodanih uzoraka; sljedeći ide na indeks total % cap. */
  private var total = 0L

  @Synchronized
  fun add(s: Sample) {
    val i = (total % cap).toInt()
    t[i] = s.tMs
    v[0][i] = s.ax; v[1][i] = s.ay; v[2][i] = s.az
    v[3][i] = s.lux ?: Float.NaN
    v[4][i] = s.infra ?: Float.NaN
    v[5][i] = s.pHpa ?: Float.NaN
    v[6][i] = s.audioDb ?: Float.NaN
    total++
  }

  /** Broj uzoraka u prozoru (najviše windowSeconds · sampleRateHz). */
  val size: Int @Synchronized get() = minOf(total, windowCap.toLong()).toInt()

  /**
   * Zapisuje prozor kao CSV sa smanjenom preciznošću ([Precision]): `# ključ=vrijednost`
   * komentari iz [meta] (dobiva vrijeme prvog i zadnjeg uzorka), zaglavlje, redovi od najstarijeg.
   * Vraća broj zapisanih redaka.
   */
  fun writeCsv(out: Appendable, meta: (firstMs: Long, lastMs: Long) -> Map<String, String>): Int {
    val start: Long
    val end: Long
    val firstMs: Long
    val lastMs: Long
    synchronized(this) {
      end = total
      start = end - minOf(total, windowCap.toLong())
      if (end == start) return 0
      firstMs = t[(start % cap).toInt()]
      lastMs = t[((end - 1) % cap).toInt()]
    }
    meta(firstMs, lastMs).forEach { (k, value) -> out.append("# ").append(k).append('=').append(value.replace('\n', ' ')).append('\n') }
    out.append(Sample.COLUMNS.joinToString(",")).append('\n')

    val dec = IntArray(VALUE_COLUMNS) { Precision.DECIMALS[Sample.COLUMNS[it + 1]] ?: 6 }
    val ct = LongArray(CHUNK)
    val cv = Array(VALUE_COLUMNS) { FloatArray(CHUNK) }
    val sb = StringBuilder(96)
    var k = start
    while (k < end) {
      val m = minOf(CHUNK.toLong(), end - k).toInt()
      synchronized(this) {
        // uzorak k je još u spremniku dok vrijedi total - k <= cap
        check(total - k <= cap) { "Prozor je prepisan tijekom zapisa paketa." }
        for (j in 0 until m) {
          val i = ((k + j) % cap).toInt()
          ct[j] = t[i]
          for (c in 0 until VALUE_COLUMNS) cv[c][j] = v[c][i]
        }
      }
      for (j in 0 until m) {
        sb.setLength(0)
        sb.append(ct[j])
        for (c in 0 until VALUE_COLUMNS) {
          sb.append(',')
          Precision.appendRounded(sb, cv[c][j].toDouble(), dec[c])
        }
        sb.append('\n')
        out.append(sb)
      }
      k += m
    }
    return (end - start).toInt()
  }

  companion object {
    /** Stupci uz t_ms: ax, ay, az, lux, infra, p_hpa, audio_db (redoslijed kao [Sample.COLUMNS]). */
    const val VALUE_COLUMNS = 7
    private const val CHUNK = 2048
  }
}
