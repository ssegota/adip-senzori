package hr.adip.senzori.recording

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Zapisuje mono PCM 16-bit WAV. Zaglavlje se dovršava (veličine) pri [close]. */
class WavWriter(val file: File, private val sampleRate: Int) {
  private val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
  private var dataBytes = 0L
  private val bytes = ByteArray(16 * 1024)

  init { out.write(header(0)) }

  @Synchronized
  fun write(buf: ShortArray, n: Int) {
    var i = 0
    while (i < n) {
      val m = minOf(n - i, bytes.size / 2)
      for (k in 0 until m) {
        val v = buf[i + k].toInt()
        bytes[2 * k] = (v and 0xFF).toByte()
        bytes[2 * k + 1] = ((v shr 8) and 0xFF).toByte()
      }
      out.write(bytes, 0, m * 2)
      dataBytes += m * 2
      i += m
    }
  }

  @Synchronized
  fun close() {
    runCatching { out.flush(); out.close() }
    runCatching {
      RandomAccessFile(file, "rw").use { raf ->
        raf.seek(0)
        raf.write(header(dataBytes))
      }
    }
  }

  private fun header(data: Long): ByteArray {
    val h = ByteArray(44)
    fun str(o: Int, s: String) = s.forEachIndexed { i, c -> h[o + i] = c.code.toByte() }
    fun i32(o: Int, v: Long) { for (k in 0..3) h[o + k] = ((v shr (8 * k)) and 0xFF).toByte() }
    fun i16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v shr 8) and 0xFF).toByte() }
    val d = minOf(data, 0xFFFFFFFFL - 36)
    str(0, "RIFF"); i32(4, 36 + d); str(8, "WAVE")
    str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 1)
    i32(24, sampleRate.toLong()); i32(28, sampleRate * 2L); i16(32, 2); i16(34, 16)
    str(36, "data"); i32(40, d)
    return h
  }
}
