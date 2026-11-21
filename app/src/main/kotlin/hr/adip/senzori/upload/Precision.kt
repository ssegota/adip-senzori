package hr.adip.senzori.upload

import java.io.BufferedReader
import java.io.Writer
import kotlin.math.roundToLong

/**
 * Smanjena preciznost vrijednosti koje se spremaju na server (stream i slanje zapisa).
 * CSV na uređaju se NE mijenja — u njemu ostaje puna preciznost.
 *
 * Broj decimala po stupcu:
 *  - ax/ay/az 3 (1 mm/s²)
 *  - lux 1
 *  - infra 6 (1 µ punog raspona ≈ −120 dBFS, ispod šuma 16-bitnog mikrofona; ili 1 µPa ako je kalibrirano)
 *  - p_hpa 3 (0,1 Pa)
 *  - audio_db 3
 * Stupci kojih nema u tablici (npr. t_ms) prolaze nepromijenjeni.
 */
object Precision {
  val DECIMALS: Map<String, Int> = mapOf(
    "ax" to 3, "ay" to 3, "az" to 3,
    "lux" to 1,
    "infra" to 6,
    "p_hpa" to 3,
    "audio_db" to 3,
  )

  /** Kratki opis za `# precision=` u CSV-u na serveru. */
  val DESCRIPTION: String = DECIMALS.entries.joinToString(";") { "${it.key}:${it.value}" } + " (decimale)"

  private val POW10 = LongArray(10).also { p -> p[0] = 1; for (i in 1 until p.size) p[i] = p[i - 1] * 10 }

  /** Dodaje [v] zaokružen na [decimals] decimala, bez nula na kraju i bez eksponenta. NaN/∞ → ništa (prazna ćelija). */
  fun appendRounded(sb: StringBuilder, v: Double, decimals: Int) {
    if (v.isNaN() || v.isInfinite()) return
    val d = decimals.coerceIn(0, POW10.size - 1)
    val scale = POW10[d]
    var n = (v * scale).roundToLong()
    if (n == 0L) { sb.append('0'); return }
    if (n < 0) { sb.append('-'); n = -n }
    sb.append(n / scale)
    var frac = n % scale
    if (frac == 0L) return
    var digits = d
    while (frac % 10 == 0L) { frac /= 10; digits-- }
    sb.append('.')
    val s = frac.toString()
    repeat(digits - s.length) { sb.append('0') }
    sb.append(s)
  }

  fun format(v: Double, decimals: Int): String = StringBuilder(16).also { appendRounded(it, v, decimals) }.toString()

  /** Decimale za svaki stupac zaglavlja; null = stupac ostaje kakav jest. */
  fun decimalsFor(columns: List<String>): Array<Int?> = Array(columns.size) { DECIMALS[columns[it].trim()] }

  /**
   * Prepisuje CSV iz [input] u [output] sa smanjenom preciznošću: komentari (`#`) i zaglavlje
   * ostaju isti, brojevi u stupcima iz [DECIMALS] se zaokružuju. Ćelija koja nije broj ostaje
   * nepromijenjena. Vraća broj redaka s podacima.
   */
  fun roundCsv(input: BufferedReader, output: Writer): Int {
    var dec: Array<Int?>? = null
    var rows = 0
    val sb = StringBuilder(128)
    while (true) {
      val line = input.readLine() ?: break
      if (dec == null) {
        output.write(line); output.write("\n")
        if (line.isNotBlank() && !line.startsWith("#")) dec = decimalsFor(line.split(','))
        continue
      }
      if (line.isBlank() || line.startsWith("#")) { output.write(line); output.write("\n"); continue }
      sb.setLength(0)
      var start = 0
      var col = 0
      while (true) {
        val end = line.indexOf(',', start).let { if (it < 0) line.length else it }
        if (col > 0) sb.append(',')
        val d = dec.getOrNull(col)
        val v = if (d != null && end > start) line.substring(start, end).trim().toDoubleOrNull() else null
        if (d != null && v != null) appendRounded(sb, v, d) else sb.append(line, start, end)
        col++
        if (end >= line.length) break
        start = end + 1
      }
      sb.append('\n')
      output.write(sb.toString())
      rows++
    }
    return rows
  }
}
