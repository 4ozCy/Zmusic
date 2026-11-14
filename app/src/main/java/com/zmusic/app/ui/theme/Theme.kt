@file:OptIn(ExperimentalTextApi::class)

package com.zmusic.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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
 * Direction: nocturnal liquid glass. A deep obsidian room lit from behind by slow aurora light
 * that follows the music, with a few floating panes of smoked glass in front of it. Type does the
 * talking; there is no artwork anywhere.
 *
 * The app is deliberately dark-only: the whole look depends on light glowing through glass.
 * Accent is scarce on purpose. Amber marks "this is playing / do this", indigo only ever appears
 * as light (aurora, glow, refraction), never as a control colour.
 */

/** Raw palette. Plain top-level values so non-composable modifiers and shaders can use them. */
object Nocturne {
    /** Page base. */
    val Obsidian = Color(0xFF070709)

    /** Smoked charcoal glass body. */
    val Smoke = Color(0xFF14151A)

    /** Primary type. */
    val Alabaster = Color(0xFFF5F5F7)

    /** Secondary type. */
    val Silver = Color(0xFF8E8E93)

    /** Warm light. The one accent used on controls and the current song. */
    val Amber = Color(0xFFE2A96B)

    /** Cool light. Aurora and refraction only. */
    val Indigo = Color(0xFF6C6FD9)

    /** Deep blue-grey that sits between the two lights in the aurora. */
    val Slate = Color(0xFF2B3A67)
}

private val Scheme = darkColorScheme(
    primary = Nocturne.Amber,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A2C1D),
    onPrimaryContainer = Nocturne.Alabaster,
    secondary = Nocturne.Silver,
    onSecondary = Color.White,
    secondaryContainer = Nocturne.Smoke,
    onSecondaryContainer = Nocturne.Alabaster,
    tertiary = Nocturne.Indigo,
    onTertiary = Nocturne.Alabaster,
    background = Nocturne.Obsidian,
    onBackground = Nocturne.Alabaster,
    surface = Nocturne.Obsidian,
    onSurface = Nocturne.Alabaster,
    surfaceVariant = Nocturne.Smoke,
    onSurfaceVariant = Nocturne.Silver,
    surfaceContainerLowest = Color(0xFF040405),
    surfaceContainerLow = Color(0xFF0D0D10),
    surfaceContainer = Color(0xFF111216),
    surfaceContainerHigh = Nocturne.Smoke,
    surfaceContainerHighest = Color(0xFF1C1D24),
    outline = Color(0xFF4A4B52),
    outlineVariant = Color(0xFF26272D),
    error = Color(0xFFE8857A),
    onError = Color.White,
)

/** Roles Material 3 has no slot for. */
@Immutable
data class ExtendedColors(
    /** 0.5dp rules and inactive tracks. */
    val hairline: Color,
    /** Tertiary text: durations, counts, row numbers. */
    val faint: Color,
    /** Warm light. */
    val amber: Color,
    /** Cool light. */
    val indigo: Color,
    /** Mid tone between the two lights. */
    val slate: Color,
    /** Primary type: pure white. */
    val text: Color,
    /** Secondary type: white, turned down so titles still lead. */
    val textSoft: Color,
    /** Tertiary type: durations, counts, row numbers. Still white, quieter again. */
    val textDim: Color,
)

private val Extended = ExtendedColors(
    hairline = Nocturne.Alabaster.copy(alpha = 0.10f),
    faint = Nocturne.Silver.copy(alpha = 0.82f),
    amber = Nocturne.Amber,
    indigo = Nocturne.Indigo,
    slate = Nocturne.Slate,
    text = Color.White,
    textSoft = Color.White.copy(alpha = 0.76f),
    textDim = Color.White.copy(alpha = 0.58f),
)

private val LocalExtended = staticCompositionLocalOf { Extended }

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

/** Monospaced tabular figures for timestamps, row numbers and counts. */
val TimeStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/** The big index numerals on the glass tiles (01, 02, 03). */
val IndexStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 16.sp,
    letterSpacing = 1.sp,
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
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
)

/*
 * Shape is a small vocabulary, not one radius: full pills for things you press and for the dock,
 * 26dp for the featured tiles, 22dp for the mini player, and no container at all for list rows.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun ZmusicTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtended provides Extended) {
        MaterialTheme(
            colorScheme = Scheme,
            typography = AppTypography,
            shapes = AppShapes,
        ) {
            // Material's default content colour is black unless a Surface sets it, and this app
            // draws on bare glass with no Surface. Without this, any Text that does not name a
            // colour comes out black on the dark backdrop.
            CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
        }
    }
}
