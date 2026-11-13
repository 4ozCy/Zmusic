package com.zmusic.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    @Test
    fun parsesTimeAndText() {
        val lines = Lrc.parse("[00:12.50]Hello world")
        assertEquals(1, lines.size)
        assertEquals(12_500L, lines[0].timeMs)
        assertEquals("Hello world", lines[0].text)
    }

    @Test
    fun handlesMultipleTagsAndSorts() {
        val lines = Lrc.parse("[01:00.00][00:05.00]Chorus\n[00:30.00]Verse")
        assertEquals(listOf(5_000L, 30_000L, 60_000L), lines.map { it.timeMs })
        assertEquals("Chorus", lines[0].text)
    }

    @Test
    fun ignoresMetadataTags() {
        assertTrue(Lrc.parse("[ar:Someone]\n[ti:Something]").isEmpty())
    }
}
