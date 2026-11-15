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
import androidx.compose.ui.geometry.Size
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
private const val HOLE_R = 0.0805f // radius of the shadow: sqrt(27) times the hole's mass, M = 0.0155
private const val DISC_TILT = 0.20f // how flat the disc and the orbits look (1 = seen from above)
private const val ROLL_DEG = -18.3f // the whole system leans by this much
private const val TAU = 6.2831855f

// The shader's disc texture cross-fades once every two turns of the spin, so the spin may wrap at
// any whole number of those cycles without a visible jump.
private const val SPIN_WRAP = 4f * TAU

/*
 * A black hole seen nearly edge-on, with a thin accretion disc, four planets orbiting in the
 * disc's plane, and a star field bent round it.
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
 * back (the thinner arc under the shadow). Ray radii come from Beloborodov's approximation on the
 * way in and its mirror image about periapsis on the way out, with a fitted deflection angle; both
 * are within about 2% of exact Schwarzschild orbits. No ray marching: one log, a few sqrt, two atan.
 *
 * The gas is coloured as a black body by its temperature, which falls with radius, and is shifted
 * by gravity and by its orbital speed: the side coming toward us is bluer and much brighter. Gas
 * closer in orbits faster (Kepler), so its streaks shear; two copies of the texture half a cycle
 * apart cross-fade so the shear never winds up. Planets passing behind the hole are lensed into
 * arcs. Everything is lit in linear light, tone-mapped, and dithered by under one 8-bit step.
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

const float M = 0.0155;        // the hole's mass, in screen widths
const float BC = 5.196152;     // radius of the shadow, sqrt(27) M
const float COS_I = 0.20;      // the disc is seen this close to edge-on
const float SIN_I = 0.979796;
const float ROLL_C = 0.9494;
const float ROLL_S = 0.3140;
const float R_IN = 6.0;        // innermost stable orbit: the disc's inner edge
const float PI = 3.14159265;
const float TAU = 6.2831853;
const float DIR = 1.0;         // which way the disc turns: +1 brings the left side toward us
const float TE2 = 0.0227;      // Einstein radius squared for the far sky, screen widths

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

float discFbm(float2 p) {
    float s = 0.5 * wnoise(p, 12.0);
    s += 0.25 * wnoise(p * 2.0 + float2(0.0, 7.3), 24.0);
    s += 0.125 * wnoise(p * 4.0 + float2(0.0, 3.1), 48.0);
    return s * 1.142857;
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

// Light from the disc where a ray crosses it at radius r and azimuth phi. xImg is the ray's
// sideways offset on screen (M), which fixes its angular momentum and so the Doppler shift.
// rgb is emitted light, a is how opaque the gas is there.
float4 discAt(float r, float phi, float xImg, float shA, float shB, float wA, float outer, float gain) {
    if (r < R_IN * 0.95 || r > outer * 1.5) {
        return float4(0.0);
    }
    float x = R_IN / r;
    float flux = x * x * x * max(1.0 - sqrt(x), 0.0) * 17.6;
    float g = sqrt(max(1.0 - 3.0 / r, 0.02)) / (1.0 + 1.2 * DIR * xImg * SIN_I * pow(r, -1.5));

    // Gas closer in laps the gas further out (Kepler), so the texture shears. Two copies offset by
    // half a cycle cross-fade, so the shear never winds up.
    float kep = pow(10.0 / r, 1.5) * (12.0 / TAU) * DIR;
    float lr = log(r) * 8.0;
    float az = phi * (12.0 / TAU);
    float2 qa = float2(az + shA * kep, lr);
    float2 qb = float2(az + shB * kep, lr + 19.7);
    float wB = 1.0 - wA;
    float norm = 1.0 / sqrt(wA * wA + wB * wB);
    // Fine streaks along the orbit...
    float n = 0.5 + (wA * discFbm(qa) + wB * discFbm(qb) - 0.5) * norm;
    // ...riding on broad clumps of brighter and darker gas.
    float k = 0.5 + (wA * wnoise(qa * float2(0.5, 0.35), 6.0) + wB * wnoise(qb * float2(0.5, 0.35), 6.0) - 0.5) * norm;
    float streak = smoothstep(0.30, 0.90, n);
    float tex = (0.30 + 0.70 * smoothstep(0.15, 0.85, k)) * (0.25 + 0.75 * streak) + 0.9 * pow(streak, 6.0);

    float edge = smoothstep(R_IN * 0.95, R_IN * 1.3, r) * (1.0 - smoothstep(outer * 0.6, outer * 1.35, r));
    float t = 3400.0 * pow(flux, 0.4) * g;
    float g2 = g * g;
    float lum = flux * flux * sqrt(flux) * g2 * g * sqrt(g) * tex * edge * gain * 1.6;
    return float4(blackbody(t) * lum, edge * (0.55 + 0.45 * min(tex, 1.0)));
}

// A planet at angle th on its orbit (screen widths) in the disc plane, lit by the disc.
// rgb is premultiplied, a is coverage. kind: 0 rock, 1 ice giant, 2 airless moon, 3 ringed giant.
float4 planet(float2 q, float orbit, float size, float th, float kind, float3 c1, float3 c2, float3 axis,
              float spin, float pxw, float light, float3 lightCol, float3 ambient) {
    float sn = sin(th);
    float cs = cos(th);
    float2 centre = float2(orbit * cs, orbit * sn * COS_I);
    float sz = size * (1.0 + 0.18 * sn);
    float2 e = (q - centre) / sz;
    float e2 = dot(e, e);
    float reach = kind > 2.5 ? 5.2 : 1.6;
    if (e2 > reach) {
        return float4(0.0);
    }
    float3 L = -float3(cs, sn * COS_I, sn * SIN_I);
    float len = sqrt(e2);
    float aa = pxw / sz;
    float cover = 1.0 - smoothstep(1.0 - 1.5 * aa, 1.0 + 0.5 * aa, len);
    float3 n = float3(e, sqrt(max(1.0 - e2, 0.0)));

    float3 b1 = normalize(cross(axis, float3(0.0, 0.0, 1.0)));
    float3 b2 = cross(axis, b1);
    float lat = dot(n, axis);
    float u0 = dot(n, b1);
    float w0 = dot(n, b2);
    float cS = cos(spin);
    float sS = sin(spin);
    float3 nl = float3(u0 * cS - w0 * sS, lat, u0 * sS + w0 * cS);

    float3 albedo = c1;
    float atmos = 0.0;
    if (kind < 0.5) {
        float h = fbm3(nl * 3.2);
        float crater = smoothstep(0.62, 0.70, noise3(nl * 7.0 + 3.0));
        albedo = mix(c2, c1, smoothstep(0.30, 0.72, h)) * (1.0 - 0.35 * crater);
    } else if (kind < 1.5) {
        float band = 0.5 + 0.5 * sin(lat * 10.0 + 1.8 * noise3(nl * 2.6));
        albedo = mix(c1, c2, band * 0.55);
        atmos = 0.9;
    } else if (kind < 2.5) {
        float maria = smoothstep(0.45, 0.62, fbm3(nl * 2.2 + 11.0));
        albedo = mix(c1, c2, maria) * (0.86 + 0.28 * noise3(nl * 9.0));
    } else {
        float turb = noise3(nl * 3.0 + 2.0);
        float band = 0.5 + 0.5 * sin(lat * 15.0 + 2.2 * turb);
        float fine = 0.5 + 0.5 * sin(lat * 41.0 + turb * 3.0);
        albedo = mix(c1, c2, band) * (0.88 + 0.12 * fine);
        atmos = 0.6;
    }

    float ndl = dot(n, L);
    // The disc is a broad light, so the terminator is soft.
    float diff = smoothstep(-0.18, 0.9, ndl);
    float limb = atmos > 0.0 ? pow(max(n.z, 0.0), 0.3) : 1.0;

    // Ring shadow across the giant's face.
    float ringIn = 1.38;
    float ringOut = 2.2;
    if (kind > 2.5) {
        float la = dot(L, axis);
        float tt = -lat / (abs(la) > 0.001 ? la : 0.001);
        if (tt > 0.0) {
            float hr = length(n + tt * L);
            float band = smoothstep(ringIn, ringIn + 0.06, hr) * (1.0 - smoothstep(ringOut - 0.1, ringOut, hr));
            diff *= 1.0 - 0.6 * band;
        }
    }

    float3 col = albedo * (lightCol * (diff * light) + ambient) * limb;
    float rim = pow(1.0 - n.z, 2.5) * smoothstep(-0.35, 0.5, ndl);
    col += mix(c1, float3(0.6, 0.75, 1.0), 0.4) * (rim * atmos * 0.5 * light);
    float4 outCol = float4(col * cover, cover);

    // Haze above the limb. Seen with the light behind it, it lights up all round.
    if (atmos > 0.0 && len > 0.98) {
        float2 ed = e / len;
        float facing = clamp(dot(ed, L.xy) * 0.7 + 0.6 * max(-L.z, 0.0) + 0.15, 0.0, 1.0);
        float halo = exp(-(len - 1.0) * 26.0) * facing * atmos * light * 0.55;
        outCol.rgb += mix(c1, float3(0.7, 0.85, 1.0), 0.5) * halo * (1.0 - cover);
    }

    if (kind > 2.5) {
        float zr = -(e.x * axis.x + e.y * axis.y) / axis.z;
        float3 pr = float3(e, zr);
        float rr = length(pr);
        if (rr > ringIn - 0.05 && rr < ringOut + 0.05 && (zr > 0.0 || len > 1.0)) {
            // Faint inner ring, dense middle ring, a dark gap, then a thinner outer ring.
            float dens = smoothstep(ringIn, ringIn + 0.03, rr) * (1.0 - smoothstep(ringOut - 0.04, ringOut, rr));
            dens *= mix(0.25, 1.0, smoothstep(1.45, 1.58, rr)) * mix(1.0, 0.6, smoothstep(1.88, 1.95, rr));
            dens *= 0.55 + 0.45 * wnoise(float2(rr * 36.0, 3.5), 999.0);
            dens *= 1.0 - 0.92 * smoothstep(0.035, 0.0, abs(rr - 1.86));
            float pl = dot(pr, L);
            float shade = (pl < 0.0 && dot(pr, pr) - pl * pl < 1.0) ? 0.08 : 1.0;
            // We see the lit face of the rings only when the light is on our side of their plane.
            float face = dot(L, axis) * axis.z > 0.0 ? 1.0 : 0.3;
            float3 ringCol = mix(c2, float3(0.85, 0.78, 0.66), 0.6) * (lightCol * (light * shade * face * (0.3 + 0.7 * abs(dot(L, axis)))) + ambient);
            float ra = dens * 0.8;
            outCol = float4(outCol.rgb * (1.0 - ra) + ringCol * ra, outCol.a + ra * (1.0 - outCol.a));
        }
    }
    return outCol;
}

half4 main(float2 fragCoord) {
    float aspect = uSize.y / uSize.x;
    float px = 1.0 / uSize.x;
    float2 uv = fragCoord * px;
    float2 holeAt = float2(0.5, aspect * 0.34);
    float2 d0 = uv - holeAt;
    float2 d = float2(d0.x * ROLL_C - d0.y * ROLL_S, d0.x * ROLL_S + d0.y * ROLL_C);
    float t = uTime;

    // Work in linear light; squaring is close enough to the sRGB curve for this.
    float3 base = float3(uBase.rgb * uBase.rgb);
    float3 amber = float3(uAmber.rgb * uAmber.rgb);
    float3 indigo = float3(uIndigo.rgb * uIndigo.rgb);
    float3 slate = float3(uSlate.rgb * uSlate.rgb);
    float gain = 0.70 + 0.60 * uBass;

    // Ray geometry, in units of M, y up.
    float2 p = float2(d.x, -d.y) / M;
    float b = max(length(p), 1e-4);
    float cosT = p.x / b;
    float sinT = p.y / b;
    float pxM = px / M;
    float captured = 1.0 - smoothstep(BC - 0.6 * pxM, BC + 0.6 * pxM, b);

    // ---- The sky behind, bent round the hole (point lens) ----
    float r2 = dot(d0, d0) + 1e-6;
    float2 sky = holeAt + d0 * (1.0 - TE2 / r2);
    float3 col = base;
    float2 n1 = sky - float2(0.88, aspect * 0.80);
    float2 n2 = sky - float2(0.08, aspect * 0.10);
    float cloud = noise3(float3(sky * 3.0, t * 0.02));
    col += mix(indigo, slate, 0.5 + 0.5 * sin(t * 0.07)) * (exp(-dot(n1, n1) / 0.30) * (0.030 + 0.020 * uMid) * (0.6 + 0.8 * cloud));
    col += slate * (exp(-dot(n2, n2) / 0.20) * 0.025 * (0.6 + 0.8 * cloud));

    // Faint stars: many, small.
    float2 g1 = sky * 70.0;
    float2 c1 = floor(g1);
    float h1 = hash12(c1);
    float2 o1 = float2(hash12(c1 + 17.0), hash12(c1 + 41.0)) * 0.7 + 0.15;
    float dist1 = length(fract(g1) - o1) / (70.0 * px);
    float s1 = step(0.92, h1) * exp(-dist1 * dist1 / (0.35 + 0.5 * fract(h1 * 11.0)));
    float3 warmStar = float3(1.0, 0.78, 0.55);
    float3 coolStar = float3(0.78, 0.86, 1.0);
    col += mix(warmStar, coolStar, fract(h1 * 7.0)) * (s1 * (0.008 + 0.16 * pow(fract(h1 * 3.0), 4.0)));
    // Bright stars: few, with a soft glow, twinkling with the treble.
    float2 g2 = sky * 13.0;
    float2 c2 = floor(g2);
    float h2 = hash12(c2 + 3.0);
    float2 o2 = float2(hash12(c2 + 23.0), hash12(c2 + 61.0)) * 0.6 + 0.2;
    float dist2 = length(fract(g2) - o2) / (13.0 * px);
    float tw = 0.75 + 0.25 * sin(t * (0.9 + 2.5 * h2) + h2 * 40.0) * (0.4 + 0.6 * uTreble);
    float s2 = step(0.89, h2) * (exp(-dist2 * dist2 / 0.9) + 0.04 * exp(-dist2 / 3.0)) * tw;
    col += mix(warmStar, coolStar, fract(h2 * 13.0)) * (s2 * (0.25 + 0.6 * fract(h2 * 5.0)));
    // Light from very close to the shadow is squeezed into a thin, faint ring.
    col *= smoothstep(BC, BC * 1.35, b);

    // ---- Planets on the far half of their orbits, seen through the lens ----
    float3 lightCol = mix(amber, float3(1.0, 0.92, 0.80), 0.55) * 1.2;
    float3 amb = indigo * 0.02 + slate * 0.03;
    float3 dustA = float3(0.66, 0.34, 0.16);
    float3 dustB = float3(0.30, 0.15, 0.09);
    float3 iceA = indigo * 0.9 + float3(0.05, 0.08, 0.12);
    float3 iceB = float3(0.55, 0.62, 0.95);
    float3 moonA = float3(0.60, 0.60, 0.62);
    float3 moonB = float3(0.30, 0.30, 0.34);
    float3 gasA = float3(0.74, 0.46, 0.28);
    float3 gasB = float3(0.46, 0.30, 0.30);
    float3 ax1 = normalize(float3(0.20, -0.95, 0.25));
    float3 ax3 = normalize(float3(-0.42, -0.88, 0.30));

    float th1 = t * 0.130900 + 0.6;
    float th2 = t * 0.078540 + 2.9;
    float th3 = t * 0.052360 + 4.4;
    float th4 = t * 0.031416 + 1.7;
    float vis = 1.0 - captured;
    float plight = gain * 0.78;
    float4 pl = float4(0.0);
    // Far planets sit behind the hole, so their light is bent too: more the further back they are.
    float sn1 = sin(th1);
    float sn2 = sin(th2);
    float sn3 = sin(th3);
    float sn4 = sin(th4);
    float dd = dot(d, d) + 1e-6;
    if (sn1 < 0.0) { float z = -0.40 * sn1; pl = planet(d * (1.0 - TE2 * z / (z + 0.3) / dd), 0.40, 0.019, th1, 0.0, dustA, dustB, ax1, t * 0.05, px, plight, lightCol, amb); col = col * (1.0 - pl.a * vis) + pl.rgb * vis; }
    if (sn2 < 0.0) { float z = -0.52 * sn2; pl = planet(d * (1.0 - TE2 * z / (z + 0.3) / dd), 0.52, 0.028, th2, 1.0, iceA, iceB, ax1, t * 0.09, px, plight, lightCol, amb); col = col * (1.0 - pl.a * vis) + pl.rgb * vis; }
    if (sn3 < 0.0) { float z = -0.65 * sn3; pl = planet(d * (1.0 - TE2 * z / (z + 0.3) / dd), 0.65, 0.021, th3, 2.0, moonA, moonB, ax1, t * 0.03, px, plight, lightCol, amb); col = col * (1.0 - pl.a * vis) + pl.rgb * vis; }
    if (sn4 < 0.0) { float z = -0.84 * sn4; pl = planet(d * (1.0 - TE2 * z / (z + 0.3) / dd), 0.84, 0.036, th4, 3.0, gasA, gasB, ax3, t * 0.07, px, plight, lightCol, amb); col = col * (1.0 - pl.a * vis) + pl.rgb * vis; }

    // ---- Photon ring: light that orbited the hole before escaping ----
    float gRing = 0.76 / (1.0 + DIR * cosT * 0.27);
    float ringW = max(0.10, 1.2 * pxM);
    float ringD = (b - BC * 1.012) / ringW;
    if (abs(ringD) < 4.0) {
        col += blackbody(4400.0 * gRing) * (exp(-ringD * ringD) * 0.9 * gRing * gRing * gRing * gain) * (1.0 - captured);
    }

    // ---- The disc ----
    float outer = 15.0 + 5.0 * uMid;
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
    float xImg = p.x;
    float defl = b > BC ? deflection(b) : 0.0;

    // Second crossing, after the ray has swung round the back: the far side's underside, seen under the hole.
    if (b > BC && defl > psi1) {
        float pe2 = min(defl - psi1, 0.5 * (PI + defl));
        float r2d = rayRadius(b, pe2);
        float4 s = discAt(r2d, phi + PI, xImg, shA, shB, wA, outer, gain);
        col = col * (1.0 - s.a) + s.rgb;
    }
    // First crossing. A ray inside the shadow still sees the near side of the disc on its way in.
    if (b > BC || cP > 0.0) {
        float pe1 = b > BC ? min(psi1, min(PI + defl - psi1, 0.5 * (PI + defl))) : psi1;
        float r1d = rayRadius(b, pe1);
        float4 s = discAt(r1d, phi, xImg, shA, shB, wA, outer, gain);
        col = col * (1.0 - s.a) + s.rgb;
    }

    // ---- Planets on the near half, in front of everything ----
    if (sn1 >= 0.0) { pl = planet(d, 0.40, 0.019, th1, 0.0, dustA, dustB, ax1, t * 0.05, px, plight, lightCol, amb); col = col * (1.0 - pl.a) + pl.rgb; }
    if (sn2 >= 0.0) { pl = planet(d, 0.52, 0.028, th2, 1.0, iceA, iceB, ax1, t * 0.09, px, plight, lightCol, amb); col = col * (1.0 - pl.a) + pl.rgb; }
    if (sn3 >= 0.0) { pl = planet(d, 0.65, 0.021, th3, 2.0, moonA, moonB, ax1, t * 0.03, px, plight, lightCol, amb); col = col * (1.0 - pl.a) + pl.rgb; }
    if (sn4 >= 0.0) { pl = planet(d, 0.84, 0.036, th4, 3.0, gasA, gasB, ax3, t * 0.07, px, plight, lightCol, amb); col = col * (1.0 - pl.a) + pl.rgb; }

    // ---- Bloom: the brightest light spills a little ----
    float2 ell = float2(p.x / 20.0, p.y / 6.5);
    float beam = 1.0 - 0.45 * DIR * cosT;
    col += amber * (exp(-dot(ell, ell)) * 0.035 * beam * gain * vis);
    col += amber * (exp(-max(b - BC, 0.0) / 1.4) * 0.05 * gain * vis);

    float y = fragCoord.y / uSize.y;
    col *= 1.0 - 0.35 * smoothstep(0.55, 1.0, y);

    // Tone map (soft shoulder), back to display gamma, dither below one 8-bit step.
    col = 1.0 - exp(-col * 1.15);
    col = sqrt(col);
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
// The same numbers are written into the shader.
private val PlanetOrbit = floatArrayOf(0.40f, 0.52f, 0.65f, 0.84f)
private val PlanetSize = floatArrayOf(0.019f, 0.028f, 0.021f, 0.036f)
private val PlanetSpeed = floatArrayOf(0.130900f, 0.078540f, 0.052360f, 0.031416f)
private val PlanetPhase = floatArrayOf(0.6f, 2.9f, 4.4f, 1.7f)

// Day-side colours: dusty rock, ice giant, airless moon, ringed giant.
private val PlanetTint = arrayOf(Color(0xFFCF9466), Color(0xFF7580E0), Color(0xFFC5C5C9), Color(0xFFDBAD87))
private const val RINGED = 3

// The giant's rings seen from here: an ellipse whose long axis leans by this much, this flat.
private const val RING_LEAN_DEG = -25.5f
private const val RING_FLAT = 0.30f
private val NightSide = Color(0xFF07080C)

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
            val hot = Color(0xFFFBEBD5)
            val nebula = Brush.radialGradient(
                0f to Nocturne.Indigo.copy(alpha = 0.20f),
                0.5f to Nocturne.Slate.copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // Unit radius is the disc's outer edge at 3.3 shadow radii; the gap inside is the hole.
            val discBrush = Brush.radialGradient(
                0.33f to Color.Transparent,
                0.40f to hot.copy(alpha = 0.95f),
                0.58f to Nocturne.Amber.copy(alpha = 0.50f),
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
            // The far side of the disc, lifted over the top of the shadow by the hole's gravity.
            // Unit radius is 2.66 shadow radii, so the dark gap above the shadow ends at 1.25.
            val arcBrush = Brush.radialGradient(
                0.43f to Color.Transparent,
                0.50f to hot.copy(alpha = 0.85f),
                0.66f to Nocturne.Amber.copy(alpha = 0.40f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // The far side's underside, bent the other way under the shadow: a thinner arc.
            // Unit radius is 1.65 shadow radii.
            val underBrush = Brush.radialGradient(
                0.62f to Color.Transparent,
                0.68f to hot.copy(alpha = 0.65f),
                0.84f to Nocturne.Amber.copy(alpha = 0.30f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            // The side of the disc coming toward us is boosted (relativistic beaming).
            val beaming = Brush.radialGradient(
                0f to hot.copy(alpha = 0.55f),
                0.5f to hot.copy(alpha = 0.16f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val planetBrushes = Array(PlanetTint.size) { planetShading(PlanetTint[it]) }
            val ringColor = Color(0xFFD9C7A8)
            val ringStroke = Stroke(0.42f)
            val ringStrokeOuter = Stroke(0.16f)
            val photonStroke = Stroke(unit * 1.2f)
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
                    val alpha = (0.20f + 0.50f * twinkle * (0.45f + 0.55f * treble)) * (0.3f + 0.7f * seeds[k + 2] * seeds[k + 2])
                    drawCircle(Color.White, unit * (0.4f + 0.8f * seeds[k + 2]), Offset(seeds[k] * w, seeds[k + 1] * h), alpha = alpha)
                }

                withTransform({
                    translate(w * HOLE_X, h * HOLE_Y)
                    rotate(ROLL_DEG, Offset.Zero)
                }) {
                    // Back to front: far planets, the disc's far side lifted over the top, the disc,
                    // its underside under the shadow, the shadow and photon ring, then the near half
                    // of the disc again so it crosses in front of the shadow, and the near planets.
                    planets(t, w, far = true, planetBrushes, ringColor, ringStroke, ringStrokeOuter, gain)
                    clipRect(left = -2f * w, top = -2f * h, right = 2f * w, bottom = 0f) {
                        withTransform({ scale(1.28f, 1f, Offset.Zero) }) {
                            orb(arcBrush, 0f, 0f, holeR * 2.66f, gain)
                        }
                    }
                    drawDisc(discBrush, streaks, holeR, spinDeg, gain)
                    clipRect(left = -2f * w, top = 0f, right = 2f * w, bottom = 2f * h) {
                        withTransform({ scale(1.1f, 1f, Offset.Zero) }) {
                            orb(underBrush, 0f, 0f, holeR * 1.65f, gain * 0.8f)
                        }
                    }
                    drawCircle(Color.Black, holeR, Offset.Zero)
                    drawCircle(hot, holeR * 1.012f, Offset.Zero, alpha = 0.6f * gain, style = photonStroke)
                    clipRect(left = -2f * w, top = 0f, right = 2f * w, bottom = 2f * h) {
                        drawDisc(discBrush, streaks, holeR, spinDeg, gain)
                    }
                    withTransform({ scale(1f, 0.42f, Offset.Zero) }) {
                        orb(beaming, -holeR * 2.1f, holeR * 0.5f, holeR * 1.8f, gain * 0.85f)
                    }
                    planets(t, w, far = false, planetBrushes, ringColor, ringStroke, ringStrokeOuter, gain)
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
        drawCircle(streaks, holeR * 2.2f, Offset.Zero, alpha = gain, style = Stroke(holeR * 1.5f))
    }
}

/** A lit sphere as a unit-radius gradient: full colour where the light falls, into the night side at the rim. */
private fun planetShading(tint: Color) = Brush.radialGradient(
    0f to tint,
    0.45f to Color(tint.red * 0.62f, tint.green * 0.60f, tint.blue * 0.60f),
    0.80f to Color(tint.red * 0.12f, tint.green * 0.12f, tint.blue * 0.16f),
    1f to NightSide,
    center = Offset.Zero,
    radius = 1f,
)

/** Draws the planets on the far half of their orbits, or the ones on the near half. Origin is the hole. */
private fun DrawScope.planets(
    t: Float,
    w: Float,
    far: Boolean,
    shading: Array<Brush>,
    ringColor: Color,
    ringStroke: Stroke,
    ringStrokeOuter: Stroke,
    gain: Float,
) {
    for (i in PlanetOrbit.indices) {
        val th = t * PlanetSpeed[i] + PlanetPhase[i]
        val sn = sin(th)
        val cs = cos(th)
        if ((sn < 0f) != far) continue
        val r = w * PlanetSize[i] * (1f + 0.18f * sn)
        val cx = w * PlanetOrbit[i] * cs
        val cy = w * PlanetOrbit[i] * sn * DISC_TILT
        // Lit from the hole. On the far side we see the day side, so the light sits near the
        // middle; on the near side the light is behind it and only the rim facing the hole glows.
        val lx = -cs
        val ly = -sn * DISC_TILT
        val ll = sqrt(lx * lx + ly * ly).coerceAtLeast(1e-3f)
        val back = 0.5f + 0.5f * sn
        val reach = r * (0.30f + 0.85f * back)
        val gx = cx + lx / ll * reach
        val gy = cy + ly / ll * reach
        val spread = r * (1.75f - 0.55f * back)
        val light = (1f - 0.62f * back) * gain

        if (i == RINGED) ring(cx, cy, r, front = false, ringColor, ringStroke, ringStrokeOuter, light)
        drawCircle(NightSide, r, Offset(cx, cy))
        withTransform({
            translate(gx, gy)
            scale(spread, spread, Offset.Zero)
        }) {
            drawCircle(shading[i], radius = r / spread, center = Offset((cx - gx) / spread, (cy - gy) / spread), alpha = light)
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
    withTransform({
        translate(cx, cy)
        rotate(RING_LEAN_DEG, Offset.Zero)
        scale(r, r, Offset.Zero)
    }) {
        clipRect(left = -3f, top = if (front) 0f else -3f, right = 3f, bottom = if (front) 3f else 0f) {
            drawOval(color, Offset(-1.75f, -1.75f * RING_FLAT), Size(3.5f, 3.5f * RING_FLAT), alpha = 0.75f * light, style = stroke)
            drawOval(color, Offset(-2.1f, -2.1f * RING_FLAT), Size(4.2f, 4.2f * RING_FLAT), alpha = 0.45f * light, style = strokeOuter)
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
