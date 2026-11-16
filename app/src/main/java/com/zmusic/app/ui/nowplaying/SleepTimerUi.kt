package com.zmusic.app.ui.nowplaying

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zmusic.app.playback.SleepTimer
import com.zmusic.app.ui.components.HairlineDivider
import com.zmusic.app.ui.components.frostedGlassCard
import com.zmusic.app.ui.components.pressClick
import com.zmusic.app.ui.songs.OptionRow
import com.zmusic.app.ui.songs.SheetGrabber
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne
import com.zmusic.app.ui.theme.TimeStyle
import com.zmusic.app.ui.theme.ZmusicTheme
import com.zmusic.app.util.formatCountdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

/** Time left on [timer], ticking once a second while it counts down. Null when it is off. */
@Composable
fun sleepTimerLabel(timer: SleepTimer): String? {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(timer) {
        now = SystemClock.elapsedRealtime()
        while (timer is SleepTimer.At) {
            // Wake on the next whole second of the countdown, so the display never skips one.
            delay(((timer.endsAtElapsedMs - now) % 1000L).let { if (it <= 0) 1000L else it })
            now = SystemClock.elapsedRealtime()
        }
    }
    return when (timer) {
        SleepTimer.Off -> null
        SleepTimer.EndOfSong -> "Stops after this song"
        is SleepTimer.At -> "Stops in ${formatCountdown(timer.endsAtElapsedMs - now)}"
    }
}

/**
 * Small glass chip on the Now Playing stage while a sleep timer runs: a moon and the time left.
 * Tap it to change or cancel the timer.
 */
@Composable
fun SleepChip(timer: SleepTimer, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = sleepTimerLabel(timer) ?: return
    val short = when (timer) {
        is SleepTimer.At -> label.removePrefix("Stops in ")
        else -> "End of song"
    }
    Row(
        modifier = modifier
            .pressClick(onClickLabel = "Change sleep timer", onClick = onClick)
            .semantics { contentDescription = "Sleep timer. $label" }
            .height(34.dp)
            .frostedGlassCard(CircleShape, surfaceAlpha = 0.10f)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = Nocturne.Amber, modifier = Modifier.size(16.dp))
        Text(
            short,
            style = TimeStyle,
            color = AppTheme.extended.text,
            modifier = Modifier.padding(start = 6.dp).clearAndSetSemantics { },
        )
    }
}

/** Choose when playback stops: after a number of minutes, or when the current song ends. */
@Composable
fun SleepTimerSheet(
    timer: SleepTimer,
    onDismiss: () -> Unit,
    onMinutes: (Int) -> Unit,
    onEndOfSong: () -> Unit,
    onOff: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun choose(action: () -> Unit) {
        action()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Nocturne.Smoke,
        contentColor = AppTheme.extended.text,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = { SheetGrabber() },
    ) {
        SleepTimerContent(timer, onChoose = ::choose, onMinutes = onMinutes, onEndOfSong = onEndOfSong, onOff = onOff)
    }
}

/** The picker's body. [onChoose] runs a row's action and closes the sheet. */
@Composable
fun SleepTimerContent(
    timer: SleepTimer,
    onChoose: (() -> Unit) -> Unit,
    onMinutes: (Int) -> Unit,
    onEndOfSong: () -> Unit,
    onOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = sleepTimerLabel(timer)
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge, color = AppTheme.extended.text)
            Text(
                label ?: "Playback pauses when the time is up.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (label != null) Nocturne.Amber else AppTheme.extended.textSoft,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        HairlineDivider(Modifier.padding(top = 16.dp, bottom = 6.dp))
        SLEEP_MINUTES.forEach { minutes ->
            OptionRow(Icons.Rounded.Timer, minutesLabel(minutes)) { onChoose { onMinutes(minutes) } }
        }
        OptionRow(
            Icons.Rounded.Bedtime,
            "End of this song",
            tint = if (timer == SleepTimer.EndOfSong) Nocturne.Amber else AppTheme.extended.text,
        ) { onChoose(onEndOfSong) }
        if (timer != SleepTimer.Off) {
            OptionRow(Icons.Rounded.TimerOff, "Turn off") { onChoose(onOff) }
        }
    }
}

/** "15 minutes", "1 hour", "1 h 30 min". */
fun minutesLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes % 60 == 0 -> if (minutes == 60) "1 hour" else "${minutes / 60} hours"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

@Preview(name = "Sleep timer", widthDp = 360, showBackground = true, backgroundColor = 0xFF14151A)
@Composable
private fun SleepTimerPreview() {
    ZmusicTheme {
        SleepTimerContent(SleepTimer.EndOfSong, onChoose = {}, onMinutes = {}, onEndOfSong = {}, onOff = {})
    }
}
