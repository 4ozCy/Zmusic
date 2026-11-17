package com.zmusic.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * [AstronautPose], [AstronautRig] and [AstronautFrame] work out on the CPU everything the
 * astronaut's shaders draw. These checks run them against the real model: the bones stay rigid and
 * joined, every vertex of the figure stays inside the picture it is drawn into in every pose, the
 * wave happens and reads as a wave, and nothing ever jumps, the hourly wrap included.
 */
class AstronautPoseTest {

    private val mesh = AstronautMesh.parse(File("src/main/assets/${AstronautMesh.ASSET}").readBytes())
    private val w = 1080f
    private val h = 2340f
    private val palette = floatArrayOf(0.78f, 0.44f, 0.18f, 0.18f, 0.19f, 0.72f, 0.03f, 0.05f, 0.16f)

    private class Posed(val pose: AstronautPose, val frame: AstronautFrame)

    private fun posed(t: Float) = Posed(AstronautPose(), AstronautFrame()).apply {
        pose.update(t, w, h)
        frame.set(pose, mesh, w, 0.5f, palette)
    }

    /** [m] (column-major, at bone [b]) applied to a point. */
    private fun apply(m: FloatArray, b: Int, x: Float, y: Float, z: Float) = FloatArray(3) { r ->
        m[b * 16 + r] * x + m[b * 16 + 4 + r] * y + m[b * 16 + 8 + r] * z + m[b * 16 + 12 + r]
    }

    private fun joint(j: Int) = floatArrayOf(mesh.jointX(j), mesh.jointY(j), mesh.jointZ(j))

    /** Where a model-space point under bone [b] lands on screen, px. */
    private fun screen(p: Posed, b: Int, point: FloatArray): FloatArray {
        val s = apply(p.frame.bones, b, point[0], point[1], point[2])
        val v = apply(p.frame.view, 0, s[0], s[1], s[2])
        val pr = p.frame.proj
        val cw = pr[11] * v[2]
        val nx = (pr[0] * v[0] + pr[8] * v[2]) / cw
        val ny = (pr[5] * v[1] + pr[9] * v[2]) / cw
        val side = p.frame.side
        return floatArrayOf(p.frame.left + (nx + 1f) / 2f * side, p.frame.top + (ny + 1f) / 2f * side)
    }

    @Test
    fun theModelIsWellFormed() {
        assertTrue(mesh.vertexCount in 1000 until 65536)
        assertEquals(0, mesh.indexCount % 3)
        val idx = mesh.indices.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until mesh.indexCount) assertTrue((idx.get(i).toInt() and 0xFFFF) < mesh.vertexCount)
        val v = mesh.vertices.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until mesh.vertexCount) {
            val o = i * AstronautMesh.VERTEX_BYTES
            assertTrue(v.get(o + 6).toInt() and 0xFF < AstronautRig.BONES)
            assertTrue(v.get(o + 7).toInt() and 0xFF < AstronautRig.BONES)
            assertTrue(v.get(o + 12).toInt() and 0xFF < mesh.materialCount)
            val nx = v.get(o + 8) / 127f
            val ny = v.get(o + 9) / 127f
            val nz = v.get(o + 10) / 127f
            assertEquals("normal of vertex $i", 1f, sqrt(nx * nx + ny * ny + nz * nz), 0.03f)
        }
        // One visor and some lamps, for the shader's special cases.
        val kinds = (0 until mesh.materialCount).map { mesh.materials[it * 8 + 5].toInt() }
        assertEquals(1, kinds.count { it == 1 })
        assertTrue(kinds.count { it == 2 } >= 1)
    }

    @Test
    fun bonesStayRigidAndJoined() {
        val tol = 2e-4f
        var t = 0f
        while (t < 3600f) {
            val b = posed(t).frame.bones
            for (bone in 0 until AstronautRig.BONES) {
                // Columns of the rotation are unit length and square to each other.
                for (i in 0 until 3) for (j in 0 until 3) {
                    var dot = 0f
                    for (k in 0 until 3) dot += b[bone * 16 + i * 4 + k] * b[bone * 16 + j * 4 + k]
                    assertEquals("bone $bone at $t s", if (i == j) 1f else 0f, dot, tol)
                }
            }
            fun same(tag: String, a: FloatArray, c: FloatArray) {
                for (i in 0 until 3) assertEquals("$tag at $t s", a[i], c[i], tol)
            }
            fun pivot(joint: Int, parent: Int, child: Int) {
                val p = joint(joint)
                same("joint $joint", apply(b, parent, p[0], p[1], p[2]), apply(b, child, p[0], p[1], p[2]))
            }
            pivot(AstronautMesh.J_NECK, AstronautRig.B_ROOT, AstronautRig.B_HEAD)
            pivot(AstronautMesh.J_SHOULDER_L, AstronautRig.B_ROOT, AstronautRig.B_UPPER_ARM_L)
            pivot(AstronautMesh.J_ELBOW_L, AstronautRig.B_UPPER_ARM_L, AstronautRig.B_FOREARM_L)
            pivot(AstronautMesh.J_SHOULDER_R, AstronautRig.B_ROOT, AstronautRig.B_UPPER_ARM_R)
            pivot(AstronautMesh.J_ELBOW_R, AstronautRig.B_UPPER_ARM_R, AstronautRig.B_FOREARM_R)
            pivot(AstronautMesh.J_HIP_L, AstronautRig.B_ROOT, AstronautRig.B_THIGH_L)
            pivot(AstronautMesh.J_KNEE_L, AstronautRig.B_THIGH_L, AstronautRig.B_SHIN_L)
            pivot(AstronautMesh.J_HIP_R, AstronautRig.B_ROOT, AstronautRig.B_THIGH_R)
            pivot(AstronautMesh.J_KNEE_R, AstronautRig.B_THIGH_R, AstronautRig.B_SHIN_R)
            t += 0.37f
        }
    }

    @Test
    fun everyVertexStaysInsideThePicture() {
        // Decode the model once: positions, two bones and a weight per vertex.
        val n = mesh.vertexCount
        val pos = FloatArray(n * 3)
        val b0 = IntArray(n)
        val b1 = IntArray(n)
        val wt = FloatArray(n)
        val v = mesh.vertices.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val o = i * AstronautMesh.VERTEX_BYTES
            for (k in 0 until 3) pos[i * 3 + k] = mesh.posOffset[k] + mesh.posScale[k] * (v.getShort(o + k * 2) / 32767f)
            b0[i] = v.get(o + 6).toInt() and 0xFF
            b1[i] = v.get(o + 7).toInt() and 0xFF
            wt[i] = (v.get(o + 11).toInt() and 0xFF) / 255f
        }
        // Every 3.1 s over an hour, the middle of every wave, when the hand is highest, and the
        // peak of every jet puff (every 15 s from 4 s in, and as each wave starts).
        val times = (0 until 1161).map { it * 3.1f } +
            (0 until 120).map { WAVE_AT + it * WAVE_PERIOD + 2.6f } +
            (0 until 240).map { 4.05f + it * 15f } +
            (0 until 120).map { WAVE_AT + it * WAVE_PERIOD + 0.05f }
        var worst = 0f
        var worstAt = ""
        for (t in times) {
            val p = posed(t)
            val f = p.frame
            val m = FloatArray(16)
            for (i in 0 until n) {
                for (k in 0 until 16) m[k] = f.bones[b0[i] * 16 + k] * wt[i] + f.bones[b1[i] * 16 + k] * (1f - wt[i])
                val s = apply(m, 0, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
                val e = apply(f.view, 0, s[0], s[1], s[2])
                val cw = -e[2]
                val nx = (f.proj[0] * e[0] + f.proj[8] * e[2]) / cw
                val ny = (f.proj[5] * e[1] + f.proj[9] * e[2]) / cw
                val here = max(abs(nx), abs(ny))
                if (here > worst) { worst = here; worstAt = "vertex $i at $t s" }
            }
            // The jet puff too, when there is one.
            if (f.puff > 0f) for (i in 0 until AstronautFrame.PUFF_BLOBS) {
                val x = f.puffBlobs[i * 4]
                val y = f.puffBlobs[i * 4 + 1]
                val z = f.puffBlobs[i * 4 + 2]
                val r = f.puffBlobs[i * 4 + 3]
                val cw = -z
                val px = abs(f.proj[0] * x + f.proj[8] * z) / cw + abs(f.proj[0] * r) / cw
                val py = abs(f.proj[5] * y + f.proj[9] * z) / cw + abs(f.proj[5] * r) / cw
                if (max(px, py) > worst) { worst = max(px, py); worstAt = "puff $i at $t s" }
            }
        }
        // Inside, and not on the outermost pixel, where multisampling could clip an edge.
        assertTrue("$worstAt reaches $worst of the picture's half width", worst < 1f - 2f / posed(0f).frame.side)
    }

    @Test
    fun wavesEveryHalfMinuteFacingYou() {
        for (k in 0 until 120) {
            val start = WAVE_AT + k * WAVE_PERIOD
            if (start + 5f >= 3600f) break
            val mid = posed(start + 2.6f)
            assertEquals("faces you at $start s", 1f, mid.pose.face, 1e-6f)
            assertEquals("arm up at $start s", 1f, mid.pose.wave, 1e-6f)

            // Upright and turned toward you: his up is screen up, his front is toward the camera.
            val view = mid.frame.view
            val s = mid.pose.scale
            assertTrue("upright at $start s", view[5] / s > 0.9f)
            assertTrue("facing you at $start s", view[10] / s > 0.85f)

            // The waving hand is up above the shoulder, the forearm standing up from the elbow.
            val shoulder = screen(mid, AstronautRig.B_ROOT, joint(AstronautMesh.J_SHOULDER_R))
            val elbow = screen(mid, AstronautRig.B_UPPER_ARM_R, joint(AstronautMesh.J_ELBOW_R))
            val wrist = screen(mid, AstronautRig.B_FOREARM_R, joint(AstronautMesh.J_WRIST_R))
            assertTrue("hand above the shoulder at $start s", wrist[1] < shoulder[1] - 20f)
            assertTrue("forearm up at $start s", wrist[1] < elbow[1] - 15f)

            // And it swings from side to side.
            val xs = (0 until 20).map { i ->
                val p = posed(start + 1.9f + i * 0.08f)
                screen(p, AstronautRig.B_FOREARM_R, joint(AstronautMesh.J_WRIST_R))[0] -
                    screen(p, AstronautRig.B_UPPER_ARM_R, joint(AstronautMesh.J_ELBOW_R))[0]
            }
            assertTrue("swings at $start s", xs.max() - xs.min() > 12f)

            // Between waves: none of it.
            val between = posed(start + 15f)
            assertEquals(0f, between.pose.face, 0f)
            assertEquals(0f, between.pose.wave, 0f)
        }
    }

    @Test
    fun movesSmoothlyFrameToFrameIncludingTheHourlyWrap() {
        val points = listOf(
            AstronautRig.B_HEAD to AstronautMesh.J_NECK,
            AstronautRig.B_FOREARM_L to AstronautMesh.J_WRIST_L,
            AstronautRig.B_FOREARM_R to AstronautMesh.J_WRIST_R,
            AstronautRig.B_SHIN_L to AstronautMesh.J_KNEE_L,
            AstronautRig.B_ROOT to AstronautMesh.J_PUFF,
        )
        val step = 1f / 60f
        var prev = posed(3600f - 2f)
        var t = 3600f - 2f + step
        var frames = 0
        // Through the last two seconds of the hour, the wrap to zero, and on through a whole hour.
        while (frames < 60 * 3600) {
            val clock = if (t >= 3600f) t - 3600f else t
            val cur = posed(clock)
            for ((bone, j) in points) {
                val a = screen(prev, bone, joint(j))
                val c = screen(cur, bone, joint(j))
                // Turning to face you is the quickest move: a few pixels a frame at most.
                assertTrue("joint $j at $clock s moved ${a[0] - c[0]}, ${a[1] - c[1]}", abs(a[0] - c[0]) < 12f && abs(a[1] - c[1]) < 12f)
            }
            assertTrue("scale at $clock s", abs(cur.pose.scale - prev.pose.scale) < 0.01f)
            assertTrue("puff at $clock s", cur.pose.puff in 0f..1f)
            prev = cur
            t = if (t >= 3600f) clock + step else t + step
            frames++
        }
    }

    @Test
    fun litFromTheHole() {
        for (step in 0 until 400) {
            val t = step * 7.3f
            val p = posed(t)
            val dx = w * HOLE_X - p.pose.x
            val dy = h * HOLE_Y - p.pose.y
            assertTrue("light toward the hole at $t s", p.pose.lightX * dx + p.pose.lightY * dy > 0f)
            val l = p.frame.light
            assertEquals(1f, sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2]), 1e-5f)
            assertTrue("light from behind at $t s", l[2] < 0f)
            // Screen y is down, view y is up.
            assertTrue(p.pose.lightY * l[1] <= 0f)
        }
    }
}
