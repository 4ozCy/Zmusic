package com.zmusic.app.ui.components

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.zmusic.app.debug.BugLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The figure's shaders. GLSL ES 1.00, so they run on every device the app supports.
 *
 * The vertex shader blends each vertex's two bones (see AstronautRig) and looks up its material.
 * The fragment shader lights the suit the way the scene lights it: the disc is the key light and it
 * is behind and to one side, so the suit is mostly in shadow with a warm rim on every edge that
 * faces the hole, and a dim, cool fill from the stars keeps the shadowed side readable. The hard
 * parts shine a little and the metal fittings mirror the disc's glow. The gold visor is a mirror
 * for the disc, brightest where it curves away; the helmet lamps are on. It all ends in the same
 * tone curve as the rest of the scene.
 */
private const val FIGURE_VERTEX = """
uniform mat4 uBones[%BONES%];
uniform mat4 uView;
uniform mat4 uProj;
uniform vec3 uPosScale;
uniform vec3 uPosOffset;
uniform vec4 uMat[%MATERIALS%];
attribute vec3 aPos;
attribute vec2 aBones;
attribute vec3 aNormal;
attribute float aWeight;
attribute float aMaterial;
varying vec3 vNormal;
varying vec3 vEye;
varying vec4 vAlbedo;
varying vec2 vSurface;
void main() {
    mat4 skin = uBones[int(aBones.x + 0.5)] * aWeight + uBones[int(aBones.y + 0.5)] * (1.0 - aWeight);
    vec4 p = uView * (skin * vec4(uPosOffset + uPosScale * aPos, 1.0));
    vNormal = (uView * (skin * vec4(aNormal, 0.0))).xyz;
    vEye = -p.xyz;
    int m = int(aMaterial + 0.5) * 2;
    vAlbedo = uMat[m];
    vSurface = uMat[m + 1].xy;
    gl_Position = uProj * p;
}
"""

private const val FIGURE_FRAGMENT = """
precision mediump float;
uniform vec3 uLight;
uniform vec3 uKey;
uniform vec3 uFill;
uniform vec3 uAmbient;
varying vec3 vNormal;
varying vec3 vEye;
varying vec4 vAlbedo;
varying vec2 vSurface;
const vec3 FILL_DIR = vec3(-0.36, 0.56, 0.75);
void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(vEye);
    vec3 L = uLight;
    vec3 albedo = vAlbedo.rgb;
    float gloss = vAlbedo.a;
    float metal = vSurface.x;
    float kind = vSurface.y;
    float facing = clamp(dot(n, v), 0.0, 1.0);
    vec3 r = reflect(-v, n);
    vec2 lxy = L.xy / max(length(L.xy), 1e-4);
    vec3 c;
    if (kind > 1.5) {
        // A helmet lamp, switched on.
        c = vec3(1.6, 1.75, 1.9);
    } else if (kind > 0.5) {
        // The visor: gold-coated glass, a mirror for the disc's glow, darker face on.
        float fres = 0.08 + 0.92 * pow(1.0 - facing, 3.0);
        vec2 rxy = r.xy / max(length(r.xy), 1e-4);
        float toward = max(dot(rxy, lxy), 0.0);
        vec3 env = uAmbient * 0.6 + uKey * (pow(toward, 4.0) * (1.0 - r.z) * 1.2);
        env += uKey * (0.22 + 0.40 * smoothstep(-0.7, 0.8, dot(r.xy, lxy))) + uFill * 0.6;
        c = vec3(0.004, 0.005, 0.010) + env * vec3(1.0, 0.72, 0.34) * (0.35 + 0.65 * fres);
        c += uKey * pow(max(dot(r, L), 0.0), 40.0) * 1.5;
        c += (uFill * 2.0 + 0.03) * smoothstep(0.93, 0.99, dot(r, normalize(vec3(-0.40, 0.55, 0.73))));
    } else {
        float wrap = max(dot(n, L) + 0.2, 0.0) / 1.2;
        float diff = wrap * wrap;
        vec2 nxy = n.xy / max(length(n.xy), 1e-4);
        float rim = pow(1.0 - facing, 2.0) * smoothstep(0.05, 1.0, dot(nxy, lxy));
        float fill = max(dot(n, FILL_DIR), 0.0);
        float spec = pow(max(dot(n, normalize(L + v)), 0.0), gloss) * (gloss + 8.0) / 80.0;
        c = albedo * (uKey * diff + uFill * fill + uAmbient);
        c += uKey * (rim * 2.2 * (0.4 + 0.6 * albedo.g) + spec * 0.25);
        vec2 rr = r.xy / max(length(r.xy), 1e-4);
        vec3 env = uKey * 0.5 * smoothstep(-0.2, 1.0, dot(rr, lxy)) * (1.0 - r.z) + uFill * 1.5 * max(dot(r, FILL_DIR), 0.0);
        c += env * metal * mix(vec3(1.0), albedo * 3.0, 0.5);
    }
    c = sqrt(1.0 - exp(-c * 1.2));
    gl_FragColor = vec4(c, 1.0);
}
"""

/** A soft round blob of gas, facing the camera. */
private const val PUFF_VERTEX = """
uniform mat4 uProj;
uniform vec4 uBlob;
attribute vec2 aCorner;
varying vec2 vUv;
void main() {
    vUv = aCorner;
    gl_Position = uProj * vec4(uBlob.xyz + vec3(aCorner * uBlob.w, 0.0), 1.0);
}
"""

private const val PUFF_FRAGMENT = """
precision mediump float;
uniform vec4 uColor;
varying vec2 vUv;
void main() {
    float g = max(exp(-3.0 * dot(vUv, vUv)) - 0.0498, 0.0) / 0.9502;
    gl_FragColor = uColor * g;
}
"""

/**
 * Draws the astronaut in 3D on its own thread, with OpenGL ES 2 into an offscreen surface, and
 * hands each finished frame to the main thread as a bitmap. That keeps him an ordinary part of the
 * Compose backdrop: he sits between the hole and the glass, and the glass blurs him with
 * everything else, which a SurfaceView could not do.
 *
 * The main thread calls [submit] once a frame with what to draw, and [latest] to get the newest
 * finished picture. Bitmaps go round three slots, so the GL thread never writes one that is on
 * screen: one is shown, one is finished and waiting, one is being drawn. A frame that comes in
 * while one is drawing replaces the last request, so a slow device just draws fewer of them.
 *
 * If anything about GL fails, it says so once in the bug log and he is simply not drawn.
 */
internal class AstronautRenderer(private val assets: AssetManager) {

    /** One drawn frame: the bitmap and where it goes on screen. */
    class Picture(val bitmap: Bitmap) {
        val image: ImageBitmap = bitmap.asImageBitmap()
        var left = 0
        var top = 0
    }

    private val thread = HandlerThread("Astronaut", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    private val handler = Handler(thread.looper)

    /** The model, once the GL thread has loaded it. The main thread needs its joints to pose it. */
    @Volatile
    var mesh: AstronautMesh? = null
        private set

    @Volatile
    private var failed = false

    private val lock = Any()
    private val requested = AstronautFrame()
    private var posted = false
    private var ready: Picture? = null
    private var shown: Picture? = null
    private val free = ArrayList<Picture>(3)

    // GL thread only.
    private val drawing = AstronautFrame()
    private var gl: Gl? = null

    init {
        handler.post {
            try {
                mesh = AstronautMesh.load(assets)
            } catch (e: Exception) {
                fail("Could not load the astronaut model", e)
            }
        }
    }

    /** Main thread: draw this next. */
    fun submit(frame: AstronautFrame) {
        if (failed) return
        synchronized(lock) {
            requested.copyFrom(frame)
            if (posted) return
            posted = true
        }
        handler.post(draw)
    }

    /** Main thread: the newest finished picture, or null before the first. */
    fun latest(): Picture? = synchronized(lock) {
        val next = ready ?: return shown
        shown?.let { free.add(it) }
        shown = next
        ready = null
        next
    }

    /** Lets go of GL and stops the thread. */
    fun release() {
        failed = true
        handler.post {
            gl?.release()
            gl = null
            thread.quitSafely()
        }
    }

    private val draw = Runnable {
        synchronized(lock) {
            drawing.copyFrom(requested)
            posted = false
        }
        if (failed) return@Runnable
        val model = mesh ?: return@Runnable
        try {
            val g = gl ?: Gl(model).also { gl = it }
            val picture = synchronized(lock) { take(drawing.side) }
            g.draw(drawing, picture.bitmap)
            picture.left = drawing.left
            picture.top = drawing.top
            synchronized(lock) {
                ready?.let { free.add(it) }
                ready = picture
            }
        } catch (e: Exception) {
            gl?.release()
            gl = null
            fail("The astronaut could not be drawn with OpenGL, so he is hidden", e)
        }
    }

    /** A free slot of the right size, or a new one. At most three of a size are ever in use. */
    private fun take(side: Int): Picture {
        free.removeAll { it.bitmap.width != side }
        return free.removeLastOrNull() ?: Picture(Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888))
    }

    private fun fail(message: String, e: Exception) {
        if (!failed) BugLog.w("Backdrop", message, e)
        failed = true
    }

    /** The GL side: an ES 2 context on a pbuffer the size of the picture, the model in buffers, two programs. */
    private class Gl(private val mesh: AstronautMesh) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val config: EGLConfig
        private val context: EGLContext
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var side = 0
        private var pixels: ByteBuffer = ByteBuffer.allocateDirect(4)

        private val figure: Int
        private val puff: Int
        private val buffers = IntArray(3)

        private val uBones: Int
        private val uView: Int
        private val uProj: Int
        private val uLight: Int
        private val uKey: Int
        private val uFill: Int
        private val uAmbient: Int
        private val aPos: Int
        private val aBones: Int
        private val aNormal: Int
        private val aWeight: Int
        private val aMaterial: Int
        private val uPuffProj: Int
        private val uBlob: Int
        private val uColor: Int
        private val aCorner: Int

        init {
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed: ${egl()}" }
            // 4x multisampling smooths his outline. A device that offers it but cannot make a
            // surface with it, or does not offer it, gets the plain config instead.
            var chosen: Pair<EGLConfig, EGLContext>? = null
            for (samples in intArrayOf(4, 0)) {
                val c = choose(samples) ?: continue
                val ctx = EGL14.eglCreateContext(
                    display, c, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
                )
                if (ctx == EGL14.EGL_NO_CONTEXT) continue
                val probe = EGL14.eglCreatePbufferSurface(display, c, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
                if (probe != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, probe, probe, ctx)) {
                    surface = probe
                    side = 16
                    pixels = ByteBuffer.allocateDirect(16 * 16 * 4)
                    chosen = c to ctx
                    break
                }
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (probe != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, probe)
                EGL14.eglDestroyContext(display, ctx)
            }
            val (c, ctx) = chosen ?: error("No EGL config that can draw offscreen: ${egl()}")
            config = c
            context = ctx

            figure = program(
                FIGURE_VERTEX
                    .replace("%BONES%", AstronautRig.BONES.toString())
                    .replace("%MATERIALS%", (mesh.materialCount * 2).toString()),
                FIGURE_FRAGMENT,
            )
            uBones = GLES20.glGetUniformLocation(figure, "uBones")
            uView = GLES20.glGetUniformLocation(figure, "uView")
            uProj = GLES20.glGetUniformLocation(figure, "uProj")
            uLight = GLES20.glGetUniformLocation(figure, "uLight")
            uKey = GLES20.glGetUniformLocation(figure, "uKey")
            uFill = GLES20.glGetUniformLocation(figure, "uFill")
            uAmbient = GLES20.glGetUniformLocation(figure, "uAmbient")
            aPos = GLES20.glGetAttribLocation(figure, "aPos")
            aBones = GLES20.glGetAttribLocation(figure, "aBones")
            aNormal = GLES20.glGetAttribLocation(figure, "aNormal")
            aWeight = GLES20.glGetAttribLocation(figure, "aWeight")
            aMaterial = GLES20.glGetAttribLocation(figure, "aMaterial")
            GLES20.glUseProgram(figure)
            GLES20.glUniform3fv(GLES20.glGetUniformLocation(figure, "uPosScale"), 1, mesh.posScale, 0)
            GLES20.glUniform3fv(GLES20.glGetUniformLocation(figure, "uPosOffset"), 1, mesh.posOffset, 0)
            // Each material's 8 floats are the shader's two vec4s as they stand.
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(figure, "uMat"), mesh.materialCount * 2, mesh.materials, 0)

            puff = program(PUFF_VERTEX, PUFF_FRAGMENT)
            uPuffProj = GLES20.glGetUniformLocation(puff, "uProj")
            uBlob = GLES20.glGetUniformLocation(puff, "uBlob")
            uColor = GLES20.glGetUniformLocation(puff, "uColor")
            aCorner = GLES20.glGetAttribLocation(puff, "aCorner")

            GLES20.glGenBuffers(3, buffers, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.vertices.capacity(), mesh.vertices, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.capacity(), mesh.indices, GLES20.GL_STATIC_DRAW)
            val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val quad = ByteBuffer.allocateDirect(corners.size * 4).order(ByteOrder.nativeOrder())
            quad.asFloatBuffer().put(corners)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[2])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.capacity(), quad, GLES20.GL_STATIC_DRAW)
            checkGl("setting up")
        }

        fun draw(f: AstronautFrame, into: Bitmap) {
            if (f.side != side) resize(f.side)
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) error("eglMakeCurrent failed: ${egl()}")

            GLES20.glViewport(0, 0, side, side)
            // Depth writes back on before the clear: the last frame's puff turned them off, and
            // glClear leaves the depth buffer alone while they are.
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            GLES20.glUseProgram(figure)
            GLES20.glUniformMatrix4fv(uBones, AstronautRig.BONES, false, f.bones, 0)
            GLES20.glUniformMatrix4fv(uView, 1, false, f.view, 0)
            GLES20.glUniformMatrix4fv(uProj, 1, false, f.proj, 0)
            GLES20.glUniform3fv(uLight, 1, f.light, 0)
            GLES20.glUniform3fv(uKey, 1, f.key, 0)
            GLES20.glUniform3fv(uFill, 1, f.fill, 0)
            GLES20.glUniform3fv(uAmbient, 1, f.ambient, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            val stride = AstronautMesh.VERTEX_BYTES
            attribute(aPos, 3, GLES20.GL_SHORT, true, stride, 0)
            attribute(aBones, 2, GLES20.GL_UNSIGNED_BYTE, false, stride, 6)
            attribute(aNormal, 3, GLES20.GL_BYTE, true, stride, 8)
            attribute(aWeight, 1, GLES20.GL_UNSIGNED_BYTE, true, stride, 11)
            attribute(aMaterial, 1, GLES20.GL_UNSIGNED_BYTE, false, stride, 12)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
            for (a in intArrayOf(aPos, aBones, aNormal, aWeight, aMaterial)) GLES20.glDisableVertexAttribArray(a)

            if (f.puff > 0f) {
                // Behind him where he is in front of it, so the depth test stays on, but the gas
                // does not hide itself.
                GLES20.glUseProgram(puff)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDepthMask(false)
                GLES20.glUniformMatrix4fv(uPuffProj, 1, false, f.proj, 0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[2])
                attribute(aCorner, 2, GLES20.GL_FLOAT, false, 8, 0)
                for (i in 0 until AstronautFrame.PUFF_BLOBS) {
                    GLES20.glUniform4fv(uBlob, 1, f.puffBlobs, i * 4)
                    GLES20.glUniform4fv(uColor, 1, f.puffColors, i * 4)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
                GLES20.glDisableVertexAttribArray(aCorner)
            }

            // glReadPixels gives RGBA bytes, premultiplied since he is drawn over clear black,
            // which is what an ARGB_8888 bitmap holds.
            pixels.position(0)
            GLES20.glReadPixels(0, 0, side, side, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            checkGl("drawing")
            pixels.position(0)
            into.copyPixelsFromBuffer(pixels)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            // Not eglTerminate: the display is shared with everything else in the process.
        }

        private fun resize(size: Int) {
            val old = surface
            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, size, EGL14.EGL_HEIGHT, size, EGL14.EGL_NONE), 0,
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed: ${egl()}" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed: ${egl()}" }
            if (old != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, old)
            side = size
            pixels = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        }

        private fun choose(samples: Int): EGLConfig? {
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                EGL14.EGL_SAMPLES, samples,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            return configs[0]
        }

        private fun program(vertex: String, fragment: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "Astronaut program did not link: ${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }

        private fun shader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Astronaut shader did not compile: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }

        private fun attribute(location: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) {
            GLES20.glEnableVertexAttribArray(location)
            GLES20.glVertexAttribPointer(location, size, type, normalized, stride, offset)
        }

        private fun checkGl(doing: String) {
            val e = GLES20.glGetError()
            check(e == GLES20.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(e)} while $doing" }
        }

        private fun egl() = "0x" + Integer.toHexString(EGL14.eglGetError())
    }
}
