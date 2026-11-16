package com.zmusic.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne
import kotlinx.coroutines.delay

/** One message at a time; a new one replaces the one on screen. */
@Stable
class NoticeState {
    /** The last message. Kept after [dismiss] so it stays readable while the pill leaves. */
    var text by mutableStateOf("")
        private set

    var visible by mutableStateOf(false)
        private set

    /** Changes with every [show], so the same text twice in a row still restarts the timer. */
    var serial by mutableLongStateOf(0L)
        private set

    fun show(message: String) {
        text = message
        visible = true
        serial++
    }

    fun dismiss() {
        visible = false
    }
}

@Composable
fun rememberNoticeState(): NoticeState = remember { NoticeState() }

/**
 * A small pill of smoked glass that drops in under the status bar for a few seconds: what just
 * happened ("Deleted …", "Renamed to …"). It sits at the top so it never covers the dock, the
 * mini player or the Now Playing controls. Screen readers hear it as it appears.
 */
@Composable
fun NoticeHost(state: NoticeState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.serial) {
        if (!state.visible) return@LaunchedEffect
        delay(NOTICE_MS)
        state.dismiss()
    }
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier.statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp),
        enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) { -it * 2 } + fadeIn(tween(160)),
        exit = slideOutVertically(tween(220)) { -it * 2 } + fadeOut(tween(180)),
    ) {
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .clip(CircleShape)
                .background(Nocturne.Smoke.copy(alpha = 0.96f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 18.dp, vertical = 11.dp),
        ) {
            Text(
                text = state.text,
                style = MaterialTheme.typography.labelLarge,
                color = AppTheme.extended.text,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

private const val NOTICE_MS = 2_800L
