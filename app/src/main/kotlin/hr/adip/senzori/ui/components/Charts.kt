package hr.adip.senzori.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** "Lijep" korak mreže (1, 2, 5 × 10^n) za zadani raspon i približan broj crta. */
internal fun niceStep(range: Float, targetTicks: Int = 4): Float {
  if (range <= 0f || !range.isFinite()) return 1f
  val raw = range / targetTicks
  val mag = 10f.pow(floor(log10(raw)))
  val norm = raw / mag
  val nice = when {
    norm < 1.5f -> 1f
    norm < 3f -> 2f
    norm < 7f -> 5f
    else -> 10f
  }
  return nice * mag
}

internal fun formatTick(v: Float, step: Float): String {
  val decimals = when {
    step >= 1f -> 0
    else -> min(4, ceil(-log10(step)).toInt())
  }
  val r = if (abs(v) < step * 1e-3f) 0f else v
  // Vrlo mali koraci (npr. nekalibrirani infrazvuk ~1e-5 FS): znanstveni zapis
  if (step < 1e-3f) {
    if (r == 0f) return "0"
    val exp = floor(log10(step)).toInt()
    val m = r / 10f.pow(exp)
    return String.format(HR, "%.0fe%d", m, exp)
  }
  return if (decimals == 0) r.roundToInt().toString() else String.format(HR, "%.${decimals}f", r)
}

/**
 * Jednostavan linijski graf na Canvasu s automatskim skaliranjem i mrežom.
 *
 * @param series nizovi jednake duljine, ravnomjerno raspoređeni u vremenu; NaN = prekid linije
 * @param xStart/xEnd vremenska os (s) koja odgovara prvom/zadnjem uzorku – za oznake osi
 * @param window vidljivi dio [0..1] (zum po x)
 * @param minSpan minimalni raspon y (da se šum ne povećava preko cijelog grafa)
 */
@Composable
fun LineChart(
  series: List<FloatArray>,
  colors: List<Color>,
  modifier: Modifier = Modifier,
  yLabel: String? = null,
  xStart: Float? = null,
  xEnd: Float? = null,
  xUnit: String = "s",
  window: ClosedFloatingPointRange<Float> = 0f..1f,
  minSpan: Float = 0f,
  includeZero: Boolean = false,
) {
  val measurer = rememberTextMeasurer()
  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val labelStyle = remember(labelColor) { TextStyle(fontSize = 10.sp, color = labelColor, fontFeatureSettings = "tnum") }

  Canvas(modifier) {
    val n = series.maxOfOrNull { it.size } ?: 0
    val leftPad = 40.dp.toPx()
    val bottomPad = if (xStart != null && xEnd != null) 16.dp.toPx() else 4.dp.toPx()
    val topPad = 6.dp.toPx()
    val plotW = size.width - leftPad - 4.dp.toPx()
    val plotH = size.height - topPad - bottomPad
    if (plotW <= 0f || plotH <= 0f) return@Canvas

    val i0 = if (n > 1) floor(window.start * (n - 1)).toInt().coerceIn(0, n - 1) else 0
    val i1 = if (n > 1) ceil(window.endInclusive * (n - 1)).toInt().coerceIn(i0, n - 1) else 0

    // Autoskaliranje na vidljivom dijelu
    var lo = Float.POSITIVE_INFINITY
    var hi = Float.NEGATIVE_INFINITY
    for (s in series) {
      val e = min(i1, s.size - 1)
      for (i in i0..e) {
        val v = s[i]
        if (v.isFinite()) { if (v < lo) lo = v; if (v > hi) hi = v }
      }
    }
    if (!lo.isFinite()) { lo = 0f; hi = 1f }
    if (includeZero) { lo = min(lo, 0f); hi = max(hi, 0f) }
    val span0 = hi - lo
    val needSpan = max(minSpan, max(span0, max(abs(hi), 1e-6f) * 1e-3f))
    if (span0 < needSpan) { val c = (hi + lo) / 2f; lo = c - needSpan / 2f; hi = c + needSpan / 2f }
    val pad = (hi - lo) * 0.08f
    lo -= pad; hi += pad

    fun yOf(v: Float) = topPad + plotH * (1f - (v - lo) / (hi - lo))

    // Mreža y + oznake
    val step = niceStep(hi - lo)
    var tick = ceil(lo / step) * step
    var guard = 0
    while (tick <= hi && guard++ < 20) {
      val y = yOf(tick)
      drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f)
      drawLabel(measurer, formatTick(tick, step), labelStyle, Offset(leftPad - 4.dp.toPx(), y), alignRight = true)
      tick += step
    }
    if (yLabel != null) {
      drawText(measurer, yLabel, Offset(leftPad + 4.dp.toPx(), topPad), labelStyle)
    }

    // Mreža x (vrijeme)
    if (xStart != null && xEnd != null && n > 1) {
      val vs = xStart + (xEnd - xStart) * window.start
      val ve = xStart + (xEnd - xStart) * window.endInclusive
      if (ve > vs) {
        val xs = niceStep(ve - vs, 5)
        var t = ceil(vs / xs) * xs
        var g = 0
        val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
        while (t <= ve + xs * 1e-3f && g++ < 30) {
          val x = leftPad + plotW * (t - vs) / (ve - vs)
          drawLine(gridColor, Offset(x, topPad), Offset(x, topPad + plotH), strokeWidth = 1f, pathEffect = dash)
          val lbl = formatTick(t, xs) + if (g == 1) " $xUnit" else ""
          val tl = measurer.measure(lbl, labelStyle)
          val lx = (x - tl.size.width / 2f).coerceIn(leftPad, size.width - tl.size.width)
          drawText(tl, topLeft = Offset(lx, topPad + plotH + 2.dp.toPx()))
          t += xs
        }
      }
    }

    // Linije
    if (i1 > i0) {
      val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
      val count = (i1 - i0).toFloat()
      series.forEachIndexed { si, s ->
        val path = Path()
        var pen = false
        val e = min(i1, s.size - 1)
        for (i in i0..e) {
          val v = s[i]
          if (!v.isFinite()) { pen = false; continue }
          val x = leftPad + plotW * (i - i0) / count
          val y = yOf(v)
          if (pen) path.lineTo(x, y) else { path.moveTo(x, y); pen = true }
        }
        drawPath(path, colors.getOrElse(si) { Color.Gray }, style = stroke)
      }
    }
  }
}

private fun DrawScope.drawLabel(m: TextMeasurer, text: String, style: TextStyle, anchor: Offset, alignRight: Boolean) {
  val l = m.measure(text, style)
  val x = if (alignRight) anchor.x - l.size.width else anchor.x
  val y = (anchor.y - l.size.height / 2f).coerceIn(0f, size.height - l.size.height)
  drawText(l, topLeft = Offset(x, y))
}

/** Stupčasti spektar 0..maxHz (vrijednosti normalizirane 0..1). */
@Composable
fun BarSpectrum(
  spectrum: FloatArray,
  maxHz: Float,
  color: Color,
  modifier: Modifier = Modifier,
  peakHz: Float? = null,
) {
  val measurer = rememberTextMeasurer()
  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val labelStyle = remember(labelColor) { TextStyle(fontSize = 10.sp, color = labelColor, fontFeatureSettings = "tnum") }
  Canvas(modifier) {
    val bottomPad = 16.dp.toPx()
    val plotH = size.height - bottomPad
    val w = size.width
    drawLine(gridColor, Offset(0f, plotH), Offset(w, plotH), 1f)
    val n = spectrum.size
    if (n > 0) {
      val maxV = max(1e-9f, spectrum.maxOrNull()?.takeIf { it > 1f } ?: 1f)
      val bw = w / n
      val gap = if (bw > 4f) bw * 0.18f else 0f
      for (i in 0 until n) {
        val v = (spectrum[i] / maxV).coerceIn(0f, 1f)
        if (!v.isFinite() || v <= 0f) continue
        val h = plotH * v
        val hz = maxHz * (i + 0.5f) / n
        val isPeak = peakHz != null && peakHz > 0f && abs(hz - peakHz) <= maxHz / n
        drawRect(
          color = if (isPeak) color else color.copy(alpha = 0.6f),
          topLeft = Offset(i * bw + gap / 2f, plotH - h),
          size = Size(bw - gap, h),
        )
      }
    }
    val step = if (maxHz <= 25f) 5f else niceStep(maxHz, 4)
    var f = 0f
    while (f <= maxHz + 1e-3f) {
      val x = w * f / maxHz
      val lbl = formatTick(f, step) + if (f >= maxHz - 1e-3f) " Hz" else ""
      val tl = measurer.measure(lbl, labelStyle)
      drawText(tl, topLeft = Offset((x - tl.size.width / 2f).coerceIn(0f, w - tl.size.width), plotH + 2.dp.toPx()))
      f += step
    }
  }
}

/**
 * Zum/pomak po x-osi: dva prsta = zum, horizontalni potez = pomak.
 * Vertikalni potezi se propuštaju roditelju (scroll).
 */
fun Modifier.xZoomPan(state: MutableState<ClosedFloatingPointRange<Float>>, minSpan: Float = 0.005f): Modifier =
  pointerInput(state) {
    awaitEachGesture {
      awaitFirstDown(requireUnconsumed = false)
      var locked = false
      var dx = 0f
      var dy = 0f
      val slop = viewConfiguration.touchSlop
      while (true) {
        val ev = awaitPointerEvent()
        if (ev.changes.none { it.pressed }) break
        if (ev.changes.any { it.isConsumed } && !locked) break
        val pressed = ev.changes.count { it.pressed }
        val pan = ev.calculatePan()
        val zoom = ev.calculateZoom()
        if (!locked) {
          if (pressed >= 2) locked = true
          else {
            dx += pan.x; dy += pan.y
            if (abs(dy) > slop && abs(dy) > abs(dx)) break
            if (abs(dx) > slop) locked = true
          }
        }
        if (locked) {
          val w = size.width.toFloat().coerceAtLeast(1f)
          val r = state.value
          val span = r.endInclusive - r.start
          val cx = (ev.calculateCentroid(useCurrent = true).x / w).coerceIn(0f, 1f)
          val newSpan = (span / zoom).coerceIn(minSpan, 1f)
          val anchor = r.start + cx * span
          val start = (anchor - cx * newSpan - pan.x / w * newSpan).coerceIn(0f, 1f - newSpan)
          state.value = start..(start + newSpan)
          ev.changes.forEach { if (it.positionChanged()) it.consume() }
        }
      }
    }
  }
