package hr.adip.senzori.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import hr.adip.senzori.data.ThemeMode

private val LightColors = lightColorScheme(
  primary = NightBlue,
  onPrimary = Color.White,
  primaryContainer = NightBlueContainerLight,
  onPrimaryContainer = OnNightBlueContainerLight,
  secondary = AmberLight,
  onSecondary = Color.White,
  secondaryContainer = AmberContainerLight,
  onSecondaryContainer = OnAmberContainerLight,
  tertiary = TealLight,
  onTertiary = Color.White,
  tertiaryContainer = TealContainerLight,
  onTertiaryContainer = Color(0xFF00201D),
  background = BackgroundLight,
  onBackground = OnSurfaceLight,
  surface = BackgroundLight,
  onSurface = OnSurfaceLight,
  surfaceVariant = SurfaceContainerHighestLight,
  onSurfaceVariant = OnSurfaceVariantLight,
  surfaceContainerLowest = SurfaceContainerLowestLight,
  surfaceContainerLow = SurfaceContainerLowLight,
  surfaceContainer = SurfaceContainerLight,
  surfaceContainerHigh = SurfaceContainerHighLight,
  surfaceContainerHighest = SurfaceContainerHighestLight,
  outline = OutlineLight,
  outlineVariant = OutlineVariantLight,
)

private val DarkColors = darkColorScheme(
  primary = NightBlueDark,
  onPrimary = OnNightBlueDark,
  primaryContainer = NightBlueContainerDark,
  onPrimaryContainer = OnNightBlueContainerDark,
  secondary = AmberDark,
  onSecondary = OnAmberDark,
  secondaryContainer = AmberContainerDark,
  onSecondaryContainer = OnAmberContainerDark,
  tertiary = TealDark,
  onTertiary = Color(0xFF003732),
  tertiaryContainer = TealContainerDark,
  onTertiaryContainer = Color(0xFF9CF2E8),
  background = BackgroundDark,
  onBackground = OnSurfaceDark,
  surface = BackgroundDark,
  onSurface = OnSurfaceDark,
  surfaceVariant = SurfaceContainerHighestDark,
  onSurfaceVariant = OnSurfaceVariantDark,
  surfaceContainerLowest = SurfaceContainerLowestDark,
  surfaceContainerLow = SurfaceContainerLowDark,
  surfaceContainer = SurfaceContainerDark,
  surfaceContainerHigh = SurfaceContainerHighDark,
  surfaceContainerHighest = SurfaceContainerHighestDark,
  outline = OutlineDark,
  outlineVariant = OutlineVariantDark,
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
  ThemeMode.SYSTEM -> isSystemInDarkTheme()
  ThemeMode.LIGHT -> false
  ThemeMode.DARK -> true
}

@Composable
fun ADIPSenzoriTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    typography = Typography,
    content = content,
  )
}
