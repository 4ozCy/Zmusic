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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import com.zmusic.app.ui.theme.washTone
import kotlin.random.Random

/** Static film grain, tiled. Dithers the gradient so it never bands. Built once. */
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
 * Calm radial colour wash from the cover's seed, blended into the base canvas, plus 2% grain.
 * Track changes crossfade between two static layers, so nothing is reallocated per frame.
 */
@Composable
fun WashBackdrop(seed: Color, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    AnimatedContent(
        targetState = seed,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) },
        label = "wash",
    ) { c -> WashLayer(washTone(c, dark)) }
}

@Composable
private fun WashLayer(tone: Color) {
    val bg = MaterialTheme.colorScheme.background
    val grain = rememberGrainBrush()
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val wash = Brush.radialGradient(
                listOf(tone.copy(alpha = 0.34f), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 0.16f),
                radius = size.width * 1.1f,
            )
            val fade = Brush.verticalGradient(
                listOf(Color.Transparent, bg),
                startY = size.height * 0.5f,
                endY = size.height,
            )
            onDrawBehind {
                drawRect(wash)
                drawRect(fade)
                drawRect(grain, alpha = 0.02f)
            }
        },
    )
}
