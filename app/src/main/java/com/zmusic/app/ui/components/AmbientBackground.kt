package com.zmusic.app.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.zmusic.app.debug.BugLog
import com.zmusic.app.playback.AudioFrequencyTracker
import com.zmusic.app.ui.theme.Nocturne
import kotlin.math.cos
import kotlin.math.floor
import kotlin.random.Random
import kotlin.math.sin

/*
 * Three soft light orbs drifting on slow Lissajous paths over the obsidian base, two aurora
 * curtains waving across them, and a ring of light that the bass pushes out of the warm orb.
 *
 *   uBass   swells every orb, brightens the warm core and drives the ring
 *   uMid    bends the whole field (a slow liquid warp), shifts the cool orb's hue, lifts the curtains
 *   uTreble adds a fine, fast shimmer on top of the warp and makes the upper curtain glint
 *
 * Kept deliberately cheap: no loops, no texture reads, six exp() calls per pixel. It runs
 * full-screen behind everything at up to 120 Hz, so it has to cost almost nothing.
 * The last line dithers by less than one 8-bit step, which removes banding in the dark gradients.
 */
private const val AURORA_AGSL = """
uniform float2 uSize;
uniform float uTime;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
layout(color) uniform half4 uBase;
layout(color) uniform half4 uAmber;
layout(color) uniform half4 uIndigo;
layout(color) uniform half4 uSlate;

float hash21(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

float orb(float2 p, float2 centre, float radius) {
    float2 d = p - centre;
    return exp(-dot(d, d) / (radius * radius));
}

// A soft band of light whose centre line waves along x.
float curtain(float2 p, float y0, float amp, float freq, float speed, float width, float t) {
    float y = y0 + amp * sin(p.x * freq + t * speed) + amp * 0.5 * sin(p.x * freq * 2.3 - t * speed * 1.7);
    float d = (p.y - y) / width;
    return exp(-d * d);
}

half4 main(float2 fragCoord) {
    float aspect = uSize.y / uSize.x;
    float2 p = fragCoord / uSize.x;
    float t = uTime;

    float warp = 0.035 + 0.060 * uMid;
    p += warp * float2(sin(p.y * 3.1 + t * 0.31), cos(p.x * 2.7 - t * 0.27));
    p += (0.004 + 0.012 * uTreble) * float2(sin(p.y * 13.0 - t * 1.7), cos(p.x * 11.0 + t * 1.3));

    float swell = 1.0 + 0.38 * uBass;
    float gain = 0.50 + 0.60 * uBass;

    float2 c1 = float2(0.22 + 0.10 * sin(t * 0.11), aspect * (0.16 + 0.05 * cos(t * 0.09)));
    float2 c2 = float2(0.84 + 0.09 * cos(t * 0.08), aspect * (0.40 + 0.08 * sin(t * 0.07)));
    float2 c3 = float2(0.35 + 0.14 * sin(t * 0.06 + 2.0), aspect * (0.80 + 0.06 * cos(t * 0.10)));

    float warm = orb(p, c1, 0.42 * swell);
    float cool = orb(p, c2, 0.50 * swell);
    float low = orb(p, c3, 0.46 * swell);

    float drift = 0.5 + 0.5 * sin(t * 0.21 + p.x * 2.0 + uMid * 2.5);

    float3 base = float3(uBase.rgb);
    float3 amber = float3(uAmber.rgb);
    float3 indigo = float3(uIndigo.rgb);
    float3 slate = float3(uSlate.rgb);

    float3 col = base;
    col += amber * (warm * gain * 0.26);
    col += mix(indigo, slate, drift) * (cool * gain * 0.34);
    col += mix(slate, amber, drift * 0.35) * (low * gain * 0.20);
    col += amber * (warm * warm * warm * uBass * 0.18);

    float c1Band = curtain(p, aspect * 0.30, 0.05 + 0.04 * uMid, 5.0, 0.35, 0.040 + 0.030 * uBass, t);
    float c2Band = curtain(p, aspect * 0.63, 0.06 + 0.03 * uMid, 3.6, -0.27, 0.055 + 0.030 * uBass, t);
    float sweep = 0.55 + 0.45 * sin(p.x * 2.2 + t * 0.17);
    float3 hue1 = mix(indigo, amber, 0.5 + 0.5 * sin(t * 0.13 + p.x * 3.0));
    float3 hue2 = mix(slate, indigo, 0.5 + 0.5 * cos(t * 0.11 - p.x * 2.0));
    col += hue1 * (c1Band * sweep * (0.09 + 0.16 * uTreble + 0.08 * uBass));
    col += hue2 * (c2Band * (1.0 - 0.6 * sweep) * (0.10 + 0.14 * uMid));

    float ringD = (length(p - c1) - (0.10 + 0.45 * uBass)) / 0.035;
    col += amber * (exp(-ringD * ringD) * uBass * uBass * 0.10);

    float y = fragCoord.y / uSize.y;
    col *= 1.0 - 0.35 * smoothstep(0.55, 1.0, y);

    col += (hash21(fragCoord) - 0.5) * (1.5 / 255.0);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/**
 * The app's backdrop: audio-reactive aurora light on obsidian, with a layer of drifting stardust
 * and the occasional shooting star in front of it.
 *
 * Android 13+ (API 33, where `RuntimeShader` exists) runs the AGSL shader above. Older versions,
 * or a device whose driver rejects the shader, get the same three orbs drawn as radial gradients
 * on a Canvas, moving on the same paths and reacting to the same three bands.
 *
 * Either way it redraws by reading [AudioFrequencyTracker.frame] inside the draw lambda, so it
 * never recomposes, and it stops with the tracker's frame loop when the app is not visible.
 */
@Composable
fun AmbientAudioShaderBackground(modifier: Modifier = Modifier) {
    val tracker = LocalAudioTracker.current
    val shader = if (Build.VERSION.SDK_INT >= 33) remember { createAuroraShader() } else null
    Box(modifier) {
        if (Build.VERSION.SDK_INT >= 33 && shader != null) {
            AgslAurora(tracker, shader, Modifier.matchParentSize())
        } else {
            CanvasAurora(tracker, Modifier.matchParentSize())
        }
        Stardust(tracker, Modifier.matchParentSize())
    }
}

@RequiresApi(33)
private fun createAuroraShader(): RuntimeShader? = try {
    RuntimeShader(AURORA_AGSL)
} catch (e: IllegalArgumentException) {
    BugLog.w("Backdrop", "Aurora shader was rejected by this device, using the gradient fallback", e)
    null
}

@RequiresApi(33)
@Composable
private fun AgslAurora(tracker: AudioFrequencyTracker, shader: RuntimeShader, modifier: Modifier) {
    Box(
        modifier.drawWithCache {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setColorUniform("uBase", Nocturne.Obsidian.toArgb())
            shader.setColorUniform("uAmber", Nocturne.Amber.toArgb())
            shader.setColorUniform("uIndigo", Nocturne.Indigo.toArgb())
            shader.setColorUniform("uSlate", Nocturne.Slate.toArgb())
            val brush = ShaderBrush(shader)
            onDrawBehind {
                tracker.frame.longValue // redraw every frame without recomposing
                shader.setFloatUniform("uTime", tracker.seconds)
                shader.setFloatUniform("uBass", tracker.bass.value)
                shader.setFloatUniform("uMid", tracker.mid.value)
                shader.setFloatUniform("uTreble", tracker.treble.value)
                drawRect(brush)
            }
        },
    )
}

/** Gradient fallback. Brushes are built once at unit size and placed with transforms, so a frame allocates nothing. */
@Composable
private fun CanvasAurora(tracker: AudioFrequencyTracker, modifier: Modifier) {
    Box(
        modifier.drawWithCache {
            fun unitOrb(color: Color) = Brush.radialGradient(
                0f to color,
                0.45f to color.copy(alpha = color.alpha * 0.42f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val warm = unitOrb(Nocturne.Amber.copy(alpha = 0.30f))
            val cool = unitOrb(Nocturne.Indigo.copy(alpha = 0.34f))
            val low = unitOrb(Nocturne.Slate.copy(alpha = 0.40f))
            val calm = Brush.verticalGradient(
                0.55f to Color.Transparent,
                1f to Nocturne.Obsidian.copy(alpha = 0.45f),
            )
            onDrawBehind {
                tracker.frame.longValue
                val t = tracker.seconds
                val bass = tracker.bass.value
                val mid = tracker.mid.value
                val w = size.width
                val h = size.height
                val swell = 1f + 0.38f * bass
                val gain = (0.55f + 0.45f * bass).coerceAtMost(1f)
                val sway = 0.04f * mid * w
                drawRect(Nocturne.Obsidian)
                orb(warm, w * (0.22f + 0.10f * sin(t * 0.11f)) + sway * sin(t * 0.31f), h * (0.16f + 0.05f * cos(t * 0.09f)), w * 0.70f * swell, gain)
                orb(cool, w * (0.84f + 0.09f * cos(t * 0.08f)), h * (0.40f + 0.08f * sin(t * 0.07f)) + sway * cos(t * 0.27f), w * 0.82f * swell, gain)
                orb(low, w * (0.35f + 0.14f * sin(t * 0.06f + 2f)) - sway * sin(t * 0.23f), h * (0.80f + 0.06f * cos(t * 0.10f)), w * 0.76f * swell, gain)
                drawRect(calm)
            }
        },
    )
}

private fun DrawScope.orb(brush: Brush, cx: Float, cy: Float, radius: Float, alpha: Float) {
    translate(cx, cy) {
        scale(radius, radius, pivot = Offset.Zero) {
            drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = alpha)
        }
    }
}

private const val MOTES = 54
private const val STAR_PERIOD = 11f
private const val STAR_LIFE = 0.9f
private val MoteTints = arrayOf(Color.White, Color(0xFFFFD9A8), Color(0xFFB9BCFF))

private fun fract(v: Float) = v - floor(v)

/**
 * Foreground particles for the backdrop, on every Android version:
 *
 * - Motes: small points of light rising slowly, swaying and twinkling. Bass makes them swell,
 *   treble makes them sparkle. Nearer ones are bigger and faster, which gives the field depth.
 * - A shooting star every [STAR_PERIOD] seconds, on a different line each time.
 *
 * Every position is a pure function of the tracker's clock, so there is no per-particle state,
 * a frame allocates nothing, and it all stops with the tracker when motion is turned off.
 */
@Composable
private fun Stardust(tracker: AudioFrequencyTracker, modifier: Modifier) {
    // Fixed seed: the field looks the same on every launch instead of reshuffling.
    val seeds = remember {
        val r = Random(7)
        FloatArray(MOTES * 5) { r.nextFloat() }
    }
    Box(
        modifier.drawWithCache {
            val unit = 1.dp.toPx()
            onDrawBehind {
                tracker.frame.longValue // redraw every frame without recomposing
                val t = tracker.seconds
                val bass = tracker.bass.value
                val treble = tracker.treble.value
                val w = size.width
                val h = size.height

                for (i in 0 until MOTES) {
                    val k = i * 5
                    val depth = 0.35f + 0.65f * seeds[k + 2]
                    val phase = seeds[k + 3] * 6.283f
                    val x = (seeds[k] + 0.035f * sin(t * (0.25f + 0.3f * seeds[k + 4]) + phase)) * w
                    // Rises off the top and wraps to just below the bottom edge.
                    val y = (fract(seeds[k + 1] - t * 0.012f * depth) * 1.1f - 0.05f) * h
                    val twinkle = 0.5f + 0.5f * sin(t * (0.9f + 2.2f * seeds[k + 4]) + phase)
                    val alpha = (0.10f + 0.50f * twinkle * (0.45f + 0.55f * treble)) * depth
                    val radius = unit * (0.6f + 1.5f * depth) * (1f + 0.55f * bass)
                    val tint = MoteTints[i % MoteTints.size]
                    val centre = Offset(x, y)
                    drawCircle(tint, radius * 3.4f, centre, alpha = alpha * 0.16f)
                    drawCircle(tint, radius, centre, alpha = alpha)
                }

                val cycle = floor(t / STAR_PERIOD)
                val local = t - cycle * STAR_PERIOD
                if (local < STAR_LIFE) {
                    val life = local / STAR_LIFE
                    // Cheap per-cycle hashes for where the streak starts and how steep it falls.
                    val a = fract(sin(cycle * 12.9898f) * 43758.547f)
                    val b = fract(sin(cycle * 78.233f) * 12543.123f)
                    val dirX = 0.80f + 0.15f * b
                    val dirY = 0.36f + 0.18f * a
                    val headX = (-0.10f + 0.70f * a) * w + dirX * w * 0.55f * life
                    val headY = (0.04f + 0.32f * b) * h + dirY * w * 0.55f * life
                    val tail = w * 0.22f
                    // Fades in fast, out slow.
                    val glow = (if (life < 0.15f) life / 0.15f else (1f - life) / 0.85f) * 0.85f
                    // Four overlapping strokes, each shorter and brighter, stand in for a gradient.
                    for (seg in 0 until 4) {
                        val f = 1f - seg / 4f
                        drawLine(
                            color = Color.White,
                            start = Offset(headX - dirX * tail * f, headY - dirY * tail * f),
                            end = Offset(headX, headY),
                            strokeWidth = unit * (0.7f + 0.45f * seg),
                            cap = StrokeCap.Round,
                            alpha = glow * (0.10f + 0.16f * seg),
                        )
                    }
                    drawCircle(Color.White, unit * 5f, Offset(headX, headY), alpha = glow * 0.22f)
                }
            }
        },
    )
}
