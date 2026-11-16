package com.zmusic.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun formatsMinutesAndSeconds() = assertEquals("3:05", formatTime(185_000))

    @Test
    fun formatsHours() = assertEquals("1:01:05", formatTime(3_665_000))

    @Test
    fun negativeBecomesZero() = assertEquals("0:00", formatTime(-5))

    @Test
    fun formatsGains() {
        assertEquals("0 dB", formatDb(0f))
        assertEquals("0 dB", formatDb(0.04f))
        assertEquals("+3 dB", formatDb(3f))
        assertEquals("+3.5 dB", formatDb(3.5f))
        assertEquals("−12 dB", formatDb(-12f))
        assertEquals("−0.5 dB", formatDb(-0.5f))
    }

    @Test
    fun countdownRoundsUpToTheSecond() {
        assertEquals("0:01", formatCountdown(1))
        assertEquals("0:09", formatCountdown(8_200))
        assertEquals("30:00", formatCountdown(30 * 60_000L))
        assertEquals("0:00", formatCountdown(-100))
    }
}
