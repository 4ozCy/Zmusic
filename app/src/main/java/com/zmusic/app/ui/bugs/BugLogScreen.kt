package com.zmusic.app.ui.bugs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zmusic.app.debug.BugLog
import com.zmusic.app.ui.components.GlassIconButton
import com.zmusic.app.ui.components.HairlineDivider
import com.zmusic.app.ui.components.ScreenHeader
import com.zmusic.app.ui.components.frostedGlassCard
import com.zmusic.app.ui.components.pressClick
import com.zmusic.app.ui.theme.AppTheme
import com.zmusic.app.ui.theme.Nocturne
import com.zmusic.app.ui.theme.TimeStyle
import kotlinx.coroutines.delay

private enum class Filter(val label: String) {
    All("All"),
    Problems("Errors"),
    Warnings("Warnings"),
    Info("Info"),
    ;

    fun accepts(e: BugLog.Entry): Boolean = when (this) {
        All -> true
        Problems -> e.isProblem
        Warnings -> e.level == BugLog.Level.Warn
        Info -> e.level == BugLog.Level.Info
    }
}

private val BugLog.Level.color: Color
    get() = when (this) {
        BugLog.Level.Info -> Color(0xFF9FB4FF)
        BugLog.Level.Warn -> Color(0xFFFFC98A)
        BugLog.Level.Error -> Color(0xFFFF8F85)
        BugLog.Level.Fatal -> Color(0xFFFF5D7A)
    }

private val ConsoleStyle = TimeStyle.copy(fontSize = 12.5.sp, lineHeight = 18.sp)
private val MetaStyle = TimeStyle.copy(fontSize = 11.sp, lineHeight = 15.sp)

/**
 * Bugs. A live console of everything [BugLog] recorded: crashes, playback and scan errors,
 * warnings, and a few milestones for context. Newest at the bottom, like a terminal. It follows
 * new output while you are at the bottom and holds still once you scroll up to read.
 *
 * @param contentPadding status bar at the top, floating dock and mini player at the bottom.
 */
@Composable
fun BugLogScreen(contentPadding: PaddingValues) {
    val entries by BugLog.entries.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    val shown = remember(entries, filter) { entries.filter(filter::accepts) }
    val problems = remember(entries) { entries.count { it.isProblem } }

    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
    ) {
        ScreenHeader(
            eyebrow = when (problems) {
                0 -> "All clear"
                1 -> "1 problem logged"
                else -> "$problems problems logged"
            },
            title = "Bug log",
        ) {
            GlassIconButton(
                icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                description = if (copied) "Copied" else "Copy log",
            ) {
                clipboard.setText(AnnotatedString(BugLog.dump(shown)))
                copied = true
            }
            GlassIconButton(Icons.Rounded.DeleteSweep, "Clear log") { BugLog.clear() }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Filter.entries) { f ->
                FilterChip(
                    label = f.label,
                    count = remember(entries, f) { entries.count(f::accepts) },
                    selected = f == filter,
                ) { filter = f }
            }
        }

        Console(
            entries = shown,
            emptyText = if (entries.isEmpty()) "log is empty" else "nothing under \"${filter.label.lowercase()}\"",
            modifier = Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 14.dp),
        )
    }
}

@Composable
private fun FilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .pressClick(onClick = onClick)
            .height(36.dp)
            .frostedGlassCard(CircleShape, surfaceAlpha = if (selected) 0.14f else 0.08f)
            .then(if (selected) Modifier.background(Nocturne.Amber.copy(alpha = 0.26f)) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = AppTheme.extended.text, maxLines = 1)
        Text(count.toString(), style = MetaStyle, color = AppTheme.extended.textSoft, maxLines = 1)
    }
}

/** The terminal pane: a title bar with a live light, then the scrolling output and a blinking cursor. */
@Composable
private fun Console(entries: List<BugLog.Entry>, emptyText: String, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Follow new output only while the user is parked at the bottom.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canGoDown) -> if (scrolling) follow = !canGoDown }
    }
    LaunchedEffect(entries.size, entries.lastOrNull()?.id) {
        // Index entries.size is the cursor line, the last item in the list.
        if (follow) listState.scrollToItem(entries.size)
    }

    Column(
        modifier
            .fillMaxWidth()
            .frostedGlassCard(RoundedCornerShape(22.dp), surfaceAlpha = 0.08f)
            // Darker than the other glass so the output reads like a screen.
            .background(Color(0xFF040405).copy(alpha = 0.50f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (c in WindowDots) Box(Modifier.size(9.dp).clip(CircleShape).background(c))
            Text(
                text = "zmusic ~ console",
                style = MetaStyle,
                color = AppTheme.extended.textSoft,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                maxLines = 1,
            )
            LiveLight()
        }
        HairlineDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text("# $emptyText", style = ConsoleStyle, color = AppTheme.extended.textDim)
                }
            }
            items(entries, key = { it.id }) { entry ->
                LogLine(entry, Modifier.animateItem())
            }
            item(key = "cursor") { CursorLine() }
        }
    }
}

private val WindowDots = listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840))

/** Green dot with a ring pulsing out of it, and the word LIVE. */
@Composable
private fun LiveLight() {
    val pulse = rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "livePulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier.size(7.dp).drawBehind {
                val p = pulse.value
                val r = size.minDimension / 2f
                drawCircle(LiveGreen, r * (1f + 1.8f * p), alpha = 0.5f * (1f - p))
                drawCircle(LiveGreen, r)
            },
        )
        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = AppTheme.extended.text)
    }
}

private val LiveGreen = Color(0xFF4ADE80)

/**
 * One entry: a coloured rule for the level, then `time LEVEL source` and the message in white.
 * Entries that carry a stack trace expand when tapped; the trace scrolls sideways instead of
 * wrapping, so frames stay one per line.
 */
@Composable
private fun LogLine(entry: BugLog.Entry, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val tone = entry.level.color
    val detail = entry.detail
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (detail != null) {
                    Modifier.clickable(onClickLabel = if (expanded) "Hide stack trace" else "Show stack trace") {
                        expanded = !expanded
                    }
                } else {
                    Modifier
                },
            )
            .drawBehind {
                if (entry.isProblem) drawRect(tone, alpha = 0.08f)
                val w = 2.5.dp.toPx()
                drawRoundRect(tone, Offset(0f, 3.dp.toPx()), Size(w, size.height - 6.dp.toPx()), CornerRadius(w / 2f))
            }
            .padding(start = 11.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(BugLog.formatTime(entry.timeMs), style = MetaStyle, color = AppTheme.extended.textDim, maxLines = 1)
            Text(entry.level.tag, style = MetaStyle.copy(fontWeight = FontWeight.Medium), color = tone, maxLines = 1)
            Text(entry.source, style = MetaStyle, color = AppTheme.extended.textSoft, maxLines = 1)
        }
        Text(entry.message, style = ConsoleStyle, color = AppTheme.extended.text, modifier = Modifier.padding(top = 2.dp))
        if (detail != null) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
            ) {
                Text(
                    text = detail.trimEnd(),
                    style = MetaStyle,
                    color = AppTheme.extended.textSoft,
                    softWrap = false,
                    modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                )
            }
            Text(
                text = if (expanded) "▾ hide stack trace" else "▸ stack trace",
                style = MetaStyle,
                color = AppTheme.extended.textDim,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** The prompt at the end of the output, with a block cursor that blinks. */
@Composable
private fun CursorLine() {
    val blink = rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "cursorBlink",
    )
    Row(Modifier.padding(start = 11.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(">", style = ConsoleStyle, color = Nocturne.Amber)
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier
                .size(width = 8.dp, height = 15.dp)
                .graphicsLayer { alpha = if (blink.value < 0.5f) 1f else 0f }
                .background(AppTheme.extended.text),
        )
    }
}
