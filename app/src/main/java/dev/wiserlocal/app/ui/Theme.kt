package dev.wiserlocal.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colours with a meaning: orange for heat, blue for hot water, green for "on". */
@Immutable
data class Accents(
    val heat: Color,
    val heatContainer: Color,
    val onHeatContainer: Color,
    val water: Color,
    val waterContainer: Color,
    val onWaterContainer: Color,
    val good: Color,
    val danger: Color,
)

private val LightAccents = Accents(
    heat = Color(0xFFD9570D),
    heatContainer = Color(0xFFFFE3D2),
    onHeatContainer = Color(0xFF3A1300),
    water = Color(0xFF1C6FC4),
    waterContainer = Color(0xFFD6E7FF),
    onWaterContainer = Color(0xFF001D3A),
    good = Color(0xFF1E8A4C),
    danger = Color(0xFFC62828),
)

private val DarkAccents = Accents(
    heat = Color(0xFFFF9E61),
    heatContainer = Color(0xFF5A2600),
    onHeatContainer = Color(0xFFFFDCC8),
    water = Color(0xFF8CC4FF),
    waterContainer = Color(0xFF0B3D6B),
    onWaterContainer = Color(0xFFD6E7FF),
    good = Color(0xFF6FD49A),
    danger = Color(0xFFFF8A80),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1E6B52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4EFD3),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4C6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE9DA),
    onSecondaryContainer = Color(0xFF082017),
    tertiary = Color(0xFF1C6FC4),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6E7FF),
    onTertiaryContainer = Color(0xFF001D3A),
    background = Color(0xFFF5F8F6),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5F8F6),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF404944),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF4F1),
    surfaceContainer = Color(0xFFE9EFEB),
    surfaceContainerHigh = Color(0xFFE3EAE6),
    surfaceContainerHighest = Color(0xFFDDE4E0),
    outline = Color(0xFF707974),
    outlineVariant = Color(0xFFBFC9C3),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7DD8B0),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513C),
    onPrimaryContainer = Color(0xFF9AF5CB),
    secondary = Color(0xFFB3CCBE),
    onSecondary = Color(0xFF1E352A),
    secondaryContainer = Color(0xFF354B40),
    onSecondaryContainer = Color(0xFFCEE9DA),
    tertiary = Color(0xFF8CC4FF),
    onTertiary = Color(0xFF00315C),
    tertiaryContainer = Color(0xFF0B3D6B),
    onTertiaryContainer = Color(0xFFD6E7FF),
    background = Color(0xFF0F1412),
    onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0F1412),
    onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF404944),
    error = Color(0xFFFFB4AB),
)

val LocalAccents = staticCompositionLocalOf { LightAccents }

/** [mode] is "system", "light" or "dark". */
@Composable
fun WiserTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalAccents provides if (dark) DarkAccents else LightAccents) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}
