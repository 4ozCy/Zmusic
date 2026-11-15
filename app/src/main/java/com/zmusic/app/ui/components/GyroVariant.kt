package com.zmusic.app.ui.components

import kotlin.random.Random

/**
 * Which sculpture stands at the centre of Now Playing. [GyroDeck] deals a new one each time a
 * song starts.
 */
enum class GyroVariant {
    /** Three gimballed metal hoops over a summoning circle. Also the app's brand mark. */
    Gyroscope,

    /** Cel-shaded red and silver clockwork round a dark core. Faces you flat; steps, punches and slashes. */
    Eclipse,

    /** A gold neon sign: a pinwheel star spinning in a double ring. Spins, sways, buzzes and flips. */
    Sunwheel,

    /** Pale engraved plates stacked in depth like a hologram, ticking like a clock. */
    Astrolabe
}

/**
 * A shuffled deck holding each [GyroVariant] once. Every song that starts takes the next card,
 * so in any run of four songs each sculpture shows exactly once: none gets starved and none hogs
 * the stage. When the deck runs out it is reshuffled, and the new deck never opens with the card
 * the old one ended on, so the same sculpture never shows twice running.
 */
class GyroDeck private constructor(private val cards: List<GyroVariant>, private val at: Int) {
    /** The card on show. */
    val current: GyroVariant get() = cards[at]

    /** The deck after dealing the next card. */
    fun next(random: Random = Random.Default): GyroDeck =
        if (at + 1 < cards.size) GyroDeck(cards, at + 1) else GyroDeck(shuffled(current, random), 0)

    /** The deck as text, for keeping on disk: the cards in order, a semicolon, then the place. */
    fun save(): String = cards.joinToString(",") { it.name } + ";" + at

    companion object {
        /** A new deck in random order. */
        fun fresh(random: Random = Random.Default): GyroDeck = GyroDeck(shuffled(null, random), 0)

        /**
         * A deck kept by [save], or a fresh one when [text] is missing or no longer fits, for
         * instance because a sculpture was added or renamed since it was kept.
         */
        fun restore(text: String?, random: Random = Random.Default): GyroDeck {
            val parts = text?.split(";") ?: return fresh(random)
            if (parts.size != 2) return fresh(random)
            val cards = parts[0].split(",").mapNotNull { name -> GyroVariant.entries.firstOrNull { it.name == name } }
            val at = parts[1].toIntOrNull() ?: return fresh(random)
            val complete = cards.size == GyroVariant.entries.size && cards.toSet().size == cards.size
            if (!complete || at !in cards.indices) return fresh(random)
            return GyroDeck(cards, at)
        }

        /** Every variant in random order, never starting with [last]. */
        private fun shuffled(last: GyroVariant?, random: Random): List<GyroVariant> {
            val cards = GyroVariant.entries.shuffled(random).toMutableList()
            if (cards.size > 1 && cards[0] == last) {
                // Swap the repeat with a random later card, so the new deck still opens fairly.
                val swap = 1 + random.nextInt(cards.size - 1)
                val first = cards[0]
                cards[0] = cards[swap]
                cards[swap] = first
            }
            return cards
        }
    }
}
