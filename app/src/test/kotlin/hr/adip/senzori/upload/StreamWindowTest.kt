package hr.adip.senzori.upload

import hr.adip.senzori.sensor.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamWindowTest {
  private fun sample(n: Long) = Sample(1_000_000L + n * 100, n * 0.0001f, -1f, 9.80665f, if (n % 2 == 0L) 12.34f else null, 1.5E-5f, 1013.25678f, -40.04f)

  private fun rows(csv: String) = csv.lines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1)

  @Test fun prazanProzor() {
    val w = StreamWindow(sampleRateHz = 10, windowSeconds = 60)
    val sb = StringBuilder()
    assertEquals(0, w.writeCsv(sb) { _, _ -> emptyMap() })
    assertEquals("", sb.toString())
  }

  @Test fun drziSamoZadnjiProzor() {
    // 10 Hz, prozor 60 s = 600 uzoraka; dodamo 2000 (200 s)
    val w = StreamWindow(sampleRateHz = 10, windowSeconds = 60, marginSeconds = 5)
    for (n in 0 until 2000L) w.add(sample(n))
    assertEquals(600, w.size)
    var first = 0L; var last = 0L
    val sb = StringBuilder()
    val n = w.writeCsv(sb) { f, l -> first = f; last = l; linkedMapOf("startedAt" to f.toString()) }
    assertEquals(600, n)
    assertEquals(sample(1400).tMs, first)
    assertEquals(sample(1999).tMs, last)
    val csv = sb.toString()
    assertTrue(csv.startsWith("# startedAt=${sample(1400).tMs}\n" + Sample.COLUMNS.joinToString(",") + "\n"))
    val r = rows(csv)
    assertEquals(600, r.size)
    // najstariji prvi, zaokruženo, null → prazno
    assertEquals("${sample(1400).tMs},0.14,-1,9.807,12.3,0.000015,1013.257,-40.04", r.first())
    assertEquals("${sample(1999).tMs},0.2,-1,9.807,,0.000015,1013.257,-40.04", r.last())
  }

  @Test fun djelomicanProzor() {
    val w = StreamWindow(sampleRateHz = 50, windowSeconds = 3600)
    for (n in 0 until 123L) w.add(sample(n))
    val sb = StringBuilder()
    assertEquals(123, w.writeCsv(sb) { _, _ -> emptyMap() })
    assertEquals(123, rows(sb.toString()).size)
  }

  @Test fun zapisDokSeDodaju() {
    // Uzorkovanje teče dok se paket zapisuje: rezultat mora biti cijeli, uzastopan prozor.
    val w = StreamWindow(sampleRateHz = 100, windowSeconds = 60, marginSeconds = 30)
    for (n in 0 until 10_000L) w.add(sample(n))
    val t = Thread { for (n in 10_000L until 11_000L) { w.add(sample(n)); Thread.sleep(0, 200_000) } }
    t.start()
    val sb = StringBuilder()
    val n = w.writeCsv(sb) { _, _ -> emptyMap() }
    t.join()
    val ts = rows(sb.toString()).map { it.substringBefore(',').toLong() }
    assertEquals(n, ts.size)
    assertEquals(6000, n)
    assertTrue(ts.zipWithNext().all { (a, b) -> b - a == 100L })
  }
}
