package com.zmusic.app.ui.sound

import androidx.lifecycle.ViewModel
import com.zmusic.app.playback.eq.EqPreset
import com.zmusic.app.playback.eq.EqSettings
import com.zmusic.app.playback.eq.EqualizerStore
import kotlinx.coroutines.flow.StateFlow

/** Everything the Sound panel can ask for. Keeps the panel stateless and previewable. */
data class SoundActions(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onPreset: (EqPreset) -> Unit = {},
    val onBandChange: (band: Int, db: Float) -> Unit = { _, _ -> },
    val onPreampChange: (Float) -> Unit = {},
    val onBassChange: (Float) -> Unit = {},
    val onReset: () -> Unit = {},
)

/**
 * The equalizer as the Sound panel sees it. Any edit switches the equalizer on, so moving a
 * slider is always heard.
 */
class SoundViewModel(private val store: EqualizerStore) : ViewModel() {
    val settings: StateFlow<EqSettings> = store.settings

    val actions = SoundActions(
        onEnabledChange = { on -> store.update { it.copy(enabled = on) } },
        onPreset = { preset -> store.update { it.copy(enabled = true, gainsDb = preset.gainsDb) } },
        onBandChange = { band, db ->
            store.update { s -> s.copy(enabled = true, gainsDb = s.gainsDb.mapIndexed { i, g -> if (i == band) db else g }) }
        },
        onPreampChange = { db -> store.update { it.copy(enabled = true, preampDb = db) } },
        onBassChange = { db -> store.update { it.copy(enabled = true, bassDb = db) } },
        onReset = { store.update { EqSettings(enabled = it.enabled) } },
    )
}
