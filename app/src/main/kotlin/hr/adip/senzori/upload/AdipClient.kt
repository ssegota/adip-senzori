package hr.adip.senzori.upload

import android.os.Build
import hr.adip.senzori.BuildConfig
import hr.adip.senzori.recording.RecordingInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Klijent za ADIP web servis (/api/senzori na adip.hr).
 * Autentikacija: `Authorization: Bearer <token>`.
 */
class AdipClient(val deviceId: String) {
  val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"

  private fun open(baseUrl: String, path: String, token: String, method: String): HttpURLConnection {
    val url = URL(baseUrl.trimEnd('/') + path)
    return (url.openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = 10_000
      readTimeout = 30_000
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "ADIP-Senzori/${BuildConfig.VERSION_NAME}")
      if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
    }
  }

  /** Čita JSON odgovor; status izvan 2xx baca [HttpStatusException], neispravan odgovor [IOException]. */
  private fun HttpURLConnection.readResult(): JSONObject {
    val code = responseCode
    val body = (if (code in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    val json = runCatching { JSONObject(body) }.getOrNull()
    if (code !in 200..299 || json == null || !json.optBoolean("ok", false)) {
      val msg = json?.optString("error")?.takeIf { it.isNotBlank() } ?: body.take(120).ifBlank { "prazan odgovor" }
      if (code !in 200..299) throw HttpStatusException(code, "HTTP $code: $msg")
      throw IOException("HTTP $code: $msg")
    }
    return json
  }

  /**
   * Jeftina provjera tokena i dostupnosti servera (GET /api/senzori/token, nekoliko stotina
   * bajtova). Stream je zove prije svakog paketa, pa opozvan token ili srušen server ne troše
   * promet cijelim paketom. Vraća naziv tokena.
   */
  suspend fun checkToken(baseUrl: String, token: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val c = open(baseUrl, "/api/senzori/token", token, "GET")
      try {
        val t = c.readResult()
        t.optString("naziv").ifBlank { t.optString("id") }
      } finally { c.disconnect() }
    }
  }

  suspend fun status(baseUrl: String, token: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val c = open(baseUrl, "/api/senzori/status", token, "GET")
      try {
        val j = c.readResult()
        val base = "OK (${j.optString("service")} v${j.optInt("version")})"
        if (token.isBlank()) return@runCatching "$base, token nije postavljen"
        // Provjera tokena (GET /api/senzori/token)
        val auth = open(baseUrl, "/api/senzori/token", token, "GET")
        try {
          when (auth.responseCode) {
            200 -> {
              val t = auth.readResult()
              "$base, token ispravan (${t.optString("naziv").ifBlank { t.optString("id") }})"
            }
            401, 403 -> "$base, TOKEN NEISPRAVAN"
            else -> "$base, token se ne može provjeriti (HTTP ${auth.responseCode})"
          }
        } finally { auth.disconnect() }
      } finally { c.disconnect() }
    }
  }

  /** Kreira novi token na serveru (POST /api/senzori/tokeni) uz lozinku društva. Vraća token. */
  suspend fun createToken(baseUrl: String, lozinka: String, naziv: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val body = JSONObject().put("lozinka", lozinka).put("naziv", naziv).toString().toByteArray()
      val c = open(baseUrl, "/api/senzori/tokeni", "", "POST")
      try {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        c.readResult().getString("token")
      } finally { c.disconnect() }
    }
  }

  /**
   * Šalje CSV zapis s uređaja (POST /api/senzori/upload). Datoteka na uređaju se ne mijenja,
   * a vrijednosti se pri slanju zaokružuju ([Precision]). Vraća broj spremljenih redova.
   */
  suspend fun uploadCsv(baseUrl: String, token: String, info: RecordingInfo): Result<Int> =
    uploadFile(baseUrl, token, sessionIdFor(info.name), info.file, info.sampleRateHz, roundValues = true)

  /**
   * Šalje satni paket streama (CSV koji je već zaokružen) pod stalnim ID-om [streamSessionId].
   * Server zamjenjuje prethodni paket ovog uređaja, pa na serveru uvijek ostaje samo zadnji.
   */
  suspend fun uploadStream(baseUrl: String, token: String, file: File, sampleRateHz: Int): Result<Int> =
    uploadFile(baseUrl, token, streamSessionId, file, sampleRateHz, roundValues = false)

  /** POST /api/senzori/upload (multipart). Postojeća sesija istog ID-a na serveru se zamjenjuje. */
  private suspend fun uploadFile(
    baseUrl: String, token: String, sessionId: String, file: File, sampleRateHz: Int, roundValues: Boolean,
  ): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
      val boundary = "----adip" + UUID.randomUUID().toString().replace("-", "")
      val c = open(baseUrl, "/api/senzori/upload", token, "POST")
      try {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setChunkedStreamingMode(64 * 1024)
        DataOutputStream(c.outputStream.buffered(64 * 1024)).use { out ->
          fun field(name: String, value: String) {
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
            out.write(value.toByteArray()); out.writeBytes("\r\n")
          }
          field("sessionId", sessionId)
          field("deviceId", deviceId)
          field("deviceModel", deviceModel)
          field("appVersion", BuildConfig.VERSION_NAME)
          if (sampleRateHz > 0) field("sampleRateHz", sampleRateHz.toString())
          out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$sessionId.csv\"\r\nContent-Type: text/csv\r\n\r\n")
          if (roundValues) {
            val w = out.writer(Charsets.UTF_8).buffered(64 * 1024)
            file.bufferedReader().use { Precision.roundCsv(it, w) }
            w.flush()
          } else {
            file.inputStream().use { it.copyTo(out) }
          }
          out.writeBytes("\r\n--$boundary--\r\n")
        }
        c.readResult().optInt("rows")
      } finally { c.disconnect() }
    }
  }

  /** ID sesije na serveru: ime datoteke + kratki ID uređaja (dozvoljeno [A-Za-z0-9_-], max 64). */
  fun sessionIdFor(recordingName: String): String =
    "${recordingName}_${deviceId.take(8)}".replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)

  /** Stalni ID streama ovog uređaja: svaki satni paket zamjenjuje prethodni. */
  val streamSessionId: String = "stream_${deviceId.take(8)}".replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
}
