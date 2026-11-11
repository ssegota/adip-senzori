package hr.adip.senzori.ui.sleep

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import hr.adip.senzori.sensor.SensorEngine
import hr.adip.senzori.ui.components.formatDuration
import hr.adip.senzori.ui.components.nowLike
import hr.adip.senzori.ui.components.select
import hr.adip.senzori.ui.theme.SleepRed
import hr.adip.senzori.ui.theme.SleepRedDim
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is android.content.ContextWrapper -> baseContext.findActivity()
  else -> null
}

/**
 * Noćni način: crni zaslon (OLED), minimalna svjetlina, skrivene sistemske trake.
 * Mjerenje i snimanje se nastavljaju. Dvostruki dodir (ili natrag) za izlaz.
 */
@Composable
fun SleepOverlay(engine: SensorEngine, onExit: () -> Unit) {
  val activity = LocalContext.current.findActivity()
  val view = LocalView.current
  DisposableEffect(activity) {
    val window = activity?.window
    val controller = window?.let { WindowCompat.getInsetsController(it, view) }
    window?.let {
      it.attributes = it.attributes.apply { screenBrightness = 0.01f }
      it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller?.hide(WindowInsetsCompat.Type.systemBars())
    onDispose {
      window?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
      // FLAG_KEEP_SCREEN_ON vraća efekt postavke keepScreenOn u AppRoot
      controller?.show(WindowInsetsCompat.Type.systemBars())
    }
  }
  BackHandler(onBack = onExit)

  val recording by engine.state.select { it.recording }
  val startedMs by engine.state.select { it.recordingStartedMs }
  val mode by engine.state.select { it.sessionMode }
  val now by produceState(System.currentTimeMillis()) {
    while (true) { value = System.currentTimeMillis(); delay(1000L - value % 1000L) }
  }
  val clock = remember24h.format(Date(now))

  BoxWithConstraints(
    Modifier
      .fillMaxSize()
      .background(Color.Black)
      .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onExit() }) },
  ) {
    // lagani pomak položaja svake minute (zaštita OLED-a od urezivanja)
    val minute = (now / 60_000L).toInt()
    val dx = (((minute * 37) % 61) - 30).dp
    val dy = (((minute * 53) % 81) - 40).dp
    Column(
      Modifier.align(Alignment.Center).offset(dx, dy),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(clock, style = TextStyle(color = SleepRed, fontSize = 40.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"))
      if (recording) {
        val elapsed = ((nowLike(startedMs) - startedMs) / 1000).coerceAtLeast(0)
        Text(
          when (mode) {
            hr.adip.senzori.data.SessionMode.STREAM -> "● STREAM ${formatDuration(elapsed)}"
            hr.adip.senzori.data.SessionMode.BOTH -> "● STREAM + REC ${formatDuration(elapsed)}"
            else -> "● REC ${formatDuration(elapsed)}"
          },
          style = TextStyle(color = SleepRed, fontSize = 14.sp, fontFeatureSettings = "tnum"),
        )
      }
    }
    Box(Modifier.align(Alignment.BottomCenter).offset(y = (-32).dp)) {
      Text("Dodirni dvaput za izlaz", style = TextStyle(color = SleepRedDim, fontSize = 11.sp))
    }
  }
}

private val remember24h = SimpleDateFormat("HH:mm", Locale.ROOT)
