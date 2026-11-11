package hr.adip.senzori.upload

import android.os.Build
import hr.adip.senzori.BuildConfig
import hr.adip.senzori.recording.RecordingInfo
import hr.adip.senzori.sensor.Sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
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

  private fun HttpURLConnection.readResult(): JSONObject {
    val code = responseCode
    val body = (if (code in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    val json = runCatching { JSONObject(body) }.getOrNull()
    if (code !in 200..299 || json == null || !json.optBoolean("ok", false)) {
      val msg = json?.optString("error")?.takeIf { it.isNotBlank() } ?: body.take(120).ifBlank { "prazan odgovor" }
      throw RuntimeException("HTTP $code: $msg")
    }
    return json
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

  /** Šalje paket redova uživo (POST /api/senzori/batch). Vraća ukupan broj redova sesije na serveru. */
  suspend fun sendBatch(
    baseUrl: String, token: String, sessionId: String, startedAtIso: String, sampleRateHz: Int, rows: List<Sample>,
  ): Result<Long> = withContext(Dispatchers.IO) {
    runCatching {
      val body = JSONObject().apply {
        put("sessionId", sessionId)
        put("deviceId", deviceId)
        put("deviceModel", deviceModel)
        put("appVersion", BuildConfig.VERSION_NAME)
        put("startedAt", startedAtIso)
        put("sampleRateHz", sampleRateHz)
        put("columns", JSONArray(Sample.COLUMNS))
        put("rows", JSONArray().apply {
          rows.forEach { s ->
            put(JSONArray().apply {
              s.toCsvRow().forEach { v -> put(if (v == null || (v is Float && (v.isNaN() || v.isInfinite()))) JSONObject.NULL else v) }
            })
          }
        })
      }.toString().toByteArray()
      val c = open(baseUrl, "/api/senzori/batch", token, "POST")
      try {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        c.readResult().optLong("total")
      } finally { c.disconnect() }
    }
  }

  /** Šalje cijeli CSV zapis (POST /api/senzori/upload, multipart). Vraća broj spremljenih redova. */
  suspend fun uploadCsv(baseUrl: String, token: String, info: RecordingInfo): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
      val boundary = "----adip" + UUID.randomUUID().toString().replace("-", "")
      val c = open(baseUrl, "/api/senzori/upload", token, "POST")
      try {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setChunkedStreamingMode(64 * 1024)
        DataOutputStream(c.outputStream).use { out ->
          fun field(name: String, value: String) {
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
            out.write(value.toByteArray()); out.writeBytes("\r\n")
          }
          field("sessionId", sessionIdFor(info.name))
          field("deviceId", deviceId)
          field("deviceModel", deviceModel)
          if (info.sampleRateHz > 0) field("sampleRateHz", info.sampleRateHz.toString())
          out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${info.file.name}\"\r\nContent-Type: text/csv\r\n\r\n")
          info.file.inputStream().use { it.copyTo(out) }
          out.writeBytes("\r\n--$boundary--\r\n")
        }
        c.readResult().optInt("rows")
      } finally { c.disconnect() }
    }
  }

  /** ID sesije na serveru: ime datoteke + kratki ID uređaja (dozvoljeno [A-Za-z0-9_-], max 64). */
  fun sessionIdFor(recordingName: String): String =
    "${recordingName}_${deviceId.take(8)}".replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
}
