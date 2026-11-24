package hr.adip.senzori.upload

/**
 * Raspored pokušaja slanja streama, kao čista logika bez Androida (da se može testirati).
 * Vrijeme je u ms na monotonom satu.
 *
 * Redovni termini su t0 + n·[periodMs]. Nakon uspjeha sljedeći je pokušaj na prvom
 * redovnom terminu iza trenutnog vremena. Nakon prolazne greške odmak raste prema
 * [UploadPolicy.retryDelayMs] i ne vraća se na raspored dok slanje ne uspije, pa ni
 * server koji dugo ne radi ne dobiva više od otprilike jednog paketa na sat.
 */
class StreamSchedule(private val periodMs: Long, startMs: Long) {
  private var slot = startMs + periodMs

  /** Kad ide sljedeći pokušaj. */
  var nextAtMs: Long = slot
    private set

  /** Broj uzastopnih prolaznih grešaka. */
  var failures: Int = 0
    private set

  /**
   * Bilježi ishod pokušaja u trenutku [nowMs] (null = uspjeh). Vraća false ako slanje treba
   * zaustaviti ([UploadPolicy.Failure.AUTH] ili [UploadPolicy.Failure.PERMANENT]).
   */
  fun onResult(failure: UploadPolicy.Failure?, nowMs: Long): Boolean {
    when (failure) {
      null -> {
        failures = 0
        while (slot <= nowMs) slot += periodMs
        nextAtMs = slot
      }
      UploadPolicy.Failure.TRANSIENT -> {
        failures++
        nextAtMs = nowMs + UploadPolicy.retryDelayMs(failures)
      }
      UploadPolicy.Failure.AUTH, UploadPolicy.Failure.PERMANENT -> return false
    }
    return true
  }

  /** Upisan je novi token: pokušaj odmah, bez zaostalog odmaka. */
  fun resume(nowMs: Long) {
    failures = 0
    nextAtMs = nowMs
  }
}
