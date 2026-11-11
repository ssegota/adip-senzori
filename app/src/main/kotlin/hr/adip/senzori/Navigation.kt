package hr.adip.senzori

import androidx.compose.runtime.Composable
import hr.adip.senzori.data.AppSettings
import hr.adip.senzori.ui.AppRoot

/** Jednostavna navigacija temeljena na stanju (kartice) – vidi [AppRoot]. */
@Composable
fun MainNavigation(app: AdipApp, settings: AppSettings) = AppRoot(app, settings)
