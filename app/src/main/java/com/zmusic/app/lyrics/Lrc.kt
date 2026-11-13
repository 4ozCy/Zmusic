package com.zmusic.app.lyrics

data class LyricLine(val timeMs: Long, val text: String)

/** Parser for the common LRC format: lines like `[01:23.45] some words`, several time tags per line allowed. */
object Lrc {
    private val TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            val matches = TAG.findAll(line).toList()
            if (matches.isEmpty()) continue // metadata like [ar:Artist] has no digits, so it is skipped here
            val text = line.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val minutes = m.groupValues[1].toLong()
                val seconds = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val millis = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
                out += LyricLine((minutes * 60 + seconds) * 1000 + millis, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
