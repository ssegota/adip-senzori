package hr.adip.senzori.ui.level

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import hr.adip.senzori.data.AppSettings
import hr.adip.senzori.data.SettingsRepository
import hr.adip.senzori.sensor.SensorEngine
import hr.adip.senzori.ui.components.fmt
import hr.adip.senzori.ui.components.select
import hr.adip.senzori.ui.theme.ChartColors
import hr.adip.senzori.ui.theme.NumberStyles
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

private const val LEVEL_TOL = 0.5f
private const val CIRCLE_RANGE = 10f // ° na rubu kruga
private const val BAR_RANGE = 10f

/** Sirovi kutovi (°) iz vektora gravitacije: x = nagib oko osi y (roll), y = nagib oko osi x (pitch). */
private fun rawAngles(g: FloatArray): Pair<Float, Float> {
  val gx = g.getOrElse(0) { 0f }
  val gy = g.getOrElse(1) { 0f }
  val gz = g.getOrElse(2) { 9.81f }
  val pitch = Math.toDegrees(atan2(gy.toDouble(), sqrt((gx * gx + gz * gz).toDouble()))).toFloat()
  val roll = Math.toDegrees(atan2(gx.toDouble(), sqrt((gy * gy + gz * gz).toDouble()))).toFloat()
  return pitch to roll
}

@Composable
fun LevelScreen(
  engine: SensorEngine,
  settingsRepo: SettingsRepository,
  settings: AppSettings,
  contentPadding: PaddingValues,
) {
  LifecycleResumeEffect(engine) {
    engine.start()
    onPauseOrDispose { }
  }
  val scope = rememberCoroutineScope()
  val gravity by engine.state.select { it.gravity }
  val (rawPitch, rawRoll) = rawAngles(gravity)
  val pitch = rawPitch - settings.levelOffsetPitch
  val roll = rawRoll - settings.levelOffsetRoll
  val isLevel = abs(pitch) < LEVEL_TOL && abs(roll) < LEVEL_TOL

  val haptic = LocalHapticFeedback.current
  LaunchedEffect(isLevel) { if (isLevel) haptic.performHapticFeedback(HapticFeedbackType.Confirm) }

  val springSpec = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)
  val aRoll by animateFloatAsState(roll.coerceIn(-90f, 90f), springSpec, label = "roll")
  val aPitch by animateFloatAsState(pitch.coerceIn(-90f, 90f), springSpec, label = "pitch")
  val accent by animateColorAsState(if (isLevel) ChartColors.Level else MaterialTheme.colorScheme.secondary, label = "accent")

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(contentPadding)
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
      AngleReadout("X", roll, isLevel)
      AngleReadout("Y", pitch, isLevel)
    }

    CircleLevel(
      dx = aRoll, dy = -aPitch, accent = accent, isLevel = isLevel,
      modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).aspectRatio(1f),
    )

    BarLevel("X", aRoll, accent, Modifier.fillMaxWidth().widthIn(max = 420.dp))
    BarLevel("Y", -aPitch, accent, Modifier.fillMaxWidth().widthIn(max = 420.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      FilledTonalButton(onClick = {
        scope.launch { settingsRepo.update { it.copy(levelOffsetPitch = rawPitch, levelOffsetRoll = rawRoll) } }
      }) {
        Icon(Icons.Outlined.CenterFocusStrong, null)
        Spacer(Modifier.width(6.dp))
        Text("Kalibriraj")
      }
      OutlinedButton(onClick = {
        scope.launch { settingsRepo.update { it.copy(levelOffsetPitch = 0f, levelOffsetRoll = 0f) } }
      }) {
        Icon(Icons.Outlined.RestartAlt, null)
        Spacer(Modifier.width(6.dp))
        Text("Poništi")
      }
    }
    val calibrated = settings.levelOffsetPitch != 0f || settings.levelOffsetRoll != 0f
    Text(
      if (calibrated) "Kalibracija: X ${fmt(settings.levelOffsetRoll, 2)}°, Y ${fmt(settings.levelOffsetPitch, 2)}°"
      else "Položi uređaj na ravnu površinu i pritisni Kalibriraj za poništavanje odstupanja senzora.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun AngleReadout(label: String, deg: Float, isLevel: Boolean) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
      fmt(deg, 1) + "°",
      style = NumberStyles.huge,
      color = if (isLevel) ChartColors.Level else MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun CircleLevel(dx: Float, dy: Float, accent: Color, isLevel: Boolean, modifier: Modifier) {
  val ring = MaterialTheme.colorScheme.outlineVariant
  val face = MaterialTheme.colorScheme.surfaceContainerHigh
  val cross = MaterialTheme.colorScheme.outline
  Canvas(modifier) {
    val c = center
    val r = size.minDimension / 2f - 4.dp.toPx()
    val bubbleR = r * 0.14f
    val usable = r - bubbleR
    drawCircle(face, r, c)
    drawCircle(ring, r, c, style = Stroke(2.dp.toPx()))
    for (deg in listOf(2f, 5f)) drawCircle(ring, usable * deg / CIRCLE_RANGE + bubbleR, c, style = Stroke(1.dp.toPx()))
    drawLine(cross, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
    drawLine(cross, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
    // centralni prsten ±0.5°
    val tolR = bubbleR + usable * LEVEL_TOL / CIRCLE_RANGE
    drawCircle(accent, tolR, c, style = Stroke(2.5.dp.toPx()))

    var bx = dx / CIRCLE_RANGE * usable
    var by = dy / CIRCLE_RANGE * usable
    val d = hypot(bx, by)
    if (d > usable) { bx *= usable / d; by *= usable / d }
    val bc = Offset(c.x + bx, c.y + by)
    drawCircle(
      Brush.radialGradient(
        listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = if (isLevel) 0.95f else 0.85f)),
        center = Offset(bc.x - bubbleR * 0.3f, bc.y - bubbleR * 0.3f),
        radius = bubbleR * 1.3f,
      ),
      bubbleR, bc,
    )
    drawCircle(Color.White.copy(alpha = 0.6f), bubbleR * 0.25f, Offset(bc.x - bubbleR * 0.35f, bc.y - bubbleR * 0.35f))
  }
}

@Composable
private fun BarLevel(label: String, value: Float, accent: Color, modifier: Modifier) {
  val track = MaterialTheme.colorScheme.surfaceContainerHigh
  val mark = MaterialTheme.colorScheme.outline
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(20.dp))
    Canvas(Modifier.weight(1f).height(36.dp)) {
      val h = size.height
      val w = size.width
      val rad = CornerRadius(h / 2f, h / 2f)
      drawRoundRect(track, size = size, cornerRadius = rad)
      val bubbleW = h * 1.6f
      val usable = (w - bubbleW) / 2f
      val tolW = usable * LEVEL_TOL / BAR_RANGE
      drawLine(accent, Offset(w / 2f - bubbleW / 2f - tolW, 4f), Offset(w / 2f - bubbleW / 2f - tolW, h - 4f), 2.dp.toPx())
      drawLine(accent, Offset(w / 2f + bubbleW / 2f + tolW, 4f), Offset(w / 2f + bubbleW / 2f + tolW, h - 4f), 2.dp.toPx())
      for (k in listOf(-5f, 5f)) {
        val x = w / 2f + usable * k / BAR_RANGE
        drawLine(mark, Offset(x, h * 0.3f), Offset(x, h * 0.7f), 1f)
      }
      val x = w / 2f + (value / BAR_RANGE).coerceIn(-1f, 1f) * usable
      drawRoundRect(
        accent.copy(alpha = 0.85f),
        topLeft = Offset(x - bubbleW / 2f, 4f),
        size = Size(bubbleW, h - 8f),
        cornerRadius = CornerRadius((h - 8f) / 2f, (h - 8f) / 2f),
      )
    }
  }
}
