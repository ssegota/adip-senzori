package hr.adip.senzori

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hr.adip.senzori.data.AppSettings
import hr.adip.senzori.ui.theme.ADIPSenzoriTheme
import hr.adip.senzori.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val app = AdipApp.get(this)
    setContent {
      val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
      val dark = isDarkTheme(settings.themeMode)
      // ikone sistemskih traka prema odabranoj temi (ne samo prema sustavu)
      DisposableEffect(dark) {
        enableEdgeToEdge(
          statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
          navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        )
        onDispose { }
      }
      ADIPSenzoriTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(app, settings)
        }
      }
    }
  }

  override fun onStop() {
    super.onStop()
    // Štedi bateriju kad aplikacija nije vidljiva; SensorEngine.stop() ne prekida snimanje.
    if (!isChangingConfigurations) AdipApp.get(this).engine.stop()
  }
}
