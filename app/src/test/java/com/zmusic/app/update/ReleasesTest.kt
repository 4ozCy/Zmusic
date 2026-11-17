package com.zmusic.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleasesTest {
    @Test
    fun versionIsTheLastNumberInTheTag() {
        assertEquals(57, Releases.versionFromTag("v0.2.57"))
        assertEquals(58, Releases.versionFromTag("v0.3.58"))
        assertEquals(120, Releases.versionFromTag(" v1.0.120 "))
        assertNull(Releases.versionFromTag("latest"))
    }

    @Test
    fun displayNameDropsTheV() {
        assertEquals("0.3.58", Releases.displayName("v0.3.58"))
    }

    @Test
    fun notesDropMergeLineAndInstallHint() {
        val body = "Merge pull request #18 from 4ozCy/ccr-846628f2-ua0pdz\n\n" +
            "Make the astronaut wave\n\n\n\nSigned release build. Download the .apk below and install it."
        assertEquals("Make the astronaut wave", Releases.cleanNotes(body))
        assertEquals("", Releases.cleanNotes(null))
        assertEquals("", Releases.cleanNotes("Signed release build. Download the .apk below and install it."))
    }
}
