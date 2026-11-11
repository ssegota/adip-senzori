package hr.adip.senzori.ui.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import hr.adip.senzori.data.AppSettings
import hr.adip.senzori.sensor.EngineState
import hr.adip.senzori.sensor.SensorEngine
import hr.adip.senzori.ui.components.BarSpectrum
import hr.adip.senzori.ui.components.LegendDot
import hr.adip.senzori.ui.components.LineChart
import hr.adip.senzori.ui.components.SensorCard
import hr.adip.senzori.ui.components.fmt
import hr.adip.senzori.ui.components.formatDuration
import hr.adip.senzori.ui.components.rememberElapsedSeconds
import hr.adip.senzori.ui.components.select
import hr.adip.senzori.ui.theme.ChartColors
import hr.adip.senzori.ui.theme.NumberStyles
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

@Composable
fun LiveScreen(engine: SensorEngine, settings: AppSettings, contentPadding: PaddingValues) {
  LifecycleResumeEffect(engine) {
    engine.start()
    onPauseOrDispose { }
  }
  val state = engine.state
  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(contentPadding)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    RecordPanel(engine)
    AccelCard(state)
    LightCard(state)
    InfraCard(engine, settings)
    PressureCard(state)
    Spacer(Modifier.height(8.dp))
  }
}

// ---------------------------------------------------------------- snimanje

private data class RecUi(
  val recording: Boolean,
  val startedMs: Long,
  val rows: Long,
  val rate: Int,
  val running: Boolean,
  val upload: hr.adip.senzori.sensor.UploadInfo,
)

@Composable
private fun RecordPanel(engine: SensorEngine) {
  val context = LocalContext.current
  val ui by engine.state.select { RecUi(it.recording, it.recordingStartedMs, it.recordedRows, it.sampleRateHz, it.running, it.upload) }
  val elapsed by rememberElapsedSeconds(ui.recording, ui.startedMs)

  val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    // neovisno o odgovoru – obavijest nije nužna za snimanje
    engine.startRecording(context)
  }

  fun onStart() {
    if (Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      engine.startRecording(context)
    }
  }

  val container by animateColorAsState(
    if (ui.recording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
    label = "recContainer",
  )
  val onContainer = if (ui.recording) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer

  ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = container)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
            if (ui.recording) "Snimanje u tijeku" else if (ui.running) "Mjerenje uživo" else "Mjerenje zaustavljeno",
            style = MaterialTheme.typography.labelLarge,
            color = onContainer,
          )
          Text(
            formatDuration(if (ui.recording) elapsed else 0),
            style = NumberStyles.big,
            color = onContainer,
          )
          Text(
            if (ui.recording) "${ui.rows} redaka" else "Pritisni Start za CSV zapis",
            style = NumberStyles.small,
            color = onContainer.copy(alpha = 0.8f),
          )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
          AssistChip(
            onClick = {},
            label = { Text("${ui.rate} Hz", style = NumberStyles.small) },
            leadingIcon = { Icon(Icons.Outlined.Speed, null, Modifier.size(18.dp)) },
          )
          if (ui.recording) {
            Button(
              onClick = { engine.stopRecording(context) },
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
              contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            ) {
              Icon(Icons.Filled.Stop, null)
              Spacer(Modifier.width(6.dp))
              Text("Stop")
            }
          } else {
            Button(onClick = ::onStart, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
              Icon(Icons.Filled.FiberManualRecord, null, tint = Color(0xFFFF5A5F))
              Spacer(Modifier.width(6.dp))
              Text("Start")
            }
          }
        }
      }
      AnimatedVisibility(ui.recording && ui.upload.enabled) { UploadLine(ui.upload, onContainer) }
    }
  }
}

@Composable
private fun UploadLine(u: hr.adip.senzori.sensor.UploadInfo, color: Color) {
  val icon = when {
    u.lastError != null -> Icons.Outlined.CloudOff
    u.lastOkAtMs > 0 -> Icons.Outlined.CloudDone
    else -> Icons.Outlined.CloudUpload
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    val text = buildString {
      append("Poslano ${u.sentRows}")
      if (u.pendingRows > 0) append(" · čeka ${u.pendingRows}")
      if (u.lastError != null) append(" · greška: ${u.lastError}")
      else if (u.lastOkAtMs > 0) append(" · OK prije ${((System.currentTimeMillis() - u.lastOkAtMs) / 1000).coerceAtLeast(0)} s")
    }
    Text(text, style = NumberStyles.small, color = color, maxLines = 2)
  }
}

// ---------------------------------------------------------------- akceleracija

@Composable
private fun AccelCard(state: StateFlow<EngineState>) {
  SensorCard("Akceleracija", Icons.Outlined.Vibration) {
    val latest by state.select { it.latest }
    val s = latest
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      AxisValue("x", s?.ax, ChartColors.X)
      AxisValue("y", s?.ay, ChartColors.Y)
      AxisValue("z", s?.az, ChartColors.Z)
      val mag = s?.let { sqrt(it.ax * it.ax + it.ay * it.ay + it.az * it.az) }
      AxisValue("|a|", mag, MaterialTheme.colorScheme.onSurface)
    }
    val history by state.select { it.history }
    val h = history
    LineChart(
      series = listOf(h.ax, h.ay, h.az),
      colors = listOf(ChartColors.X, ChartColors.Y, ChartColors.Z),
      modifier = Modifier.fillMaxWidth().height(150.dp),
      yLabel = "m/s²",
      xStart = if (h.size > 1) -(h.tMs.last() - h.tMs.first()) / 1000f else null,
      xEnd = if (h.size > 1) 0f else null,
      minSpan = 0.5f,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      LegendDot(ChartColors.X, "x"); LegendDot(ChartColors.Y, "y"); LegendDot(ChartColors.Z, "z")
    }
  }
}

@Composable
private fun AxisValue(label: String, v: Float?, color: Color) {
  Column(horizontalAlignment = Alignment.Start) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
    Text(fmt(v, 2), style = NumberStyles.medium)
  }
}

// ---------------------------------------------------------------- svjetlo

@Composable
private fun LightCard(state: StateFlow<EngineState>) {
  val hasLight by state.select { it.hasLight }
  SensorCard("Luksmetar", Icons.Outlined.LightMode) {
    if (!hasLight) {
      Text("Nije dostupno – uređaj nema senzor svjetla.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      return@SensorCard
    }
    val lux by state.select { it.latest?.lux }
    Row(verticalAlignment = Alignment.Bottom) {
      Text(fmt(lux, if ((lux ?: 0f) < 100f) 1 else 0), style = NumberStyles.big)
      Spacer(Modifier.width(6.dp))
      Text("lx", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
    val history by state.select { it.history }
    LineChart(
      series = listOf(history.lux),
      colors = listOf(ChartColors.Lux),
      modifier = Modifier.fillMaxWidth().height(80.dp),
      minSpan = 2f,
      includeZero = true,
    )
  }
}

// ---------------------------------------------------------------- infrazvuk

@Composable
private fun InfraCard(engine: SensorEngine, settings: AppSettings) {
  val context = LocalContext.current
  val state = engine.state
  var granted by remember {
    mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
  }
  LifecycleResumeEffect(Unit) {
    granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    onPauseOrDispose { }
  }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    granted = ok
    if (ok) engine.restartInfra()
  }
  val info by state.select { it.infra }
  SensorCard("Infrazvuk", Icons.Outlined.GraphicEq) {
    if (!settings.infraEnabled) {
      Text("Isključeno u postavkama.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      return@SensorCard
    }
    if (!granted) {
      Text(
        "Za mjerenje infrazvuka potreban je pristup mikrofonu. Zvuk se ne snima niti šalje – koristi se samo niskofrekventni dio signala.",
        style = MaterialTheme.typography.bodyMedium,
      )
      FilledTonalButton(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
        Icon(Icons.Outlined.Mic, null)
        Spacer(Modifier.width(6.dp))
        Text("Dopusti mikrofon")
      }
      return@SensorCard
    }
    val i = info
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
      Column {
        Row(verticalAlignment = Alignment.Bottom) {
          Text(if (i.levelDb.isFinite()) fmt(i.levelDb, 1) else "—", style = NumberStyles.big)
          Spacer(Modifier.width(6.dp))
          Text(
            if (i.calibrated) "dB SPL" else "dBFS",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
          )
        }
        Text(if (i.calibrated) "kalibrirano" else "relativno (nekalibrirano)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Column(horizontalAlignment = Alignment.End) {
        Text("RMS ${fmt(i.rms, if (i.calibrated) 3 else 5)} ${if (i.calibrated) "Pa" else "FS"}", style = NumberStyles.small)
        Text("vrh ${fmt(i.peakHz, 2)} Hz", style = NumberStyles.small)
      }
    }
    Text("Spektar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    BarSpectrum(
      spectrum = i.spectrum,
      maxHz = i.spectrumMaxHz,
      color = ChartColors.Infra,
      peakHz = i.peakHz,
      modifier = Modifier.fillMaxWidth().height(90.dp),
    )
    Text("Signal (0,5–20 Hz)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val history by state.select { it.history }
    LineChart(
      series = listOf(history.infra),
      colors = listOf(ChartColors.Infra),
      modifier = Modifier.fillMaxWidth().height(100.dp),
      yLabel = if (i.calibrated) "Pa" else "FS",
      minSpan = if (i.calibrated) 0.01f else 1e-5f,
    )
    Text(i.status, style = NumberStyles.mono, color = if (i.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
  }
}

// ---------------------------------------------------------------- tlak

@Composable
private fun PressureCard(state: StateFlow<EngineState>) {
  val has by state.select { it.hasPressure }
  if (!has) return
  val p by state.select { it.latest?.pHpa }
  SensorCard("Barometar", Icons.Outlined.Compress) {
    Row(verticalAlignment = Alignment.Bottom) {
      Text(fmt(p, 2), style = NumberStyles.medium)
      Spacer(Modifier.width(6.dp))
      Text("hPa", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
