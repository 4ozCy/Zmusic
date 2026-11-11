package com.zmusic.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Graphite = Color(0xFF090A0E)
val Silver = Color(0xFFA9B0BA)
val Mint = Color(0xFF5EEAD4)
val Violet = Color(0xFF9A8CFF)
val Accent = Mint

val AccentGradient = Brush.horizontalGradient(listOf(Mint, Violet))

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00201B),
    secondary = Violet,
    background = Graphite,
    onBackground = Color(0xFFE9EBEF),
    surface = Graphite,
    onSurface = Color(0xFFE9EBEF),
    surfaceVariant = Color(0xFF1B1E25),
    onSurfaceVariant = Silver,
    surfaceContainer = Color(0xFF12141A),
    surfaceContainerHigh = Color(0xFF181B22),
    outline = Color(0xFF343944),
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
        displaySmall = it.displaySmall.merge(TextStyle(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)),
        headlineMedium = it.headlineMedium.merge(TextStyle(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)),
        headlineSmall = it.headlineSmall.merge(TextStyle(fontWeight = FontWeight.Bold)),
        titleMedium = it.titleMedium.merge(TextStyle(fontWeight = FontWeight.SemiBold)),
    )
}

/** Big screen title with the accent gradient painted through the text. */
@Composable
fun gradientTitleStyle(): TextStyle =
    MaterialTheme.typography.displaySmall.copy(brush = AccentGradient)

/** Dark by default. Light scheme is ready; the Settings switch arrives in a later phase. */
@Composable
fun ZmusicTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ZTypography,
        content = content,
    )
}
