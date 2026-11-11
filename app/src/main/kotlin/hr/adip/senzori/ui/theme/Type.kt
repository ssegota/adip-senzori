package hr.adip.senzori.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography()

/** Tabularne znamenke – brojevi ne "plešu" pri osvježavanju. */
private const val TNUM = "tnum"

object NumberStyles {
  val huge = TextStyle(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Light, fontFeatureSettings = TNUM)
  val big = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TNUM)
  val medium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM)
  val small = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TNUM)
  val mono = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
}
