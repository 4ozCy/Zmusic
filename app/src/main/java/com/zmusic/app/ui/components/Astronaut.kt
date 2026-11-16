package com.zmusic.app.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import com.zmusic.app.debug.BugLog
import com.zmusic.app.playback.AudioFrequencyTracker
import com.zmusic.app.ui.theme.Nocturne
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAU = 6.2831855f

// The figure is drawn in its own units (about a helmet's width), origin at the chest. Everything
// it draws, tether included, stays within this many units of the origin.
private const val REACH = 3.1f

/*
 * An astronaut drifting in front of the black hole, built from rounded boxes and tapered tubes
 * (signed distance fields) and lit like a figure in the scene would be:
 *
 *   the disc is the key light, and it is behind and to one side, so the suit is mostly in shadow
 *   with a warm rim on every edge that faces the hole. A dim cool fill from the stars keeps the
 *   shadowed side readable. Each part pads round at its edges like a pressurised suit, and casts a
 *   soft contact shadow on the parts behind it. The gold visor shows a small bright arc of the
 *   disc and a glint. The chest unit's amber light breathes with the bass.
 *
 *   uCenter, uScale      where the figure is on screen (px) and px per unit
 *   uRot                 cos and sin of minus its roll: turns screen space into the figure's frame
 *   uL, uH, uLxy         in the figure's frame: the unit vector toward the hole (the key light),
 *                        the Blinn half vector between it and the viewer, and its xy normalised
 *   uArm1/2, uHand1/2    elbow and wrist (xy, zw), then the end of the glove, of each arm
 *   uLeg1/2, uFoot1/2    knee and ankle, then the toe of the boot, of each leg
 *   uGain                bass, which brightens the key light
 *   uTime                the tether's slow wave
 *
 * Everything that is the same for every pixel (the pose, the light) is worked out once a frame
 * by AstronautPose. It is drawn only inside a square round the figure, so it costs very little.
 */
private const val ASTRONAUT_AGSL = """
uniform float2 uCenter;
uniform float uScale;
uniform float2 uRot;
uniform float uTime;
uniform float3 uL;
uniform float3 uH;
uniform float2 uLxy;
uniform float4 uArm1;
uniform float2 uHand1;
uniform float4 uArm2;
uniform float2 uHand2;
uniform float4 uLeg1;
uniform float2 uFoot1;
uniform float4 uLeg2;
uniform float2 uFoot2;
uniform float uGain;
layout(color) uniform half4 uAmber;
layout(color) uniform half4 uIndigo;
layout(color) uniform half4 uSlate;

float2 rot(float2 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return float2(c * p.x - s * p.y, s * p.x + c * p.y);
}

// Rounded box: signed distance in x, outward direction of the nearest edge in yz.
float3 sdgBox(float2 p, float2 b, float r) {
    float2 w = abs(p) - (b - r);
    float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    float g = max(w.x, w.y);
    float2 q = max(w, 0.0);
    float l = length(q);
    float dist = (g > 0.0 ? l : g) - r;
    float2 dir = g > 0.0 ? q / max(l, 1e-5) : (w.x > w.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    return float3(dist, s * dir);
}

// Tapered capsule from a (radius ra) to b (radius rb). x: signed distance (close enough for a
// gentle taper). yz: offset from the axis in radii, the sideways part of a tube's normal.
// w: how far along the axis, 0 at a and 1 at b.
float4 sdgLimb(float2 p, float2 a, float2 b, float ra, float rb) {
    float2 pa = p - a;
    float2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-5), 0.0, 1.0);
    float2 v = pa - ba * h;
    float r = mix(ra, rb, h);
    return float4(length(v) - r, v / r, h);
}

float3 sdgCapsule(float2 p, float2 a, float2 b, float r) {
    return sdgLimb(p, a, b, r, r).xyz;
}

float4 over(float4 acc, float3 c, float a) {
    return float4(c * a + acc.rgb * (1.0 - a), a + acc.a * (1.0 - a));
}

// Lit by the disc from behind (rim light on the edges facing it) and a dim cool fill from the front.
float3 shade(float3 n, float3 albedo, float3 L, float3 key, float3 fill, float3 amb, float gloss) {
    float ndl = dot(n, L);
    float wrap = max(ndl + 0.2, 0.0) / 1.2;
    float diff = wrap * wrap;
    float2 nl = n.xy / max(length(n.xy), 1e-4);
    float rim = pow(1.0 - n.z, 2.5) * smoothstep(0.05, 1.0, dot(nl, uLxy));
    float fl = max(dot(n, float3(-0.36, 0.56, 0.75)), 0.0);
    float spec = pow(max(dot(n, uH), 0.0), gloss);
    return albedo * (key * diff + fill * fl + amb) + key * (rim * 1.4 * (0.4 + 0.6 * albedo.g) + spec * 0.25);
}

// Lays one part over what is drawn so far. bevel > 0: a padded box whose edges round over in
// that distance. bevel <= 0: a tube or ball, whose normal comes straight from the capsule.
float4 part(float4 acc, float3 dg, float bevel, float3 albedo, float gloss, float aa,
            float3 L, float3 key, float3 fill, float3 amb) {
    // A soft contact shadow where this part sits over the ones behind it.
    acc.rgb *= 1.0 - 0.38 * exp(-max(dg.x, 0.0) / 0.06);
    float a = 1.0 - smoothstep(-aa, aa, dg.x);
    if (a <= 0.0) {
        return acc;
    }
    float2 nxy = bevel > 0.0 ? dg.yz * clamp(1.0 + dg.x / bevel, 0.0, 1.0) : dg.yz;
    float l2 = dot(nxy, nxy);
    if (l2 > 1.0) {
        nxy /= sqrt(l2);
        l2 = 1.0;
    }
    float3 n = float3(nxy, sqrt(1.0 - l2));
    return over(acc, shade(n, albedo, L, key, fill, amb, gloss), a);
}

// A suit segment that tapers from a to b. Bellows grooves gather towards b when folds > 0.
float4 limb(float4 acc, float2 q, float2 a, float2 b, float ra, float rb, float folds, float3 albedo,
            float aa, float3 L, float3 key, float3 fill, float3 amb) {
    float4 dg = sdgLimb(q, a, b, ra, rb);
    float groove = folds * smoothstep(0.62, 0.8, dg.w) * (0.5 + 0.5 * cos((1.0 - dg.w) * 6.2831853 * 6.0));
    return part(acc, dg.xyz, 0.0, albedo * (1.0 - 0.28 * groove), 6.0, aa, L, key, fill, amb);
}

half4 main(float2 fragCoord) {
    // Figure space: units of about a helmet's width, y up, origin at the chest.
    float2 p = fragCoord - uCenter;
    float2 q = float2(uRot.x * p.x - uRot.y * p.y, uRot.y * p.x + uRot.x * p.y) / uScale;
    q.y = -q.y;
    float aa = 0.9 / uScale;
    float3 L = uL;

    float3 amber = float3(uAmber.rgb * uAmber.rgb);
    float3 indigo = float3(uIndigo.rgb * uIndigo.rgb);
    float3 slate = float3(uSlate.rgb * uSlate.rgb);
    float3 key = mix(amber, float3(1.0, 0.93, 0.82), 0.25) * (1.1 + 0.9 * uGain);
    float3 fill = mix(slate, indigo, 0.3) * 0.13;
    float3 amb = slate * 0.04;

    // The relaxed crouch a body settles into without gravity: arms float up and out with the
    // elbows bent, knees bent. Shoulders and hips are fixed; the joints below them drift on
    // their own slow beats (see AstronautPose).
    float t = uTime;
    float2 s1 = float2(-0.52, 0.50);
    float2 e1 = uArm1.xy;
    float2 w1 = uArm1.zw;
    float2 g1 = uHand1;
    float2 s2 = float2(0.52, 0.50);
    float2 e2 = uArm2.xy;
    float2 w2 = uArm2.zw;
    float2 g2 = uHand2;
    float2 h1 = float2(-0.24, -0.74);
    float2 k1 = uLeg1.xy;
    float2 f1 = uLeg1.zw;
    float2 t1 = uFoot1;
    float2 h2 = float2(0.24, -0.74);
    float2 k2 = uLeg2.xy;
    float2 f2 = uLeg2.zw;
    float2 t2 = uFoot2;

    float3 suit = float3(0.70, 0.70, 0.68);
    float3 pack = float3(0.58, 0.58, 0.58);
    float3 glove = float3(0.42, 0.42, 0.44);
    float3 boot = float3(0.26, 0.26, 0.28);
    float3 trim = float3(0.30, 0.31, 0.34);
    float4 acc = float4(0.0);

    // Tether, trailing off behind and fading out.
    float2 ts = float2(-0.66, -0.20);
    float sx = ts.x - q.x;
    if (sx > 0.0 && sx < 2.3) {
        float wave = sin(sx * 2.4 - t * 0.5235988);
        float yc = ts.y - 0.25 * sx + 0.18 * wave;
        float slope = -0.25 + 0.432 * cos(sx * 2.4 - t * 0.5235988);
        float dist = abs(q.y - yc) / sqrt(1.0 + slope * slope) - 0.028;
        float ta = (1.0 - smoothstep(-aa, aa, dist)) * (1.0 - smoothstep(1.2, 2.3, sx));
        float rimT = smoothstep(0.0, 1.0, -L.y * sign(q.y - yc) * 0.5 + 0.5);
        acc = over(acc, trim * (fill + amb) + key * (0.10 + 0.25 * rimT), ta);
    }

    acc = part(acc, sdgBox(q - float2(-0.10, 0.25), float2(0.62, 0.72), 0.16), 0.18, pack, 8.0, aa, L, key, fill, amb);
    acc = limb(acc, q, s1, e1, 0.21, 0.17, 1.0, suit, aa, L, key, fill, amb);
    acc = limb(acc, q, e1, w1, 0.17, 0.14, 0.0, suit, aa, L, key, fill, amb);
    acc = part(acc, sdgCapsule(q, mix(e1, w1, 0.93), mix(e1, w1, 1.03), 0.15), 0.0, trim, 20.0, aa, L, key, fill, amb);
    acc = part(acc, sdgLimb(q, w1, g1, 0.12, 0.14).xyz, 0.0, glove, 10.0, aa, L, key, fill, amb);
    acc = limb(acc, q, h1, k1, 0.26, 0.21, 1.0, suit, aa, L, key, fill, amb);
    acc = limb(acc, q, k1, f1, 0.21, 0.17, 0.0, suit, aa, L, key, fill, amb);
    acc = part(acc, sdgLimb(q, f1, t1, 0.18, 0.15).xyz, 0.0, boot, 12.0, aa, L, key, fill, amb);
    acc = part(acc, sdgBox(q - float2(0.0, -0.62), float2(0.46, 0.24), 0.20), 0.20, suit, 6.0, aa, L, key, fill, amb);
    acc = part(acc, sdgBox(q - float2(0.0, 0.10), float2(0.55, 0.62), 0.30), 0.26, suit, 6.0, aa, L, key, fill, amb);
    acc = limb(acc, q, h2, k2, 0.26, 0.21, 1.0, suit, aa, L, key, fill, amb);
    acc = limb(acc, q, k2, f2, 0.21, 0.17, 0.0, suit, aa, L, key, fill, amb);
    acc = part(acc, sdgLimb(q, f2, t2, 0.18, 0.15).xyz, 0.0, boot, 12.0, aa, L, key, fill, amb);

    // Chest control box with three small lights; the amber one breathes with the bass.
    acc = part(acc, sdgBox(q - float2(0.06, 0.24), float2(0.26, 0.15), 0.05), 0.05, trim, 16.0, aa, L, key, fill, amb);
    float3 led = float3(length(q - float2(-0.07, 0.24)), length(q - float2(0.06, 0.24)), length(q - float2(0.19, 0.24)));
    float3 ledA = 1.0 - smoothstep(0.022, 0.036, led);
    acc.rgb += amber * (ledA.x * (0.6 + 1.2 * uGain)) + float3(0.9, 0.18, 0.12) * (ledA.y * 0.5) + indigo * (ledA.z * 0.9);

    acc = limb(acc, q, s2, e2, 0.21, 0.17, 1.0, suit, aa, L, key, fill, amb);
    acc = limb(acc, q, e2, w2, 0.17, 0.14, 0.0, suit, aa, L, key, fill, amb);
    acc = part(acc, sdgCapsule(q, mix(e2, w2, 0.93), mix(e2, w2, 1.03), 0.15), 0.0, trim, 20.0, aa, L, key, fill, amb);
    acc = part(acc, sdgLimb(q, w2, g2, 0.12, 0.14).xyz, 0.0, glove, 10.0, aa, L, key, fill, amb);

    // Neck ring, a lamp on the helmet's far side, then the helmet.
    acc = part(acc, sdgBox(q - float2(0.02, 0.76), float2(0.30, 0.08), 0.07), 0.08, trim, 16.0, aa, L, key, fill, amb);
    acc = part(acc, sdgBox(q - float2(-0.42, 1.42), float2(0.10, 0.07), 0.035), 0.04, trim, 16.0, aa, L, key, fill, amb);
    float2 hc = float2(0.03, 1.22);
    acc = part(acc, sdgCapsule(q, hc, hc, 0.47), 0.0, float3(0.78, 0.78, 0.77), 28.0, aa, L, key, fill, amb);

    // Visor: gold-coated glass with a dark seal round it. It mirrors the hole on the side that faces it.
    float2 vc = float2(0.10, 1.20);
    float2 vr = float2(0.33, 0.27);
    float2 vq = (q - vc) / vr;
    float vl = length(vq);
    float vd = (vl - 1.0) * vr.y;
    float seal = 1.0 - smoothstep(-aa, aa, vd - 0.05);
    acc = over(acc, trim * (fill + amb) * 0.6, seal);
    float va = 1.0 - smoothstep(-aa, aa, vd);
    if (va > 0.0) {
        float3 nv = normalize(float3(vq.x * 0.75 + 0.18, vq.y * 0.75, 1.0));
        float3 R = reflect(float3(0.0, 0.0, -1.0), nv);
        float fres = 0.08 + 0.92 * pow(1.0 - nv.z, 3.0);
        float2 lh = normalize(L.xy);
        float toward = max(dot(normalize(R.xy + 1e-4), lh), 0.0);
        float3 env = slate * 0.03 + key * (pow(toward, 4.0) * (1.0 - R.z) * 0.9);
        // The disc in miniature: a bright tilted band with the dark hole on it.
        float2 hq = vq - lh * 0.45;
        float2 hb = rot(hq, -0.30);
        float band = exp(-pow((length(hb / float2(0.34, 0.075)) - 1.0) / 0.22, 2.0));
        float hole = smoothstep(0.075, 0.11, length(hq));
        band *= hb.y < 0.0 ? 1.0 : hole;
        env += key * (band * 2.0 + exp(-dot(hq, hq) / 0.03) * 0.45) * hole;
        float3 gold = float3(1.0, 0.76, 0.42);
        float3 vcol = float3(0.004, 0.005, 0.010) + env * gold * (0.35 + 0.65 * fres);
        // Highlights: a hot glint from the disc and a soft window from the fill.
        vcol += key * pow(max(dot(R, L), 0.0), 40.0) * 1.5;
        vcol += (fill * 4.0 + 0.04) * smoothstep(0.80, 0.97, dot(R, normalize(float3(-0.40, 0.55, 0.73))));
        acc = over(acc, vcol, va);
    }

    float a = acc.a;
    if (a <= 0.0) {
        return half4(0.0);
    }
    float3 c = acc.rgb / a;
    c = sqrt(1.0 - exp(-c * 1.2));
    return half4(half3(c * a), half(a));
}
"""

/**
 * Where the astronaut is, this frame. A pure function of the tracker's clock, so there is no
 * state to save, nothing allocates, and it stops with the tracker when motion is turned off.
 * Every period divides an hour, so the clock's hourly wrap is invisible.
 *
 * [rig] adds what the shader needs in the figure's own frame (units of about a helmet's width,
 * y up, origin at the chest). None of it changes from pixel to pixel, so it is worked out here
 * once instead of in the shader for every pixel of the figure.
 */
internal class AstronautPose {
    var x = 0f
    var y = 0f
    var angle = 0f
    var scale = 1f
    var lightX = 0f
    var lightY = -1f

    /** Cosine and sine of minus [angle]: they turn screen space into the figure's frame. */
    var rotCos = 1f
        private set
    var rotSin = 0f
        private set

    /** Unit vector toward the hole, the key light; the Blinn half vector between it and the viewer; its xy, normalised. */
    val light = FloatArray(3)
    val half = FloatArray(3)
    val lightXY = FloatArray(2)

    /**
     * The joints that drift, 2 floats each: elbow, wrist and glove end of the far arm, then the
     * near arm; knee, ankle and toe of the far leg, then the near leg.
     */
    val joints = FloatArray(24)

    fun update(seconds: Float, w: Float, h: Float) {
        // A slow drift through the lower half of the screen, turning over every five minutes.
        x = w * (0.5f + 0.30f * sin(TAU * seconds / 144f))
        y = h * (0.70f + 0.06f * sin(TAU * seconds / 100f + 1f))
        angle = TAU * seconds / 300f + 0.30f * sin(TAU * seconds / 36f)
        scale = w * 0.046f
        val dx = w * HOLE_X - x
        val dy = h * HOLE_Y - y
        val len = hypot(dx, dy).coerceAtLeast(1f)
        lightX = dx / len
        lightY = dy / len
    }

    /** Works out the shader's view of the pose. Call after [update] with the same [seconds]. */
    fun rig(seconds: Float) {
        rotCos = cos(-angle)
        rotSin = sin(-angle)
        // The light, turned into the figure's frame (where y points up) and tipped behind it.
        val lx = rotCos * lightX - rotSin * lightY
        val ly = rotSin * lightX + rotCos * lightY
        unit(lx, -ly, -0.32f, light)
        unit(light[0], light[1], light[2] + 1f, half)
        val flat = max(hypot(light[0], light[1]), 1e-4f)
        lightXY[0] = light[0] / flat
        lightXY[1] = light[1] / flat

        // Each limb drifts on its own slow beat (all periods divide an hour).
        val t = seconds
        val a1 = 0.10f * sin(t * 0.6981317f)
        val a2 = 0.12f * sin(t * 0.5235988f + 1.3f)
        val l1 = 0.06f * sin(t * 0.6283185f + 2.1f)
        val l2 = 0.08f * sin(t * 0.4188790f + 0.4f)
        chain(0, -0.52f, 0.50f, -0.53f, -0.40f, a1, -0.10f, 0.50f, -0.02f, 0.14f, a1 * 1.6f)
        chain(6, 0.52f, 0.50f, 0.54f, -0.32f, a2, 0.16f, 0.50f, 0.03f, 0.14f, a2 * 1.5f)
        chain(12, -0.24f, -0.74f, -0.18f, -0.71f, l1, 0.12f, -0.67f, -0.20f, -0.10f, l1 * 1.4f)
        chain(18, 0.24f, -0.74f, 0.31f, -0.66f, l2, -0.13f, -0.68f, 0.22f, -0.08f, l2 * 1.4f)
    }

    /**
     * Three joints hanging off a fixed root ([rx], [ry]): the first segment ([ux], [uy]) turned
     * by [a], the next two ([vx], [vy]) and ([ex], [ey]) by [b]. Stored at [at] in [joints].
     */
    private fun chain(at: Int, rx: Float, ry: Float, ux: Float, uy: Float, a: Float, vx: Float, vy: Float, ex: Float, ey: Float, b: Float) {
        val ca = cos(a)
        val sa = sin(a)
        val cb = cos(b)
        val sb = sin(b)
        val jx = rx + ca * ux - sa * uy
        val jy = ry + sa * ux + ca * uy
        val kx = jx + cb * vx - sb * vy
        val ky = jy + sb * vx + cb * vy
        joints[at] = jx
        joints[at + 1] = jy
        joints[at + 2] = kx
        joints[at + 3] = ky
        joints[at + 4] = kx + cb * ex - sb * ey
        joints[at + 5] = ky + sb * ex + cb * ey
    }

    private fun unit(x: Float, y: Float, z: Float, out: FloatArray) {
        val len = sqrt(x * x + y * y + z * z)
        out[0] = x / len
        out[1] = y / len
        out[2] = z / len
    }
}

/** The astronaut, as its own full-size layer. AGSL on Android 13+, Canvas shapes below that. */
@Composable
internal fun FloatingAstronaut(tracker: AudioFrequencyTracker, modifier: Modifier) {
    val shader = if (Build.VERSION.SDK_INT >= 33) remember { createAstronautShader() } else null
    if (Build.VERSION.SDK_INT >= 33 && shader != null) {
        AgslAstronaut(tracker, shader, modifier)
    } else {
        CanvasAstronaut(tracker, modifier)
    }
}

@RequiresApi(33)
private fun createAstronautShader(): RuntimeShader? = try {
    RuntimeShader(ASTRONAUT_AGSL)
} catch (e: IllegalArgumentException) {
    BugLog.w("Backdrop", "Astronaut shader was rejected by this device, drawing it with shapes instead", e)
    null
}

@RequiresApi(33)
@Composable
private fun AgslAstronaut(tracker: AudioFrequencyTracker, shader: RuntimeShader, modifier: Modifier) {
    val pose = remember { AstronautPose() }
    Box(
        modifier.drawWithCache {
            shader.setColorUniform("uAmber", Nocturne.Amber.toArgb())
            shader.setColorUniform("uIndigo", Nocturne.Indigo.toArgb())
            shader.setColorUniform("uSlate", Nocturne.Slate.toArgb())
            val brush = ShaderBrush(shader)
            onDrawBehind {
                tracker.frame.longValue // redraw every frame without recomposing
                val t = tracker.seconds
                pose.update(t, size.width, size.height)
                pose.rig(t)
                shader.setFloatUniform("uCenter", pose.x, pose.y)
                shader.setFloatUniform("uScale", pose.scale)
                shader.setFloatUniform("uRot", pose.rotCos, pose.rotSin)
                shader.setFloatUniform("uTime", t)
                shader.setFloatUniform("uL", pose.light[0], pose.light[1], pose.light[2])
                shader.setFloatUniform("uH", pose.half[0], pose.half[1], pose.half[2])
                shader.setFloatUniform("uLxy", pose.lightXY[0], pose.lightXY[1])
                val j = pose.joints
                shader.setFloatUniform("uArm1", j[0], j[1], j[2], j[3])
                shader.setFloatUniform("uHand1", j[4], j[5])
                shader.setFloatUniform("uArm2", j[6], j[7], j[8], j[9])
                shader.setFloatUniform("uHand2", j[10], j[11])
                shader.setFloatUniform("uLeg1", j[12], j[13], j[14], j[15])
                shader.setFloatUniform("uFoot1", j[16], j[17])
                shader.setFloatUniform("uLeg2", j[18], j[19], j[20], j[21])
                shader.setFloatUniform("uFoot2", j[22], j[23])
                shader.setFloatUniform("uGain", tracker.bass.value)
                val half = pose.scale * REACH
                drawRect(brush, topLeft = Offset(pose.x - half, pose.y - half), size = Size(2f * half, 2f * half))
            }
        },
    )
}

private val SuitShadow = Color(0xFF2B2E3B)
private val PackShadow = Color(0xFF242733)
private val TrimShadow = Color(0xFF16171D)
private val VisorGlass = Color(0xFF07080D)
private val RimLight = Color(0xFFF4C894)
private val VisorGold = Color(0xFFE8B66E)

/**
 * The same figure for Android 12 and older, from round-capped lines and rounded rectangles: each
 * part in its shadow colour, with a warm highlight slid toward the hole. Drawn in figure units
 * (y down here) inside one transform, so nothing allocates per frame.
 */
@Composable
private fun CanvasAstronaut(tracker: AudioFrequencyTracker, modifier: Modifier) {
    val pose = remember { AstronautPose() }
    Box(
        modifier.drawWithCache {
            onDrawBehind {
                tracker.frame.longValue
                val t = tracker.seconds
                pose.update(t, size.width, size.height)
                // The light, turned into the figure's frame.
                val ca = cos(pose.angle)
                val sa = sin(pose.angle)
                val lx = ca * pose.lightX + sa * pose.lightY
                val ly = -sa * pose.lightX + ca * pose.lightY
                val rim = (0.55f + 0.45f * tracker.bass.value).coerceAtMost(1f)

                // Same limb drift as the shader.
                val a1 = 0.10f * sin(t * 0.6981317f)
                val a2 = 0.12f * sin(t * 0.5235988f + 1.3f)
                val l1 = 0.06f * sin(t * 0.6283185f + 2.1f)
                val l2 = 0.08f * sin(t * 0.4188790f + 0.4f)

                withTransform({
                    translate(pose.x, pose.y)
                    rotate(pose.angle * (360f / TAU), Offset.Zero)
                    scale(pose.scale, pose.scale, Offset.Zero)
                }) {
                    // Tether, trailing off behind.
                    var px = -0.66f
                    var py = 0.20f
                    for (i in 1..10) {
                        val s = i * 0.22f
                        val nx = -0.66f - s
                        val ny = 0.20f + 0.25f * s - 0.18f * sin(s * 2.4f - t * 0.5235988f)
                        drawLine(TrimShadow, Offset(px, py), Offset(nx, ny), strokeWidth = 0.06f, cap = StrokeCap.Round, alpha = 1f - i / 11f)
                        drawLine(RimLight, Offset(px, py), Offset(nx, ny), strokeWidth = 0.025f, cap = StrokeCap.Round, alpha = 0.35f * rim * (1f - i / 11f))
                        px = nx
                        py = ny
                    }

                    box(-0.10f, -0.25f, 0.62f, 0.72f, 0.16f, PackShadow, lx, ly, rim)
                    // Far arm and leg, behind the body.
                    limb(-0.52f, -0.50f, -0.53f, 0.40f, a1, -0.10f, -0.50f, a1 * 1.6f, 0.19f, 0.155f, SuitShadow, lx, ly, rim)
                    limb(-0.24f, 0.74f, -0.18f, 0.71f, l1, 0.12f, 0.67f, l1 * 1.4f, 0.235f, 0.19f, SuitShadow, lx, ly, rim)
                    box(0f, 0.62f, 0.46f, 0.24f, 0.20f, SuitShadow, lx, ly, rim)
                    box(0f, -0.10f, 0.55f, 0.62f, 0.30f, SuitShadow, lx, ly, rim)
                    limb(0.24f, 0.74f, 0.31f, 0.66f, l2, -0.13f, 0.68f, l2 * 1.4f, 0.235f, 0.19f, SuitShadow, lx, ly, rim)
                    box(0.06f, -0.24f, 0.26f, 0.15f, 0.05f, TrimShadow, lx, ly, rim * 0.5f)
                    drawCircle(Nocturne.Amber, 0.035f, Offset(-0.07f, -0.24f), alpha = 0.5f + 0.5f * tracker.bass.value)
                    drawCircle(Color(0xFFE5483A), 0.035f, Offset(0.06f, -0.24f), alpha = 0.6f)
                    drawCircle(Nocturne.Indigo, 0.035f, Offset(0.19f, -0.24f), alpha = 0.9f)
                    limb(0.52f, -0.50f, 0.54f, 0.32f, a2, 0.16f, -0.50f, a2 * 1.5f, 0.19f, 0.155f, SuitShadow, lx, ly, rim)

                    // Neck ring, helmet, visor.
                    box(0.02f, -0.76f, 0.30f, 0.08f, 0.07f, TrimShadow, lx, ly, rim * 0.6f)
                    drawCircle(SuitShadow, 0.47f, Offset(0.03f, -1.22f))
                    drawCircle(RimLight, 0.30f, Offset(0.03f + lx * 0.20f, -1.22f + ly * 0.20f), alpha = 0.40f * rim)
                    drawCircle(SuitShadow, 0.36f, Offset(0.03f - lx * 0.07f, -1.22f - ly * 0.07f), alpha = 0.8f)
                    drawOval(TrimShadow, Offset(0.10f - 0.38f, -1.20f - 0.32f), Size(0.76f, 0.64f))
                    drawOval(VisorGlass, Offset(0.10f - 0.33f, -1.20f - 0.27f), Size(0.66f, 0.54f))
                    drawOval(VisorGold, Offset(0.10f + lx * 0.16f - 0.13f, -1.20f + ly * 0.16f - 0.03f), Size(0.26f, 0.06f), alpha = 0.75f * rim)
                }
            }
        },
    )
}

/**
 * One limb from the shoulder or hip at (sx, sy): an upper segment along (ux, uy) turned by ua, a
 * lower one along (fx, fy) turned by fa, thickness ru tapering to rf, with a glove or boot at
 * the end. Shadow colour first, then a narrower warm highlight slid toward the light (lx, ly).
 */
private fun DrawScope.limb(
    sx: Float,
    sy: Float,
    ux: Float,
    uy: Float,
    ua: Float,
    fx: Float,
    fy: Float,
    fa: Float,
    ru: Float,
    rf: Float,
    shadow: Color,
    lx: Float,
    ly: Float,
    rim: Float,
) {
    val cu = cos(ua)
    val su = sin(ua)
    val ex = sx + cu * ux + su * uy
    val ey = sy - su * ux + cu * uy
    val cf = cos(fa)
    val sf = sin(fa)
    val wx = ex + cf * fx + sf * fy
    val wy = ey - sf * fx + cf * fy
    tube(sx, sy, ex, ey, ru, shadow, lx, ly, rim)
    tube(ex, ey, wx, wy, rf, shadow, lx, ly, rim)
    // Glove or boot: a little past the end, the same way the lower segment points.
    val ox = (wx - ex) * 0.22f
    val oy = (wy - ey) * 0.22f
    tube(wx, wy, wx + ox, wy + oy, rf * 0.95f, TrimShadow, lx, ly, rim * 0.7f)
}

private fun DrawScope.tube(ax: Float, ay: Float, bx: Float, by: Float, r: Float, shadow: Color, lx: Float, ly: Float, rim: Float) {
    drawLine(shadow, Offset(ax, ay), Offset(bx, by), strokeWidth = 2f * r, cap = StrokeCap.Round)
    val ox = lx * r * 0.5f
    val oy = ly * r * 0.5f
    drawLine(RimLight, Offset(ax + ox, ay + oy), Offset(bx + ox, by + oy), strokeWidth = r * 0.8f, cap = StrokeCap.Round, alpha = 0.45f * rim)
}

private fun DrawScope.box(cx: Float, cy: Float, hw: Float, hh: Float, corner: Float, shadow: Color, lx: Float, ly: Float, rim: Float) {
    drawRoundRect(shadow, Offset(cx - hw, cy - hh), Size(2f * hw, 2f * hh), CornerRadius(corner, corner))
    val ox = lx * hw * 0.22f
    val oy = ly * hh * 0.22f
    val ih = hh * 0.72f
    val iw = hw * 0.72f
    drawRoundRect(
        RimLight,
        Offset(cx + ox - iw, cy + oy - ih),
        Size(2f * iw, 2f * ih),
        CornerRadius(corner * 0.7f, corner * 0.7f),
        alpha = 0.22f * rim,
    )
    drawRoundRect(shadow, Offset(cx - ox * 0.5f - iw * 0.9f, cy - oy * 0.5f - ih * 0.9f), Size(1.8f * iw, 1.8f * ih), CornerRadius(corner * 0.6f, corner * 0.6f), alpha = 0.85f)
}
