package com.zmusic.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [AstronautPose] works out once a frame what the astronaut shader draws. These checks hold it
 * to the shader's maths, keep the limbs in one piece, and make sure the wave happens, reads as a
 * wave, and never makes the figure jump.
 */
class AstronautPoseTest {

    private class V(val x: Float, val y: Float)

    private fun rot(p: V, a: Float): V {
        val c = cos(a)
        val s = sin(a)
        return V(c * p.x - s * p.y, s * p.x + c * p.y)
    }

    private fun normalize(x: Float, y: Float, z: Float): FloatArray {
        val len = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / len, y / len, z / len)
    }

    private fun posed(t: Float) = AstronautPose().apply {
        update(t, 1080f, 2340f)
        rig(t)
    }

    /** Roots (shoulders, hips) and the rest length of each segment below them, in [AstronautPose.joints] order. */
    private val roots = listOf(V(-0.52f, 0.50f), V(0.52f, 0.50f), V(-0.24f, -0.74f), V(0.24f, -0.74f))
    private val lengths = listOf(
        listOf(hypot(-0.53f, -0.40f), hypot(-0.10f, 0.50f), hypot(-0.02f, 0.14f)),
        listOf(hypot(0.54f, -0.32f), hypot(0.16f, 0.50f), hypot(0.03f, 0.14f)),
        listOf(hypot(-0.20f, -0.82f), hypot(0.13f, -0.76f), hypot(-0.22f, -0.10f)),
        listOf(hypot(0.34f, -0.74f), hypot(-0.14f, -0.76f), hypot(0.24f, -0.08f)),
    )

    @Test
    fun limbsKeepTheirLengthsThroughEveryMove() {
        // Every 0.37 s over an hour lands all through the waves as well as between them.
        var t = 0f
        while (t < 3600f) {
            val j = posed(t).joints
            for (limb in 0 until 4) {
                var px = roots[limb].x
                var py = roots[limb].y
                for (seg in 0 until 3) {
                    val k = limb * 6 + seg * 2
                    assertEquals("limb $limb segment $seg at $t s", lengths[limb][seg], hypot(j[k] - px, j[k + 1] - py), 1e-4f)
                    px = j[k]
                    py = j[k + 1]
                }
            }
            t += 0.37f
        }
    }

    @Test
    fun lightAndFrameMatchTheShader() {
        for (step in 0 until 400) {
            val t = step * 7.3f
            val pose = posed(t)

            // q = rot(fragCoord - uCenter, -uAngle) / uScale: check it on a sample offset.
            val q = rot(V(37f, -81f), -pose.angle)
            assertEquals(q.x, pose.rotCos * 37f - pose.rotSin * -81f, 1e-3f)
            assertEquals(q.y, pose.rotSin * 37f + pose.rotCos * -81f, 1e-3f)

            // L = normalize(float3(lw.x, -lw.y, -0.32)) with lw = rot(uLight, -uAngle).
            val lw = rot(V(pose.lightX, pose.lightY), -pose.angle)
            val l = normalize(lw.x, -lw.y, -0.32f)
            for (i in 0 until 3) assertEquals("L[$i] at $t s", l[i], pose.light[i], 1e-5f)

            // h = normalize(L + float3(0, 0, 1)) and L.xy / max(length(L.xy), 1e-4), from shade().
            val h = normalize(l[0], l[1], l[2] + 1f)
            for (i in 0 until 3) assertEquals("H[$i] at $t s", h[i], pose.half[i], 1e-5f)
            val flat = maxOf(sqrt(l[0] * l[0] + l[1] * l[1]), 1e-4f)
            assertEquals(l[0] / flat, pose.lightXY[0], 1e-5f)
            assertEquals(l[1] / flat, pose.lightXY[1], 1e-5f)

            // uHead = (cos, sin) of the helmet's tilt.
            assertEquals(cos(pose.head), pose.headCos, 1e-6f)
            assertEquals(sin(pose.head), pose.headSin, 1e-6f)
        }
    }

    @Test
    fun wavesEveryHalfMinuteStandingUpright() {
        for (k in 0 until 120) {
            val start = WAVE_AT + k * WAVE_PERIOD
            if (start + 3f >= 3600f) break
            // Halfway through: upright, the near hand up past the helmet and open.
            val mid = posed(start + 2.6f)
            assertEquals("faces you at $start s", 1f, mid.face, 1e-6f)
            assertEquals("arm up at $start s", 1f, mid.wave, 1e-6f)
            val roll = atan2(sin(mid.angle), cos(mid.angle))
            assertTrue("upright at $start s (roll $roll)", abs(roll - 0.10f) < 0.05f)
            assertTrue("hand above the shoulder at $start s", mid.joints[11] > 0.50f + 0.8f)

            // The hand really swings: its tip crosses from one side of the wrist to the other.
            val tips = (0 until 20).map { i ->
                val p = posed(start + 1.9f + i * 0.08f)
                p.joints[10] - p.joints[8]
            }
            assertTrue("swings at $start s", tips.max() - tips.min() > 0.08f)

            // Between waves: none of it.
            val between = posed(start + 15f)
            assertEquals(0f, between.face, 0f)
            assertEquals(0f, between.wave, 0f)
        }
    }

    @Test
    fun movesSmoothlyFrameToFrameIncludingTheHourlyWrap() {
        val step = 1f / 60f
        var prev = posed(3600f - 2f)
        var t = 3600f - 2f + step
        var frames = 0
        // Through the last two seconds of the hour, the wrap to zero, and on through a whole hour.
        while (frames < 60 * 3600) {
            val clock = if (t >= 3600f) t - 3600f else t
            val cur = posed(clock)
            val tag = "at $clock s"
            // The roll may land on the other side of a whole turn, so compare the direction.
            assertTrue("roll $tag", hypot(cos(cur.angle) - cos(prev.angle), sin(cur.angle) - sin(prev.angle)) < 0.08f)
            assertTrue("x $tag", abs(cur.x - prev.x) < 3f)
            assertTrue("y $tag", abs(cur.y - prev.y) < 3f)
            assertTrue("scale $tag", abs(cur.scale - prev.scale) < 0.2f)
            assertTrue("head $tag", abs(cur.head - prev.head) < 0.03f)
            for (i in 0 until 24) assertTrue("joint $i $tag", abs(cur.joints[i] - prev.joints[i]) < 0.06f)
            assertTrue("puff $tag", cur.puff in 0f..1f)
            prev = cur
            t = if (t >= 3600f) clock + step else t + step
            frames++
        }
    }
}
