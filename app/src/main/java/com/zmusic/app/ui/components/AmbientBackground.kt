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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.zmusic.app.debug.BugLog
import com.zmusic.app.playback.AudioFrequencyTracker
import com.zmusic.app.ui.theme.Nocturne
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

// Scene geometry, shared by the shader and the Canvas fallback. Lengths are fractions of the
// screen width, so the scene keeps its proportions on any phone.
private const val HOLE_X = 0.5f
private const val HOLE_Y = 0.34f // fraction of the screen height
private const val HOLE_R = 0.085f
private const val DISC_TILT = 0.24f // how flat the disc and the orbits look (1 = seen from above)
private const val ROLL_DEG = -18.3f // the whole system leans by this much
private const val TAU = 6.2831855f

/*
 * A black hole with a tilted accretion disc, four planets on orbits in the disc's plane, and a
 * star field that bends around the hole.
 *
 *   uSpin   rotation of the disc pattern in radians. The host advances it only while a song
 *           plays, so the ring turns with the music and coasts to a stop on pause.
 *   uBass   brightens the disc, the halo and the glow around the hole
 *   uMid    widens the disc's outer edge and lifts the nebula
 *   uTreble makes the stars twinkle harder
 *
 * Nothing here is ray-marched. The disc is a squashed annulus, the far side of the disc that
 * gravity bends over the top of the hole is a soft ring hugging the shadow, and the star lensing
 * is the point-lens formula. Cost per pixel: two atan, four exp, four small sphere tests.
 * The last line dithers by less than one 8-bit step, which removes banding in the dark gradients.
 */
private const val BLACK_HOLE_AGSL = """
uniform float2 uSize;
uniform float uTime;
uniform float uSpin;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
layout(color) uniform half4 uBase;
layout(color) uniform half4 uAmber;
layout(color) uniform half4 uIndigo;
layout(color) uniform half4 uSlate;

const float R = 0.085;
const float K = 0.24;
const float ROLL_C = 0.9494;
const float ROLL_S = 0.3140;

float hash21(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

// One planet: rgb is its shaded colour, a is how much of this pixel it covers.
// It is lit from the hole, so it shows a full face on the far side and a crescent up close.
float4 planet(float2 d, float orbit, float size, float th, float3 tint, float px, float outside) {
    float sn = sin(th);
    float cs = cos(th);
    float2 centre = float2(orbit * cs, orbit * sn * K);
    float sz = size * (1.0 + 0.18 * sn);
    float2 e = (d - centre) / sz;
    float e2 = dot(e, e);
    float nz = sqrt(max(1.0 - e2, 0.0));
    float lit = max(dot(float3(e, nz), float3(-cs, -sn * K, -sn * 0.97)), 0.0);
    float cover = 1.0 - smoothstep(1.0 - 2.0 * px / sz, 1.0, sqrt(e2));
    // On the far half of its orbit the planet passes behind the hole.
    cover *= mix(outside, 1.0, step(0.0, sn));
    return float4(tint * (0.22 + 0.78 * lit), cover);
}

float orbitLine(float qLen, float orbit, float px) {
    return 1.0 - smoothstep(0.0, 2.5 * px, abs(qLen - orbit));
}

half4 main(float2 fragCoord) {
    float aspect = uSize.y / uSize.x;
    float px = 1.0 / uSize.x;
    float2 uv = fragCoord * px;
    float2 holeAt = float2(0.5, aspect * 0.34);
    float2 d0 = uv - holeAt;
    float2 d = float2(d0.x * ROLL_C - d0.y * ROLL_S, d0.x * ROLL_S + d0.y * ROLL_C);
    float r = length(d);
    float t = uTime;

    float3 base = float3(uBase.rgb);
    float3 amber = float3(uAmber.rgb);
    float3 indigo = float3(uIndigo.rgb);
    float3 slate = float3(uSlate.rgb);
    float3 hot = mix(amber, float3(1.0), 0.72);
    float3 ember = amber * float3(1.0, 0.55, 0.32);

    float outside = smoothstep(R * 0.97, R * 1.03, r);
    float gain = 0.62 + 0.75 * uBass;

    // Deep space: two faint nebula clouds so the corners are not flat black.
    float2 n1 = uv - float2(0.88, aspect * 0.80);
    float2 n2 = uv - float2(0.08, aspect * 0.10);
    float3 col = base;
    col += mix(indigo, slate, 0.5 + 0.5 * sin(t * 0.07)) * (exp(-dot(n1, n1) / 0.30) * (0.13 + 0.10 * uMid));
    col += slate * (exp(-dot(n2, n2) / 0.20) * 0.10);

    // Stars, seen through the hole's gravity: light from behind is pushed outward around it.
    float lens = (R * 1.5) * (R * 1.5) / (r * r + 0.00001);
    float2 g = (holeAt + d0 * (1.0 - lens)) * 26.0;
    float2 cell = floor(g);
    float h = hash21(cell);
    float2 inCell = fract(g) - 0.5 - (float2(hash21(cell + 17.0), hash21(cell + 41.0)) - 0.5) * 0.7;
    float twinkle = 0.5 + 0.5 * sin(t * (0.8 + 3.0 * h) + h * 40.0);
    float starSize = 0.035 + 0.060 * fract(h * 7.0);
    float star = step(0.70, h) * (1.0 - smoothstep(0.0, starSize, length(inCell)));
    float3 starTint = mix(float3(1.0), mix(amber, indigo, step(0.5, fract(h * 13.0))), 0.35);
    col += starTint * (star * (0.30 + 0.70 * twinkle * (0.45 + 0.55 * uTreble)));

    // Warm glow of everything falling in.
    col += amber * (exp(-r * r / 0.060) * 0.060 * gain);

    // The event horizon swallows all of the above.
    col *= outside;

    // Disc plane coordinates: undo the tilt, then measure in hole radii.
    float2 q = float2(d.x, d.y / K);
    float qLen = length(q);
    float rr = qLen / R;
    float ang = atan(q.y, q.x);
    float nearSide = step(0.0, q.y);
    float seen = max(nearSide, outside);

    col += slate * (0.16 * seen * (orbitLine(qLen, 0.36, px) + orbitLine(qLen, 0.47, px)
        + orbitLine(qLen, 0.61, px) + orbitLine(qLen, 0.79, px)));

    float3 dust = mix(amber, float3(1.0), 0.25);
    float3 ice = mix(slate, float3(1.0), 0.50);
    float3 rose = mix(amber, indigo, 0.5) * 1.15;
    float4 p1 = planet(d, 0.36, 0.018, t * 0.130900 + 0.6, dust, px, outside);
    float4 p2 = planet(d, 0.47, 0.026, t * 0.078540 + 2.9, indigo * 1.25, px, outside);
    float4 p3 = planet(d, 0.61, 0.019, t * 0.052360 + 4.4, ice, px, outside);
    float4 p4 = planet(d, 0.79, 0.034, t * 0.031416 + 1.7, rose, px, outside);
    col = mix(col, p1.rgb, p1.a);
    col = mix(col, p2.rgb, p2.a);
    col = mix(col, p3.rgb, p3.a);
    col = mix(col, p4.rgb, p4.a);

    // Far side of the disc, bent up and over the hole: a ring that hugs the shadow, taller on top.
    float hEdge = r / R - 1.0;
    float a2 = atan(d.y, d.x);
    float top = smoothstep(0.5, -0.7, d.y / max(r, 0.0001));
    float halo = smoothstep(0.0, 0.05, hEdge) * exp(-hEdge / mix(0.20, 0.42, top)) * mix(0.35, 1.0, top);
    halo *= 0.75 + 0.25 * sin(5.0 * (a2 + uSpin) + hEdge * 7.0);
    halo *= 1.0 - 0.40 * cos(a2);
    float ringD = (r / R - 1.035) / 0.030;
    float photon = exp(-ringD * ringD);
    col += hot * ((halo * 0.90 + photon * 0.55) * gain);

    // The disc itself. Two swirl layers at different speeds read as gas shearing, the lanes are
    // fine concentric ringlets, and the side turning toward the viewer is brighter.
    float band = smoothstep(1.45, 1.75, rr) * (1.0 - smoothstep(2.0, 3.2 + 0.4 * uMid, rr));
    float swirl = 0.55 + 0.24 * sin(3.0 * (ang + uSpin) + rr * 3.0) + 0.14 * sin(7.0 * (ang + 2.0 * uSpin) - rr * 6.0);
    float lanes = 0.72 + 0.28 * sin(rr * 19.0 + 2.0 * (ang + uSpin));
    float doppler = 1.0 - 0.55 * cos(ang);
    float heat = 1.0 - smoothstep(1.5, 3.0, rr);
    float3 discCol = mix(ember, hot, heat * heat);
    col += discCol * (band * swirl * lanes * doppler * seen * gain * 1.15);

    float y = fragCoord.y / uSize.y;
    col *= 1.0 - 0.35 * smoothstep(0.55, 1.0, y);

    col += (hash21(fragCoord) - 0.5) * (1.5 / 255.0);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/**
 * The app's backdrop: a black hole whose ring turns while a song plays, planets orbiting it,
 * and the occasional shooting star in front.
 *
 * Android 13+ (API 33, where `RuntimeShader` exists) runs the AGSL shader above. Older versions,
 * or a device whose driver rejects the shader, get a simpler version of the same scene drawn
 * with gradients on a Canvas: same hole, same planets on the same orbits, no star lensing.
 *
 * Either way it redraws by reading [AudioFrequencyTracker.frame] inside the draw lambda, so it
 * never recomposes, and it stops with the tracker's frame loop when the app is not visible.
 */
@Composable
fun AmbientAudioShaderBackground(modifier: Modifier = Modifier) {
    val tracker = LocalAudioTracker.current
    val spin = remember { RingSpin() }
    val shader = if (Build.VERSION.SDK_INT >= 33) remember { createBlackHoleShader() } else null
    Box(modifier) {
        if (Build.VERSION.SDK_INT >= 33 && shader != null) {
            AgslBlackHole(tracker, spin, shader, Modifier.matchParentSize())
        } else {
            CanvasBlackHole(tracker, spin, Modifier.matchParentSize())
        }
        ShootingStar(tracker, Modifier.matchParentSize())
    }
}

/**
 * The disc's rotation angle. It speeds up when playback starts (faster on heavy bass), and
 * coasts down over a couple of seconds when playback stops, like something with mass.
 * Call [advance] once per drawn frame, on the main thread.
 */
private class RingSpin {
    private var angle = 0f
    private var rate = 0f
    private var lastSeconds = -1f

    fun advance(tracker: AudioFrequencyTracker): Float {
        val now = tracker.seconds
        var dt = if (lastSeconds < 0f) 0f else now - lastSeconds
        if (dt < 0f) dt += 3600f // the tracker's clock wraps once an hour
        dt = dt.coerceIn(0f, 0.05f)
        lastSeconds = now

        val playing = tracker.source != AudioFrequencyTracker.Source.Breathing
        val target = if (playing) 0.45f + 1.10f * tracker.bass.value else 0f
        rate += (target - rate) * (1f - exp(-dt * 1.6f))
        // Every angular term in the shader is a whole multiple of the spin, so wrapping is seamless.
        angle = (angle + rate * dt) % TAU
        return angle
    }
}

@RequiresApi(33)
private fun createBlackHoleShader(): RuntimeShader? = try {
    RuntimeShader(BLACK_HOLE_AGSL)
} catch (e: IllegalArgumentException) {
    BugLog.w("Backdrop", "Black hole shader was rejected by this device, using the gradient fallback", e)
    null
}

@RequiresApi(33)
@Composable
private fun AgslBlackHole(tracker: AudioFrequencyTracker, spin: RingSpin, shader: RuntimeShader, modifier: Modifier) {
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
                shader.setFloatUniform("uSpin", spin.advance(tracker))
                shader.setFloatUniform("uBass", tracker.bass.value)
                shader.setFloatUniform("uMid", tracker.mid.value)
                shader.setFloatUniform("uTreble", tracker.treble.value)
                drawRect(brush)
            }
        },
    )
}

private const val STARS = 70

// Orbit radius and planet radius (fractions of screen width), angular speed (rad/s), start angle.
// Each speed is a whole number of turns per hour, so the tracker's hourly clock wrap is invisible.
private val PlanetOrbit = floatArrayOf(0.36f, 0.47f, 0.61f, 0.79f)
private val PlanetSize = floatArrayOf(0.018f, 0.026f, 0.019f, 0.034f)
private val PlanetSpeed = floatArrayOf(0.130900f, 0.078540f, 0.052360f, 0.031416f)
private val PlanetPhase = floatArrayOf(0.6f, 2.9f, 4.4f, 1.7f)
private val PlanetTint = arrayOf(Color(0xFFE9BF90), Color(0xFF8083F0), Color(0xFF959CB3), Color(0xFFC0A0B8))

/** Gradient fallback. Brushes are built once at unit size and placed with transforms, so a frame allocates nothing. */
@Composable
private fun CanvasBlackHole(tracker: AudioFrequencyTracker, spin: RingSpin, modifier: Modifier) {
    // Fixed seed: the sky looks the same on every launch instead of reshuffling.
    val seeds = remember {
        val r = Random(7)
        FloatArray(STARS * 4) { r.nextFloat() }
    }
    Box(
        modifier.drawWithCache {
            val unit = 1.dp.toPx()
            val hot = Color(0xFFF7E8D6)
            val nebula = Brush.radialGradient(
                0f to Nocturne.Indigo.copy(alpha = 0.20f),
                0.5f to Nocturne.Slate.copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // Unit radius is the disc's outer edge at 3.3 hole radii; the gap inside is the hole.
            val discBrush = Brush.radialGradient(
                0.42f to Color.Transparent,
                0.52f to hot.copy(alpha = 0.90f),
                0.70f to Nocturne.Amber.copy(alpha = 0.50f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // Brighter lobes that turn with the spin, laid over the disc.
            val streaks = Brush.sweepGradient(
                0f to Color.Transparent,
                0.12f to hot.copy(alpha = 0.34f),
                0.30f to Color.Transparent,
                0.46f to hot.copy(alpha = 0.22f),
                0.60f to Color.Transparent,
                0.78f to hot.copy(alpha = 0.30f),
                1f to Color.Transparent,
                center = Offset.Zero,
            )
            // Unit radius is 2.4 hole radii, so the shadow's edge sits at 0.417.
            val halo = Brush.radialGradient(
                0.41f to Color.Transparent,
                0.44f to hot.copy(alpha = 0.70f),
                0.62f to Nocturne.Amber.copy(alpha = 0.20f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val calm = Brush.verticalGradient(
                0.55f to Color.Transparent,
                1f to Nocturne.Obsidian.copy(alpha = 0.45f),
            )
            onDrawBehind {
                tracker.frame.longValue
                val t = tracker.seconds
                val bass = tracker.bass.value
                val treble = tracker.treble.value
                val spinDeg = spin.advance(tracker) * (360f / TAU)
                val w = size.width
                val h = size.height
                val holeR = w * HOLE_R
                val gain = (0.62f + 0.75f * bass).coerceAtMost(1f)

                drawRect(Nocturne.Obsidian)
                orb(nebula, w * 0.88f, h * 0.80f, w * 0.90f, 1f)

                for (i in 0 until STARS) {
                    val k = i * 4
                    val twinkle = 0.5f + 0.5f * sin(t * (0.8f + 3f * seeds[k + 3]) + seeds[k + 3] * 40f)
                    val alpha = (0.25f + 0.60f * twinkle * (0.45f + 0.55f * treble)) * (0.4f + 0.6f * seeds[k + 2])
                    drawCircle(Color.White, unit * (0.5f + 0.9f * seeds[k + 2]), Offset(seeds[k] * w, seeds[k + 1] * h), alpha = alpha)
                }

                withTransform({
                    translate(w * HOLE_X, h * HOLE_Y)
                    rotate(ROLL_DEG, Offset.Zero)
                }) {
                    // Back to front: far planets, the disc, the hole and its halo, then the near
                    // half of the disc again so it crosses in front of the shadow, near planets.
                    planets(t, w, far = true)
                    drawDisc(discBrush, streaks, holeR, spinDeg, gain)
                    drawCircle(Color.Black, holeR, Offset.Zero)
                    orb(halo, 0f, 0f, holeR * 2.4f, gain)
                    clipRect(left = -2f * w, top = 0f, right = 2f * w, bottom = 2f * h) {
                        drawDisc(discBrush, streaks, holeR, spinDeg, gain)
                    }
                    drawCircle(hot, holeR * 1.035f, Offset.Zero, alpha = 0.55f * gain, style = Stroke(unit * 1.5f))
                    planets(t, w, far = false)
                }
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

/** The accretion disc, centred on the origin: a glowing annulus squashed by [DISC_TILT], with lobes turned by [spinDeg]. */
private fun DrawScope.drawDisc(disc: Brush, streaks: Brush, holeR: Float, spinDeg: Float, gain: Float) {
    withTransform({
        scale(1f, DISC_TILT, Offset.Zero)
        rotate(-spinDeg, Offset.Zero)
    }) {
        orb(disc, 0f, 0f, holeR * 3.3f, gain)
        drawCircle(streaks, holeR * 2.35f, Offset.Zero, alpha = gain, style = Stroke(holeR * 1.5f))
    }
}

/** Draws the planets on the far half of their orbits, or the ones on the near half. Origin is the hole. */
private fun DrawScope.planets(t: Float, w: Float, far: Boolean) {
    for (i in PlanetOrbit.indices) {
        val th = t * PlanetSpeed[i] + PlanetPhase[i]
        val sn = sin(th)
        if ((sn < 0f) != far) continue
        // Lit from the hole: bright on the far side, where we see its day side, dim up close.
        val shade = 0.22f + 0.78f * (0.5f - 0.5f * sn)
        val tint = PlanetTint[i]
        drawCircle(
            color = Color(tint.red * shade, tint.green * shade, tint.blue * shade),
            radius = w * PlanetSize[i] * (1f + 0.18f * sn),
            center = Offset(w * PlanetOrbit[i] * cos(th), w * PlanetOrbit[i] * sn * DISC_TILT),
        )
    }
}

private const val STAR_PERIOD = 11f
private const val STAR_LIFE = 0.9f

private fun fract(v: Float) = v - floor(v)

/**
 * A shooting star every [STAR_PERIOD] seconds, on a different line each time, on every Android
 * version. Its position is a pure function of the tracker's clock, so there is no state, a frame
 * allocates nothing, and it stops with the tracker when motion is turned off.
 */
@Composable
private fun ShootingStar(tracker: AudioFrequencyTracker, modifier: Modifier) {
    Box(
        modifier.drawWithCache {
            val unit = 1.dp.toPx()
            onDrawBehind {
                tracker.frame.longValue // redraw every frame without recomposing
                val t = tracker.seconds
                val w = size.width
                val h = size.height

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
