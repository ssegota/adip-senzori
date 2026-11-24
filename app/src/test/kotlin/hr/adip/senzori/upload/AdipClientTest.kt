package hr.adip.senzori.upload

import com.sun.net.httpserver.HttpServer
import hr.adip.senzori.upload.UploadPolicy.Failure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/** Pravi HTTP put klijenta protiv lokalnog servera: koji kod daje koju vrstu greške. */
class AdipClientTest {
  private lateinit var server: HttpServer
  private lateinit var url: String
  private val primljenoBajtova = AtomicLong()
  /** Odgovor po ruti: status i tijelo. */
  private val odgovori = mutableMapOf<String, Pair<Int, String>>()

  @Before fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
      primljenoBajtova.addAndGet(ex.requestBody.readBytes().size.toLong())
      val (code, body) = odgovori[ex.requestURI.path] ?: (404 to """{"ok":false,"error":"nema"}""")
      val b = body.toByteArray()
      ex.sendResponseHeaders(code, b.size.toLong())
      ex.responseBody.use { it.write(b) }
    }
    server.start()
    url = "http://127.0.0.1:${server.address.port}"
  }

  @After fun stop() { runCatching { server.stop(0) } }

  private val client = AdipClient("bafa0493-test")

  @Test fun opozvanTokenJeAuth() = runBlocking {
    odgovori["/api/senzori/token"] = 401 to """{"ok":false,"error":"Nije dopušteno — neispravan ili nedostaje token."}"""
    val r = client.checkToken(url, "opozvan")
    assertEquals(Failure.AUTH, UploadPolicy.classify(r.exceptionOrNull()!!))
    assertTrue(r.exceptionOrNull()!!.message!!.startsWith("HTTP 401: Nije dopušteno"))
  }

  @Test fun cpanelBandwidthStranicaJeProlazna() = runBlocking {
    odgovori["/api/senzori/token"] = 500 to "<html><title>509 Bandwidth Limit Exceeded</title></html>"
    val r = client.checkToken(url, "x")
    assertEquals(Failure.TRANSIENT, UploadPolicy.classify(r.exceptionOrNull()!!))
  }

  @Test fun html200JeProlazno() = runBlocking {
    odgovori["/api/senzori/token"] = 200 to "<html>One moment, please...</html>"
    val r = client.checkToken(url, "x")
    assertEquals(Failure.TRANSIENT, UploadPolicy.classify(r.exceptionOrNull()!!))
  }

  @Test fun ispravanToken() = runBlocking {
    odgovori["/api/senzori/token"] = 200 to """{"ok":true,"id":"1a2b3c4d","naziv":"Opservatorij"}"""
    assertEquals("Opservatorij", client.checkToken(url, "dobar").getOrThrow())
    assertEquals(0L, primljenoBajtova.get()) // provjera ne šalje tijelo
  }

  @Test fun prevelikPaketJeTrajnaGreska() = runBlocking {
    odgovori["/api/senzori/upload"] = 413 to """{"ok":false,"error":"Paket je prevelik"}"""
    val f = File.createTempFile("paket", ".csv").apply {
      writeText("t_ms,ax\n1,0.1\n")
      deleteOnExit()
    }
    val r = client.uploadStream(url, "dobar", f, 50)
    assertEquals(Failure.PERMANENT, UploadPolicy.classify(r.exceptionOrNull()!!))
  }

  @Test fun serverNedostupanJeProlazno() = runBlocking {
    server.stop(0)
    val r = client.checkToken(url, "x")
    assertEquals(Failure.TRANSIENT, UploadPolicy.classify(r.exceptionOrNull()!!))
  }
}
