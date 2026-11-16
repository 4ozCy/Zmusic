package com.zmusic.app.playback.eq

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * The one copy of the equalizer settings, kept on disk so they survive restarts. The UI writes
 * through [update]; the playback service's [EqualizerAudioProcessor] reads [settings] on the audio
 * thread. Both live in the app's single process.
 */
class EqualizerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(EqSettings.decode(prefs.getString(KEY, null)))
    val settings: StateFlow<EqSettings> = _settings.asStateFlow()

    /** Applies [transform] and saves the result. Call from the main thread. */
    fun update(transform: (EqSettings) -> EqSettings) {
        val before = _settings.value
        val after = _settings.updateAndGet { transform(it).sanitized() }
        // A slider drag sends dozens of updates a second; SharedPreferences only writes the last.
        if (after != before) prefs.edit().putString(KEY, after.encode()).apply()
    }

    private companion object {
        const val PREFS = "zmusic_sound"
        const val KEY = "equalizer"
    }
}
