package hr.adip.senzori.upload

import hr.adip.senzori.upload.UploadPolicy.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class UploadPolicyTest {
  @Test fun vrsteGresaka() {
    assertEquals(Failure.AUTH, UploadPolicy.classify(401))
    assertEquals(Failure.AUTH, UploadPolicy.classify(403))
    assertEquals(Failure.TRANSIENT, UploadPolicy.classify(408))
    assertEquals(Failure.TRANSIENT, UploadPolicy.classify(429))
    assertEquals(Failure.PERMANENT, UploadPolicy.classify(400))
    assertEquals(Failure.PERMANENT, UploadPolicy.classify(404))
    assertEquals(Failure.PERMANENT, UploadPolicy.classify(413))
    assertEquals(Failure.TRANSIENT, UploadPolicy.classify(500)) // i cPanelova stranica "509 Bandwidth Limit Exceeded" dolazi kao 500
    assertEquals(Failure.TRANSIENT, UploadPolicy.classify(503))
    assertEquals(Failure.AUTH, UploadPolicy.classify(HttpStatusException(401, "HTTP 401: Nije dopušteno")))
    assertEquals(Failure.TRANSIENT, UploadPolicy.classify(SocketTimeoutException()))
    assertEquals(Failure.TRANSIENT, UploadPolicy.classify(IOException("HTTP 200: prazan odgovor")))
  }

  @Test fun odmakRaste() {
    val min = 60_000L
    assertEquals(listOf(5 * min, 10 * min, 20 * min, 40 * min, 60 * min, 60 * min),
      (1..6).map { UploadPolicy.retryDelayMs(it) })
    assertEquals(60 * min, UploadPolicy.retryDelayMs(1000)) // bez preljeva
    assertEquals(5 * min, UploadPolicy.retryDelayMs(0))
  }

  private val sat = 3_600_000L
  private val dan = 24 * sat

  /** Simulira dan rada: vraća broj pokušaja slanja. */
  private fun pokusajaUDanu(ishod: (pokusaj: Int) -> Failure?): Int {
    val plan = StreamSchedule(sat, 0)
    var n = 0
    while (plan.nextAtMs <= dan) {
      val now = plan.nextAtMs
      if (!plan.onResult(ishod(n++), now)) break
    }
    return n
  }

  @Test fun normalnoJednomNaSat() {
    assertEquals(24, pokusajaUDanu { null })
  }

  @Test fun opozvanTokenSamoJedanPokusaj() {
    assertEquals(1, pokusajaUDanu { Failure.AUTH })
    // i kad token bude opozvan usred rada
    assertEquals(6, pokusajaUDanu { if (it < 5) null else Failure.AUTH })
  }

  @Test fun trajnaGreskaSamoJedanPokusaj() {
    assertEquals(1, pokusajaUDanu { Failure.PERMANENT })
  }

  @Test fun serverNeRadiNajviseOkoJednomNaSat() {
    // 1. termin u 60 min, pa +5, +10, +20, +40, pa svakih 60 min → 5 + 21 = 26 pokušaja
    val n = pokusajaUDanu { Failure.TRANSIENT }
    assertTrue("previše pokušaja: $n", n <= 26)
    assertTrue(n >= 20)
  }

  @Test fun nakonOporavkaNatragNaRaspored() {
    val plan = StreamSchedule(sat, 0)
    assertTrue(plan.onResult(Failure.TRANSIENT, sat))            // u 60 min ne uspije
    assertEquals(sat + 5 * 60_000L, plan.nextAtMs)
    assertTrue(plan.onResult(null, plan.nextAtMs))                // u 65 min uspije
    assertEquals(2 * sat, plan.nextAtMs)                          // sljedeći redovni termin
    assertEquals(0, plan.failures)
  }

  @Test fun noviTokenOdmah() {
    val plan = StreamSchedule(sat, 0)
    assertFalse(plan.onResult(Failure.AUTH, sat))
    plan.resume(sat + 123)
    assertEquals(sat + 123, plan.nextAtMs)
  }
}
