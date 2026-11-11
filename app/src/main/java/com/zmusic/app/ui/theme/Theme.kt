package com.zmusic.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Graphite = Color(0xFF0E0F11)
val Silver = Color(0xFFB8BEC6)
val Accent = Color(0xFF5EEAD4)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00201B),
    secondary = Silver,
    background = Graphite,
    onBackground = Color(0xFFE6E8EB),
    surface = Graphite,
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF202327),
    onSurfaceVariant = Silver,
    surfaceContainer = Color(0xFF17191C),
    surfaceContainerHigh = Color(0xFF1E2125),
    outline = Color(0xFF3A3F45),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    background = Color(0xFFF6F7F8),
    onBackground = Color(0xFF14171A),
    surface = Color(0xFFF6F7F8),
    onSurface = Color(0xFF14171A),
    surfaceVariant = Color(0xFFE3E6E9),
    onSurfaceVariant = Color(0xFF4A5057),
    surfaceContainer = Color(0xFFECEEF0),
    surfaceContainerHigh = Color(0xFFE3E6E9),
)

private val ZTypography = Typography().let {
    it.copy(
        headlineMedium = it.headlineMedium.merge(TextStyle(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)),
        headlineSmall = it.headlineSmall.merge(TextStyle(fontWeight = FontWeight.Bold)),
        titleMedium = it.titleMedium.merge(TextStyle(fontWeight = FontWeight.SemiBold)),
    )
}

/** Dark by default. Light scheme is ready; the Settings switch arrives in a later phase. */
@Composable
fun ZmusicTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ZTypography,
        content = content,
    )
}
