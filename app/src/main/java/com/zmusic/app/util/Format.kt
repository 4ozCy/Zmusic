package com.zmusic.app.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/**
 * A gain for display: "+3.5 dB", "0 dB", "−2 dB". Whole numbers drop the decimal, and the minus
 * is a real minus sign so the figures line up in a mono face.
 */
fun formatDb(db: Float): String {
    val tenths = (db * 10f).roundToInt()
    if (tenths == 0) return "0 dB"
    val sign = if (tenths > 0) "+" else "−"
    val whole = abs(tenths) / 10
    val frac = abs(tenths) % 10
    return if (frac == 0) "$sign$whole dB" else "$sign$whole.$frac dB"
}

/** Time left on a countdown, rounded up to the second: "1:05:00", "23:14", "0:09". */
fun formatCountdown(ms: Long): String = formatTime(((ms.coerceAtLeast(0L) + 999) / 1000) * 1000)
