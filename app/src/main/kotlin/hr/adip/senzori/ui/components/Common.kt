package hr.adip.senzori.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.adip.senzori.sensor.EngineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

val HR: Locale = Locale.forLanguageTag("hr-HR")

fun fmt(v: Float?, decimals: Int = 2): String =
  if (v == null || !v.isFinite()) "—" else String.format(HR, "%.${decimals}f", v)

fun fmt(v: Double?, decimals: Int = 2): String =
  if (v == null || !v.isFinite()) "—" else String.format(HR, "%.${decimals}f", v)

fun formatDuration(totalSeconds: Long): String {
  val s = totalSeconds.coerceAtLeast(0)
  return String.format(HR, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
}

fun formatBytes(b: Long): String = when {
  b < 1024 -> "$b B"
  b < 1024 * 1024 -> String.format(HR, "%.1f kB", b / 1024.0)
  b < 1024L * 1024 * 1024 -> String.format(HR, "%.1f MB", b / 1024.0 / 1024.0)
  else -> String.format(HR, "%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
}

/**
 * Trenutačno vrijeme u istoj vremenskoj bazi kao [startedMs]: ako izgleda kao epoch (ms od 1970.)
 * koristi System.currentTimeMillis, inače SystemClock.elapsedRealtime.
 */
fun nowLike(startedMs: Long): Long =
  if (startedMs > 1_000_000_000_000L) System.currentTimeMillis() else SystemClock.elapsedRealtime()

/** Proteklo vrijeme snimanja u sekundama, osvježava se svakih pola sekunde dok traje. */
@Composable
fun rememberElapsedSeconds(recording: Boolean, startedMs: Long): State<Long> =
  produceState(0L, recording, startedMs) {
    if (!recording || startedMs <= 0L) { value = 0L; return@produceState }
    while (true) {
      value = (nowLike(startedMs) - startedMs) / 1000
      delay(500)
    }
  }

/**
 * Selektor dijela stanja motora: komponenta se rekomponira samo kad se odabrana vrijednost promijeni.
 */
@Composable
fun <T> StateFlow<EngineState>.select(selector: (EngineState) -> T): State<T> {
  val flow = remember(this) { this.map(selector).distinctUntilChanged() }
  return flow.collectAsStateWithLifecycle(initialValue = selector(this.value))
}

@Composable
fun SensorCard(
  title: String,
  icon: ImageVector,
  modifier: Modifier = Modifier,
  trailing: @Composable (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  ElevatedCard(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
      }
      content()
    }
  }
}

@Composable
fun LegendDot(color: Color, label: String, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
    Spacer(Modifier.width(4.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
