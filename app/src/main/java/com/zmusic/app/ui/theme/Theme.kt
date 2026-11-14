@file:OptIn(ExperimentalTextApi::class)

package com.zmusic.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmusic.app.R

/*
 * Direction: calm, typographic, cover-free. Neutrals are #0B0B0D (dark) and #F6F6F4 (light),
 * nudged by one muted seed colour derived from the current song's name. The seed's chroma is
 * capped so contrast always holds. The identity of the app is the live audio visual and the type,
 * not artwork.
 */

/** Used when nothing is playing. A muted warm tone. */
val DefaultSeed = Color(0xFFC9A27A)

/** Stable muted seed for a song or album name. */
fun seedFromText(text: String): Color {
    val hue = ((text.hashCode() and 0x7fffffff) % 360).toFloat()
    return Color.hsv(hue, 0.30f, 0.80f)
}

private fun hsvOf(c: Color): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), out)
    return out
}

/** Soft tone for glows and tints: the seed pulled toward a calm, low-chroma colour. */
fun washTone(seed: Color, dark: Boolean): Color {
    val h = hsvOf(seed)
    return if (dark) Color.hsv(h[0], h[1].coerceAtMost(0.50f), 0.62f)
    else Color.hsv(h[0], h[1].coerceAtMost(0.35f), 0.95f)
}

fun buildColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val h = hsvOf(seed)
    val hue = h[0]
    val chroma = h[1].coerceIn(0.14f, 0.38f)
    return if (dark) {
        val bg = lerpColor(Color(0xFF0B0B0D), Color.hsv(hue, chroma, 0.40f), 0.08f)
        val ink = Color(0xFFEDEDEF)
        val primary = Color.hsv(hue, chroma.coerceAtMost(0.34f), 0.93f)
        fun step(t: Float) = lerpColor(bg, ink, t)
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF0B0B0D),
            primaryContainer = lerpColor(bg, primary, 0.20f),
            onPrimaryContainer = ink,
            secondary = lerpColor(ink, bg, 0.30f),
            onSecondary = bg,
            secondaryContainer = step(0.10f),
            onSecondaryContainer = ink,
            tertiary = primary,
            background = bg,
            onBackground = ink,
            surface = bg,
            onSurface = ink,
            surfaceVariant = step(0.10f),
            onSurfaceVariant = lerpColor(ink, bg, 0.30f),
            surfaceContainerLowest = lerpColor(bg, Color.Black, 0.40f),
            surfaceContainerLow = step(0.03f),
            surfaceContainer = step(0.05f),
            surfaceContainerHigh = step(0.08f),
            surfaceContainerHighest = step(0.11f),
            outline = step(0.30f),
            outlineVariant = step(0.12f),
        )
    } else {
        val bg = lerpColor(Color(0xFFF6F6F4), Color.hsv(hue, chroma, 0.95f), 0.10f)
        val ink = Color(0xFF111113)
        val primary = Color.hsv(hue, chroma.coerceIn(0.30f, 0.60f), 0.38f)
        fun step(t: Float) = lerpColor(bg, ink, t)
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = lerpColor(bg, primary, 0.16f),
            onPrimaryContainer = ink,
            secondary = lerpColor(ink, bg, 0.35f),
            onSecondary = Color.White,
            secondaryContainer = step(0.07f),
            onSecondaryContainer = ink,
            tertiary = primary,
            background = bg,
            onBackground = ink,
            surface = bg,
            onSurface = ink,
            surfaceVariant = step(0.07f),
            onSurfaceVariant = lerpColor(ink, bg, 0.35f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = step(0.025f),
            surfaceContainer = step(0.045f),
            surfaceContainerHigh = step(0.07f),
            surfaceContainerHighest = step(0.10f),
            outline = step(0.30f),
            outlineVariant = step(0.12f),
        )
    }
}

@Immutable
data class ExtendedColors(val hairline: Color)

private val LocalExtended = staticCompositionLocalOf { ExtendedColors(Color(0x1AFFFFFF)) }

object AppTheme {
    val extended: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtended.current
}

// Manrope (UI) and JetBrains Mono (timestamps), both SIL OFL, downloaded into res/font at build time.
private fun manrope(w: Int) = Font(
    R.font.manrope,
    FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)

private fun mono(w: Int) = Font(
    R.font.jetbrains_mono,
    FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)

private val Sans = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))
private val Mono = FontFamily(mono(400), mono(500))

/** Monospaced tabular figures for timestamps. */
val TimeStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-1.4).sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.1).sp),
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.0.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ZmusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    seed: Color = DefaultSeed,
    content: @Composable () -> Unit,
) {
    val scheme = remember(seed, darkTheme) { buildColorScheme(seed, darkTheme) }
    CompositionLocalProvider(LocalExtended provides ExtendedColors(scheme.onSurface.copy(alpha = 0.10f))) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
