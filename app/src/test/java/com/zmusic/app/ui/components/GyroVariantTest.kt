package com.zmusic.app.ui.components

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GyroVariantTest {
    /** The first [n] cards dealt from a fresh deck. */
    private fun deal(n: Int, random: Random): List<GyroVariant> {
        var deck = GyroDeck.fresh(random)
        val cards = mutableListOf(deck.current)
        while (cards.size < n) {
            deck = deck.next(random)
            cards += deck.current
        }
        return cards
    }

    @Test
    fun everySculptureShowsOnceInEachRoundOfFour() {
        val cards = deal(400, Random(7))
        for (round in cards.chunked(GyroVariant.entries.size)) {
            assertEquals(GyroVariant.entries.toSet(), round.toSet())
        }
    }

    @Test
    fun theSameSculptureNeverShowsTwiceRunning() {
        val cards = deal(400, Random(11))
        for (k in 1 until cards.size) assertNotEquals("card $k", cards[k - 1], cards[k])
    }

    @Test
    fun theFirstCardCanBeAnyOfThem() {
        val seen = (0 until 200).map { GyroDeck.fresh(Random(it)).current }.toSet()
        assertEquals(GyroVariant.entries.toSet(), seen)
    }

    @Test
    fun aKeptDeckCarriesOnWhereItLeftOff() {
        val random = Random(5)
        var deck = GyroDeck.fresh(random)
        repeat(6) { deck = deck.next(random) }
        val kept = GyroDeck.restore(deck.save())
        assertEquals(deck.save(), kept.save())
        assertEquals(deck.current, kept.current)
    }

    @Test
    fun aDeckThatNoLongerFitsIsDealtAfresh() {
        val broken = listOf(
            null,
            "",
            "Eclipse;0",
            "Eclipse,Eclipse,Sunwheel,Astrolabe;0",
            "Gyroscope,Eclipse,Sunwheel,Astrolabe;9",
            "Gyroscope,Eclipse,Sunwheel,Comet;1",
            "Gyroscope,Eclipse,Sunwheel,Astrolabe;x",
        )
        for (text in broken) {
            val names = GyroDeck.restore(text, Random(3)).save().substringBefore(";").split(",")
            assertEquals("$text", GyroVariant.entries.map { it.name }.toSet(), names.toSet())
            assertEquals("$text", GyroVariant.entries.size, names.size)
        }
    }
}
