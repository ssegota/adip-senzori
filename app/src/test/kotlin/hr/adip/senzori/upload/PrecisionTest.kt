package hr.adip.senzori.upload

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

class PrecisionTest {
  @Test fun zaokruzivanje() {
    assertEquals("9.807", Precision.format(9.80665f.toDouble(), 3))
    assertEquals("-0.109", Precision.format(-0.10891724, 3))
    assertEquals("0", Precision.format(-0.0004, 3))       // bez "-0"
    assertEquals("51", Precision.format(51.0, 1))          // bez ".0"
    assertEquals("1012.539", Precision.format(1012.5394, 3))
    assertEquals("-0.000322", Precision.format(-3.2178E-4, 6)) // bez eksponenta
    assertEquals("0.000001", Precision.format(1.2E-6, 6))
    assertEquals("-62.7", Precision.format(-62.7432, 1))
    assertEquals("", Precision.format(Double.NaN, 3))
    assertEquals("", Precision.format(Double.POSITIVE_INFINITY, 3))
  }

  @Test fun csvZapisaSUredaja() {
    val ulaz = listOf(
      "# sampleRateHz=50",
      "# deviceId=abc",
      "t_ms,ax,ay,az,lux,infra,p_hpa,audio_db",
      "1762855200000,0.08377075,-0.10891724,9.800766,51.0,-3.2178E-4,1012.5394,-62.7432",
      "1762855200020,0.1,0.2,9.8,,1.0E-7,,",
    ).joinToString("\n", postfix = "\n")
    val izlaz = StringWriter()
    val n = Precision.roundCsv(ulaz.reader().buffered(), izlaz)
    assertEquals(2, n)
    assertEquals(
      listOf(
        "# sampleRateHz=50",
        "# deviceId=abc",
        "t_ms,ax,ay,az,lux,infra,p_hpa,audio_db",
        "1762855200000,0.084,-0.109,9.801,51,-0.000322,1012.539,-62.743",
        "1762855200020,0.1,0.2,9.8,,0,,",
      ).joinToString("\n", postfix = "\n"),
      izlaz.toString(),
    )
  }

  @Test fun nepoznatiStupciOstajuIsti() {
    val izlaz = StringWriter()
    Precision.roundCsv("t_ms,ax,nesto\n1762855200000,1.23456,3.14159265\n".reader().buffered(), izlaz)
    assertEquals("t_ms,ax,nesto\n1762855200000,1.235,3.14159265\n", izlaz.toString())
  }
}
