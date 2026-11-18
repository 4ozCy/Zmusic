package com.zmusic.app.ui.components

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The astronaut model, as scripts/astronaut_mesh.py writes it to assets/astronaut.mesh: a rigged
 * triangle mesh ready to hand to OpenGL as it is. See that script for the layout.
 *
 * Model units: one unit tall, y up, facing +z, the figure's left hand on +x.
 */
internal class AstronautMesh(
    val vertexCount: Int,
    val indexCount: Int,
    /** A vertex's position is [posOffset] + [posScale] * its snorm16 position. */
    val posScale: FloatArray,
    val posOffset: FloatArray,
    /** The point the figure turns about. */
    val centre: FloatArray,
    /** How far from [centre] any part of the figure reaches, in any pose it is put in. */
    val radius: Float,
    /** x, y, z of each point in J_* order. */
    val joints: FloatArray,
    /** Per material: linear albedo r, g, b, gloss exponent, reflection, kind, 0, 0. */
    val materials: FloatArray,
    /** [VERTEX_BYTES] per vertex, little endian, positioned at 0. */
    val vertices: ByteBuffer,
    /** One u16 per index, three per triangle, positioned at 0. */
    val indices: ByteBuffer,
) {
    val materialCount get() = materials.size / 8

    fun jointX(j: Int) = joints[j * 3]
    fun jointY(j: Int) = joints[j * 3 + 1]
    fun jointZ(j: Int) = joints[j * 3 + 2]

    companion object {
        const val ASSET = "astronaut.mesh"
        const val VERTEX_BYTES = 16

        const val J_NECK = 0
        const val J_SHOULDER_L = 1
        const val J_ELBOW_L = 2
        const val J_SHOULDER_R = 3
        const val J_ELBOW_R = 4
        const val J_HIP_L = 5
        const val J_KNEE_L = 6
        const val J_HIP_R = 7
        const val J_KNEE_R = 8
        const val J_WRIST_L = 9
        const val J_WRIST_R = 10
        const val J_PUFF = 11
        const val J_PUFF_AIM = 12
        const val JOINTS = 13

        fun load(assets: AssetManager): AstronautMesh = assets.open(ASSET).use { parse(it.readBytes()) }

        fun parse(bytes: ByteArray): AstronautMesh {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "ZAST") { "Not an astronaut mesh" }
            require(b.int == 1) { "Unknown astronaut mesh version" }
            val vertexCount = b.int
            val indexCount = b.int
            val materialCount = b.int
            val jointCount = b.int
            require(jointCount == JOINTS) { "Expected $JOINTS joints, found $jointCount" }
            val posScale = floats(b, 3)
            val posOffset = floats(b, 3)
            val centre = floats(b, 3)
            val radius = b.float
            val joints = floats(b, jointCount * 3)
            val materials = floats(b, materialCount * 8)
            val vertices = direct(b, vertexCount * VERTEX_BYTES)
            val indices = direct(b, indexCount * 2)
            require(!b.hasRemaining()) { "Astronaut mesh has ${b.remaining()} bytes left over" }
            return AstronautMesh(vertexCount, indexCount, posScale, posOffset, centre, radius, joints, materials, vertices, indices)
        }

        private fun floats(b: ByteBuffer, n: Int) = FloatArray(n) { b.float }

        private fun direct(b: ByteBuffer, n: Int): ByteBuffer {
            val out = ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN)
            val slice = b.slice().order(ByteOrder.LITTLE_ENDIAN)
            slice.limit(n)
            out.put(slice)
            out.position(0)
            b.position(b.position() + n)
            return out
        }
    }
}
