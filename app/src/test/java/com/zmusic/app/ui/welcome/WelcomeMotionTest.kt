package com.zmusic.app.ui.welcome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeMotionTest {
    private val bars = WelcomeMotion.BAR_X.indices

    @Test
    fun startsExactlyLikeTheSplashIcon() {
        for (t in 0L..WelcomeMotion.HOLD) {
            for (i in bars) assertEquals(1f, WelcomeMotion.barScale(i, t), 0f)
            assertEquals(0f, WelcomeMotion.lift(t), 0f)
            assertEquals(0f, WelcomeMotion.caption(t), 0f)
        }
    }

    @Test
    fun barsDipFromTheMiddleOutAndStayVisible() {
        assertTrue(WelcomeMotion.dipStart(2) < WelcomeMotion.dipStart(1))
        assertEquals(WelcomeMotion.dipStart(1), WelcomeMotion.dipStart(3))
        assertTrue(WelcomeMotion.dipStart(1) < WelcomeMotion.dipStart(0))
        for (t in 0L..WelcomeMotion.EXIT_AT) for (i in bars) {
            val s = WelcomeMotion.barScale(i, t)
            assertTrue("bar $i at $t: $s", s.isFinite() && s > 0.2f && s < 1.6f)
        }
        assertTrue(WelcomeMotion.barScale(2, WelcomeMotion.landAt) < 0.4f)
    }

    @Test
    fun springsSettleAndTypeNeverOvershoots() {
        assertEquals(1f, WelcomeMotion.spring(3000, damping = 0.42f, omega = 19f), 0.001f)
        assertEquals(1f, WelcomeMotion.spring(3000, damping = 1f, omega = 12f), 0.001f)
        for (t in 0L..WelcomeMotion.EXIT_AT) {
            assertTrue(WelcomeMotion.caption(t) in 0f..1f)
            assertTrue(WelcomeMotion.rule(t) in 0f..1f)
            for (i in 0 until 6) assertTrue(WelcomeMotion.letter(i, t) in 0f..1f)
        }
        // Everything is in place well before the screen starts to leave.
        val settled = WelcomeMotion.EXIT_AT - 300
        assertEquals(1f, WelcomeMotion.lift(settled), 0.02f)
        for (i in 0 until 6) assertEquals(1f, WelcomeMotion.letter(i, settled), 0.02f)
        assertEquals(1f, WelcomeMotion.rule(settled), 0.02f)
    }

    @Test
    fun lettersArriveInOrder() {
        val t = 1100L
        for (i in 1 until 6) assertTrue(WelcomeMotion.letter(i - 1, t) >= WelcomeMotion.letter(i, t))
    }

    @Test
    fun ripplesRunOnceAfterTheLanding() {
        assertTrue(WelcomeMotion.ripple(0, WelcomeMotion.landAt - 1) < 0f)
        assertEquals(1f, WelcomeMotion.ripple(1, WelcomeMotion.EXIT_AT), 0f)
    }

    @Test
    fun exitFadesAndFinishesFromWhereverItStarts() {
        for (exitAt in listOf(0L, 400L, WelcomeMotion.EXIT_AT)) {
            assertEquals(0f, WelcomeMotion.exit(exitAt, exitAt), 0f)
            assertFalse(WelcomeMotion.finished(exitAt + WelcomeMotion.EXIT - 1, exitAt))
            assertEquals(1f, WelcomeMotion.exit(exitAt + WelcomeMotion.EXIT, exitAt), 0f)
            assertTrue(WelcomeMotion.finished(exitAt + WelcomeMotion.EXIT, exitAt))
        }
    }

    @Test
    fun stillModeIsSettledFromTheFirstFrame() {
        for (i in bars) assertEquals(1f, WelcomeMotion.barScale(i, 0, still = true), 0f)
        assertEquals(1f, WelcomeMotion.lift(0, still = true), 0f)
        assertEquals(1f, WelcomeMotion.letter(5, 0, still = true), 0f)
    }
}
