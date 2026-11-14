package com.zmusic.app.ui.components

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GyroVariantTest {
    @Test
    fun aNewSongNeverKeepsTheSameSculpture() {
        val random = Random(7)
        var current = GyroVariant.next(null, random)
        repeat(500) {
            val next = GyroVariant.next(current, random)
            assertNotEquals(current, next)
            current = next
        }
    }

    @Test
    fun everySculptureComesUp() {
        val random = Random(11)
        val seen = mutableSetOf<GyroVariant>()
        var current: GyroVariant? = null
        repeat(200) {
            current = GyroVariant.next(current, random)
            seen += current!!
        }
        assertEquals(GyroVariant.entries.toSet(), seen)
    }

    @Test
    fun theFirstPickCanBeAnyOfThem() {
        val random = Random(3)
        val seen = (0 until 200).map { GyroVariant.next(null, random) }.toSet()
        assertEquals(GyroVariant.entries.toSet(), seen)
    }
}
