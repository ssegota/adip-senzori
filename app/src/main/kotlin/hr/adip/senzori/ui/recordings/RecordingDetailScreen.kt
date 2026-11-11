package hr.adip.senzori.ui.recordings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hr.adip.senzori.recording.RecordingData
import hr.adip.senzori.recording.RecordingRepository
import hr.adip.senzori.ui.components.LegendDot
import hr.adip.senzori.ui.components.LineChart
import hr.adip.senzori.ui.components.SensorCard
import hr.adip.senzori.ui.components.fmt
import hr.adip.senzori.ui.components.formatBytes
import hr.adip.senzori.ui.components.formatDuration
import hr.adip.senzori.ui.components.xZoomPan
import hr.adip.senzori.ui.theme.ChartColors
import hr.adip.senzori.ui.theme.NumberStyles
import java.io.File

private class Channel(val key: String, val label: String, val color: Color)

private class Group(val title: String, val icon: ImageVector, val unit: String, val channels: List<Channel>, val minSpan: Float)

private val KNOWN = listOf(
  Group("Akceleracija", Icons.Outlined.Vibration, "m/s²", listOf(Channel("ax", "x", ChartColors.X), Channel("ay", "y", ChartColors.Y), Channel("az", "z", ChartColors.Z)), 0.5f),
  Group("Svjetlo", Icons.Outlined.LightMode, "lx", listOf(Channel("lux", "lux", ChartColors.Lux)), 2f),
  Group("Infrazvuk", Icons.Outlined.GraphicEq, "", listOf(Channel("infra", "infra", ChartColors.Infra)), 1e-5f),
  Group("Tlak", Icons.Outlined.Compress, "hPa", listOf(Channel("p_hpa", "p", ChartColors.Pressure)), 0.05f),
)

private class Stats(val min: Float, val max: Float, val mean: Float, val valid: Int)

private fun stats(a: FloatArray): Stats {
  var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY; var sum = 0.0; var n = 0
  for (v in a) if (v.isFinite()) { if (v < lo) lo = v; if (v > hi) hi = v; sum += v; n++ }
  return if (n == 0) Stats(Float.NaN, Float.NaN, Float.NaN, 0) else Stats(lo, hi, (sum / n).toFloat(), n)
}

@Composable
fun RecordingDetailScreen(repo: RecordingRepository, file: File, contentPadding: PaddingValues, onBack: () -> Unit) {
  var data by remember(file) { mutableStateOf<RecordingData?>(null) }
  var error by remember(file) { mutableStateOf<String?>(null) }
  LaunchedEffect(file) {
    runCatching { repo.load(file) }
      .onSuccess { data = it }
      .onFailure { error = it.message ?: it.javaClass.simpleName }
  }
  val window = remember(file) { mutableStateOf(0f..1f) }

  Column(Modifier.fillMaxSize().padding(contentPadding)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Natrag") }
      Text(file.nameWithoutExtension, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      val zoomed = window.value.start > 0f || window.value.endInclusive < 1f
      if (zoomed) IconButton(onClick = { window.value = 0f..1f }) { Icon(Icons.Outlined.ZoomOutMap, "Poništi zum") }
    }
    val d = data
    when {
      error != null -> Text("Greška pri čitanju: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
      d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      else -> DetailContent(d, window, repo)
    }
  }
}

@Composable
private fun DetailContent(d: RecordingData, window: androidx.compose.runtime.MutableState<ClosedFloatingPointRange<Float>>, repo: RecordingRepository) {
  val durS = if (d.t.size > 1) (d.t.last() - d.t.first()) / 1000f else d.info.durationS.toFloat()
  val relT = remember(d) { if (d.t.isEmpty()) FloatArray(0) else FloatArray(d.t.size) { (d.t[it] - d.t.first()) / 1000f } }
  val infraUnit = d.info.meta["units"]?.let { if (it.contains("infra=Pa")) "Pa" else "FS" } ?: ""
  val groups = remember(d) {
    val known = KNOWN.mapNotNull { g ->
      val ch = g.channels.filter { c -> d.values[c.key]?.any { it.isFinite() } == true }
      if (ch.isEmpty()) null else Group(g.title, g.icon, if (g.title == "Infrazvuk") infraUnit else g.unit, ch, g.minSpan)
    }
    val knownKeys = KNOWN.flatMap { g -> g.channels.map { it.key } }.toSet() + "t_ms"
    val extra = d.values.keys.filter { it !in knownKeys && d.values[it]?.any { v -> v.isFinite() } == true }
      .map { Group(it, Icons.AutoMirrored.Outlined.ShowChart, "", listOf(Channel(it, it, ChartColors.Pressure)), 0f) }
    known + extra
  }

  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      "${formatDuration(d.info.durationS.toLong())} · ${d.info.rows} redaka · ${d.info.sampleRateHz} Hz · ${formatBytes(d.info.sizeBytes)}",
      style = NumberStyles.small,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Zum: dva prsta · pomak: vodoravni potez", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

    d.info.audioFile?.let { wav -> AudioCard(wav, repo) }

    for (g in groups) {
      SensorCard(g.title, g.icon) {
        LineChart(
          series = g.channels.map { d.values[it.key] ?: FloatArray(0) },
          colors = g.channels.map { it.color },
          modifier = Modifier.fillMaxWidth().height(170.dp).xZoomPan(window),
          yLabel = g.unit.ifEmpty { null },
          xStart = 0f,
          xEnd = durS,
          t = relT,
          window = window.value,
          minSpan = g.minSpan,
        )
        for (c in g.channels) {
          val st = remember(d, c.key) { stats(d.values[c.key] ?: FloatArray(0)) }
          val dec = if (c.key == "infra") 5 else if (c.key == "p_hpa") 2 else 3
          Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(c.color, c.label, Modifier.width(56.dp))
            Text(
              "min ${fmt(st.min, dec)}   max ${fmt(st.max, dec)}   sr. ${fmt(st.mean, dec)}",
              style = NumberStyles.small,
            )
          }
        }
      }
    }

    if (d.info.meta.isNotEmpty()) {
      SensorCard("Metapodaci", Icons.Outlined.Info) {
        for ((k, v) in d.info.meta) {
          Row {
            Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
            Text(v, style = NumberStyles.mono)
          }
        }
      }
    }
    Text(
      "Grafovi prikazuju prorijeđene podatke (min/max po odsječku); srednja vrijednost je približna.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(16.dp))
  }
}

/** Reprodukcija pripadnog WAV-a (MediaPlayer se oslobađa pri izlasku s ekrana). */
@Composable
private fun AudioCard(wav: File, repo: RecordingRepository) {
  val context = LocalContext.current
  var player by remember(wav) { mutableStateOf<MediaPlayer?>(null) }
  var error by remember(wav) { mutableStateOf<String?>(null) }
  var playing by remember(wav) { mutableStateOf(false) }
  var posMs by remember(wav) { mutableIntStateOf(0) }
  var durMs by remember(wav) { mutableIntStateOf(0) }
  var dragging by remember(wav) { mutableStateOf<Float?>(null) }

  DisposableEffect(wav) {
    val mp = MediaPlayer()
    try {
      mp.setDataSource(wav.absolutePath)
      mp.prepare()
      mp.setOnCompletionListener { playing = false; posMs = 0; it.seekTo(0) }
      durMs = mp.duration.coerceAtLeast(0)
      player = mp
    } catch (e: Exception) {
      error = e.message ?: e.javaClass.simpleName
      mp.release()
    }
    onDispose {
      player = null
      runCatching { mp.release() }
    }
  }
  LaunchedEffect(playing, player) {
    val mp = player ?: return@LaunchedEffect
    while (playing) {
      posMs = runCatching { mp.currentPosition }.getOrDefault(posMs)
      delay(200)
    }
  }

  SensorCard(
    "Zvuk",
    Icons.Outlined.Mic,
    trailing = {
      IconButton(onClick = {
        try {
          context.startActivity(Intent.createChooser(repo.shareIntent(wav), "Podijeli zvuk").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) { }
      }) { Icon(Icons.Outlined.Share, "Podijeli WAV") }
    },
  ) {
    val err = error
    if (err != null) {
      Text("Reprodukcija nije moguća: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      return@SensorCard
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      FilledTonalIconButton(
        enabled = player != null,
        onClick = {
          val mp = player ?: return@FilledTonalIconButton
          if (playing) { mp.pause(); playing = false } else { mp.start(); playing = true }
        },
      ) {
        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pauza" else "Reproduciraj")
      }
      Spacer(Modifier.width(8.dp))
      Slider(
        value = dragging ?: if (durMs > 0) posMs.toFloat() / durMs else 0f,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
          val f = dragging
          dragging = null
          if (f != null && durMs > 0) {
            val target = (f * durMs).toInt()
            player?.seekTo(target)
            posMs = target
          }
        },
        enabled = player != null && durMs > 0,
        modifier = Modifier.weight(1f),
      )
    }
    val shown = dragging?.let { (it * durMs).toLong() } ?: posMs.toLong()
    Text(
      "${formatDuration(shown / 1000)} / ${formatDuration(durMs / 1000L)} · ${formatBytes(wav.length())} · ${wav.name}",
      style = NumberStyles.small,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
