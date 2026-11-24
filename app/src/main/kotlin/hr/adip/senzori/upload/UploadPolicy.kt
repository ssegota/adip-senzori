package hr.adip.senzori.upload

import java.io.IOException

/** Server je odgovorio statusom izvan 2xx. [code] je HTTP statusni kod. */
class HttpStatusException(val code: Int, message: String) : IOException(message)

/**
 * Pravila za automatsko slanje streama (od verzije 1.6).
 *
 * Svaki pokušaj troši promet hostinga: server primi cijelo tijelo zahtjeva prije nego što
 * odgovori, a cPanel broji i dolazne bajtove. Verzija 1.4 je s opozvanim tokenom ponavljala
 * odbijeni paket svake 2–3 s i potrošila mjesečni limit adip.hr. Zato se greške dijele u
 * tri vrste:
 *  - [Failure.AUTH]: 401/403 ili prazan token. Slanje staje i ne ponavlja se dok se u
 *    postavkama ne upiše drugi token.
 *  - [Failure.PERMANENT]: ostale greške 4xx (npr. 400, 404, 413). Isti zahtjev bi opet pao,
 *    pa slanje staje do kraja sesije.
 *  - [Failure.TRANSIENT]: nema mreže, timeout, 5xx, 408, 429 ili neočekivan odgovor.
 *    Ponavlja se s rastućim odmakom ([retryDelayMs]), u prosjeku najviše jednom na sat.
 */
object UploadPolicy {
  enum class Failure { AUTH, PERMANENT, TRANSIENT }

  /** Prvi ponovni pokušaj nakon 5 min… */
  const val RETRY_BASE_MS = 5 * 60_000L
  /** …a odmak se udvostručuje do najviše sat vremena. */
  const val RETRY_MAX_MS = 60 * 60_000L

  fun classify(e: Throwable): Failure =
    if (e is HttpStatusException) classify(e.code) else Failure.TRANSIENT

  fun classify(httpCode: Int): Failure = when (httpCode) {
    401, 403 -> Failure.AUTH
    408, 429 -> Failure.TRANSIENT
    in 400..499 -> Failure.PERMANENT
    else -> Failure.TRANSIENT
  }

  /** Odmak prije ponovnog pokušaja nakon [consecutiveFailures] uzastopnih grešaka: 5, 10, 20, 40, 60, 60… min. */
  fun retryDelayMs(consecutiveFailures: Int): Long {
    val n = consecutiveFailures.coerceIn(1, 8)
    return minOf(RETRY_BASE_MS shl (n - 1), RETRY_MAX_MS)
  }
}
