package com.zmusic.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroModelTest {
    private val bands = FloatArray(12) { 0.3f }

    private fun GyroModel.step(frame: Int, bass: Float) =
        update(t = frame / 60f, bass = bass, mid = 0.5f, treble = 0.4f, bands = bands, cx = 500f, cy = 400f, unit = 230f, tickWidth = 5f)

    @Test
    fun steadyBassNeverSetsOffAnImpact() {
        val model = GyroModel(segments = 120, halo = true)
        for (frame in 0 until 600) {
            model.step(frame, bass = 0.36f)
            assertEquals(0f, model.impact, 0f)
            assertEquals(0f, model.boltAlpha, 0f)
            assertEquals(0f, model.focusAlpha, 0f)
        }
    }

    @Test
    fun kickSetsOffAnImpactThatDiesAway() {
        val model = GyroModel(segments = 120, halo = true)
        for (frame in 0 until 300) model.step(frame, bass = 0.3f)
        for (frame in 300 until 304) model.step(frame, bass = 0.95f)
        assertTrue(model.impact > 0.9f)
        assertTrue(model.boltAlpha > 0f)
        assertTrue(model.focusAlpha > 0f)
        for (frame in 304 until 400) model.step(frame, bass = 0.3f)
        assertEquals(0f, model.impact, 0f)
        assertEquals(0f, model.boltAlpha, 0f)
    }

    @Test
    fun impactsStayUnderThreeASecond() {
        val model = GyroModel(segments = 120, halo = true)
        var impacts = 0
        var last = 0f
        var lastCounted = -100
        // Ten seconds of a kick every tenth of a second.
        for (frame in 0 until 600) {
            model.step(frame, bass = if (frame % 6 < 2) 0.95f else 0.2f)
            // An impact may keep rising for a few frames; count it once.
            if (model.impact > last && frame - lastCounted > 8) {
                impacts++
                lastCounted = frame
            }
            last = model.impact
        }
        assertTrue(impacts > 0)
        assertTrue("$impacts impacts in ten seconds", impacts <= 28)
    }

    @Test
    fun everyPointIsFinite() {
        val model = GyroModel(segments = 120, halo = true)
        for (frame in 0 until 900) {
            model.step(frame, bass = if (frame % 30 < 3) 0.95f else 0.3f)
            assertTrue(model.xy.all { it.isFinite() })
            assertTrue(model.sigilXY.all { it.isFinite() })
            assertTrue(model.boltXY.all { it.isFinite() })
            assertTrue(model.focusXY.all { it.isFinite() })
        }
        assertTrue(model.sigilPoints > 400)
    }

    @Test
    fun smallSizesCarryNoExtras() {
        val model = GyroModel(segments = 36, halo = false)
        for (frame in 0 until 120) model.step(frame, bass = if (frame % 30 < 3) 0.95f else 0.3f)
        assertEquals(0, model.sigilPoints)
        assertEquals(0, model.bolts)
        assertEquals(0, model.focusLines)
        assertEquals(36 * 3 + 1, model.count)
    }
}
