package com.zmusic.app.ui.components

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAU = 6.2831855f

// Every WAVE_PERIOD seconds, starting WAVE_AT seconds in, the astronaut rights himself with a puff
// from the jet pack, turns to face you and waves. Both divide an hour, so the clock's wrap is invisible.
internal const val WAVE_PERIOD = 30f
internal const val WAVE_AT = 6f

// A short double puff from the jet pack this often, between waves.
private const val PUFF_PERIOD = 15f
private const val PUFF_AT = 4f

/** How big he is on screen: pixels per model unit (his standing height), as a fraction of the screen width. */
internal const val ASTRONAUT_SIZE = 0.21f

/** The most [AstronautPose.scale] reaches, drifting toward you and leaning in to wave. */
internal const val ASTRONAUT_MAX_SCALE = 1.1f

/** How far behind him the disc's light comes from, against its direction across the screen. */
private const val LIGHT_BEHIND = 0.30f

/** The camera's distance, in frame radii. Far enough for only a touch of perspective. */
private const val CAMERA = 6f

/**
 * Where the astronaut is and how he holds himself, this frame. A pure function of the tracker's
 * clock, so there is no state to save, nothing allocates, and it stops with the tracker when
 * motion is turned off. Every period divides an hour, so the clock's hourly wrap is invisible.
 *
 * Most of the time he drifts and tumbles head over heels, turning slowly so you see his back and
 * his sides, settled in the loose crouch a body takes without gravity: arms up and forward, elbows
 * and knees bent, each limb swaying on its own slow beat, with now and then a puff from the jet
 * pack. Every [WAVE_PERIOD] seconds he puffs himself upright, turns to face you, leans in, tips his
 * helmet and waves with his right hand, then lets the tumble take him again.
 *
 * Angles are radians. Limb angles are measured from the model's standing A-pose (see [AstronautRig]).
 */
internal class AstronautPose {
    /** The figure's centre on screen, px. */
    var x = 0f
    var y = 0f

    /** Turn in the plane of the screen, clockwise. */
    var roll = 0f

    /** Turn about his own head-to-toe axis; 0 faces you, positive turns his front to the right. */
    var yaw = 0f

    /** Tip toward you (top of the helmet coming forward). */
    var pitch = 0f

    /** Size against [ASTRONAUT_SIZE]: drifting toward you and back, and leaning in to wave. */
    var scale = 1f

    /** Unit vector from him toward the hole, in screen space (y down). The disc is his key light. */
    var lightX = 0f
    var lightY = -1f

    /** 0..1: how far he has turned upright to face you for a wave. */
    var face = 0f
        private set

    /** 0..1: how far the right arm is up to wave. */
    var wave = 0f
        private set

    /** -1..1: the waving hand's side-to-side swing. */
    var swing = 0f
        private set

    /** 0..1: a puff of gas from the jet pack. */
    var puff = 0f
        private set

    /** Helmet on the neck: nod (chin down is negative), turn, tilt. */
    val head = FloatArray(3)

    /** Each arm: out to the side, forward, twist about the upper arm, elbow bend. */
    val armL = FloatArray(4)
    val armR = FloatArray(4)

    /** Each leg: forward, out to the side, knee bend. */
    val legL = FloatArray(3)
    val legR = FloatArray(3)

    fun update(seconds: Float, w: Float, h: Float) {
        val t = seconds
        // Where this frame is in the wave: he turns upright, the arm comes up, the hand swings
        // about once a second, then it all lets go in the same order.
        val ph = cycle(t - WAVE_AT, WAVE_PERIOD)
        face = ramp(ph, 0f, 1.4f) * (1f - ramp(ph, 4.6f, 6.0f))
        wave = ramp(ph, 0.9f, 1.8f) * (1f - ramp(ph, 4.0f, 4.9f))
        swing = sin(TAU * (ph - 1.6f)) * ramp(ph, 1.3f, 1.9f) * (1f - ramp(ph, 3.6f, 4.2f))

        // A slow drift through the lower half of the screen.
        x = w * (0.5f + 0.30f * sin(TAU * t / 144f))
        y = h * (0.70f + 0.06f * sin(TAU * t / 100f + 1f))

        // To wave he rolls and turns to the upright nearest where the wave began. That target is
        // held for the whole wave, so he eases there and back without a jump; whole turns are free.
        val r = roll(t)
        val rs = roll(t - ph)
        roll = r + (rs - wrap(rs) + 0.10f + 0.04f * sin(TAU * t / 6f) - r) * face
        val yw = yaw(t)
        val ys = yaw(t - ph)
        yaw = yw + (ys - wrap(ys) + 0.35f - yw) * face
        pitch = (0.30f * sin(TAU * t / 45f) + 0.10f * sin(TAU * t / 18f + 1f)) * (1f - face) + 0.06f * face
        scale = 1f + 0.035f * sin(TAU * t / 20f) + 0.06f * face

        // Looking about, or tipped toward the waving hand with a small nod.
        val look = 0.10f * sin(TAU * t / 15f + 0.7f) + 0.04f * sin(TAU * t / 6f)
        head[0] = mix(-0.10f + 0.05f * sin(TAU * t / 12f + 0.4f), -0.05f + 0.04f * sin(TAU * t / 2f), face)
        head[1] = mix(look, -0.15f, face)
        head[2] = mix(0.06f * sin(TAU * t / 10f + 1.2f), 0.15f, face)

        // Arms float up and forward, elbows bent, each on two slow beats of its own. To wave, the
        // right upper arm comes out level and turns so the forearm stands up, and the forearm
        // swings from the elbow.
        armL[0] = 0.32f + 0.10f * sin(TAU * t / 9f) + 0.04f * sin(TAU * t / 5f + 0.8f)
        armL[1] = 0.60f + 0.12f * sin(TAU * t / 12f + 2.0f) - 0.10f * face
        armL[2] = 0f
        armL[3] = 1.10f + 0.20f * sin(TAU * t / 10f + 0.5f) - 0.20f * face
        armR[0] = mix(0.38f + 0.10f * sin(TAU * t / 12f + 1.3f) + 0.04f * sin(TAU * t / 4f + 2.2f), 1.22f + 0.06f * swing, wave)
        armR[1] = mix(0.50f + 0.12f * sin(TAU * t / 9f + 0.6f), 0.17f, wave)
        armR[2] = mix(0f, -1.57f, wave)
        armR[3] = mix(1.25f + 0.20f * sin(TAU * t / 8f + 1.1f), 1.75f + 0.35f * swing, wave)

        // Knees up a little, bent, drifting; they straighten slightly as he rights himself.
        val settle = 1f - 0.2f * face
        legL[0] = (0.56f + 0.10f * sin(TAU * t / 10f + 2.1f) + 0.04f * sin(TAU * t / 6f)) * settle
        legL[1] = 0.07f
        legL[2] = 0.87f + 0.14f * sin(TAU * t / 15f + 0.4f)
        legR[0] = (0.38f + 0.10f * sin(TAU * t / 15f + 0.4f) + 0.03f * sin(TAU * t / 8f + 1.1f)) * settle
        legR[1] = 0.04f
        legR[2] = 0.70f + 0.12f * sin(TAU * t / 10f + 2.6f)

        // A short double puff now and then, and one as he rights himself to wave.
        puff = max(burst(cycle(t - PUFF_AT, PUFF_PERIOD)), burst(ph))

        val dx = w * HOLE_X - x
        val dy = h * HOLE_Y - y
        val len = hypot(dx, dy).coerceAtLeast(1f)
        lightX = dx / len
        lightY = dy / len
    }

    internal companion object {
        /** The tumble on its own: a full turn every five minutes, rocking as it goes. */
        fun roll(t: Float) = TAU * t / 300f + 0.30f * sin(TAU * t / 36f)

        /** Turning about his own axis: once every four minutes, swaying as he goes. */
        fun yaw(t: Float) = TAU * t / 240f + 0.6f * sin(TAU * t / 50f + 0.3f)

        /** [a] wrapped into -pi..pi. */
        fun wrap(a: Float) = atan2(sin(a), cos(a))

        /** [v] wrapped into 0 until [period]. */
        fun cycle(v: Float, period: Float): Float {
            val r = v % period
            return if (r < 0f) r + period else r
        }

        /** Smoothstep of [v] from [a] to [b]. */
        fun ramp(v: Float, a: Float, b: Float): Float {
            val k = ((v - a) / (b - a)).coerceIn(0f, 1f)
            return k * k * (3f - 2f * k)
        }

        fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k

        /** Two quick bursts of gas, [age] seconds after the first, each dying away in a fraction of a second. */
        fun burst(age: Float): Float {
            if (age >= 2f) return 0f
            val first = ramp(age, 0f, 0.05f) * exp(-age / 0.3f)
            val second = if (age < 0.4f) 0f else 0.6f * ramp(age, 0.4f, 0.45f) * exp(-(age - 0.4f) / 0.3f)
            return (first + second).coerceAtMost(1f)
        }
    }
}

/**
 * Bends the model's standing A-pose into [AstronautPose]'s: one rigid transform per bone, in the
 * model's own units, for the vertex shader to blend between (each vertex has two bones and a
 * weight; see scripts/astronaut_mesh.py). The root (torso, pack) never moves here: turning the
 * whole figure is the view's job.
 */
internal object AstronautRig {
    const val B_ROOT = 0
    const val B_HEAD = 1
    const val B_UPPER_ARM_L = 2
    const val B_FOREARM_L = 3
    const val B_UPPER_ARM_R = 4
    const val B_FOREARM_R = 5
    const val B_THIGH_L = 6
    const val B_SHIN_L = 7
    const val B_THIGH_R = 8
    const val B_SHIN_R = 9
    const val BONES = 10

    /**
     * Writes the bones' matrices (column-major 4x4, [BONES] of them) into [out]. [scratch] is 48
     * floats of working space, so nothing allocates.
     */
    fun pose(pose: AstronautPose, mesh: AstronautMesh, out: FloatArray, scratch: FloatArray) {
        Mat4.identity(out, B_ROOT * 16)

        // The helmet turns, nods and tilts on the neck.
        val h = pose.head
        Mat4.rotation(scratch, 0, 0f, 1f, 0f, h[1])
        Mat4.rotation(scratch, 16, 1f, 0f, 0f, h[0])
        Mat4.multiply(scratch, 32, scratch, 0, scratch, 16)
        Mat4.rotation(scratch, 0, 0f, 0f, 1f, h[2])
        Mat4.multiply(out, B_HEAD * 16, scratch, 32, scratch, 0)
        pivot(out, B_HEAD, mesh, AstronautMesh.J_NECK)

        arm(pose.armL, 1f, AstronautMesh.J_SHOULDER_L, AstronautMesh.J_ELBOW_L, B_UPPER_ARM_L, B_FOREARM_L, mesh, out, scratch)
        arm(pose.armR, -1f, AstronautMesh.J_SHOULDER_R, AstronautMesh.J_ELBOW_R, B_UPPER_ARM_R, B_FOREARM_R, mesh, out, scratch)
        leg(pose.legL, 1f, AstronautMesh.J_HIP_L, AstronautMesh.J_KNEE_L, B_THIGH_L, B_SHIN_L, mesh, out, scratch)
        leg(pose.legR, -1f, AstronautMesh.J_HIP_R, AstronautMesh.J_KNEE_R, B_THIGH_R, B_SHIN_R, mesh, out, scratch)
    }

    /**
     * Upper arm: out to the side about the depth axis ([side] is +1 for his left, -1 for his right,
     * so both go outward), forward about the left-right axis, twisted about its own length. The
     * forearm bends at the elbow about the axis square to the upper arm and to his front.
     */
    private fun arm(a: FloatArray, side: Float, shoulder: Int, elbow: Int, upper: Int, fore: Int, mesh: AstronautMesh, out: FloatArray, scratch: FloatArray) {
        val ux = mesh.jointX(elbow) - mesh.jointX(shoulder)
        val uy = mesh.jointY(elbow) - mesh.jointY(shoulder)
        val uz = mesh.jointZ(elbow) - mesh.jointZ(shoulder)
        Mat4.rotation(scratch, 0, 0f, 0f, 1f, side * a[0])
        Mat4.rotation(scratch, 16, -1f, 0f, 0f, a[1])
        Mat4.multiply(scratch, 32, scratch, 0, scratch, 16)
        Mat4.rotation(scratch, 0, ux, uy, uz, side * a[2])
        Mat4.multiply(out, upper * 16, scratch, 32, scratch, 0)
        pivot(out, upper, mesh, shoulder)

        Mat4.rotation(scratch, 0, uy, -ux, 0f, a[3])
        pivot(scratch, 0, mesh, elbow)
        Mat4.multiply(out, fore * 16, out, upper * 16, scratch, 0)
    }

    /** Thigh: out to the side, then forward, at the hip. Shin: bends back at the knee. */
    private fun leg(l: FloatArray, side: Float, hip: Int, knee: Int, thigh: Int, shin: Int, mesh: AstronautMesh, out: FloatArray, scratch: FloatArray) {
        Mat4.rotation(scratch, 0, 0f, 0f, 1f, side * l[1])
        Mat4.rotation(scratch, 16, -1f, 0f, 0f, l[0])
        Mat4.multiply(out, thigh * 16, scratch, 0, scratch, 16)
        pivot(out, thigh, mesh, hip)

        Mat4.rotation(scratch, 0, 1f, 0f, 0f, l[2])
        pivot(scratch, 0, mesh, knee)
        Mat4.multiply(out, shin * 16, out, thigh * 16, scratch, 0)
    }

    private fun pivot(m: FloatArray, bone: Int, mesh: AstronautMesh, joint: Int) =
        Mat4.pivot(m, bone * 16, mesh.jointX(joint), mesh.jointY(joint), mesh.jointZ(joint))
}

/**
 * Everything the renderer needs to draw one frame, worked out on the main thread from the pose so
 * the GL thread only uploads it: the bitmap's size and where it goes on screen, the bones, the
 * camera, the light, and the jet puff.
 *
 * The figure is drawn into a square bitmap that the main thread puts at ([left], [top]) on screen
 * without scaling. The square is just big enough for the figure at its largest, and the fraction of
 * a pixel that rounding [left] and [top] leaves is put back by the projection, so he drifts
 * smoothly instead of a pixel at a time.
 */
internal class AstronautFrame {
    var side = 0
    var left = 0
    var top = 0

    /** Column-major 4x4 per bone, [AstronautRig.BONES] of them. */
    val bones = FloatArray(AstronautRig.BONES * 16)

    /** Model units to view space: y up, z toward you, the camera at the origin. */
    val view = FloatArray(16)

    /** View space to clip space, flipped top to bottom so the pixels read back the right way up. */
    val proj = FloatArray(16)

    /** Unit vector toward the disc, in view space. */
    val light = FloatArray(3)

    /** Linear light colours: the disc, the stars' fill, ambient. */
    val key = FloatArray(3)
    val fill = FloatArray(3)
    val ambient = FloatArray(3)

    /** The jet puff: [PUFF_BLOBS] blobs, each view-space x, y, z and radius, then its colour (premultiplied, with alpha). */
    val puffBlobs = FloatArray(PUFF_BLOBS * 4)
    val puffColors = FloatArray(PUFF_BLOBS * 4)
    var puff = 0f

    private val scratch = FloatArray(48)

    /**
     * Works the frame out from [pose] (already updated). [screenWidth] sizes the figure, [gain] is
     * the bass, which brightens the disc's light, and [palette] is the theme's amber, indigo and
     * slate as linear rgb.
     */
    fun set(pose: AstronautPose, mesh: AstronautMesh, screenWidth: Float, gain: Float, palette: FloatArray) {
        AstronautRig.pose(pose, mesh, bones, scratch)

        // The bitmap, and where its centre lands on it after rounding to whole pixels.
        val ppu = screenWidth * ASTRONAUT_SIZE
        val reach = mesh.radius * ASTRONAUT_MAX_SCALE
        var s = ceil(2f * reach * ppu).toInt() + 2
        if (s and 1 == 1) s++
        side = s
        left = floor(pose.x - s / 2f).toInt()
        top = floor(pose.y - s / 2f).toInt()
        val cx = 2f * (pose.x - left) / s - 1f
        val cy = 2f * (pose.y - top) / s - 1f

        // A camera far enough back for only a touch of perspective, scaled so a model unit at the
        // figure's centre is exactly ppu pixels. Rows come back from glReadPixels bottom first and
        // fill the bitmap from the top, so the projection turns y over to make them land upright.
        val d = CAMERA * reach
        val f = d * ppu / (s / 2f)
        val near = d - 1.5f * reach
        val far = d + 1.5f * reach
        proj.fill(0f)
        proj[0] = f
        proj[5] = -f
        proj[8] = -cx
        proj[9] = -cy
        proj[10] = -(far + near) / (far - near)
        proj[11] = -1f
        proj[14] = -2f * far * near / (far - near)

        // view = translate(0, 0, -d) * scale * roll * pitch * yaw * translate(-centre). The roll is
        // clockwise on screen, which is negative about view z (y up).
        Mat4.rotation(scratch, 0, 0f, 0f, 1f, -pose.roll)
        Mat4.rotation(scratch, 16, 1f, 0f, 0f, pose.pitch)
        Mat4.multiply(scratch, 32, scratch, 0, scratch, 16)
        Mat4.rotation(scratch, 0, 0f, 1f, 0f, pose.yaw)
        Mat4.multiply(view, 0, scratch, 32, scratch, 0)
        for (i in 0 until 12) view[i] *= pose.scale
        val c = mesh.centre
        for (row in 0 until 3) {
            view[12 + row] = -(view[row] * c[0] + view[4 + row] * c[1] + view[8 + row] * c[2])
        }
        view[14] -= d

        // Toward the hole across the screen (y down there, up here), and tipped behind him.
        val ll = sqrt(pose.lightX * pose.lightX + pose.lightY * pose.lightY + LIGHT_BEHIND * LIGHT_BEHIND)
        light[0] = pose.lightX / ll
        light[1] = -pose.lightY / ll
        light[2] = -LIGHT_BEHIND / ll

        // The disc's warm light, brighter on the bass; a cool, mostly grey fill; a little slate.
        val k = 1.1f + 0.9f * gain
        for (i in 0 until 3) {
            val amber = palette[i]
            val indigo = palette[3 + i]
            val slate = palette[6 + i]
            key[i] = AstronautPose.mix(amber, WARM_WHITE[i], 0.25f) * k
            fill[i] = AstronautPose.mix(slate, indigo, 0.3f)
            ambient[i] = slate * 0.05f
        }
        val grey = (fill[0] + fill[1] + fill[2]) / 3f
        for (i in 0 until 3) fill[i] = AstronautPose.mix(fill[i], grey, 0.55f) * 0.32f

        puff(pose, mesh)
    }

    /**
     * Blobs of gas from under the pack, growing and fading as they go. They are on the root bone,
     * so they are just the view of two fixed points.
     */
    private fun puff(pose: AstronautPose, mesh: AstronautMesh) {
        puff = pose.puff
        if (puff <= 0f) return
        val j = AstronautMesh.J_PUFF
        val a = AstronautMesh.J_PUFF_AIM
        // The gas, lit by the fill and a little of the disc, tone mapped as the figure is.
        val r = toneMap(fill[0] * 6f + key[0] * 0.35f + 0.02f)
        val g = toneMap(fill[1] * 6f + key[1] * 0.35f + 0.02f)
        val b = toneMap(fill[2] * 6f + key[2] * 0.35f + 0.02f)
        for (i in 0 until PUFF_BLOBS) {
            val along = 0.12f + 0.26f * i
            val mx = mesh.jointX(j) + (mesh.jointX(a) - mesh.jointX(j)) * along
            val my = mesh.jointY(j) + (mesh.jointY(a) - mesh.jointY(j)) * along
            val mz = mesh.jointZ(j) + (mesh.jointZ(a) - mesh.jointZ(j)) * along
            for (row in 0 until 3) {
                puffBlobs[i * 4 + row] = view[row] * mx + view[4 + row] * my + view[8 + row] * mz + view[12 + row]
            }
            puffBlobs[i * 4 + 3] = (0.035f + 0.035f * i) * pose.scale
            val alpha = puff * 0.42f / (1f + i)
            puffColors[i * 4] = r * alpha
            puffColors[i * 4 + 1] = g * alpha
            puffColors[i * 4 + 2] = b * alpha
            puffColors[i * 4 + 3] = alpha
        }
    }

    fun copyFrom(o: AstronautFrame) {
        side = o.side
        left = o.left
        top = o.top
        o.bones.copyInto(bones)
        o.view.copyInto(view)
        o.proj.copyInto(proj)
        o.light.copyInto(light)
        o.key.copyInto(key)
        o.fill.copyInto(fill)
        o.ambient.copyInto(ambient)
        o.puffBlobs.copyInto(puffBlobs)
        o.puffColors.copyInto(puffColors)
        puff = o.puff
    }

    companion object {
        const val PUFF_BLOBS = 4
        private val WARM_WHITE = floatArrayOf(1f, 0.93f, 0.82f)

        /** The figure shader's tone curve: a soft shoulder, then back to display gamma. */
        fun toneMap(c: Float) = sqrt(1f - exp(-c * 1.2f))
    }
}

/** Column-major 4x4 matrices in float arrays, at an offset, without allocating. */
internal object Mat4 {
    fun identity(m: FloatArray, o: Int) {
        for (i in 0 until 16) m[o + i] = if (i % 5 == 0) 1f else 0f
    }

    /** A rotation by [angle] about the axis ([ax], [ay], [az]), which need not be unit length. */
    fun rotation(m: FloatArray, o: Int, ax: Float, ay: Float, az: Float, angle: Float) {
        val len = sqrt(ax * ax + ay * ay + az * az)
        val x = ax / len
        val y = ay / len
        val z = az / len
        val c = cos(angle)
        val s = sin(angle)
        val t = 1f - c
        m[o] = t * x * x + c
        m[o + 1] = t * x * y + s * z
        m[o + 2] = t * x * z - s * y
        m[o + 3] = 0f
        m[o + 4] = t * x * y - s * z
        m[o + 5] = t * y * y + c
        m[o + 6] = t * y * z + s * x
        m[o + 7] = 0f
        m[o + 8] = t * x * z + s * y
        m[o + 9] = t * y * z - s * x
        m[o + 10] = t * z * z + c
        m[o + 11] = 0f
        m[o + 12] = 0f
        m[o + 13] = 0f
        m[o + 14] = 0f
        m[o + 15] = 1f
    }

    /** [out] = [a] * [b]. [out] must not overlap either. */
    fun multiply(out: FloatArray, oo: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int) {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[ao + k * 4 + row] * b[bo + col * 4 + k]
                out[oo + col * 4 + row] = sum
            }
        }
    }

    /** Makes the rotation in [m] turn about the point ([px], [py], [pz]) instead of the origin. */
    fun pivot(m: FloatArray, o: Int, px: Float, py: Float, pz: Float) {
        for (row in 0 until 3) {
            m[o + 12 + row] += (if (row == 0) px else if (row == 1) py else pz) -
                (m[o + row] * px + m[o + 4 + row] * py + m[o + 8 + row] * pz)
        }
    }
}
