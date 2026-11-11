package hr.adip.senzori.sensor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Biquad filtar (RBJ cookbook), u dvostrukoj preciznosti zbog niskih fc/fs omjera. */
class Biquad private constructor(
  private val b0: Double, private val b1: Double, private val b2: Double,
  private val a1: Double, private val a2: Double,
) {
  private var z1 = 0.0
  private var z2 = 0.0

  fun process(x: Double): Double {
    // Transponirani direktni oblik II
    val y = b0 * x + z1
    z1 = b1 * x - a1 * y + z2
    z2 = b2 * x - a2 * y
    return y
  }

  companion object {
    fun lowPass(fs: Double, fc: Double, q: Double): Biquad {
      val w = 2 * PI * fc / fs
      val alpha = sin(w) / (2 * q)
      val c = cos(w)
      val a0 = 1 + alpha
      return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    fun highPass(fs: Double, fc: Double, q: Double): Biquad {
      val w = 2 * PI * fc / fs
      val alpha = sin(w) / (2 * q)
      val c = cos(w)
      val a0 = 1 + alpha
      return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }
  }
}

/** Kaskada biquadova: Butterworth 4. reda = dva biquada s Q 0.5412 i 1.3066. */
class FilterChain(private val stages: List<Biquad>) {
  fun process(x: Double): Double {
    var v = x
    for (s in stages) v = s.process(v)
    return v
  }

  companion object {
    private val BW4_Q = doubleArrayOf(0.5411961, 1.3065630)
    fun butterLowPass4(fs: Double, fc: Double) = FilterChain(BW4_Q.map { Biquad.lowPass(fs, fc, it) })
    fun butterHighPass2(fs: Double, fc: Double) = FilterChain(listOf(Biquad.highPass(fs, fc, 0.7071068)))
  }
}

/** Decimator: anti-aliasing niskopropusni filtar + uzimanje svakog N-tog uzorka. */
class Decimator(fsIn: Double, val factor: Int, cutoffHz: Double) {
  private val lp = FilterChain.butterLowPass4(fsIn, cutoffHz)
  private var n = 0
  val fsOut = fsIn / factor

  /** Vraća NaN kad nema izlaznog uzorka. */
  fun push(x: Double): Double {
    val y = lp.process(x)
    n++
    if (n >= factor) {
      n = 0
      return y
    }
    return Double.NaN
  }
}

object Fft {
  /** Amplitudni spektar realnog signala (Hann prozor). n mora biti potencija broja 2. Vraća n/2 binova. */
  fun magnitude(signal: FloatArray): FloatArray {
    val n = signal.size
    require(n > 0 && n and (n - 1) == 0) { "n mora biti potencija broja 2" }
    val re = DoubleArray(n)
    val im = DoubleArray(n)
    var mean = 0.0
    for (v in signal) mean += v
    mean /= n
    for (i in 0 until n) {
      val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
      re[i] = (signal[i] - mean) * w
    }
    // bit-reversal
    var j = 0
    for (i in 1 until n) {
      var bit = n shr 1
      while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
      j = j xor bit
      if (i < j) {
        val tr = re[i]; re[i] = re[j]; re[j] = tr
        val ti = im[i]; im[i] = im[j]; im[j] = ti
      }
    }
    var len = 2
    while (len <= n) {
      val ang = -2 * PI / len
      val wr = cos(ang); val wi = sin(ang)
      var i = 0
      while (i < n) {
        var cr = 1.0; var ci = 0.0
        for (k in 0 until len / 2) {
          val ur = re[i + k]; val ui = im[i + k]
          val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
          val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
          re[i + k] = ur + vr; im[i + k] = ui + vi
          re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
          val nr = cr * wr - ci * wi
          ci = cr * wi + ci * wr
          cr = nr
        }
        i += len
      }
      len = len shl 1
    }
    return FloatArray(n / 2) { k -> (2 * sqrt(re[k] * re[k] + im[k] * im[k]) / n).toFloat() }
  }
}
