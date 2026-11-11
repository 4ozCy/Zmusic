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
}
