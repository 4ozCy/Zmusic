package com.zmusic.app.ui.components

import kotlin.random.Random

/**
 * Which sculpture stands at the centre of Now Playing. A new one is drawn at random each time a
 * song starts.
 */
enum class GyroVariant {
    /** Three gimballed metal hoops over a summoning circle. Also the app's brand mark. */
    Gyroscope,

    /** A crimson compass star round a dark core, inside a broken silver ring and a hoop of thorns. */
    Eclipse,

    /** Gold neon: a three-armed star spinning inside a double ring, with three spear heads and three pairs of leaves. */
    Sunwheel,

    /** A pale astrolabe: ruled discs, dials and armillary rings round a starburst, in a ring of gold dust. */
    Astrolabe;

    companion object {
        /** A variant picked at random from every one except [previous], so each new song changes the picture. */
        fun next(previous: GyroVariant?, random: Random = Random.Default): GyroVariant {
            val pool = entries.filter { it != previous }
            return pool[random.nextInt(pool.size)]
        }
    }
}
