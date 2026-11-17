package com.zmusic.app.playback

/** What a run of quick presses on a volume button asks the player to do. */
enum class VolumeShortcut(val label: String) {
    /** Volume up twice. */
    Next("next song"),

    /** Volume down twice. */
    Previous("back"),

    /** Volume up three times. */
    Pause("pause"),
}

/**
 * Counts quick presses of a volume button, one per volume step, and names the shortcut they
 * spell. Plain Kotlin with the clock passed in, so it can be tested without a phone.
 *
 * A run is presses in one direction, each within [windowMs] of the one before. Anything else is
 * someone changing the volume and spells nothing: a single press, four or more, a change of
 * direction, or steps closer together than [minGapMs] (a held button repeats that fast, a finger
 * does not).
 */
class VolumeTaps(
    val windowMs: Long = 600L,
    private val minGapMs: Long = 80L,
) {
    /** A finished run: the [shortcut] it spelled and the volume before its first press. */
    data class Run(val shortcut: VolumeShortcut, val volumeBefore: Int)

    private var count = 0
    private var up = false
    private var volumeBefore = 0
    private var lastAtMs = 0L
    private var spoiled = false

    /** The volume went from [from] to [to] at [atMs]. */
    fun onStep(from: Int, to: Int, atMs: Long) {
        if (from == to) return
        if (count > 0 && atMs - lastAtMs > windowMs) reset()
        val stepUp = to > from
        if (count == 0) {
            up = stepUp
            volumeBefore = from
        } else if (stepUp != up || atMs - lastAtMs < minGapMs) {
            spoiled = true
        }
        count++
        lastAtMs = atMs
    }

    /** Ends the run. Call once [windowMs] has passed with no further step. Null if it spelled nothing. */
    fun finish(): Run? {
        val shortcut = when {
            spoiled -> null
            up && count == 2 -> VolumeShortcut.Next
            up && count == 3 -> VolumeShortcut.Pause
            !up && count == 2 -> VolumeShortcut.Previous
            else -> null
        }
        val run = shortcut?.let { Run(it, volumeBefore) }
        reset()
        return run
    }

    fun reset() {
        count = 0
        spoiled = false
    }
}
