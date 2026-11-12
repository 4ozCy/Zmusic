package com.zmusic.app.ui.theme

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Direction: "warm ink" editorial. Near-black with a brown undertone (not cold slate), cream text,
 * ONE scarce ember accent, a quiet sage secondary. Serif display type against technical condensed
 * labels, cut-corner signature shape. Each song also gets its own tint (see trackTint) that only
 * colours the Now Playing glow and the typographic tiles, so the app accent stays rare.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFA45C),
    onPrimary = Color(0xFF2B1300),
    primaryContainer = Color(0xFF4A2A0F),
    onPrimaryContainer = Color(0xFFFFDCC0),
    secondary = Color(0xFF9DB59A),
    onSecondary = Color(0xFF16230F),
    secondaryContainer = Color(0xFF2A3828),
    onSecondaryContainer = Color(0xFFD8EBD3),
    tertiary = Color(0xFFE8A0A8),
    background = Color(0xFF0C0A09),
    onBackground = Color(0xFFEFE8DF),
    surface = Color(0xFF12100E),
    onSurface = Color(0xFFEFE8DF),
    surfaceVariant = Color(0xFF26211E),
    onSurfaceVariant = Color(0xFFA89F94),
    surfaceContainerLowest = Color(0xFF0A0807),
    surfaceContainerLow = Color(0xFF161311),
    surfaceContainer = Color(0xFF1C1816),
    surfaceContainerHigh = Color(0xFF26211E),
    surfaceContainerHighest = Color(0xFF312B27),
    outline = Color(0xFF4A423B),
    outlineVariant = Color(0xFF2E2823),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB4560A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC0),
    onPrimaryContainer = Color(0xFF3A1A00),
    secondary = Color(0xFF4F6B4C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8EBD3),
    onSecondaryContainer = Color(0xFF16230F),
    tertiary = Color(0xFF9C4A55),
    background = Color(0xFFF6F1E9),
    onBackground = Color(0xFF1D1915),
    surface = Color(0xFFFBF8F3),
    onSurface = Color(0xFF1D1915),
    surfaceVariant = Color(0xFFE4DDD1),
    onSurfaceVariant = Color(0xFF6A6258),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1ECE3),
    surfaceContainer = Color(0xFFEBE5DB),
    surfaceContainerHigh = Color(0xFFE4DDD1),
    surfaceContainerHighest = Color(0xFFDCD4C7),
    outline = Color(0xFFB5AB9D),
    outlineVariant = Color(0xFFDDD5C8),
)

/** Tokens Material's roles don't cover. */
@Immutable
data class ExtendedColors(
    val hairline: Color,
    val heroStart: Color,
    val heroEnd: Color,
)

private val DarkExtended = ExtendedColors(
    hairline = Color(0x1AEFE8DF),
    heroStart = Color(0xFF4A2A0F),
    heroEnd = Color(0xFF1C1816),
)

private val LightExtended = ExtendedColors(
    hairline = Color(0x1F1D1915),
    heroStart = Color(0xFFFFDCC0),
    heroEnd = Color(0xFFEBE5DB),
)

private val LocalExtended = staticCompositionLocalOf { DarkExtended }

object AppTheme {
    val extended: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtended.current
}

/** 4/8 spacing scale. Use these instead of loose numbers so rhythm stays deliberate. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

// Characterful display serif + technical condensed labels + clean Roboto body.
// Bundling a custom font later is a drop-in swap of these three families.
private val Display = FontFamily.Serif
private val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))
private val Body = FontFamily.Default

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Body, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Condensed, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp),
    labelSmall = TextStyle(fontFamily = Condensed, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
)

/** Light numerals with tabular figures so animated counts and durations don't jitter. */
val NumberStyle = TextStyle(
    fontFamily = Body,
    fontWeight = FontWeight.Light,
    fontFeatureSettings = "tnum",
    letterSpacing = (-2).sp,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

/** Cut-corner signature: big soft corners on one diagonal, near-square on the other. */
val SignatureShape = RoundedCornerShape(topStart = 32.dp, topEnd = 8.dp, bottomEnd = 32.dp, bottomStart = 8.dp)
val SmallSignatureShape = RoundedCornerShape(topStart = 18.dp, topEnd = 5.dp, bottomEnd = 18.dp, bottomStart = 5.dp)

/** Every song gets a stable colour from its text. Only used for glows and typographic tiles. */
fun trackTint(seed: String, dark: Boolean): Color {
    val hue = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    return if (dark) Color.hsv(hue, 0.55f, 0.95f) else Color.hsv(hue, 0.62f, 0.78f)
}

@Composable
fun ZmusicTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtended provides if (darkTheme) DarkExtended else LightExtended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
