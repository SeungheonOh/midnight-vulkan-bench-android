package network.midnight.vulkanbench.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object BenchColors {
    val Bg = Color(0xFF0B0E14)
    val Surface = Color(0xFF131823)
    val SurfaceAlt = Color(0xFF1A2232)
    val Border = Color(0xFF253047)
    val Primary = Color(0xFF6C8CFF)
    val Cyan = Color(0xFF38BDF8)
    val Green = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

private val DarkScheme = darkColorScheme(
    primary = BenchColors.Primary,
    onPrimary = Color(0xFF0B0E14),
    secondary = BenchColors.Cyan,
    onSecondary = Color(0xFF0B0E14),
    background = BenchColors.Bg,
    onBackground = BenchColors.TextPrimary,
    surface = BenchColors.Surface,
    onSurface = BenchColors.TextPrimary,
    surfaceVariant = BenchColors.SurfaceAlt,
    onSurfaceVariant = BenchColors.TextSecondary,
    outline = BenchColors.Border,
    error = BenchColors.Red,
    onError = Color.White,
)

val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
)

@Composable
fun MidnightBenchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = Typography(),
        content = content,
    )
}
