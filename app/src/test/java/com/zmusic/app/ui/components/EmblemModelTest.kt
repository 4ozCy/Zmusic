package com.zmusic.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmblemModelTest {
    private val emblems = listOf(GyroVariant.Eclipse, GyroVariant.Sunwheel, GyroVariant.Astrolabe)
    private val bands = FloatArray(12) { 0.3f }

    private fun EmblemModel.step(frame: Int, bass: Float) =
        update(t = frame / 60f, bass = bass, mid = 0.5f, treble = 0.4f, bands = bands, cx = 500f, cy = 400f, unit = 230f, lineUnit = 2.75f)

    @Test
    fun everyPointIsFiniteAndEveryItemHasItsVertices() {
        for (variant in emblems) {
            val model = EmblemModel(variant, fine = true)
            for (frame in 0 until 900) {
                model.step(frame, bass = if (frame % 30 < 3) 0.95f else 0.3f)
                assertTrue("$variant", model.sx.all { it.isFinite() } && model.sy.all { it.isFinite() })
                assertTrue("$variant", model.width.all { it.isFinite() && it >= 0f })
                assertTrue("$variant", model.reveal.all { it in 0f..1f })
                assertTrue("$variant", model.slashXY.all { it.isFinite() })
                assertTrue("$variant", model.rayXY.all { it.isFinite() })
                assertTrue("$variant", model.rippleXY.all { it.isFinite() })
                assertTrue("$variant", model.cometX.isFinite() && model.cometY.isFinite())
                assertTrue("$variant", model.burst.isFinite() && model.burst >= 0f)
            }
            for (i in 0 until model.count) {
                assertTrue("$variant item $i", model.size[i] >= 1 && model.first[i] + model.size[i] <= model.idx.size)
                assertTrue("$variant item $i", model.glow[i] in 0..model.glowCount)
            }
            assertTrue("$variant", model.idx.all { it in model.sx.indices })
            assertTrue("$variant", model.constellation.all { it in model.sx.indices })
        }
    }

    @Test
    fun haloClassesListExactlyTheirItemsInOrder() {
        for (variant in emblems) {
            for (fine in listOf(true, false)) {
                val model = EmblemModel(variant, fine)
                assertEquals("$variant", 0, model.glowStart[0])
                for (c in 0 until model.glowCount) {
                    val expected = (0 until model.count).filter { model.glow[it] == c + 1 }
                    val listed = (model.glowStart[c] until model.glowStart[c + 1]).map { model.glowMembers[it] }
                    assertEquals("$variant fine=$fine class ${c + 1}", expected, listed)
                }
            }
        }
    }

    @Test
    fun itemsAreOrderedFarToNearAndEachAppearsOnce() {
        for (variant in emblems) {
            val model = EmblemModel(variant, fine = true)
            for (frame in 0 until 240) model.step(frame, bass = 0.4f)
            assertEquals("$variant", (0 until model.count).toList(), model.order.sorted())
        }
    }

    @Test
    fun steadyBassNeverSetsOffAnImpact() {
        for (variant in emblems) {
            val model = EmblemModel(variant, fine = true)
            for (frame in 0 until 600) {
                model.step(frame, bass = 0.36f)
                assertEquals("$variant", 0f, model.impact, 0f)
                assertEquals("$variant", 0f, model.burst, 0f)
            }
        }
    }

    @Test
    fun kickSetsOffEachEmblemsOwnEffectThatDiesAway() {
        for (variant in emblems) {
            val model = EmblemModel(variant, fine = true)
            for (frame in 0 until 300) model.step(frame, bass = 0.3f)
            for (frame in 300 until 304) model.step(frame, bass = 0.95f)
            assertTrue("$variant", model.impact > 0.9f)
            assertTrue("$variant", model.burst > 0f)
            when (variant) {
                GyroVariant.Eclipse -> assertTrue(model.slashAlpha > 0f)
                GyroVariant.Sunwheel -> assertTrue(model.rayAlpha > 0f)
                else -> assertTrue(model.rippleAlpha.any { it > 0f } && model.constellationAlpha > 0f)
            }
            for (frame in 304 until 400) model.step(frame, bass = 0.3f)
            assertEquals("$variant", 0f, model.impact, 0f)
            assertEquals("$variant", 0f, model.burst, 0f)
        }
    }

    @Test
    fun onlyEachEmblemCarriesItsOwnEffect() {
        val eclipse = EmblemModel(GyroVariant.Eclipse, fine = true)
        val sunwheel = EmblemModel(GyroVariant.Sunwheel, fine = true)
        val astrolabe = EmblemModel(GyroVariant.Astrolabe, fine = true)
        assertTrue(eclipse.slashes > 0 && eclipse.rays == 0 && eclipse.ripples == 0)
        assertTrue(sunwheel.rays > 0 && sunwheel.slashes == 0 && sunwheel.ripples == 0)
        assertTrue(astrolabe.ripples > 0 && astrolabe.slashes == 0 && astrolabe.rays == 0)
        assertTrue(astrolabe.constellation.isNotEmpty())
    }

    @Test
    fun spectrumMovesItsMarks() {
        for (variant in emblems) {
            val quiet = EmblemModel(variant, fine = true, intro = false)
            val loud = EmblemModel(variant, fine = true, intro = false)
            quiet.update(0f, 0.3f, 0.5f, 0.4f, FloatArray(12), 500f, 400f, 230f, 2.75f)
            loud.update(0f, 0.3f, 0.5f, 0.4f, FloatArray(12) { 1f }, 500f, 400f, 230f, 2.75f)
            assertTrue("$variant", quiet.sx.indices.any { quiet.sx[it] != loud.sx[it] || quiet.sy[it] != loud.sy[it] })
        }
    }

    @Test
    fun theAstrolabeDrawsItselfIn() {
        val model = EmblemModel(GyroVariant.Astrolabe, fine = true)
        model.step(0, bass = 0.3f)
        assertTrue(model.reveal.all { it == 0f })
        for (frame in 1 until 150) model.step(frame, bass = 0.3f)
        assertTrue(model.reveal.all { it == 1f })
    }

    @Test
    fun withoutTheEntranceEverythingIsWholeAtOnce() {
        for (variant in emblems) {
            val model = EmblemModel(variant, fine = true, intro = false)
            model.step(0, bass = 0.3f)
            assertTrue("$variant", model.reveal.all { it == 1f })
            assertTrue("$variant", model.argb.any { it ushr 24 != 0 })
        }
    }

    @Test
    fun smallSizesAreCutDownAndNeverFlash() {
        for (variant in emblems) {
            val small = EmblemModel(variant, fine = false)
            val full = EmblemModel(variant, fine = true)
            assertTrue("$variant", small.count < full.count / 2)
            assertEquals(0, small.slashes + small.rays + small.ripples)
            for (frame in 0 until 120) {
                small.step(frame, bass = if (frame % 30 < 3) 0.95f else 0.3f)
                assertEquals(0f, small.impact, 0f)
                assertEquals(0f, small.burst, 0f)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun theGyroscopeIsNotAnEmblem() {
        EmblemModel(GyroVariant.Gyroscope, fine = true)
    }
}
