package com.zmusic.app.ui.sound

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zmusic.app.playback.eq.EQ_BAND_COUNT
import com.zmusic.app.playback.eq.EQ_BASS_MAX_DB
import com.zmusic.app.playback.eq.EQ_PREAMP_MAX_DB
import com.zmusic.app.playback.eq.EQ_PREAMP_MIN_DB
import com.zmusic.app.playback.eq.EqPreset
import com.zmusic.app.playback.eq.EqSettings
import com.zmusic.app.playback.eq.eqBandLabel
import com.zmusic.app.ui.components.Eyebrow
import com.zmusic.app.ui.components.TextAction
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.ui.theme.ZmusicTheme

/**
 * The equalizer, shown in the Now Playing sheet where the sculpture stands, so the transport stays
 * under your thumb while you listen to the change. Presets, ten bands over the live response
 * curve, then preamp and bass boost.
 */
@Composable
fun SoundPanel(settings: EqSettings, actions: SoundActions, modifier: Modifier = Modifier) {
    val dim by animateFloatAsState(if (settings.enabled) 1f else 0.42f, tween(220), label = "eqDim")
    BoxWithConstraints(modifier) {
        val graphHeight = (maxHeight * 0.42f).coerceIn(150.dp, 240.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Equalizer", color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = when {
                            !settings.enabled -> "Off"
                            else -> settings.preset?.label ?: "Custom"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = AppTheme.extended.text,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = actions.onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Nocturne.Amber,
                        checkedThumbColor = Nocturne.Obsidian,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                        uncheckedBorderColor = Color.White.copy(alpha = 0.28f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.62f),
                    ),
                    modifier = Modifier.semantics { contentDescription = "Equalizer" },
                )
            }

            PresetRow(selected = settings.preset, onPreset = actions.onPreset, modifier = Modifier.padding(top = 10.dp))

            Column(Modifier.graphicsLayer { alpha = dim }) {
                EqGraph(
                    settings = settings,
                    onBandChange = actions.onBandChange,
                    modifier = Modifier.fillMaxWidth().height(graphHeight).padding(top = 12.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    for (i in 0 until EQ_BAND_COUNT) {
                        Text(
                            text = eqBandLabel(i),
                            style = TimeStyle,
                            color = AppTheme.extended.textDim,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                LevelSlider("Preamp", settings.preampDb, EQ_PREAMP_MIN_DB..EQ_PREAMP_MAX_DB, actions.onPreampChange)
                LevelSlider("Bass boost", settings.bassDb, 0f..EQ_BASS_MAX_DB, actions.onBassChange)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                TextAction("Reset", Icons.Rounded.RestartAlt, onClick = actions.onReset)
            }
        }
    }
}

/** The built-in curves as a row of small glass chips. A custom curve selects none of them. */
@Composable
private fun PresetRow(selected: EqPreset?, onPreset: (EqPreset) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EqPreset.entries.forEach { preset ->
            val on = preset == selected
            Box(
                Modifier
                    .height(48.dp)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPreset(preset) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = preset.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Nocturne.Amber else AppTheme.extended.textSoft,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (on) Nocturne.Amber.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f))
                        .border(1.dp, if (on) Nocturne.Amber.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.10f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Preview(name = "Sound panel", widthDp = 360, heightDp = 560, showBackground = true, backgroundColor = 0xFF070709)
@Composable
private fun SoundPanelPreview() {
    ZmusicTheme {
        SoundPanel(
            settings = EqSettings(enabled = true, gainsDb = EqPreset.Rock.gainsDb, preampDb = -2f, bassDb = 3f),
            actions = SoundActions(),
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        )
    }
}
