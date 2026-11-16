package com.zmusic.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FileNamesTest {
    @Test
    fun splitsBaseAndExtension() {
        assertEquals("Song", FileNames.baseName("Song.mp3"))
        assertEquals("mp3", FileNames.extension("Song.mp3"))
        assertEquals("My.Live.Take", FileNames.baseName("My.Live.Take.flac"))
        assertEquals("README", FileNames.baseName("README"))
        assertEquals("", FileNames.extension("README"))
        assertEquals(".hidden", FileNames.baseName(".hidden"))
        assertEquals("", FileNames.extension(".hidden"))
    }

    @Test
    fun keepsTheExtension() {
        assertEquals("New name.mp3", FileNames.rename("New name", "old.mp3"))
        assertEquals("New name.mp3", FileNames.rename("  New name  ", "old.mp3"))
        assertEquals("plain", FileNames.rename("plain", "noext"))
    }

    @Test
    fun typingTheExtensionDoesNotDoubleIt() {
        assertEquals("New.mp3", FileNames.rename("New.mp3", "old.mp3"))
        assertEquals("New.mp3", FileNames.rename("New.MP3", "old.mp3"))
        assertEquals("New.wav.mp3", FileNames.rename("New.wav", "old.mp3"))
    }

    @Test
    fun rejectsUnusableNames() {
        for (bad in listOf("", "   ", "a/b", "a\\b", "what?", "x:y", "quote\"", "<tag>", "pipe|", ".hidden", "tab\tname", ".mp3")) {
            assertNotNull("\"$bad\" should be rejected", FileNames.problem(bad, "old.mp3"))
            assertNull(FileNames.rename(bad, "old.mp3"))
        }
        assertNotNull(FileNames.problem("x".repeat(260), "old.mp3"))
    }

    @Test
    fun acceptsOrdinaryNames() {
        for (ok in listOf("Midnight City", "01 - Intro", "Café del Mar", "日本語", "a.b")) {
            assertNull("\"$ok\" should be accepted", FileNames.problem(ok, "old.mp3"))
        }
    }
}
