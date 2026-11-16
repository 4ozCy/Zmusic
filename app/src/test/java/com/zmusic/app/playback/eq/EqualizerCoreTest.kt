package com.zmusic.app.playback.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class EqualizerCoreTest {

    private fun gains(vararg db: Float) = db.toList()

    private fun core(settings: EqSettings, sampleRate: Int = 48_000, channels: Int = 1) =
        EqualizerCore().apply {
            configure(sampleRate, channels)
            update(settings)
        }

    @Test
    fun flatOrDisabledSettingsBypass() {
        assertTrue(core(EqSettings(enabled = true)).isBypassed)
        assertTrue(core(EqSettings(enabled = false, gainsDb = EqPreset.Bass.gainsDb)).isBypassed)
        assertFalse(core(EqSettings(enabled = true, gainsDb = EqPreset.Bass.gainsDb)).isBypassed)
        assertFalse(core(EqSettings(enabled = true, preampDb = -3f)).isBypassed)
        assertFalse(core(EqSettings(enabled = true, bassDb = 4f)).isBypassed)
    }

    @Test
    fun bypassReturnsSamplesUnchanged() {
        val eq = core(EqSettings(enabled = true))
        for (x in listOf(0.0, 0.5, -0.99, 1.0)) assertEquals(x, eq.process(0, x), 0.0)
    }

    @Test
    fun curvePassesThroughEverySliderAtBothCommonRates() {
        val centres = FloatArray(EQ_BAND_COUNT) { EQ_BAND_HZ[it] }
        val random = Random(7)
        val cases = EqPreset.entries.map { it.gainsDb } +
            List(40) { List(EQ_BAND_COUNT) { random.nextFloat() * 24f - 12f } }
        for (rate in listOf(44_100.0, 48_000.0)) {
            for (g in cases) {
                val response = EqDesign.responseDb(EqSettings(enabled = true, gainsDb = g), centres, rate)
                for (i in 0 until EQ_BAND_COUNT) {
                    assertEquals("band $i of $g at $rate", g[i], response[i], 0.25f)
                }
            }
        }
    }

    @Test
    fun boostedBandRaisesASineByItsGain() {
        val settings = EqSettings(enabled = true, gainsDb = gains(0f, 0f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f))
        val gainDb = sineGainDb(settings, 1_000.0)
        assertEquals(6.0, gainDb, 0.3)
        // Two octaves away the boost has mostly faded.
        assertEquals(0.0, sineGainDb(settings, 4_000.0), 0.8)
    }

    @Test
    fun bassShelfLiftsLowsAndLeavesHighsAlone() {
        val settings = EqSettings(enabled = true, bassDb = 8f)
        assertTrue(sineGainDb(settings, 40.0) > 6.5)
        assertEquals(0.0, sineGainDb(settings, 3_000.0), 0.3)
    }

    @Test
    fun preampScalesTheSignal() {
        val settings = EqSettings(enabled = true, preampDb = -6f)
        assertEquals(-6.0, sineGainDb(settings, 1_000.0), 0.05)
    }

    @Test
    fun extremeSettingsStayStableAndBounded() {
        val settings = EqSettings(
            enabled = true,
            gainsDb = List(EQ_BAND_COUNT) { if (it % 2 == 0) 12f else -12f },
            preampDb = 6f,
            bassDb = EQ_BASS_MAX_DB,
        )
        for (rate in listOf(22_050, 44_100, 48_000, 96_000)) {
            val eq = core(settings, rate, channels = 2)
            val random = Random(3)
            repeat(rate * 2) {
                for (ch in 0..1) {
                    val y = eq.process(ch, random.nextDouble(-0.9, 0.9))
                    assertTrue("rate $rate produced $y", y.isFinite() && abs(y) <= 1.0)
                }
            }
        }
    }

    @Test
    fun softClipIsTransparentBelowTheKneeAndBoundedAbove() {
        assertEquals(0.5, EqDesign.softClip(0.5), 0.0)
        assertEquals(-EqDesign.KNEE, EqDesign.softClip(-EqDesign.KNEE), 0.0)
        var last = EqDesign.softClip(EqDesign.KNEE)
        var x = EqDesign.KNEE
        while (x < 8.0) {
            x += 0.01
            val y = EqDesign.softClip(x)
            assertTrue(y >= last && y <= 1.0)
            assertEquals(-y, EqDesign.softClip(-x), 1e-12)
            last = y
        }
    }

    @Test
    fun settingChangesKeepFilterStateForStagesThatStayOn() {
        val rock = EqSettings(enabled = true, gainsDb = EqPreset.Rock.gainsDb)
        val nudged = rock.copy(gainsDb = rock.gainsDb.mapIndexed { i, g -> if (i == 4) g + 1f else g })
        val changed = core(rock)
        val reference = core(rock)
        val input = { n: Int -> 0.4 * sin(n * 0.05) }
        repeat(4_000) {
            changed.process(0, input(it))
            reference.process(0, input(it))
        }
        changed.update(nudged)
        // The filters carry on from where they were, so the output does not jump.
        val a = changed.process(0, input(4_000))
        val b = reference.process(0, input(4_000))
        assertEquals(b, a, 0.02)
    }

    @Test
    fun settingsSurviveEncodeAndDecode() {
        val s = EqSettings(enabled = true, gainsDb = EqPreset.Jazz.gainsDb, preampDb = -2.5f, bassDb = 3f)
        assertEquals(s, EqSettings.decode(s.encode()))
        assertEquals(EqPreset.Jazz, EqSettings.decode(s.encode()).preset)
    }

    @Test
    fun decodeFallsBackOnGarbage() {
        assertEquals(EqSettings(), EqSettings.decode(null))
        assertEquals(EqSettings(), EqSettings.decode(""))
        val odd = EqSettings.decode("1;9,x,99;nope;-4")
        assertTrue(odd.enabled)
        assertEquals(EQ_BAND_COUNT, odd.gainsDb.size)
        assertEquals(9f, odd.gainsDb[0])
        assertEquals(0f, odd.gainsDb[1])
        assertEquals(EQ_BAND_MAX_DB, odd.gainsDb[2])
        assertEquals(0f, odd.preampDb)
        assertEquals(0f, odd.bassDb)
    }

    @Test
    fun customCurveHasNoPreset() {
        assertEquals(EqPreset.Flat, EqSettings().preset)
        assertNull(EqSettings(gainsDb = gains(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)).preset)
    }

    @Test
    fun presetsStayInRange() {
        for (p in EqPreset.entries) {
            assertEquals(EQ_BAND_COUNT, p.gainsDb.size)
            assertTrue(p.gainsDb.all { it in EQ_BAND_MIN_DB..EQ_BAND_MAX_DB })
        }
    }

    /** Runs a sine through [settings] and returns the change in level, in dB, once it settles. */
    private fun sineGainDb(settings: EqSettings, hz: Double, rate: Int = 48_000): Double {
        val eq = core(settings, rate)
        val amplitude = 0.05
        val total = rate // one second
        val skip = rate / 2 // let the filters settle
        var inSum = 0.0
        var outSum = 0.0
        for (n in 0 until total) {
            val x = amplitude * sin(2.0 * PI * hz * n / rate)
            val y = eq.process(0, x)
            if (n >= skip) {
                inSum += x * x
                outSum += y * y
            }
        }
        return 20.0 * log10(sqrt(outSum) / sqrt(inSum))
    }
}
