package hr.adip.senzori.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import hr.adip.senzori.AdipApp
import hr.adip.senzori.Tab
import hr.adip.senzori.data.AppSettings
import hr.adip.senzori.ui.components.formatDuration
import hr.adip.senzori.ui.components.rememberElapsedSeconds
import hr.adip.senzori.ui.components.select
import hr.adip.senzori.ui.level.LevelScreen
import hr.adip.senzori.ui.live.LiveScreen
import hr.adip.senzori.ui.recordings.RecordingsScreen
import hr.adip.senzori.ui.settings.SettingsScreen
import hr.adip.senzori.ui.sleep.SleepOverlay
import hr.adip.senzori.ui.theme.NumberStyles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(app: AdipApp, settings: AppSettings) {
  var tabName by rememberSaveable { mutableStateOf(Tab.LIVE.name) }
  val tab = Tab.valueOf(tabName)
  var sleep by rememberSaveable { mutableStateOf(false) }
  val snackbar = remember { SnackbarHostState() }

  KeepScreenOn(settings.keepScreenOn || sleep)

  Box(Modifier.fillMaxSize()) {
    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(tab.title, fontWeight = FontWeight.SemiBold) },
          actions = {
            RecIndicator(app)
            IconButton(onClick = { sleep = true }) { Icon(Icons.Outlined.Bedtime, "Noćni način") }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
      },
      bottomBar = {
        NavigationBar {
          Tab.entries.forEach { t ->
            NavigationBarItem(
              selected = t == tab,
              onClick = { tabName = t.name },
              icon = { Icon(if (t == tab) t.selectedIcon else t.icon, null) },
              label = { Text(t.label) },
            )
          }
        }
      },
      snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
      AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
        when (t) {
          Tab.LIVE -> LiveScreen(app.engine, settings, padding)
          Tab.LEVEL -> LevelScreen(app.engine, app.settings, settings, padding)
          Tab.RECORDINGS -> RecordingsScreen(app.recordings, app.client, app.engine, settings, snackbar, padding)
          Tab.SETTINGS -> SettingsScreen(app.settings, app.client, app.engine, settings, padding)
        }
      }
    }
    if (sleep) {
      Box(Modifier.fillMaxSize().zIndex(10f)) { SleepOverlay(app.engine, onExit = { sleep = false }) }
    }
  }
}

@Composable
private fun RecIndicator(app: AdipApp) {
  val recording by app.engine.state.select { it.recording }
  val started by app.engine.state.select { it.recordingStartedMs }
  if (!recording) return
  val elapsed by rememberElapsedSeconds(true, started)
  Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text("● ", color = MaterialTheme.colorScheme.error, style = NumberStyles.small)
    Text(formatDuration(elapsed), style = NumberStyles.small, color = MaterialTheme.colorScheme.error)
  }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
  val context = LocalContext.current
  val view = LocalView.current
  DisposableEffect(on) {
    val window = (context as? Activity)?.window
    if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    view.keepScreenOn = on
    onDispose { }
  }
}
