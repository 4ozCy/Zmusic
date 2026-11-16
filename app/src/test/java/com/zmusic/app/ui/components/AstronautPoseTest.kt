package com.zmusic.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [AstronautPose.rig] does once a frame what the astronaut shader used to do for every pixel.
 * These checks hold it to that shader code, ported here line for line.
 */
class AstronautPoseTest {

    private class V(val x: Float, val y: Float) {
        operator fun plus(o: V) = V(x + o.x, y + o.y)
    }

    private fun rot(p: V, a: Float): V {
        val c = cos(a)
        val s = sin(a)
        return V(c * p.x - s * p.y, s * p.x + c * p.y)
    }

    /** The shader's old joint maths, unchanged, in the order [AstronautPose.joints] keeps them. */
    private fun shaderJoints(t: Float): List<V> {
        val a1 = 0.10f * sin(t * 0.6981317f)
        val a2 = 0.12f * sin(t * 0.5235988f + 1.3f)
        val l1 = 0.06f * sin(t * 0.6283185f + 2.1f)
        val l2 = 0.08f * sin(t * 0.4188790f + 0.4f)
        val s1 = V(-0.52f, 0.50f)
        val e1 = s1 + rot(V(-0.53f, -0.40f), a1)
        val w1 = e1 + rot(V(-0.10f, 0.50f), a1 * 1.6f)
        val g1 = w1 + rot(V(-0.02f, 0.14f), a1 * 1.6f)
        val s2 = V(0.52f, 0.50f)
        val e2 = s2 + rot(V(0.54f, -0.32f), a2)
        val w2 = e2 + rot(V(0.16f, 0.50f), a2 * 1.5f)
        val g2 = w2 + rot(V(0.03f, 0.14f), a2 * 1.5f)
        val h1 = V(-0.24f, -0.74f)
        val k1 = h1 + rot(V(-0.18f, -0.71f), l1)
        val f1 = k1 + rot(V(0.12f, -0.67f), l1 * 1.4f)
        val t1 = f1 + rot(V(-0.20f, -0.10f), l1 * 1.4f)
        val h2 = V(0.24f, -0.74f)
        val k2 = h2 + rot(V(0.31f, -0.66f), l2)
        val f2 = k2 + rot(V(-0.13f, -0.68f), l2 * 1.4f)
        val t2 = f2 + rot(V(0.22f, -0.08f), l2 * 1.4f)
        return listOf(e1, w1, g1, e2, w2, g2, k1, f1, t1, k2, f2, t2)
    }

    private fun normalize(x: Float, y: Float, z: Float): FloatArray {
        val len = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / len, y / len, z / len)
    }

    @Test
    fun jointsMatchTheShader() {
        val pose = AstronautPose()
        for (step in 0 until 400) {
            val t = step * 9.1f
            pose.update(t, 1080f, 2340f)
            pose.rig(t)
            shaderJoints(t).forEachIndexed { k, v ->
                assertEquals("joint $k x at $t s", v.x, pose.joints[k * 2], 1e-5f)
                assertEquals("joint $k y at $t s", v.y, pose.joints[k * 2 + 1], 1e-5f)
            }
        }
    }

    @Test
    fun lightAndFrameMatchTheShader() {
        val pose = AstronautPose()
        for (step in 0 until 400) {
            val t = step * 7.3f
            pose.update(t, 1080f, 2340f)
            pose.rig(t)

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
        }
    }
}
