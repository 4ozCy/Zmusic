package com.zmusic.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeTapsTest {
    /** Feeds one-step presses starting from volume 8: `+` is up, `-` is down, [gapMs] apart. */
    private fun run(presses: String, gapMs: Long = 250L): VolumeTaps.Run? {
        val taps = VolumeTaps()
        var volume = 8
        var at = 1_000L
        for (press in presses) {
            val next = if (press == '+') volume + 1 else volume - 1
            taps.onStep(volume, next, at)
            volume = next
            at += gapMs
        }
        return taps.finish()
    }

    @Test
    fun upTwiceIsNext() {
        assertEquals(VolumeTaps.Run(VolumeShortcut.Next, volumeBefore = 8), run("++"))
    }

    @Test
    fun downTwiceIsPrevious() {
        assertEquals(VolumeTaps.Run(VolumeShortcut.Previous, volumeBefore = 8), run("--"))
    }

    @Test
    fun upThreeTimesIsPause() {
        assertEquals(VolumeTaps.Run(VolumeShortcut.Pause, volumeBefore = 8), run("+++"))
    }

    @Test
    fun anythingElseIsJustVolume() {
        assertNull(run(""))
        assertNull(run("+"))
        assertNull(run("-"))
        assertNull(run("---"))
        assertNull(run("++++"))
        assertNull(run("+++++++"))
    }

    @Test
    fun changingDirectionSpellsNothing() {
        assertNull(run("+-"))
        assertNull(run("-+"))
        assertNull(run("++-"))
    }

    @Test
    fun aHeldButtonSpellsNothing() {
        // Key repeat steps the volume every 50 ms.
        assertNull(run("++", gapMs = 50L))
        assertNull(run("+++", gapMs = 50L))
    }

    @Test
    fun aJumpOfSeveralStepsCountsAsOnePress() {
        // A headset with coarser steps than the phone moves the phone's volume two at a time.
        val taps = VolumeTaps()
        taps.onStep(4, 6, 0L)
        taps.onStep(6, 8, 300L)
        assertEquals(VolumeTaps.Run(VolumeShortcut.Next, volumeBefore = 4), taps.finish())
    }

    @Test
    fun slowPressesStartANewRun() {
        val taps = VolumeTaps(windowMs = 600L)
        taps.onStep(8, 9, 0L)
        taps.onStep(9, 10, 601L) // too late to join the first press
        assertNull(taps.finish())

        taps.onStep(5, 6, 0L)
        taps.onStep(6, 7, 700L) // a new run, which the next press completes
        taps.onStep(7, 8, 900L)
        assertEquals(VolumeTaps.Run(VolumeShortcut.Next, volumeBefore = 6), taps.finish())
    }

    @Test
    fun finishClearsTheRun() {
        val taps = VolumeTaps()
        taps.onStep(8, 9, 0L)
        taps.onStep(9, 10, 200L)
        assertEquals(VolumeShortcut.Next, taps.finish()?.shortcut)
        assertNull(taps.finish())

        // A spoiled run does not spoil the one after it.
        taps.onStep(8, 9, 1_000L)
        taps.onStep(9, 8, 1_200L)
        assertNull(taps.finish())
        taps.onStep(8, 7, 2_000L)
        taps.onStep(7, 6, 2_200L)
        assertEquals(VolumeTaps.Run(VolumeShortcut.Previous, volumeBefore = 8), taps.finish())
    }
}
