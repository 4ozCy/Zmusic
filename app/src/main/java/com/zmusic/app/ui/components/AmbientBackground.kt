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
import kotlin.math.sqrt
import kotlin.random.Random

// Scene geometry, shared by the shader, the Canvas fallback and the astronaut. Lengths are
// fractions of the screen width, so the scene keeps its proportions on any phone.
internal const val HOLE_X = 0.5f
internal const val HOLE_Y = 0.34f // fraction of the screen height
private const val HOLE_R = 0.0857f // radius of the shadow: sqrt(27) times the hole's mass, M = 0.0165
private const val DISC_TILT = 0.12f // how flat the disc looks (1 = seen from above); 83 degrees from face-on
private const val ROLL_DEG = -12f // the whole system leans by this much
private const val TAU = 6.2831855f

// The shader's disc texture cross-fades once every two turns of the spin, so the spin may wrap at
// any whole number of those cycles without a visible jump.
private const val SPIN_WRAP = 4f * TAU

/*
 * A black hole seen almost edge-on, its accretion disc wrapped over and under the shadow by the
 * hole's gravity, four planets on their own tilted orbits, and a star field bent round it all.
 *
 *   uSpin   rotation of the disc gas in radians. The host advances it only while a song plays,
 *           so the disc turns with the music and coasts to a stop on pause.
 *   uBass   brightens the disc, the photon ring, the glow, and the light on the planets
 *   uMid    pushes the disc's outer edge out and lifts the nebula
 *   uTreble makes the bright stars twinkle harder
 *
 * The disc is traced, not faked: for each pixel the shader works out where a ray from the eye
 * crosses the disc plane after the hole's gravity has bent it, once on the way past (which lifts
 * the far side of the disc up over the top of the shadow) and once more after swinging round the
 * back (the arc under the shadow). Ray radii come from Beloborodov's approximation on the way in
 * and its mirror image about periapsis on the way out, with a fitted deflection angle; both are
 * within about 2% of exact Schwarzschild orbits. No ray marching.
 *
 * The lensed arcs squeeze many radii into a few rows of pixels, so each layer of the gas texture
 * fades out where it would be finer than a pixel: the shader measures how fast the crossing radius
 * changes to the next pixel down, and the arcs stay smooth instead of scratchy.
 *
 * The gas glows as a black body, about 5000 K at the inner edge and falling as r^-3/4 to deep
 * amber at the rim, pushed a little richer than a true black body so it reads as gold, not tan.
 * The side orbiting toward us is brighter and whiter, the far side redder, held to about four
 * to one so neither side burns out or vanishes. Gas closer in orbits faster (Kepler), so its
 * streaks shear; two copies of the texture half a cycle apart cross-fade so the shear never winds
 * up. A hairline photon ring sits on the shadow's edge, and a little bloom spills round the
 * shadow and along the disc (never into the shadow itself).
 *
 * The planets are lit by the disc and by dim blue starlight, so a night side shows as deep blue
 * rather than a black hole in the sky. Air gives a soft terminator with a red edge, a brighter
 * limb, and a halo that lights up all round when the planet is backlit. The giant's rings cast a
 * shadow band on it and take its shadow in turn, and they glow through from the unlit side.
 * Planets are drawn back to front by depth; any behind the hole are lensed. Everything is lit in
 * linear light, tone-mapped, and dithered by under one 8-bit step.
 */
private const val BLACK_HOLE_AGSL = """
uniform float2 uSize;
uniform float uTime;
uniform float uSpin;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
// Worked out once a frame on the CPU rather than again for every pixel: where each planet is on
// its orbit (screen widths from the hole, x right, y up, z toward us; see planetPositions) and
// how far the nebula's tint has drifted from indigo toward slate.
uniform float3 uP0;
uniform float3 uP1;
uniform float3 uP2;
uniform float3 uP3;
uniform float uNebula;
layout(color) uniform half4 uBase;
layout(color) uniform half4 uAmber;
layout(color) uniform half4 uIndigo;
layout(color) uniform half4 uSlate;

const float M = 0.0165;        // the hole's mass, in screen widths
const float BC = 5.196152;     // radius of the shadow, sqrt(27) M
const float COS_I = 0.12;      // the disc is seen this close to edge-on (83 degrees)
const float SIN_I = 0.992774;
const float ROLL_C = 0.978148; // the whole system leans by 12 degrees
const float ROLL_S = 0.207912;
const float R_IN = 6.0;        // innermost stable orbit: the disc's inner edge
const float PI = 3.14159265;
const float TAU = 6.2831853;
const float DIR = 1.0;         // which way the disc turns: +1 brings the left side toward us
const float TE2 = 0.0242;      // Einstein radius squared for the far sky, screen widths
const float CAM = 2.4;         // how far the camera is from the hole, for the planets' perspective
const float T_IN = 5800.0;     // gas temperature at the inner edge, before any shift (kelvin)
const float DISC_GAIN = 0.8;
const float BLOOM = 1.0;
const float DOP_TINT = 0.45;

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash13(float3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

// Value noise that wraps every `per` cells along x, so it can run round the disc without a seam.
float wnoise(float2 p, float per) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float x0 = mod(i.x, per);
    float x1 = mod(i.x + 1.0, per);
    float a = hash12(float2(x0, i.y));
    float b = hash12(float2(x1, i.y));
    float c = hash12(float2(x0, i.y + 1.0));
    float d = hash12(float2(x1, i.y + 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float noise3(float3 p) {
    float3 i = floor(p);
    float3 f = fract(p);
    float3 u = f * f * (3.0 - 2.0 * f);
    float a = mix(hash13(i), hash13(i + float3(1.0, 0.0, 0.0)), u.x);
    float b = mix(hash13(i + float3(0.0, 1.0, 0.0)), hash13(i + float3(1.0, 1.0, 0.0)), u.x);
    float c = mix(hash13(i + float3(0.0, 0.0, 1.0)), hash13(i + float3(1.0, 0.0, 1.0)), u.x);
    float d = mix(hash13(i + float3(0.0, 1.0, 1.0)), hash13(i + float3(1.0, 1.0, 1.0)), u.x);
    return mix(mix(a, b, u.y), mix(c, d, u.y), u.z);
}

float fbm3(float3 p) {
    return 0.57 * noise3(p) + 0.29 * noise3(p * 2.03 + 5.1) + 0.14 * noise3(p * 4.01 + 9.7);
}

// Colour of a black body at temperature t (kelvin), sampled at 610, 550 and 465 nm and
// balanced so that 6500 K is white. Brightness is left to the caller.
float3 blackbody(float t) {
    float3 e = float3(11.84, 19.87, 46.02) / (exp(float3(23587.0, 26160.0, 30942.0) / max(t, 600.0)) - 1.0);
    e *= float3(3.096, 2.765, 2.516);
    return e / max(max(e.r, e.g), e.b);
}

// Hot gas as the eye reads it in a photograph: a black body pushed richer, so gas at 3000 K
// glows amber instead of tan. White stays white.
float3 ember(float t) {
    float3 c = blackbody(t);
    return c * c;
}

// Light bending round the hole, in units of M. Total deflection of a ray that passes at impact
// parameter b (fitted to exact Schwarzschild orbits, within 2%).
float deflection(float b) {
    float ib = 1.0 / b;
    return -log(max(1.0 - BC * ib, 1e-4)) - ib * (1.196 + ib * (1.719 + ib * 4.1));
}

// Radius a ray reaches at polar angle psi from the line of sight, on the way in (Beloborodov 2002).
// The way out mirrors it about periapsis, so callers pass min(psi, pi + deflection - psi).
float rayRadius(float b, float psi) {
    float cp = 1.0 - cos(psi);
    float sp = sin(psi);
    float c = 2.0 * cp * cp;
    return 2.0 * b * b / (c + sqrt(c * c + 4.0 * sp * sp * b * b));
}

// Radius where the ray through screen point p (units of M, y up) first meets the disc plane.
float firstCrossing(float2 p) {
    float b = max(length(p), 1e-4);
    float psi = atan(COS_I, -SIN_I * p.y / b);
    if (b > BC) {
        float defl = deflection(b);
        psi = min(psi, min(PI + defl - psi, 0.5 * (PI + defl)));
    }
    return rayRadius(b, psi);
}

// Radius where that ray meets the disc plane again after swinging round behind the hole, or a
// huge number if it does not bend far enough to come back.
float secondCrossing(float2 p) {
    float b = max(length(p), 1e-4);
    if (b <= BC) {
        return 10000.0;
    }
    float psi = atan(COS_I, -SIN_I * p.y / b);
    float defl = deflection(b);
    if (defl <= psi) {
        return 10000.0;
    }
    return rayRadius(b, min(defl - psi, 0.5 * (PI + defl)));
}

// 1 while a texture's stripes are wider than a few pixels, 0 once they would be finer than one.
float keep(float cyclesPerPixel) {
    return 1.0 - smoothstep(0.12, 0.45, cyclesPerPixel);
}

// Light from the disc where a ray crosses it at radius r and azimuth phi. xImg is the ray's
// sideways offset on screen (M), which fixes its angular momentum and so the Doppler shift.
// lod is how many radial texture cells one pixel spans; the finer layers fade out as it grows.
// rgb is emitted light, a is how opaque the gas is there.
float4 discAt(float r, float phi, float xImg, float lod, float shA, float shB, float wA, float outer, float gain) {
    if (r < 3.6 || r > outer * 1.4) {
        return float4(0.0);
    }
    float x = R_IN / r;
    float x15 = x * sqrt(x);
    // Gravity reddens the light; the gas orbiting toward us is blue-shifted and brighter.
    float g = sqrt(max(1.0 - 3.0 / r, 0.05)) / (1.0 + DIR * xImg * SIN_I * x15 * 0.0680414);

    // Gas closer in laps the gas further out (Kepler), so the texture shears. Two copies offset by
    // half a cycle cross-fade, so the shear never winds up.
    float kep = x15 * 2.1517 * (12.0 / TAU) * DIR;
    float2 q = float2(phi * (12.0 / TAU), log(r) * 5.0);
    float2 qa = float2(q.x + shA * kep, q.y);
    float2 qb = float2(q.x + shB * kep, q.y + 19.7);
    float wB = 1.0 - wA;
    float norm = 1.0 / sqrt(wA * wA + wB * wB);
    // Broad lanes of denser and thinner gas...
    float lanes = 0.5 + (wA * wnoise(qa * float2(0.5, 0.6), 6.0) + wB * wnoise(qb * float2(0.5, 0.6), 6.0) - 0.5) * norm;
    // ...and filaments along the orbits, each layer kept only where the lensing has not squeezed
    // it finer than a pixel.
    float f1 = (wA * wnoise(qa * float2(1.0, 1.4) + float2(0.0, 3.1), 12.0) + wB * wnoise(qb * float2(1.0, 1.4) + float2(0.0, 3.1), 12.0) - 0.5) * norm;
    float f2 = (wA * wnoise(qa * float2(2.0, 3.1) + float2(0.0, 7.7), 24.0) + wB * wnoise(qb * float2(2.0, 3.1) + float2(0.0, 7.7), 24.0) - 0.5) * norm;
    float f3 = (wA * wnoise(qa * float2(4.0, 6.7) + float2(0.0, 1.9), 48.0) + wB * wnoise(qb * float2(4.0, 6.7) + float2(0.0, 1.9), 48.0) - 0.5) * norm;
    float fil = 0.5 + 0.55 * f1 * keep(lod * 1.4) + 0.35 * f2 * keep(lod * 3.1) + 0.25 * f3 * keep(lod * 6.7);
    float lane = mix(0.5, smoothstep(0.18, 0.82, lanes), keep(lod * 0.6));
    float tex = (0.35 + 0.95 * lane) * (0.45 + 0.95 * smoothstep(0.22, 0.86, fil));

    float inner = smoothstep(R_IN * 0.94, R_IN * 1.2, r);
    // A little gas still glows as it spirals in from the last stable orbit.
    float plunge = (1.0 - inner) * smoothstep(3.6, R_IN, r) * 0.22;
    float fade = 1.0 - smoothstep(outer * 0.6, outer * 1.25, r);
    float prof = x * x * sqrt(x) * 1.6 + 0.10 * x;
    // Temperature falls as r^-3/4. The shift tints the gas (bluer coming toward us, redder going
    // away), held back a little so the near side stays gold rather than turning blue.
    float t = T_IN * sqrt(x) * sqrt(sqrt(x)) * (1.0 - DOP_TINT + DOP_TINT * g);
    float lum = prof * tex * (inner + plunge) * fade * pow(g, 1.4) * gain * DISC_GAIN;
    float a = (inner + 0.5 * plunge) * fade * clamp(0.45 + 0.6 * tex, 0.0, 1.0);
    return float4(ember(t) * lum, a);
}

// Density of the giant's rings at rr planet radii: a faint inner ring, a broad bright one, a dark
// gap, a fainter outer ring with a narrow gap in it, and a thread of a ring just outside.
// fp is how many planet radii one pixel spans across the rings; fine ringlets fade as it grows.
float ringDensity(float rr, float fp) {
    if (rr < 1.24 || rr > 2.40) {
        return 0.0;
    }
    float d = 0.22 * smoothstep(1.24, 1.30, rr);
    d = mix(d, 0.95, smoothstep(1.50, 1.57, rr));
    d = mix(d, 0.05, smoothstep(1.93, 1.97, rr));
    d = mix(d, 0.62, smoothstep(2.01, 2.05, rr));
    d *= 1.0 - smoothstep(2.24, 2.28, rr);
    d *= 1.0 - 0.80 * keep(fp * 25.0) * (1.0 - smoothstep(0.0, 0.020, abs(rr - 2.17)));
    d += 0.30 * keep(fp * 22.0) * (1.0 - smoothstep(0.0, 0.022, abs(rr - 2.34)));
    float k = wnoise(float2(rr * 38.0, 0.5), 999.0) * 0.6 + wnoise(float2(rr * 95.0, 2.5), 999.0) * 0.4;
    float detail = keep(fp * 95.0) * 0.4 + keep(fp * 38.0) * 0.6;
    return d * (1.0 - 0.38 * detail + 0.38 * detail * k);
}

// A planet in view, lit by the disc. rgb is premultiplied, a is coverage.
//   q     this pixel (screen widths, y up, relative to the hole, system frame)
//   c     the planet's centre on screen, rad its radius
//   L     unit vector from the planet toward the hole (x right, y up, z toward us)
//   kind  0 desert world, 1 ice giant, 2 icy moon, 3 ringed giant
float4 planet(float2 q, float2 c, float rad, float3 L, float3 axis, float spin, float kind, float pxw,
              float light, float3 keyCol, float3 fillCol) {
    float2 e = (q - c) / rad;
    float e2 = dot(e, e);
    if (e2 > (kind > 2.5 ? 5.9 : 1.5)) {
        return float4(0.0);
    }
    float len = sqrt(e2);
    float aa = pxw / rad;
    float cover = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, len);
    float3 n = float3(e, sqrt(max(1.0 - e2, 0.0)));

    // Body frame: latitude along the spin axis, longitude carried round by the spin.
    float3 b1 = normalize(cross(axis, float3(0.0, 0.0, 1.0)));
    float3 b2 = cross(axis, b1);
    float lat = dot(n, axis);
    float u0 = dot(n, b1);
    float w0 = dot(n, b2);
    float cS = cos(spin);
    float sS = sin(spin);
    float3 nb = float3(u0 * cS - w0 * sS, lat, u0 * sS + w0 * cS);

    float3 albedo = float3(0.5);
    float atmos = 0.0;
    float3 air = float3(0.0);
    if (kind < 0.5) {
        // Desert world: ochre highlands, dark basalt plains, craters, an ice cap at each pole.
        float h = fbm3(nb * 2.4 + 5.0);
        float fine = noise3(nb * 10.0 + 2.0);
        albedo = mix(float3(0.26, 0.11, 0.06), float3(0.82, 0.48, 0.24), smoothstep(0.36, 0.66, h));
        albedo *= 0.80 + 0.40 * fine;
        float cr = noise3(nb * 6.5 + 9.1);
        albedo *= 1.0 - 0.32 * smoothstep(0.70, 0.76, cr) + 0.22 * (smoothstep(0.76, 0.79, cr) - smoothstep(0.79, 0.84, cr));
        albedo = mix(albedo, float3(0.94, 0.92, 0.90), smoothstep(0.80, 0.87, abs(lat) + 0.08 * (fine - 0.5)));
        atmos = 0.35;
        air = float3(1.0, 0.62, 0.44);
    } else if (kind < 1.5) {
        // Ice giant: soft bands under a deep blue haze, and one dark storm.
        float w = lat + 0.06 * (noise3(nb * 3.0 + 1.0) - 0.5);
        float band = 0.5 + 0.5 * sin(w * 11.0);
        albedo = mix(float3(0.13, 0.26, 0.64), float3(0.38, 0.64, 0.88), 0.30 + 0.45 * band);
        float3 sp = nb - float3(-0.62, 0.30, 0.72);
        albedo *= 1.0 - 0.50 * exp(-(sp.x * sp.x + sp.z * sp.z) / 0.012 - sp.y * sp.y / 0.004);
        atmos = 1.0;
        air = float3(0.50, 0.74, 1.0);
    } else if (kind < 2.5) {
        // Icy moon: pale ice crossed by rust-coloured cracks.
        float m = fbm3(nb * 3.0 + 3.0);
        albedo = mix(float3(0.58, 0.54, 0.50), float3(0.92, 0.90, 0.86), smoothstep(0.30, 0.70, m));
        float c1 = abs(noise3(nb * 3.6 + 1.0) - 0.5);
        float c2 = abs(noise3(nb * 7.0 + 6.0) - 0.5);
        float cracks = (1.0 - smoothstep(0.02, 0.07, c1)) * 0.80 + (1.0 - smoothstep(0.015, 0.055, c2)) * 0.45;
        albedo = mix(albedo, float3(0.52, 0.27, 0.15), min(cracks, 1.0));
    } else {
        // Ringed giant: cream and rust belts pulled into eddies, and a great storm.
        float turb = fbm3(nb * 3.2 + 2.0);
        float w = lat + 0.055 * (turb - 0.5);
        float belt = 0.5 + 0.5 * sin(w * 15.0 + 0.8 * sin(w * 4.0));
        float fine = 0.5 + 0.5 * sin(w * 44.0 + 4.0 * turb);
        albedo = mix(float3(0.90, 0.78, 0.58), float3(0.60, 0.38, 0.22), belt);
        albedo *= 0.88 + 0.14 * fine;
        albedo = mix(albedo, float3(0.94, 0.84, 0.66), exp(-lat * lat / 0.006) * 0.6);
        albedo = mix(albedo, float3(0.46, 0.44, 0.44), smoothstep(0.72, 0.95, abs(lat)) * 0.7);
        float3 st = nb - float3(0.70, -0.36, 0.62);
        float storm = exp(-(st.x * st.x + st.z * st.z) / 0.008 - st.y * st.y / 0.0025);
        albedo = mix(albedo, float3(0.82, 0.40, 0.18), storm * 0.85);
        atmos = 0.5;
        air = float3(1.0, 0.84, 0.62);
    }

    float ndl = dot(n, L);
    // The disc is a broad light, so the terminator is soft, and softer still under air.
    float diff = smoothstep(-0.10 - 0.18 * atmos, 1.0, ndl);
    float fp = aa / max(abs(axis.z), 0.15);
    if (kind > 2.5) {
        // The rings throw a band of shadow across the giant.
        float la = dot(L, axis);
        float tt = -lat / (abs(la) > 0.002 ? la : 0.002);
        if (tt > 0.0) {
            diff *= 1.0 - 0.85 * ringDensity(length(n + tt * L), fp);
        }
    }
    // Starlight from the upper left, in front, so night sides are dim blue rather than black.
    float fl = max(dot(n, float3(-0.4545, 0.5556, 0.6963)), 0.0);
    float3 col = albedo * (keyCol * (diff * light) + fillCol * (0.25 + 0.75 * fl));
    // Under air the terminator glows red, and the limb brightens on the lit side.
    col += keyCol * float3(1.0, 0.42, 0.18) * (exp(-(ndl - 0.05) * (ndl - 0.05) * 60.0) * atmos * 0.07 * light);
    col += air * keyCol * (pow(1.0 - n.z, 2.5) * smoothstep(-0.3, 0.7, ndl) * atmos * light * 0.45);
    float4 outCol = float4(col * cover, cover);

    // Haze above the limb. Lit from behind, it glows all the way round.
    if (atmos > 0.0 && len > 0.96) {
        float2 ed = e / len;
        float2 lxy = L.xy / max(length(L.xy), 1e-3);
        float toward = 0.5 + 0.5 * dot(ed, lxy);
        float back = max(-L.z, 0.0);
        float lit = toward * toward * (1.0 - back) + back * (0.35 + 0.65 * toward);
        float halo = exp(-(len - 1.0) * 30.0 / (0.3 + atmos)) * lit * light * (0.35 + 0.9 * back) * atmos;
        outCol.rgb += air * keyCol * (halo * (1.0 - cover));
    }

    if (kind > 2.5) {
        float zr = -(e.x * axis.x + e.y * axis.y) / axis.z;
        float3 pr = float3(e, zr);
        float rr = length(pr);
        if (rr > 1.2 && rr < 2.42 && (zr > 0.0 || len > 1.0)) {
            float dens = ringDensity(rr, fp);
            float pl = dot(pr, L);
            float shade = (pl < 0.0 && dot(pr, pr) - pl * pl < 1.0) ? 0.06 : 1.0;
            float la = dot(L, axis);
            // We see the lit face only when the light is on our side of the ring plane; from the
            // other side the rings glow with light scattered through them.
            float sameSide = la * axis.z > 0.0 ? 1.0 : 0.0;
            float back = max(-L.z, 0.0);
            float bright = sameSide * (0.35 + 0.65 * abs(la)) + (1.0 - sameSide) * (0.35 + 0.50 * back) * (1.25 - 0.75 * dens);
            float3 rc = mix(float3(0.62, 0.55, 0.46), float3(0.95, 0.84, 0.66), smoothstep(1.45, 1.75, rr));
            rc = mix(rc, float3(0.84, 0.78, 0.68), smoothstep(2.0, 2.1, rr));
            float3 ringCol = rc * (keyCol * (light * shade * bright) + fillCol * 0.6);
            float ra = dens * 0.88;
            outCol = float4(outCol.rgb * (1.0 - ra) + ringCol * ra, outCol.a + ra * (1.0 - outCol.a));
        }
    }
    return outCol;
}

// Adds a planet to the planets already drawn at this pixel, in front of them or behind by depth.
// Planets rarely overlap, so remembering the depth of the nearest one drawn so far is enough.
float4 stack(float4 layer, float layerZ, float4 pl, float z) {
    return z > layerZ ? pl + layer * (1.0 - pl.a) : layer + pl * (1.0 - layer.a);
}

float4 planetAt(float2 q, float3 P, float size, float kind, float3 axis, float spin, float pxw,
                float light, float3 keyCol, float3 fillCol) {
    float s = CAM / (CAM - P.z);
    float2 dq = q - P.xy * s;
    float reach = size * s * (kind > 2.5 ? 2.45 : 1.25);
    if (dot(dq, dq) > reach * reach) {
        return float4(0.0);
    }
    float dist = length(P);
    return planet(q, P.xy * s, size * s, -P / dist, axis, spin, kind, pxw,
                  light * clamp(0.42 / dist, 0.7, 1.3), keyCol, fillCol);
}

half4 main(float2 fragCoord) {
    float aspect = uSize.y / uSize.x;
    float px = 1.0 / uSize.x;
    float2 uv = fragCoord * px;
    float2 holeAt = float2(0.5, aspect * 0.34);
    float2 d0 = uv - holeAt;
    float2 d = float2(d0.x * ROLL_C - d0.y * ROLL_S, d0.x * ROLL_S + d0.y * ROLL_C);
    float2 qs = float2(d.x, -d.y);
    float t = uTime;

    // Work in linear light; squaring is close enough to the sRGB curve for this.
    float3 amber = float3(uAmber.rgb * uAmber.rgb);
    float3 indigo = float3(uIndigo.rgb * uIndigo.rgb);
    float3 slate = float3(uSlate.rgb * uSlate.rgb);
    float gain = 0.72 + 0.55 * uBass;

    // Ray geometry, in units of M, y up.
    float2 p = qs / M;
    float b = max(length(p), 1e-4);
    float cosT = p.x / b;
    float sinT = p.y / b;
    float pxM = px / M;
    float captured = 1.0 - smoothstep(BC - 0.6 * pxM, BC + 0.6 * pxM, b);
    float vis = 1.0 - captured;

    // ---- The sky behind, bent round the hole (point lens) ----
    float r2 = dot(d0, d0) + 1e-6;
    float2 sky = holeAt + d0 * (1.0 - TE2 / r2);
    float3 col = float3(0.0);
    // A faint river of distant stars crosses behind the hole, with dark lanes of dust along it.
    // Its grain also breaks up the two soft nebulae in the corners.
    float2 gq = sky - float2(0.5, aspect * 0.36);
    float2 gr = float2(gq.x * 0.8829 - gq.y * 0.4695, gq.x * 0.4695 + gq.y * 0.8829);
    float bw = 0.13 + 0.03 * sin(gr.x * 5.0 + 1.0);
    float river = exp(-gr.y * gr.y / (bw * bw));
    float grain = wnoise(gr * 11.0, 999.0);
    float2 n1 = sky - float2(0.88, aspect * 0.80);
    float2 n2 = sky - float2(0.08, aspect * 0.10);
    col += mix(indigo, slate, uNebula) * (exp(-dot(n1, n1) / 0.30) * (0.030 + 0.020 * uMid) * (0.6 + 0.8 * grain));
    col += slate * (exp(-dot(n2, n2) / 0.20) * 0.025 * (0.6 + 0.8 * grain));
    float lane = smoothstep(0.50, 0.78, wnoise(gr * float2(4.0, 13.0) + 7.0, 999.0)) * exp(-gr.y * gr.y / (0.004 + 0.3 * bw * bw));
    col += mix(slate, indigo * 0.8 + amber * 0.2, grain) * (river * (0.010 + 0.022 * grain) * (1.0 - 0.8 * lane));

    // Faint stars: many, small.
    float2 g1 = sky * 70.0;
    float2 c1 = floor(g1);
    float h1 = hash12(c1);
    float2 o1 = float2(hash12(c1 + 17.0), hash12(c1 + 41.0)) * 0.7 + 0.15;
    float dist1 = length(fract(g1) - o1) / (70.0 * px);
    float s1 = step(0.90 - 0.06 * river, h1) * exp(-dist1 * dist1 / (0.35 + 0.5 * fract(h1 * 11.0)));
    float3 warmStar = float3(1.0, 0.78, 0.55);
    float3 coolStar = float3(0.78, 0.86, 1.0);
    col += mix(warmStar, coolStar, fract(h1 * 7.0)) * (s1 * (0.010 + 0.18 * pow(fract(h1 * 3.0), 4.0)));
    // Bright stars: few, with a soft glow, twinkling with the treble.
    float2 g2 = sky * 13.0;
    float2 c2 = floor(g2);
    float h2 = hash12(c2 + 3.0);
    float2 o2 = float2(hash12(c2 + 23.0), hash12(c2 + 61.0)) * 0.6 + 0.2;
    float dist2 = length(fract(g2) - o2) / (13.0 * px);
    float tw = 0.75 + 0.25 * sin(t * (0.9 + 2.5 * h2) + h2 * 40.0) * (0.4 + 0.6 * uTreble);
    float s2 = step(0.89, h2) * (exp(-dist2 * dist2 / 0.9) + 0.05 * exp(-dist2 / 3.0)) * tw;
    col += mix(warmStar, coolStar, fract(h2 * 13.0)) * (s2 * (0.28 + 0.6 * fract(h2 * 5.0)));
    // Light from very close to the shadow is squeezed into a thin, faint ring.
    col *= smoothstep(BC, BC * 1.35, b);

    // ---- Planets ----
    // Sizes in screen widths. Their orbits come in as uP0..uP3, from the same table the Canvas
    // fallback draws with. Every speed and spin is a whole number of turns an hour, so the
    // clock's hourly wrap is invisible.
    float3 keyCol = mix(amber, float3(1.0, 0.90, 0.76), 0.5) * 1.3;
    float3 fillCol = mix(slate, indigo, 0.55) * 0.10;
    float plight = gain * 0.9;
    float3 P0 = uP0;
    float3 P1 = uP1;
    float3 P2 = uP2;
    float3 P3 = uP3;
    float3 ax0 = float3(0.10, 0.98, 0.17);
    float3 ax1 = float3(0.25, 0.94, 0.23);
    float3 ax2 = float3(-0.30, 0.92, 0.25);
    float3 ax3 = float3(-0.42, 0.86, 0.29);
    // Each planet is drawn once, then laid in behind the disc (the far half of its orbit, where the
    // hole bends its light too, more the further back it is) or in front of everything.
    float qq = dot(qs, qs) + 1e-6;
    float4 behind = float4(0.0);
    float behindZ = -9.0;
    float4 ahead = float4(0.0);
    float aheadZ = -9.0;
    float4 pl = planetAt(P0.z < 0.0 ? qs * (1.0 + TE2 * P0.z / (0.3 - P0.z) / qq) : qs, P0, 0.016, 2.0, ax0, t * 0.010472, px, plight, keyCol, fillCol);
    if (P0.z < 0.0) {
        behind = stack(behind, behindZ, pl, P0.z);
        behindZ = pl.a > 0.001 ? max(behindZ, P0.z) : behindZ;
    } else {
        ahead = stack(ahead, aheadZ, pl, P0.z);
        aheadZ = pl.a > 0.001 ? max(aheadZ, P0.z) : aheadZ;
    }
    pl = planetAt(P1.z < 0.0 ? qs * (1.0 + TE2 * P1.z / (0.3 - P1.z) / qq) : qs, P1, 0.024, 0.0, ax1, t * 0.069813, px, plight, keyCol, fillCol);
    if (P1.z < 0.0) {
        behind = stack(behind, behindZ, pl, P1.z);
        behindZ = pl.a > 0.001 ? max(behindZ, P1.z) : behindZ;
    } else {
        ahead = stack(ahead, aheadZ, pl, P1.z);
        aheadZ = pl.a > 0.001 ? max(aheadZ, P1.z) : aheadZ;
    }
    pl = planetAt(P2.z < 0.0 ? qs * (1.0 + TE2 * P2.z / (0.3 - P2.z) / qq) : qs, P2, 0.034, 1.0, ax2, t * 0.104720, px, plight, keyCol, fillCol);
    if (P2.z < 0.0) {
        behind = stack(behind, behindZ, pl, P2.z);
        behindZ = pl.a > 0.001 ? max(behindZ, P2.z) : behindZ;
    } else {
        ahead = stack(ahead, aheadZ, pl, P2.z);
        aheadZ = pl.a > 0.001 ? max(aheadZ, P2.z) : aheadZ;
    }
    pl = planetAt(P3.z < 0.0 ? qs * (1.0 + TE2 * P3.z / (0.3 - P3.z) / qq) : qs, P3, 0.050, 3.0, ax3, t * 0.157080, px, plight, keyCol, fillCol);
    if (P3.z < 0.0) {
        behind = stack(behind, behindZ, pl, P3.z);
    } else {
        ahead = stack(ahead, aheadZ, pl, P3.z);
    }
    col = col * (1.0 - behind.a * vis) + behind.rgb * vis;

    // ---- The disc and the photon ring ----
    float dop = 1.0 - 0.38 * DIR * cosT;
    float outer = 18.0 + 4.0 * uMid;
    if (abs(p.x) < 42.0 && abs(p.y) < 18.0) {
        float cyc = uSpin * (1.0 / (2.0 * TAU));
        float tA = fract(cyc);
        float wA = 1.0 - abs(2.0 * tA - 1.0);
        float shA = tA * 2.0 * TAU;
        float shB = fract(cyc + 0.5) * 2.0 * TAU;

        // Where the ray crosses the disc plane: polar angle psi1 (first crossing) and the azimuth.
        float psi1 = atan(COS_I, -SIN_I * sinT);
        float cP = cos(psi1);
        float sP = sin(psi1);
        float phi = atan(cP * SIN_I - sP * COS_I * sinT, sP * cosT);
        float defl = b > BC ? deflection(b) : 0.0;

        // Second crossing, after the ray has swung round the back: the far side's underside, seen
        // under the hole.
        if (b > BC && defl > psi1) {
            float r2d = rayRadius(b, min(defl - psi1, 0.5 * (PI + defl)));
            if (r2d > 3.6 && r2d < outer * 1.4) {
                float lod2 = min(abs(secondCrossing(p - float2(0.0, pxM)) - r2d) * 5.0 / r2d, 4.0);
                float4 s = discAt(r2d, phi + PI, p.x, lod2, shA, shB, wA, outer, gain);
                col = col * (1.0 - s.a) + s.rgb;
            }
        }

        // The photon ring: light that orbited the hole before escaping, a hairline at the shadow's
        // edge that is bright where the gas comes toward us and faint where it goes away. Its
        // colour runs from a 6000 K black body on that side to 4750 K on the other.
        if (b < BC * 1.6) {
            float3 hot = mix(float3(1.0, 0.9457, 0.8515), float3(1.0, 0.7562, 0.4465), 0.5 + 0.5 * DIR * cosT);
            float ringW = max(0.05, 0.55 * pxM);
            float rd = (b - BC * 1.008) / ringW;
            col += hot * ((exp(-rd * rd) * 0.40 + exp(-max(b - BC, 0.0) / 0.35) * 0.10 * vis) * dop * dop * gain);
        }

        // First crossing. A ray inside the shadow still sees the near side of the disc on its way in.
        if (b > BC || cP > 0.0) {
            float pe1 = b > BC ? min(psi1, min(PI + defl - psi1, 0.5 * (PI + defl))) : psi1;
            float r1d = rayRadius(b, pe1);
            if (r1d > 3.6 && r1d < outer * 1.4) {
                // How fast the crossing radius changes from this pixel to the one below sets the detail.
                float lod = abs(firstCrossing(p - float2(0.0, pxM)) - r1d) * 5.0 / r1d;
                float4 s = discAt(r1d, phi, p.x, lod, shA, shB, wA, outer, gain);
                col = col * (1.0 - s.a) + s.rgb;
            }
        }
    }

    // ---- Planets on the near half, in front of everything ----
    col = col * (1.0 - ahead.a) + ahead.rgb;

    // ---- Bloom: the brightest light spills into the lens ----
    // A glow hugging the shadow (it barely reaches inside), a wider warm haze, and a soft sheet of
    // light along the disc that gives it some thickness. All in the colour of 3600 K gas.
    float rim = b > BC ? exp(-(b - BC) / 1.8) : exp(-(BC - b) / 0.25);
    float halo = rim * 0.085 * dop + exp(-max(b - BC, 0.0) / 6.0) * 0.010 * vis;
    // In front of the shadow only the near side of the disc is lit, so the sheet sits below centre there.
    float gy = (p.y + 1.2 * captured) / (0.55 + 0.06 * abs(p.x));
    float sheet = exp(-gy * gy) * exp(-abs(p.x) / 15.0) * 0.09 * (1.0 - 0.38 * DIR * clamp(p.x / 10.0, -1.0, 1.0));
    col += float3(1.0, 0.5371, 0.1672) * ((halo + sheet) * gain * BLOOM);

    float y = fragCoord.y / uSize.y;
    col *= 1.0 - 0.35 * smoothstep(0.55, 1.0, y);

    // The page colour underneath (the shadow is darker than the night sky round it), then tone map
    // with a soft shoulder, back to display gamma, and dither below one 8-bit step.
    col += float3(uBase.rgb * uBase.rgb) * vis;
    col = sqrt(1.0 - exp(-col * 1.15));
    col += (hash12(fragCoord) - 0.5) * (1.5 / 255.0);
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/**
 * The app's backdrop: a black hole whose disc turns while a song plays, planets orbiting it, the
 * occasional shooting star, and an astronaut drifting in front of it all.
 *
 * Android 13+ (API 33, where `RuntimeShader` exists) runs the AGSL shader above. Older versions,
 * or a device whose driver rejects the shader, get a simpler version of the same scene drawn
 * with gradients on a Canvas: same hole, same planets on the same orbits, no lensing.
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
        FloatingAstronaut(tracker, Modifier.matchParentSize())
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
        // The shader only uses the spin through its cross-fade cycle, so wrapping on a whole number
        // of cycles is seamless. The Canvas fallback's lobes repeat every turn, which also divides it.
        angle = (angle + rate * dt) % SPIN_WRAP
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
            val pos = FloatArray(PLANETS * 3)
            onDrawBehind {
                tracker.frame.longValue // redraw every frame without recomposing
                val t = tracker.seconds
                shader.setFloatUniform("uTime", t)
                shader.setFloatUniform("uSpin", spin.advance(tracker))
                shader.setFloatUniform("uBass", tracker.bass.value)
                shader.setFloatUniform("uMid", tracker.mid.value)
                shader.setFloatUniform("uTreble", tracker.treble.value)
                // The same for every pixel, so worked out here once instead of millions of times.
                planetPositions(t, pos)
                shader.setFloatUniform("uP0", pos[0], pos[1], pos[2])
                shader.setFloatUniform("uP1", pos[3], pos[4], pos[5])
                shader.setFloatUniform("uP2", pos[6], pos[7], pos[8])
                shader.setFloatUniform("uP3", pos[9], pos[10], pos[11])
                shader.setFloatUniform("uNebula", 0.5f + 0.5f * sin(t * 0.07f))
                drawRect(brush)
            }
        },
    )
}

private const val STARS = 70

// Planet 0 is an icy moon, 1 a desert world, 2 an ice giant, 3 the ringed giant. Orbit radius and
// planet radius are fractions of the screen width; then angular speed (rad/s), start angle, how
// open the orbit looks (the cosine of its inclination) and how far it leans from the disc
// (radians). Each speed is a whole number of turns per hour, so the tracker's hourly clock wrap is
// invisible. The shader gets its planets from these too, through planetPositions.
private val PlanetOrbit = floatArrayOf(0.33f, 0.40f, 0.47f, 0.55f)
private val PlanetSize = floatArrayOf(0.016f, 0.024f, 0.034f, 0.050f)
private val PlanetSpeed = floatArrayOf(0.130900f, 0.078540f, 0.052360f, 0.031416f)
private val PlanetPhase = floatArrayOf(0.6f, 2.9f, 4.4f, 1.7f)
private val PlanetOpen = floatArrayOf(0.42f, 0.30f, 0.20f, 0.14f)
private val PlanetLean = floatArrayOf(0.244f, -0.157f, 0.105f, -0.070f)
private const val PLANETS = 4
private const val ICE_GIANT = 2
private const val RINGED = 3

// The camera's distance from the hole in screen widths: planets swinging toward us grow a little.
private const val CAM = 2.4f

// Day-side colours, and the air glow round the two planets that have much air.
private val PlanetTint = arrayOf(Color(0xFFE6E0D6), Color(0xFFD48A52), Color(0xFF6F9BE2), Color(0xFFE9CF9E))
private val IceAir = Color(0xFF8DB6FF)
private val GiantAir = Color(0xFFFFD9A8)

// The giant's rings seen from here: its spin axis leans the ellipse by this much and it is this
// flat. The broad bright ring and the fainter outer one, in planet radii.
private const val RING_LEAN_DEG = -25.5f
private const val RING_FLAT = 0.29f
private val NightSide = Color(0xFF0A0D18)

// The disc's colours, hottest first: warm white at the inner edge, gold, then deep amber.
private val DiscHot = Color(0xFFFFF0D8)
private val DiscGold = Color(0xFFF7B868)
private val DiscEmber = Color(0xFFC0602A)

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
            val nebula = Brush.radialGradient(
                0f to Nocturne.Indigo.copy(alpha = 0.20f),
                0.5f to Nocturne.Slate.copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // Unit radius is the disc's outer edge, 26 M or five shadow radii; the gap inside is the hole.
            val discBrush = Brush.radialGradient(
                0.21f to Color.Transparent,
                0.25f to DiscHot.copy(alpha = 0.95f),
                0.38f to DiscGold.copy(alpha = 0.70f),
                0.62f to DiscEmber.copy(alpha = 0.35f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // Brighter lobes that turn with the spin, laid over the disc.
            val streaks = Brush.sweepGradient(
                0f to Color.Transparent,
                0.12f to DiscHot.copy(alpha = 0.30f),
                0.30f to Color.Transparent,
                0.46f to DiscHot.copy(alpha = 0.20f),
                0.60f to Color.Transparent,
                0.78f to DiscHot.copy(alpha = 0.26f),
                1f to Color.Transparent,
                center = Offset.Zero,
            )
            // The far side of the disc, lifted over the top of the shadow by the hole's gravity.
            // Unit radius is 2.5 shadow radii; the inner edge of the disc lands at 1.28.
            val arcBrush = Brush.radialGradient(
                0.50f to Color.Transparent,
                0.53f to DiscHot.copy(alpha = 0.90f),
                0.68f to DiscGold.copy(alpha = 0.55f),
                0.85f to DiscEmber.copy(alpha = 0.20f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // The far side's underside, bent the other way under the shadow. Unit radius is 2.05
            // shadow radii; the inner edge lands at 1.23.
            val underBrush = Brush.radialGradient(
                0.59f to Color.Transparent,
                0.62f to DiscHot.copy(alpha = 0.75f),
                0.76f to DiscGold.copy(alpha = 0.45f),
                0.90f to DiscEmber.copy(alpha = 0.15f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // The side of the disc coming toward us is boosted (relativistic beaming).
            val beaming = Brush.radialGradient(
                0f to DiscHot.copy(alpha = 0.50f),
                0.5f to DiscHot.copy(alpha = 0.14f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // Bloom hugging the shadow. Unit radius is 1.8 shadow radii.
            val glow = Brush.radialGradient(
                0.50f to DiscGold.copy(alpha = 0.30f),
                0.75f to DiscGold.copy(alpha = 0.08f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val planetBrushes = Array(PLANETS) { planetShading(PlanetTint[it]) }
            val ringColor = Color(0xFFDCC9A6)
            val ringStroke = Stroke(0.45f)
            val ringStrokeOuter = Stroke(0.22f)
            val airStroke = Stroke(0.06f)
            val photonStroke = Stroke(unit * 1.0f)
            val calm = Brush.verticalGradient(
                0.55f to Color.Transparent,
                1f to Nocturne.Obsidian.copy(alpha = 0.45f),
            )
            val pos = FloatArray(PLANETS * 3)
            val order = IntArray(PLANETS)
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
                    val alpha = (0.20f + 0.50f * twinkle * (0.45f + 0.55f * treble)) * (0.3f + 0.7f * seeds[k + 2] * seeds[k + 2])
                    drawCircle(Color.White, unit * (0.4f + 0.8f * seeds[k + 2]), Offset(seeds[k] * w, seeds[k + 1] * h), alpha = alpha)
                }

                planetPositions(t, pos)
                sortByDepth(pos, order)
                withTransform({
                    translate(w * HOLE_X, h * HOLE_Y)
                    rotate(ROLL_DEG, Offset.Zero)
                }) {
                    // Back to front: far planets, the disc's far side lifted over the top, the disc,
                    // its underside under the shadow, the glow, the shadow and photon ring, then the
                    // near half of the disc again so it crosses in front of the shadow, and the
                    // near planets.
                    planets(w, far = true, pos, order, planetBrushes, ringColor, ringStroke, ringStrokeOuter, airStroke, gain)
                    clipRect(left = -2f * w, top = -2f * h, right = 2f * w, bottom = 0f) {
                        withTransform({ scale(1.25f, 1f, Offset.Zero) }) {
                            orb(arcBrush, 0f, 0f, holeR * 2.5f, gain)
                        }
                    }
                    drawDisc(discBrush, streaks, holeR, spinDeg, gain)
                    clipRect(left = -2f * w, top = 0f, right = 2f * w, bottom = 2f * h) {
                        withTransform({ scale(1.1f, 1f, Offset.Zero) }) {
                            orb(underBrush, 0f, 0f, holeR * 2.05f, gain * 0.85f)
                        }
                    }
                    orb(glow, 0f, 0f, holeR * 1.8f, gain)
                    drawCircle(Color.Black, holeR, Offset.Zero)
                    drawCircle(DiscHot, holeR * 1.008f, Offset.Zero, alpha = 0.45f * gain, style = photonStroke)
                    clipRect(left = -2f * w, top = 0f, right = 2f * w, bottom = 2f * h) {
                        drawDisc(discBrush, streaks, holeR, spinDeg, gain)
                    }
                    withTransform({ scale(1f, 0.3f, Offset.Zero) }) {
                        orb(beaming, -holeR * 2.2f, holeR * 0.3f, holeR * 1.8f, gain * 0.8f)
                    }
                    planets(w, far = false, pos, order, planetBrushes, ringColor, ringStroke, ringStrokeOuter, airStroke, gain)
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
        orb(disc, 0f, 0f, holeR * 5f, gain)
        drawCircle(streaks, holeR * 2.4f, Offset.Zero, alpha = gain, style = Stroke(holeR * 2.2f))
    }
}

/** A lit sphere as a unit-radius gradient: full colour where the light falls, into the night side at the rim. */
private fun planetShading(tint: Color) = Brush.radialGradient(
    0f to tint,
    0.45f to Color(tint.red * 0.62f, tint.green * 0.60f, tint.blue * 0.60f),
    0.80f to Color(tint.red * 0.14f, tint.green * 0.15f, tint.blue * 0.22f),
    1f to NightSide,
    center = Offset.Zero,
    radius = 1f,
)

/** Fills [out] with where each planet is now: x right, y up, z toward us, in screen widths from the hole. */
private fun planetPositions(t: Float, out: FloatArray) {
    for (i in 0 until PLANETS) {
        val a = PlanetOrbit[i]
        val th = t * PlanetSpeed[i] + PlanetPhase[i]
        val s = sin(th)
        val x = a * cos(th)
        val y = a * s * PlanetOpen[i]
        val cl = cos(PlanetLean[i])
        val sl = sin(PlanetLean[i])
        out[i * 3] = x * cl - y * sl
        out[i * 3 + 1] = x * sl + y * cl
        out[i * 3 + 2] = -a * s * sqrt(1f - PlanetOpen[i] * PlanetOpen[i])
    }
}

/** Sorts planet numbers into [order], farthest first, by the depths in [pos]. */
private fun sortByDepth(pos: FloatArray, order: IntArray) {
    for (i in order.indices) order[i] = i
    for (i in 1 until order.size) {
        val k = order[i]
        var j = i - 1
        while (j >= 0 && pos[order[j] * 3 + 2] > pos[k * 3 + 2]) {
            order[j + 1] = order[j]
            j--
        }
        order[j + 1] = k
    }
}

/** Draws the planets behind the hole, or the ones in front of it, farthest first. Origin is the hole, y down. */
private fun DrawScope.planets(
    w: Float,
    far: Boolean,
    pos: FloatArray,
    order: IntArray,
    shading: Array<Brush>,
    ringColor: Color,
    ringStroke: Stroke,
    ringStrokeOuter: Stroke,
    airStroke: Stroke,
    gain: Float,
) {
    for (i in order) {
        val px = pos[i * 3]
        val py = pos[i * 3 + 1]
        val pz = pos[i * 3 + 2]
        if ((pz < 0f) != far) continue
        val persp = CAM / (CAM - pz)
        val r = w * PlanetSize[i] * persp
        val cx = w * px * persp
        val cy = -w * py * persp
        // Lit from the hole. Behind it we see the day side, so the light sits near the middle; in
        // front of it the light is behind the planet and only the rim facing the hole glows.
        val dist = sqrt(px * px + py * py + pz * pz).coerceAtLeast(1e-3f)
        val lx = -px
        val ly = py
        val ll = sqrt(lx * lx + ly * ly).coerceAtLeast(1e-3f)
        val back = (0.5f + 0.5f * pz / dist).coerceIn(0f, 1f)
        val reach = r * (0.30f + 0.85f * back)
        val gx = cx + lx / ll * reach
        val gy = cy + ly / ll * reach
        val spread = r * (1.75f - 0.55f * back)
        val light = (1f - 0.62f * back) * gain * (0.42f / dist).coerceIn(0.7f, 1.3f)

        if (i == RINGED) ring(cx, cy, r, front = false, ringColor, ringStroke, ringStrokeOuter, light)
        drawCircle(NightSide, r, Offset(cx, cy))
        withTransform({
            translate(gx, gy)
            scale(spread, spread, Offset.Zero)
        }) {
            drawCircle(shading[i], radius = r / spread, center = Offset((cx - gx) / spread, (cy - gy) / spread), alpha = light)
        }
        // Air: a thin glow round the limb, all the way round when the planet is backlit.
        if (i == ICE_GIANT || i == RINGED) {
            val air = if (i == ICE_GIANT) IceAir else GiantAir
            withTransform({
                translate(cx, cy)
                scale(r, r, Offset.Zero)
            }) {
                drawCircle(air, 1.02f, Offset.Zero, alpha = (0.15f + 0.45f * back) * gain, style = airStroke)
            }
        }
        if (i == RINGED) ring(cx, cy, r, front = true, ringColor, ringStroke, ringStrokeOuter, light)
    }
}

/** Half of the giant's rings: the half behind the planet, or the half in front of it. */
private fun DrawScope.ring(
    cx: Float,
    cy: Float,
    r: Float,
    front: Boolean,
    color: Color,
    stroke: Stroke,
    strokeOuter: Stroke,
    light: Float,
) {
    // Scaling y by RING_FLAT (rather than drawing a flat oval) flattens the rings' width too, as
    // a ring seen at a slant really is.
    withTransform({
        translate(cx, cy)
        rotate(RING_LEAN_DEG, Offset.Zero)
        scale(r, r * RING_FLAT, Offset.Zero)
    }) {
        clipRect(left = -3f, top = if (front) 0f else -3f, right = 3f, bottom = if (front) 3f else 0f) {
            val lit = 0.35f + 0.65f * light
            drawCircle(color, 1.72f, Offset.Zero, alpha = 0.75f * lit, style = stroke)
            drawCircle(color, 2.14f, Offset.Zero, alpha = 0.50f * lit, style = strokeOuter)
        }
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
