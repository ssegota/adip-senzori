package hr.adip.senzori.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import hr.adip.senzori.BuildConfig
import hr.adip.senzori.data.AppSettings
import hr.adip.senzori.data.SettingsRepository
import hr.adip.senzori.data.ThemeMode
import hr.adip.senzori.upload.AdipClient
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
  repo: SettingsRepository,
  client: AdipClient,
  settings: AppSettings,
  contentPadding: PaddingValues,
) {
  val scope = rememberCoroutineScope()
  fun update(t: (AppSettings) -> AppSettings) { scope.launch { repo.update(t) } }

  Column(
    Modifier
      .fillMaxSize()
      .padding(contentPadding)
      .consumeWindowInsets(contentPadding)
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Section("Mjerenje") {
      Label("Frekvencija uzorkovanja")
      val rates = AppSettings.SAMPLE_RATES
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        rates.forEachIndexed { i, r ->
          SegmentedButton(
            selected = settings.sampleRateHz == r,
            onClick = { update { it.copy(sampleRateHz = r) } },
            shape = SegmentedButtonDefaults.itemShape(i, rates.size),
            icon = {},
          ) { Text("$r") }
        }
      }
      Hint("Hz. Primjenjuje se od sljedećeg pokretanja mjerenja/snimanja.")
      SwitchRow("Infrazvuk (mikrofon)", "Niskofrekventni dio signala mikrofona, 0,5–20 Hz", settings.infraEnabled) { v ->
        update { it.copy(infraEnabled = v) }
      }
      NumberField(
        label = "Kalibracija infrazvuka (Pa / FS)",
        value = settings.infraCalibrationPaPerFs,
        onValue = { v -> update { it.copy(infraCalibrationPaPerFs = v.coerceAtLeast(0f)) } },
        decimal = true,
      )
      Hint(if (settings.infraCalibrated) "Kalibrirano – razina se prikazuje u dB SPL." else "0 = nekalibrirano – razina se prikazuje relativno (dBFS).")
    }

    Section("Prikaz") {
      Label("Tema")
      val modes = listOf(ThemeMode.SYSTEM to "Sustav", ThemeMode.LIGHT to "Svijetla", ThemeMode.DARK to "Tamna")
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (m, label) ->
          SegmentedButton(
            selected = settings.themeMode == m,
            onClick = { update { it.copy(themeMode = m) } },
            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
          ) { Text(label) }
        }
      }
      SwitchRow("Zaslon uvijek uključen", "Dok je aplikacija otvorena", settings.keepScreenOn) { v ->
        update { it.copy(keepScreenOn = v) }
      }
    }

    Section("Web servis") {
      TextSetting("Adresa servera", settings.serverUrl, keyboardType = KeyboardType.Uri) { v -> update { it.copy(serverUrl = v.trim()) } }
      TextSetting("Token", settings.serverToken, password = true) { v -> update { it.copy(serverToken = v.trim()) } }
      SwitchRow("Slanje uživo", "Šalji podatke na server tijekom snimanja", settings.liveUploadEnabled) { v ->
        update { it.copy(liveUploadEnabled = v) }
      }
      NumberField(
        label = "Interval slanja (s)",
        value = settings.uploadBatchSeconds.toFloat(),
        onValue = { v -> update { it.copy(uploadBatchSeconds = v.toInt().coerceIn(1, 3600)) } },
        decimal = false,
      )
      ConnectionTest(client, settings)
    }

    Section("O aplikaciji") {
      Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("ADIP Senzori ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
          Text(
            "Akcelerometar, luksmetar, barometar i infrazvuk iz mikrofona, uzorkovani fiksnom frekvencijom i spremljeni kao CSV.",
            style = MaterialTheme.typography.bodySmall,
          )
          Text("Ograničenja mjerenja infrazvuka", style = MaterialTheme.typography.labelLarge)
          Text(
            "• Mikrofoni mobitela imaju jako prigušen odziv ispod ~10–20 Hz, pa su infrazvučne razine znatno podcijenjene i ovise o modelu uređaja.\n" +
              "• Bez kalibracije vrijednosti su relativne (dBFS) – prikladne za usporedbu promjena, ne za apsolutne razine.\n" +
              "• Najbolji rezultati postižu se s audio izvorom UNPROCESSED (bez obrade šuma i AGC-a); ako ga uređaj ne podržava, koristi se zamjenski izvor (vidi status na kartici Infrazvuk).\n" +
              "• Vjetar, dodir i vibracije kućišta stvaraju jake lažne niskofrekventne signale.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    Spacer(Modifier.height(8.dp))
  }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
  Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
  ElevatedCard(
    Modifier.fillMaxWidth(),
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
  }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      if (subtitle != null) Hint(subtitle)
    }
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

/** Tekstualno polje s lokalnim stanjem (DataStore se ažurira pri svakoj promjeni). */
@Composable
private fun TextSetting(
  label: String,
  value: String,
  password: Boolean = false,
  keyboardType: KeyboardType = KeyboardType.Text,
  onValue: (String) -> Unit,
) {
  var text by remember { mutableStateOf(value) }
  var visible by remember { mutableStateOf(false) }
  OutlinedTextField(
    value = text,
    onValueChange = { text = it; onValue(it) },
    label = { Text(label) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
    visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, autoCorrectEnabled = false),
    trailingIcon = if (password) {
      {
        IconButton(onClick = { visible = !visible }) {
          Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Sakrij" else "Prikaži")
        }
      }
    } else null,
  )
}

@Composable
private fun NumberField(label: String, value: Float, onValue: (Float) -> Unit, decimal: Boolean) {
  fun show(v: Float) = if (decimal) (if (v == 0f) "0" else v.toString().replace('.', ',')) else v.toInt().toString()
  var text by remember { mutableStateOf(show(value)) }
  val parsed = text.replace(',', '.').toFloatOrNull()
  OutlinedTextField(
    value = text,
    onValueChange = { s ->
      text = s
      s.replace(',', '.').toFloatOrNull()?.let(onValue)
    },
    label = { Text(label) },
    singleLine = true,
    isError = parsed == null,
    modifier = Modifier.fillMaxWidth(),
    keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
  )
}

@Composable
private fun ConnectionTest(client: AdipClient, settings: AppSettings) {
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<Result<String>?>(null) }
  Row(verticalAlignment = Alignment.CenterVertically) {
    FilledTonalButton(
      enabled = !busy && settings.serverUrl.isNotBlank(),
      onClick = {
        busy = true
        result = null
        scope.launch {
          result = runCatching { client.status(settings.serverUrl, settings.serverToken) }.getOrElse { Result.failure(it) }
          busy = false
        }
      },
    ) {
      if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.NetworkCheck, null)
      Spacer(Modifier.width(8.dp))
      Text("Testiraj vezu")
    }
  }
  result?.let { r ->
    r.fold(
      onSuccess = { Text("✓ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) },
      onFailure = { Text("✗ ${it.message ?: it.javaClass.simpleName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) },
    )
  }
}
