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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import com.zmusic.app.playback.AudioFrequencyTracker
import com.zmusic.app.ui.theme.Nocturne
import kotlin.math.cos
import kotlin.math.sin

/*
 * Three soft light orbs drifting on slow Lissajous paths over the obsidian base.
 *
 *   uBass   swells every orb and brightens the warm core
 *   uMid    bends the whole field (a slow liquid warp) and shifts the cool orb's hue
 *   uTreble adds a fine, fast shimmer on top of the warp
 *
 * Kept deliberately cheap: no loops, no texture reads, three exp() calls per pixel. It runs
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

    float y = fragCoord.y / uSize.y;
    col *= 1.0 - 0.35 * smoothstep(0.55, 1.0, y);

    col += (hash21(fragCoord) - 0.5) * (1.5 / 255.0);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/**
 * The app's backdrop: audio-reactive aurora light on obsidian.
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
    if (Build.VERSION.SDK_INT >= 33 && shader != null) {
        AgslAurora(tracker, shader, modifier)
    } else {
        CanvasAurora(tracker, modifier)
    }
}

@RequiresApi(33)
private fun createAuroraShader(): RuntimeShader? = try {
    RuntimeShader(AURORA_AGSL)
} catch (e: IllegalArgumentException) {
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
