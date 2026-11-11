package hr.adip.senzori.ui.recordings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hr.adip.senzori.data.AppSettings
import hr.adip.senzori.recording.RecordingInfo
import hr.adip.senzori.recording.RecordingRepository
import hr.adip.senzori.sensor.SensorEngine
import hr.adip.senzori.ui.components.HR
import hr.adip.senzori.ui.components.formatBytes
import hr.adip.senzori.ui.components.formatDuration
import hr.adip.senzori.ui.components.select
import hr.adip.senzori.ui.theme.NumberStyles
import hr.adip.senzori.upload.AdipClient
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
  repo: RecordingRepository,
  client: AdipClient,
  engine: SensorEngine,
  settings: AppSettings,
  snackbar: SnackbarHostState,
  contentPadding: PaddingValues,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var items by remember { mutableStateOf<List<RecordingInfo>?>(null) }
  var refreshing by remember { mutableStateOf(false) }
  var openPath by rememberSaveable { mutableStateOf<String?>(null) }
  var confirmDelete by remember { mutableStateOf<RecordingInfo?>(null) }
  val uploading = remember { mutableStateListOf<String>() }

  val recording by engine.state.select { it.recording }
  val activeName by engine.state.select { it.recordingName }

  suspend fun reload() {
    items = runCatching { repo.list() }.getOrElse { emptyList() }
  }
  LaunchedEffect(recording) { reload() }

  val open = openPath
  if (open != null) {
    BackHandler { openPath = null }
    RecordingDetailScreen(repo, File(open), contentPadding, onBack = { openPath = null })
    return
  }

  fun upload(info: RecordingInfo) {
    if (settings.serverToken.isBlank()) {
      scope.launch { snackbar.showSnackbar("Postavi token web servisa u Postavkama.") }
      return
    }
    val key = info.file.absolutePath
    if (key in uploading) return
    uploading += key
    scope.launch {
      val r = client.uploadCsv(settings.serverUrl, settings.serverToken, info)
      uploading -= key
      snackbar.showSnackbar(
        r.fold(
          onSuccess = { "Poslano: ${info.name} ($it redaka)" },
          onFailure = { "Slanje nije uspjelo: ${it.message ?: it.javaClass.simpleName}" },
        ),
      )
    }
  }

  fun share(info: RecordingInfo) {
    try {
      context.startActivity(Intent.createChooser(repo.shareIntent(info.file), "Podijeli zapis").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
      scope.launch { snackbar.showSnackbar("Nema aplikacije za dijeljenje.") }
    }
  }

  PullToRefreshBox(
    isRefreshing = refreshing,
    onRefresh = { scope.launch { refreshing = true; reload(); refreshing = false } },
    modifier = Modifier.fillMaxSize().padding(contentPadding),
  ) {
    val list = items
    when {
      list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      list.isEmpty() -> EmptyState()
      else -> LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(list, key = { it.file.absolutePath }) { info ->
          val isActive = recording && info.file.nameWithoutExtension == activeName
          RecordingCard(
            info = info,
            active = isActive,
            uploading = info.file.absolutePath in uploading,
            onOpen = { openPath = info.file.absolutePath },
            onShare = { share(info) },
            onUpload = { upload(info) },
            onDelete = { confirmDelete = info },
          )
        }
      }
    }
  }

  confirmDelete?.let { info ->
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      icon = { Icon(Icons.Outlined.DeleteOutline, null) },
      title = { Text("Obrisati zapis?") },
      text = { Text("${info.name} (${formatBytes(info.sizeBytes)}) bit će trajno obrisan s uređaja.") },
      confirmButton = {
        TextButton(onClick = {
          confirmDelete = null
          scope.launch {
            val ok = repo.delete(info.file)
            reload()
            snackbar.showSnackbar(if (ok) "Zapis obrisan." else "Brisanje nije uspjelo.")
          }
        }) { Text("Obriši") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Odustani") } },
    )
  }
}

@Composable
private fun EmptyState() {
  Column(
    Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.size(12.dp))
    Text("Još nema zapisa", style = MaterialTheme.typography.titleMedium)
    Text(
      "Pokreni snimanje na kartici Mjerenje – zapisi se spremaju kao CSV datoteke.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private val dateFmt = SimpleDateFormat("d. M. yyyy. HH:mm:ss", HR)

@Composable
private fun RecordingCard(
  info: RecordingInfo,
  active: Boolean,
  uploading: Boolean,
  onOpen: () -> Unit,
  onShare: () -> Unit,
  onUpload: () -> Unit,
  onDelete: () -> Unit,
) {
  ElevatedCard(
    Modifier.fillMaxWidth().clickable(enabled = !active, onClick = onOpen),
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 4.dp, bottom = 4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(info.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (active) {
          Text("● SNIMA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 12.dp))
        }
      }
      Text(dateFmt.format(Date(info.startedAtMs)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
        "${formatDuration(info.durationS.toLong())} · ${info.rows} redaka · ${info.sampleRateHz} Hz · ${formatBytes(info.sizeBytes)}",
        style = NumberStyles.small,
        modifier = Modifier.padding(top = 4.dp),
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Podijeli") }
        if (uploading) {
          Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
        } else {
          IconButton(onClick = onUpload, enabled = !active) { Icon(Icons.Outlined.CloudUpload, "Pošalji na server") }
        }
        IconButton(onClick = onDelete, enabled = !active) { Icon(Icons.Outlined.DeleteOutline, "Obriši") }
        Spacer(Modifier.width(4.dp))
      }
    }
  }
}
