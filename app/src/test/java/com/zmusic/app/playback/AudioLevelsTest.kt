package com.zmusic.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioLevelsTest {
    private val out = FloatArray(AudioLevels.BANDS)

    @Before
    fun reset() = AudioLevels.clear()

    private fun publish(us: Long, value: Float) = AudioLevels.publish(us, FloatArray(AudioLevels.BANDS) { value })

    @Test
    fun picksTheAnalysisBeingHeardNotTheNewest() {
        // The sink has analysed 400 ms ahead of what the speaker plays.
        for (k in 0..40) publish(k * 10_000L, k.toFloat())
        AudioLevels.setClock(positionUs = 0L, speed = 1f, nanos = 0L)
        assertTrue(AudioLevels.heardNow(nowNanos = 0L, out))
        assertEquals(0f, out[0])
        // 105 ms later the clock has moved on by extrapolation.
        assertTrue(AudioLevels.heardNow(nowNanos = 105_000_000L, out))
        assertEquals(10f, out[0])
    }

    @Test
    fun nothingWithoutAClock() {
        publish(0L, 1f)
        assertFalse(AudioLevels.heardNow(0L, out))
    }

    @Test
    fun nothingWhenTheClockIsFarFromTheAnalysis() {
        publish(0L, 1f)
        AudioLevels.setClock(5_000_000L, 1f, 0L)
        assertFalse(AudioLevels.heardNow(0L, out))
    }

    @Test
    fun aSeekBackDropsTheOldTimeline() {
        publish(5_000_000L, 1f)
        publish(0L, 2f)
        AudioLevels.setClock(5_000_000L, 1f, 0L)
        assertFalse(AudioLevels.heardNow(0L, out))
        AudioLevels.setClock(0L, 1f, 0L)
        assertTrue(AudioLevels.heardNow(0L, out))
        assertEquals(2f, out[0])
    }
}
