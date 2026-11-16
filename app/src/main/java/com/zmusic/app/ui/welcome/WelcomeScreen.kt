package com.zmusic.app.ui.welcome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.components.rememberReduceMotion
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne

/** Logo colours, straight from ic_launcher_foreground so the hand-off from the splash matches. */
private val BarLight = Color(0xFFEFE8DF)
private val BarAmber = Color(0xFFFFA45C)

/**
 * The system splash draws an icon without a background at 288dp. The logo starts at that size
 * and position, so the moment the splash goes, nothing appears to change until it moves.
 */
private val SPLASH_ICON = 288.dp

/** How far the logo rises and how small it ends up, making room for the words below it. */
private val LIFT = 84.dp
private const val LIFT_SCALE = 0.6f

/** Where the words sit, below the centre of the screen. */
private val WORDS_OFFSET = 58.dp

/** If animations are off in system settings: no motion, the screen just shows and fades. */
private const val STILL_EXIT_AT = 1300L

private const val NAME = "Zmusic"

/**
 * "Welcome to Zmusic", shown over the app each time it starts. The logo's bars dip and bounce,
 * rings ripple out of the amber bar, then the logo lifts away and the words rise in under it.
 * Tap anywhere to skip. [onFinished] runs once it has faded out.
 *
 * Every moving value is read from one frame clock inside draw and layer lambdas, so the whole
 * thing redraws each frame without recomposing.
 */
@Composable
fun WelcomeScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val still = rememberReduceMotion()
    val clock = remember { mutableLongStateOf(0L) }
    val exitAt = remember { mutableLongStateOf(if (still) STILL_EXIT_AT else WelcomeMotion.EXIT_AT) }
    val done by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        var origin = -1L
        while (!WelcomeMotion.finished(clock.longValue, exitAt.longValue)) {
            withFrameMillis { ms ->
                if (origin < 0) origin = ms
                clock.longValue = ms - origin
            }
        }
        done()
    }

    val skip = { exitAt.longValue = minOf(exitAt.longValue, clock.longValue) }
    val ext = AppTheme.extended

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                val e = WelcomeMotion.exit(clock.longValue, exitAt.longValue)
                alpha = 1f - e
                if (!still) {
                    val s = 1f + 0.06f * e
                    scaleX = s
                    scaleY = s
                }
            }
            .background(Nocturne.Obsidian)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = skip)
            .clearAndSetSemantics {
                contentDescription = "Welcome to Zmusic"
                onClick(label = "Skip") { skip(); true }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.longValue
            val lift = WelcomeMotion.lift(t, still)
            val scale = 1f + (LIFT_SCALE - 1f) * lift
            val unit = SPLASH_ICON.toPx() / WelcomeMotion.VIEWPORT * scale
            val center = Offset(size.width / 2f, size.height / 2f - LIFT.toPx() * lift)

            // Warm light behind the amber bar.
            val glow = WelcomeMotion.glow(t, still)
            if (glow > 0f) {
                val r = 64.dp.toPx() * (0.8f + 0.4f * scale)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to BarAmber.copy(alpha = 0.30f * glow),
                        0.45f to BarAmber.copy(alpha = 0.10f * glow),
                        1f to Color.Transparent,
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                    center = center,
                )
            }

            // Two rings, like sound leaving a speaker, as the middle bar springs back.
            if (!still) {
                for (n in 0..1) {
                    val p = WelcomeMotion.ripple(n, t)
                    if (p < 0f || p >= 1f) continue
                    val eased = 1f - (1f - p) * (1f - p) * (1f - p)
                    val radius = 30.dp.toPx() + 190.dp.toPx() * eased
                    val alpha = (1f - p) * (if (n == 0) 0.55f else 0.32f)
                    drawCircle(
                        color = BarAmber.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = (2.5f - 1.5f * p).dp.toPx()),
                    )
                }
            }

            // The logo: five rounded bars, the middle one amber.
            for (i in WelcomeMotion.BAR_X.indices) {
                val x = center.x + (WelcomeMotion.BAR_X[i] - WelcomeMotion.VIEWPORT / 2f) * unit
                val half = WelcomeMotion.BAR_HALF[i] * unit * WelcomeMotion.barScale(i, t, still)
                drawLine(
                    color = if (i == WelcomeMotion.CENTER_BAR) BarAmber else BarLight,
                    start = Offset(x, center.y - half),
                    end = Offset(x, center.y + half),
                    strokeWidth = WelcomeMotion.BAR_STROKE * unit,
                    cap = StrokeCap.Round,
                )
            }
        }

        Column(
            Modifier.align(Alignment.Center).offset(y = WORDS_OFFSET),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "WELCOME TO",
                style = MaterialTheme.typography.labelMedium,
                color = ext.textSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    val p = WelcomeMotion.caption(clock.longValue, still)
                    alpha = p
                    translationY = (1f - p) * 10.dp.toPx()
                },
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                NAME.forEachIndexed { i, c ->
                    Text(
                        c.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        color = ext.text,
                        modifier = Modifier.graphicsLayer {
                            val p = WelcomeMotion.letter(i, clock.longValue, still)
                            alpha = p
                            translationY = (1f - p) * 22.dp.toPx()
                        },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .width(40.dp)
                    .height(3.dp)
                    .graphicsLayer {
                        val p = WelcomeMotion.rule(clock.longValue, still)
                        scaleX = p
                        alpha = p
                    }
                    .background(ext.amber, MaterialTheme.shapes.extraSmall),
            )
        }
    }
}
