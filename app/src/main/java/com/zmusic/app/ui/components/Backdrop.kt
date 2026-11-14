package com.zmusic.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import com.zmusic.app.ui.theme.AppTheme
import kotlin.random.Random

/** Static film grain, tiled. Dithers gradients so they never band. Built once. */
@Composable
fun rememberGrainBrush(): Brush = remember {
    val n = 96
    val px = IntArray(n * n)
    val rnd = Random(7)
    for (i in px.indices) {
        val v = rnd.nextInt(256)
        px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/**
 * Soft colour field behind a header, in the theme's wash tone (which follows the current song).
 * Pass a [reactor] to let its strength follow low-end energy. Track changes crossfade between
 * two static layers.
 */
@Composable
fun AmbientGlow(modifier: Modifier = Modifier, reactor: AudioReactor? = null) {
    AnimatedContent(
        targetState = AppTheme.extended.wash,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) },
        label = "ambient",
    ) { tone -> GlowLayer(tone, reactor) }
}

@Composable
private fun GlowLayer(tone: Color, reactor: AudioReactor?) {
    val bg = MaterialTheme.colorScheme.background
    val grain = rememberGrainBrush()
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val wash = Brush.radialGradient(
                listOf(tone.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 0.16f),
                radius = size.width * 1.1f,
            )
            val fade = Brush.verticalGradient(
                listOf(Color.Transparent, bg),
                startY = size.height * 0.5f,
                endY = size.height,
            )
            onDrawBehind {
                val kick = if (reactor != null) {
                    @Suppress("UNUSED_VARIABLE")
                    val subscribe = reactor.frame.longValue
                    ((reactor.bass - 0.3f) / 0.5f).coerceIn(0f, 1f)
                } else {
                    0f
                }
                drawRect(wash, alpha = 0.75f + 0.25f * kick)
                drawRect(fade)
                drawRect(grain, alpha = 0.02f)
            }
        },
    )
}
