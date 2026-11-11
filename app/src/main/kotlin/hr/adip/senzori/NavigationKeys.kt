package hr.adip.senzori

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.ui.graphics.vector.ImageVector

/** Kartice donje navigacije. */
enum class Tab(val label: String, val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
  LIVE("Mjerenje", "Mjerenje uživo", Icons.Outlined.Sensors, Icons.Filled.Sensors),
  LEVEL("Libela", "Libela", Icons.Outlined.Straighten, Icons.Filled.Straighten),
  RECORDINGS("Zapisi", "Zapisi", Icons.Outlined.Folder, Icons.Filled.Folder),
  SETTINGS("Postavke", "Postavke", Icons.Outlined.Settings, Icons.Filled.Settings),
}
